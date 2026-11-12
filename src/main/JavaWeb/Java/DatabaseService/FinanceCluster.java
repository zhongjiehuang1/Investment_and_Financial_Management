package DatabaseService;

import Controller.ApiSupport;
import Controller.ClusterAvailability;
import com.mysql.cj.jdbc.MysqlXADataSource;
import org.json.JSONArray;
import org.json.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.XADataSource;
import javax.transaction.xa.Xid;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Atomic replicated transactions, with an explicitly separate legacy XA backend. */
public final class FinanceCluster implements AutoCloseable {
    @FunctionalInterface public interface Work { JSONObject run() throws Exception; }
    private static volatile FinanceCluster instance;
    private static volatile boolean shuttingDown;
    private final Properties config;
    private final XaTransactions transactions;
    private final AtomicFinanceTransactions atomic;
    private final ScheduledExecutorService recovery;
    private volatile boolean recovered;
    private volatile boolean executing;
    private volatile boolean closing;

    public static boolean enabled() { return Boolean.parseBoolean(setting("iafm.cluster.enabled", "IAFM_CLUSTER_ENABLED", "false")); }
    private static String setting(String key,String env,String fallback) {
        String value=System.getProperty(key); if(value==null)value=System.getenv(env);
        return value==null||value.isBlank()?fallback:value;
    }
    public static synchronized FinanceCluster get() {
        if(shuttingDown||!enabled()||ClusterAvailability.passive()||!DatabaseInit.clusterSchemaReady())throw new ClusterAvailability.Unavailable();
        if(instance==null)try {
            Properties properties=new Properties();
            try(var reader=Files.newBufferedReader(Path.of(setting("iafm.cluster.config","IAFM_CLUSTER_CONFIG","/etc/iafm/cluster.properties")))) { properties.load(reader); }
            instance=new FinanceCluster(properties);
        } catch(Exception failure) { throw new ClusterAvailability.Unavailable(); }
        return instance;
    }
    private FinanceCluster(Properties properties)throws Exception {
        this(properties,AtomicFinanceTransactions.enabled(properties)?Map.of():sources(properties));
    }
    private static Map<Integer,XADataSource> sources(Properties properties)throws SQLException {
        Map<Integer,XADataSource> sources=new TreeMap<>();
        for(int node:List.of(1,2)) {
            MysqlXADataSource source=new MysqlXADataSource();
            source.setUrl(boundedUrl(properties.getProperty("node"+node+".url"))); source.setUser(properties.getProperty("node"+node+".user"));
            source.setPassword(properties.getProperty("node"+node+".password")); sources.put(node,source);
        }
        return sources;
    }
    FinanceCluster(Properties properties,Map<Integer,XADataSource> sources)throws Exception {
        config=properties;
        if(AtomicFinanceTransactions.enabled(properties)) {
            if(required("idempotency.secret").length()<32)throw new IllegalStateException("Incomplete idempotency configuration.");
            atomic=new AtomicFinanceTransactions(properties);
            atomic.initialize();
            transactions=null;recovery=null;recovered=true;
            return;
        }
        atomic=null;
        if(replicated())throw new IllegalStateException("Replicated finance requires the atomic transaction backend; legacy XA is maintenance-only.");
        requireLegacyIsolation(properties);
        Path store=Path.of(required("objectStore.absolutePath"));
        String marker=Files.readString(store.resolve("identity")).trim();
        if(!RequestIdentity.validGeneration(marker))throw new IllegalStateException("Invalid coordinator identity.");
        try(Connection connection=raw(1);Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("SELECT STORE_UUID FROM CLUSTER_COORDINATOR WHERE ID=1")) {
            if(!rows.next()||!marker.equals(rows.getString(1)))throw new IllegalStateException("Coordinator store identity mismatch.");
        }
        if(required("idempotency.secret").length()<32)throw new IllegalStateException("Incomplete idempotency configuration.");
        transactions=new XaTransactions(sources,new Journal(),store,required("nodeIdentifier"),JdbcDecisionStore.from(properties,marker),this::verifyConnection);
        recovery=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"finance-xa-recovery");t.setDaemon(true);return t;});
        recovery.scheduleWithFixedDelay(this::recoverSafely,0,5,TimeUnit.SECONDS);
    }
    private String required(String key) { String value=config.getProperty(key);if(value==null||value.isBlank())throw new IllegalStateException("Incomplete cluster configuration.");return value; }
    static void requireLegacyIsolation(Properties properties) {
        for(int node:List.of(1,2)) {
            String url=properties.getProperty("node"+node+".url","");
            LegacyDatabaseIsolation.require(url);
        }
    }
    private Connection raw(int node)throws SQLException {
        Connection connection=DriverManager.getConnection(boundedUrl(required("node"+node+".url")),required("node"+node+".user"),required("node"+node+".password"));
        try{verifyConnection(connection);return connection;}
        catch(SQLException|RuntimeException failure){try{connection.close();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
    }
    static String boundedUrl(String url) {
        if(url==null)throw new IllegalArgumentException("Missing database URL.");
        if(!url.startsWith("jdbc:mysql:"))return url; // Isolated H2 tests only.
        String[] parts=url.split("\\?",2);List<String> parameters=new ArrayList<>();
        if(parts.length==2)for(String parameter:parts[1].split("&")) {
            String name=parameter.split("=",2)[0];
            if(!Set.of("connectTimeout","socketTimeout","pinGlobalTxToPhysicalConnection").contains(name))parameters.add(parameter);
        }
        parameters.add("connectTimeout=3000");parameters.add("socketTimeout=15000");parameters.add("pinGlobalTxToPhysicalConnection=true");
        return parts[0]+"?"+String.join("&",parameters);
    }
    private static void execute(Connection connection,String sql,Object...values)throws SQLException {
        try(PreparedStatement statement=connection.prepareStatement(sql)){bind(statement,values);statement.executeUpdate();}
    }
    private static void bind(PreparedStatement statement,Object...values)throws SQLException { for(int i=0;i<values.length;i++)statement.setObject(i+1,values[i]); }
    private boolean replicated(){return "jdbc".equals(config.getProperty("objectStore.backend","file"));}
    private void verifyConnection(Connection connection) throws SQLException {
        if(!replicated())return;
        ReplicationDatabaseIdentity.verify(connection,ClusterAvailability.recoveryAuthorityIdentity());
    }
    private void verifyRecoveryAuthority() throws SQLException {
        String identity=ClusterAvailability.recoveryAuthorityIdentity();
        if(identity==null)throw new SQLException("Local replicated writer authority is unavailable.");
        ReplicationDatabaseIdentity.validateEndpoints(config);
        try(Connection first=raw(1);Connection second=raw(2);
            Connection decisions=ReplicationDatabaseIdentity.open(config,"objectStore.jdbc.")) {
            for(Connection connection:new Connection[]{first,second,decisions})
                ReplicationDatabaseIdentity.verify(connection,identity);
        }
    }
    private String gate()throws SQLException {
        try(Connection connection=raw(1);Statement statement=connection.createStatement();ResultSet result=statement.executeQuery("SELECT OPERATION_ID FROM CLUSTER_GATE WHERE ID=1")) {
            if(!result.next())throw new SQLException("Missing global gate.");return result.getString(1);
        }
    }
    public boolean recoveryReady() { return !closing && (recovered || executing); }
    /** Private controller recovery must not depend on the /ready admission it enables. */
    public synchronized boolean recoveryProbe() throws Exception {
        if(!recoveryReady())return false;
        if(atomic!=null)atomic.recoveryProbe();
        return recoveryReady();
    }
    public String durabilityMode() throws SQLException {
        if(closing)throw new SQLException("Finance is closing");
        return atomic==null?"legacy":atomic.durabilityMode();
    }
    public boolean atomicMode(){return atomic!=null;}
    public record LoginOutcome(JSONObject response,Model.User user,int httpStatus) { }
    public synchronized LoginOutcome login(HttpServletRequest request,String client,String email,String password)throws Exception{
        if(closing||atomic==null||!recovered)throw new ClusterAvailability.Unavailable();
        ClusterAvailability.requireReady();
        String operation=request.getHeader("X-IAFM-Operation-Id");
        if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"A stable sign-in operation identity is required.");
        String normalized=email.trim().toLowerCase(Locale.ROOT);
        String clientHash=requestHash("/login-client",new JSONObject().put("client",client));
        String emailHash=requestHash("/login-email",new JSONObject().put("emailAddress",normalized));
        String payloadHash=requestHash("/login-attempt",new JSONObject().put("emailAddress",normalized).put("password",password));
        executing=true;
        try {
            LoginOutcome result=atomic.login(operation.toLowerCase(Locale.ROOT),clientHash,emailHash,payloadHash,normalized,password);
            ClusterAvailability.requireReady();return result;
        }finally{executing=false;}
    }
    public synchronized JSONObject loginStatus(String operation,String client,String email)throws Exception{
        if(closing||atomic==null||!recovered)throw new ClusterAvailability.Unavailable();
        ClusterAvailability.requireReady();
        if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"A valid sign-in operation identity is required.");
        String normalized=email.trim().toLowerCase(Locale.ROOT);
        JSONObject result=atomic.loginStatus(operation.toLowerCase(Locale.ROOT),requestHash("/login-client",new JSONObject().put("client",client)),
                requestHash("/login-email",new JSONObject().put("emailAddress",normalized)));
        ClusterAvailability.requireReady();return result;
    }
    private synchronized void recoverSafely() {
        if(closing){recovered=false;return;}
        // Atomic replicated transactions are recovered by InnoDB. Never scan
        // or abort another JVM's logical operation through the old XA journal.
        if(atomic!=null)return;
        // The ready endpoint depends on this recovery scan, so consult the
        // independent local group authority rather than creating a cycle.
        try {
            if(replicated())throw new IllegalStateException("Legacy XA recovery cannot run as a replicated writer.");
            transactions.recover();String operation=gate();
            if(operation!=null&&!reconcile(operation)){recovered=false;return;}
            if(transactions.hasOutstandingDecisions()){recovered=false;return;}
            recovered=true;
        } catch(Exception failure) { recovered=false; /* Keep the durable gate for the next scan. */ }
    }
    private record Branch(int node,int format,byte[] global,byte[] branch,String key,String terminal) implements Xid {
        public int getFormatId(){return format;} public byte[] getGlobalTransactionId(){return global.clone();} public byte[] getBranchQualifier(){return branch.clone();}
    }
    private List<Branch> branches(String operation)throws SQLException {
        List<Branch> values=new ArrayList<>();
        try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT NODE_ID,FORMAT_ID,GLOBAL_ID,BRANCH_ID,XID_KEY,TERMINAL FROM CLUSTER_BRANCHES WHERE OPERATION_ID=? ORDER BY NODE_ID")) {
            statement.setString(1,operation);try(ResultSet result=statement.executeQuery()){while(result.next())values.add(new Branch(result.getInt(1),result.getInt(2),result.getBytes(3),result.getBytes(4),result.getString(5),result.getString(6)));}
        }return values;
    }
    private boolean receipt(int node,String key)throws SQLException {
        try(Connection connection=raw(node);PreparedStatement statement=connection.prepareStatement("SELECT OPERATION_ID FROM CLUSTER_RECEIPTS WHERE XID_KEY=?")) {
            statement.setString(1,key);try(ResultSet result=statement.executeQuery()){return result.next();}
        }
    }
    /** No presumed abort while a live transaction or a durable commit decision exists. */
    private boolean reconcile(String operation)throws Exception {
        String uid;
        try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT TRANSACTION_UID FROM CLUSTER_OPERATIONS WHERE ID=?")) {
            statement.setString(1,operation);try(ResultSet result=statement.executeQuery()){if(!result.next())return false;uid=result.getString(1);}
        }
        if(transactions.isLive(uid)||transactions.hasDecisionLog(uid))return false;
        List<Branch> branches=branches(operation);
        int committed=0;
        for(Branch branch:branches)if(receipt(branch.node,branch.key))committed++;
        if(committed>0&&committed!=2)return false; // Never guess a missing participant's outcome.
        if(committed==0)for(Branch branch:branches) {
            if(!"ABORTED".equals(branch.terminal))transactions.abortOrphan(branch.node,branch);
        }
        finish(operation,committed==2?"COMMITTED":"ABORTED");return true;
    }
    private void finish(String operation,String state)throws SQLException {
        try(Connection connection=raw(1)) {
            connection.setAutoCommit(false);
            try {
                execute(connection,"UPDATE CLUSTER_OPERATIONS SET STATE=? WHERE ID=?",state,operation);
                execute(connection,"UPDATE CLUSTER_GATE SET OPERATION_ID=NULL WHERE ID=1 AND OPERATION_ID=?",operation);
                connection.commit();
            }catch(SQLException failure){connection.rollback();throw failure;}
        }
    }
    static String owner(Model.User user) { return user==null?"public":"1:"+user.getId()+":"+user.getGeneration().toLowerCase(Locale.ROOT); }
    public int ingress(HttpServletRequest request) {
        String node=request.getHeader("X-IAFM-Ingress-Node"),token=request.getHeader("X-IAFM-Ingress-Token");
        if(token==null||!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),required("ingress.token").getBytes(StandardCharsets.UTF_8))||!("1".equals(node)||"2".equals(node)))throw new ClusterAvailability.Unavailable();
        return Integer.parseInt(node);
    }
    public static String canonical(Object value) {
        if(value instanceof JSONObject object) {
            StringJoiner result=new StringJoiner(",","{","}");
            for(String key:new TreeSet<>(object.keySet()))result.add(JSONObject.quote(key)+":"+canonical(object.get(key)));
            return result.toString();
        }
        if(value instanceof JSONArray array) { StringJoiner result=new StringJoiner(",","[","]");for(Object item:array)result.add(canonical(item));return result.toString(); }
        return JSONObject.valueToString(value);
    }
    private static String digest(String value) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    public static String xidKey(Xid xid) { return digest(xid.getFormatId()+":"+HexFormat.of().formatHex(xid.getGlobalTransactionId())+":"+HexFormat.of().formatHex(xid.getBranchQualifier())); }
    private String requestHash(String path,JSONObject input) {
        try {
            javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(required("idempotency.secret").getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((path+"\n"+canonical(input)).getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.GeneralSecurityException impossible){throw new IllegalStateException(impossible);}
    }

    public synchronized JSONObject run(HttpServletRequest request,Model.User user,JSONObject input,boolean mutation,Work work)throws Exception {
        if(closing)throw new ClusterAvailability.Unavailable();
        ClusterAvailability.requireReady();
        if(atomic!=null) {
            if(!recovered)throw new ClusterAvailability.Unavailable();
            String operation=mutation?request.getHeader("X-IAFM-Operation-Id"):UUID.randomUUID().toString();
            if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"A valid operation identity is required.");
            executing=true;
            try {
                JSONObject response=atomic.run(operation.toLowerCase(Locale.ROOT),owner(user),requestHash(request.getServletPath(),input),
                        request.getServletPath(),ingress(request),user,mutation,work,input.has("expectedDataRevision")?String.valueOf(input.get("expectedDataRevision")):null,
                        mutation&&"/deleteUser".equals(request.getServletPath())?DeletionResultCapability.cookie(request,true):null);
                ClusterAvailability.requireReady();
                return response;
            } finally { executing=false; }
        }
        if(!recovered||gate()!=null)throw new ClusterAvailability.Unavailable();
        // A cached result never bypasses revocation or recycled-user generation validation.
        if(user!=null)try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT GENERATION FROM USERS WHERE ID=?")) {
            statement.setInt(1,user.getId());try(ResultSet result=statement.executeQuery()) {
                if(!result.next()||!user.getGeneration().equalsIgnoreCase(result.getString(1)))throw new RequestIdentity.StaleIdentity(true);
            }
        }
        String operation=mutation?request.getHeader("X-IAFM-Operation-Id"):UUID.randomUUID().toString();
        if(operation==null&&user==null&&"/register".equals(request.getServletPath()))operation=UUID.randomUUID().toString();
        if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"A valid operation identity is required.");
        operation=operation.toLowerCase(Locale.ROOT);
        String owner=owner(user),hash=requestHash(request.getServletPath(),input);
        JSONObject previous=existing(operation,owner,hash);
        if(previous!=null){ClusterAvailability.requireReady();return previous;}
        int ingress=ingress(request);
        try(Connection connection=raw(1)) {
            connection.setAutoCommit(false);
            try {
                execute(connection,"INSERT INTO CLUSTER_OPERATIONS(ID,OWNER_KEY,REQUEST_HASH,STATE) VALUES(?,?,?,'PENDING')",operation,owner,hash);
                try(PreparedStatement statement=connection.prepareStatement("UPDATE CLUSTER_GATE SET OPERATION_ID=? WHERE ID=1 AND OPERATION_ID IS NULL")) {
                    statement.setString(1,operation);if(statement.executeUpdate()!=1)throw new ClusterAvailability.Unavailable();
                }
                connection.commit();
            }catch(Exception failure){connection.rollback();throw failure;}
        }
        recovered=false;
        executing=true;
        String id=operation;JSONObject[] result=new JSONObject[1];Exception[] error=new Exception[1];
        try { transactions.execute(id,Set.of(1,2),connections->{
            try(ShardScope scope=ShardScope.begin(connections,ingress)) {
                try {
                    if(user!=null)RequestIdentity.verifyCurrentUser();
                    result[0]=work.run();
                    if(scope.isRollbackOnly())throw new ApiSupport.Failure(409,"The operation was rolled back.");
                    if(!result[0].has("status"))result[0].put("status","success");
                    if(mutation)result[0].put("operationId",id).put("operationStatus","committed");
                    if(user!=null)result[0].put("userGeneration",user.getGeneration());
                    if(user!=null&&"/deleteUser".equals(request.getServletPath()))
                        execute(connections.get(1),"DELETE FROM CLUSTER_RESULTS WHERE OPERATION_ID IN (SELECT ID FROM CLUSTER_OPERATIONS WHERE OWNER_KEY=?)",owner);
                    if(mutation)execute(connections.get(1),"INSERT INTO CLUSTER_RESULTS(OPERATION_ID,PAYLOAD) VALUES(?,?)",id,result[0].toString());
                    List<Branch> enlisted=branches(id);if(enlisted.size()!=2)throw new IllegalStateException("Incomplete transaction participants.");
                    for(Branch branch:enlisted)execute(connections.get(branch.node),"INSERT INTO CLUSTER_RECEIPTS(XID_KEY,OPERATION_ID) VALUES(?,?)",branch.key,id);
                    ClusterAvailability.requireReady();
                }catch(Exception failure){error[0]=failure;throw failure;}
            }
        });
        if(!reconcile(id))throw new ClusterAvailability.Unavailable();
        recovered=!transactions.hasOutstandingDecisions();
        if(error[0]!=null)throw error[0];
        if(!mutation) {
            try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT STATE FROM CLUSTER_OPERATIONS WHERE ID=?")) {
                statement.setString(1,id);try(ResultSet state=statement.executeQuery()) {
                    if(!state.next()||!"COMMITTED".equals(state.getString(1)))throw new ClusterAvailability.Unavailable();
                }
            }
            ClusterAvailability.requireReady();return result[0];
        }
        JSONObject committed=existing(id,owner,hash);
        if(committed==null)throw new ClusterAvailability.Unavailable();
        // Membership can change after the pre-commit check. Do not acknowledge
        // a result until the current replication view is authorized again.
        ClusterAvailability.requireReady();return committed;
        } finally { executing=false; }
    }
    private JSONObject existing(String operation,String owner,String hash)throws SQLException {
        try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT OWNER_KEY,REQUEST_HASH,STATE FROM CLUSTER_OPERATIONS WHERE ID=?")) {
            statement.setString(1,operation);try(ResultSet result=statement.executeQuery()) {
                if(!result.next())return null;
                if(!owner.equals(result.getString(1))||!hash.equals(result.getString(2)))throw new ApiSupport.Failure(409,"Operation identity was already used for different data.");
                if("ABORTED".equals(result.getString(3)))throw new ApiSupport.Failure(409,"This operation was rolled back. Refresh before trying again.");
                if(!"COMMITTED".equals(result.getString(3)))throw new ClusterAvailability.Unavailable();
            }
        }
        try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT PAYLOAD FROM CLUSTER_RESULTS WHERE OPERATION_ID=?")) {
            statement.setString(1,operation);try(ResultSet result=statement.executeQuery()){if(!result.next())throw new SQLException("Missing committed result.");return new JSONObject(result.getString(1));}
        }
    }
    public synchronized JSONObject deletedUserStatus(Model.User actor,String operation)throws Exception{
        if(closing||atomic==null||!recovered)throw new ClusterAvailability.Unavailable();
        if(actor==null||actor.getId()<=0||!RequestIdentity.validGeneration(actor.getGeneration())||!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"Invalid deletion result proof.");
        ClusterAvailability.requireReady();
        JSONObject result=atomic.deletedUserStatus(actor,operation.toLowerCase(Locale.ROOT));
        ClusterAvailability.requireReady();return result.optBoolean("notDeleted",false)?null:result;
    }
    public synchronized JSONObject deletedUserCookieStatus(HttpServletRequest request,String operation)throws Exception{
        if(closing||atomic==null||!recovered)throw new ClusterAvailability.Unavailable();
        if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"Invalid deletion result proof.");
        String cookie=DeletionResultCapability.cookie(request,false);if(cookie==null)return null;
        ClusterAvailability.requireReady();JSONObject result=atomic.deletedUserCookieStatus(cookie,operation.toLowerCase(Locale.ROOT));
        ClusterAvailability.requireReady();return result;
    }
    public synchronized JSONObject status(Model.User user,String operation)throws SQLException {
        if(atomic!=null) {
            try { return atomic.status(user,operation); }
            catch(SQLException|RuntimeException failure){throw failure;}
            catch(Exception failure){throw new SQLException("Atomic operation status is unavailable");}
        }
        if(!RequestIdentity.validGeneration(operation))throw new ApiSupport.Failure(400,"Invalid operation identity.");
        JSONObject response=new JSONObject().put("status","success").put("operationId",operation).put("state","absent");
        try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT STATE FROM CLUSTER_OPERATIONS WHERE ID=? AND OWNER_KEY=?")) {
            statement.setString(1,operation);statement.setString(2,owner(user));try(ResultSet result=statement.executeQuery()) {
                if(result.next())response.put("state",switch(result.getString(1)){case "COMMITTED"->"committed";case "ABORTED"->"aborted";default->"pending";});
            }
        }
        if("committed".equals(response.getString("state")))try(Connection connection=raw(1);PreparedStatement statement=connection.prepareStatement("SELECT PAYLOAD FROM CLUSTER_RESULTS WHERE OPERATION_ID=?")) {
            statement.setString(1,operation);try(ResultSet result=statement.executeQuery()){if(!result.next())throw new SQLException("Missing committed operation result.");response.put("result",new JSONObject(result.getString(1)));}
        }return response;
    }
    private final class Journal implements XaTransactions.Journal {
        public void uid(String operation,String uid)throws Exception {try(Connection connection=raw(1)){execute(connection,"UPDATE CLUSTER_OPERATIONS SET TRANSACTION_UID=? WHERE ID=?",uid,operation);}}
        public void started(String operation,int node,Xid xid)throws Exception {try(Connection connection=raw(1)){execute(connection,"INSERT INTO CLUSTER_BRANCHES(OPERATION_ID,NODE_ID,FORMAT_ID,GLOBAL_ID,BRANCH_ID,XID_KEY) VALUES(?,?,?,?,?,?)",operation,node,xid.getFormatId(),xid.getGlobalTransactionId(),xid.getBranchQualifier(),xidKey(xid));}}
        public void ended(int node,Xid xid,String terminal)throws Exception {try(Connection connection=raw(1)){execute(connection,"UPDATE CLUSTER_BRANCHES SET TERMINAL=? WHERE NODE_ID=? AND XID_KEY=?",terminal,node,xidKey(xid));}}
        public boolean committed(int node,Xid xid)throws Exception {return receipt(node,xidKey(xid));}
    }
    public static void shutdown() {
        FinanceCluster prior;
        synchronized(FinanceCluster.class){shuttingDown=true;prior=instance;}
        if(prior!=null)try{prior.close();}catch(Exception ignored){/* Keep the process lock if draining failed. */}
    }
    @Override public void close()throws Exception {
        closing=true;recovered=false;
        if(atomic!=null) {
            synchronized(this){ /* Drain local atomic work; no recovery ownership survives the connection. */ }
            return;
        }
        recovery.shutdownNow();
        synchronized(this){ /* Drain the executing operation before closing XA resources. */ }
        if(!recovery.awaitTermination(30,TimeUnit.SECONDS))throw new IllegalStateException("Recovery did not drain; coordinator ownership is retained.");
        synchronized(this){transactions.close();}
    }
}

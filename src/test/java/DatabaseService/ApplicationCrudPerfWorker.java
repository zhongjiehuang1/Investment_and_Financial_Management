package DatabaseService;

import Controller.ApiSupport;
import Model.User;
import org.json.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.net.URI;
import java.net.URLDecoder;
import javax.net.ssl.SSLSocket;

/** Bounded component rehearsal: existing isolated schemas and fresh synthetic actor only. */
public final class ApplicationCrudPerfWorker {
    private static String stage="configuration";
    private final Properties config=new Properties();
    private final UUID namespace;
    private final AtomicFinanceTransactions service;
    private final String email,assetName;
    private final JSONArray timings=new JSONArray();
    private User user;private int assetId;private String assetGeneration;
    private ApplicationCrudPerfWorker(Path file,UUID namespace)throws Exception {
        require(Runtime.version().feature()==21&&Runtime.getRuntime().maxMemory()<=170L*1024*1024);
        require(file.isAbsolute()&&Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(file)&&Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------"))&&Files.getOwner(file).getName().equals(System.getProperty("user.name")));
        try(var reader=Files.newBufferedReader(file)){config.load(reader);}
        for(int node:List.of(1,2)){String url=config.getProperty("node"+node+".url","");require(url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/finance_atomic_validation_"+(node==1?"a":"b")+"[?].*"));tlsPolicy(url);}
        require(AtomicFinanceTransactions.enabled(config));this.namespace=namespace;
        email=namespace.toString().replace("-","")+"@perf.invalid";assetName="perf-"+namespace.toString().substring(0,20);
        service=new AtomicFinanceTransactions(config);
        stage="transport";try(Connection c=physical()){verifyTransport(c);}
        stage="recovery";
        service.recoveryProbe();
    }
    private static void require(boolean condition)throws SQLException{if(!condition)throw new SQLException("Synthetic scope or assertion failed");}
    private static void tlsPolicy(String url)throws Exception {
        Map<String,String> values=new HashMap<>();
        for(String item:URI.create(url.substring(5)).getRawQuery().split("&")){String[] pair=item.split("=",2);String key=pair[0].toLowerCase(Locale.ROOT);require(!values.containsKey(key));values.put(key,pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"");}
        require("VERIFY_IDENTITY".equals(values.get("sslmode"))&&"TLSv1.3".equals(values.getOrDefault("tlsversions",values.get("enabledtlsprotocols"))));
        URI trust=URI.create(Objects.requireNonNull(values.get("trustcertificatekeystoreurl")));require("file".equals(trust.getScheme())&&Files.isRegularFile(Path.of(trust)));
    }
    private UUID operation(String phase){return UUID.nameUUIDFromBytes((namespace+":"+phase).getBytes(StandardCharsets.UTF_8));}
    private static String hash(String text)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}
    private Connection physical()throws SQLException{return DriverManager.getConnection(AtomicFinanceTransactions.transactionUrl(config.getProperty("node1.url")),config.getProperty("node1.user"),config.getProperty("node1.password"));}
    private static void verifyTransport(Connection c)throws Exception {
        require(c.getMetaData().getDatabaseProductVersion().equals("26.10.0")&&c.getMetaData().getDriverVersion().startsWith("mysql-connector-j-26.10.0 "));
        var mysql=c.unwrap(com.mysql.cj.jdbc.JdbcConnection.class);
        require(String.valueOf(mysql.getPropertySet().getEnumProperty(com.mysql.cj.conf.PropertyKey.sslMode).getValue()).equals("VERIFY_IDENTITY")&&mysql.getSession().isSSLEstablished());
        var socket=((com.mysql.cj.NativeSession)mysql.getSession()).getProtocol().getSocketConnection().getMysqlSocket();
        require(socket instanceof SSLSocket tls&&tls.getSession().getProtocol().equals("TLSv1.3"));
    }
    private List<RequestIdentity.Record> records(String phase){return Set.of("edit","stale","rollback","delete").contains(phase)?List.of(new RequestIdentity.Record("ALL_NON_INVESTMENT_ASSETS",assetId,assetGeneration)):List.of();}
    private JSONObject call(String phase,boolean mutation,String revision,FinanceCluster.Work work)throws Exception {
        long start=System.nanoTime();String outcome="PASS";
        stage="crud:"+phase;
        try(RequestIdentity identity=user==null?null:RequestIdentity.begin(user,mutation,records(phase),List.of())){
            JSONObject result=service.run(operation(phase).toString(),FinanceCluster.owner(user),hash(namespace+":"+phase),"/perf/"+phase,1,user,mutation,work,revision);
            require(result.getString("durability").equals("dual"));return result;
        }catch(Exception|AssertionError failure){outcome=failure instanceof FinanceDataRevision.Rejected?"EXPECTED_STALE":failure instanceof ApiSupport.Failure?"APPLICATION_REJECTED":"FAIL";throw failure;}
        finally{timings.put(new JSONObject().put("phase",phase).put("applicationMillis",(System.nanoTime()-start)/1_000_000.0).put("outcome",outcome));}
    }
    private JSONObject read(String phase)throws Exception{return call(phase,false,null,()->Model.Controller.ReadData.assetsPage(user,new JSONObject().put("kind","nonInvestment").put("page",1).put("pageSize",10)));}
    private void loadUser()throws Exception {
        try(Connection c=physical();PreparedStatement q=c.prepareStatement("SELECT ID,GENERATION FROM USERS WHERE EMAIL_ADDRESS=?")){
            q.setString(1,email);try(ResultSet r=q.executeQuery()){require(r.next());user=new User();user.setId(r.getInt(1));user.setGeneration(r.getString(2));user.setCurrencyCurrentChosen("GBP");require(!r.next());}
        }
    }
    private JSONObject edit(String value)throws Exception{return ApiSupport.result(Model.Controller.AssetsData.NonInvestmentAsset.EditAsset.editNonInvestmentAsset(user.getId(),assetId,assetName,"DEMAND_DEPOSIT","VERY_LOW_AND_LOW_RISK_TYPE",value,"GBP"));}
    private void run()throws Exception {
        try(Connection c=physical();PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM USERS WHERE EMAIL_ADDRESS=?")){q.setString(1,email);try(ResultSet r=q.executeQuery()){require(r.next()&&r.getInt(1)==0);}}
        try(Connection c=physical();PreparedStatement q=c.prepareStatement("SELECT COUNT(*) FROM CLUSTER_OPERATIONS WHERE ID=?")){
            for(String phase:List.of("register","create","edit","rollback","delete","stale")){q.setString(1,operation(phase).toString());try(ResultSet r=q.executeQuery()){require(r.next()&&r.getInt(1)==0);}}
        }
        String revision=call("initial-read",false,null,JSONObject::new).getString("dataRevision");
        String password=UUID.randomUUID().toString();
        revision=call("register",true,revision,()->ApiSupport.result(Model.Controller.RegisterAndLogin.Register.userRegister(email,password,"Synthetic","Performance"))).getString("dataRevision");
        loadUser();
        var command=new Controller.AssetsData.NonInvestmentAsset.AddAsset.Command();
        for(var value:Map.of("AssetName",assetName,"AssetType","DEMAND_DEPOSIT","RiskType","VERY_LOW_AND_LOW_RISK_TYPE","Amount","10.00","Currency","GBP").entrySet()){
            Method setter=command.getClass().getDeclaredMethod("set"+value.getKey(),String.class);setter.setAccessible(true);setter.invoke(command,value.getValue());
        }
        String created=call("create",true,revision,()->ApiSupport.result(Model.Controller.AssetsData.NonInvestmentAsset.AddAsset.addNonInvestmentAsset(user.getId(),command))).getString("dataRevision");
        JSONObject first=read("read-created");JSONArray items=first.getJSONArray("items");require(items.length()==1);JSONObject row=items.getJSONObject(0);
        assetId=row.getInt("id");assetGeneration=row.getString("generation");require(row.getString("amount").equals("10.00"));
        String edited=call("edit",true,created,()->edit("11.00")).getString("dataRevision");
        call("edit",true,created,()->{throw new AssertionError("Replay executed work");});
        try{call("stale",true,created,()->edit("99.00"));throw new AssertionError("Stale CAS accepted");}catch(FinanceDataRevision.Rejected expected){require(expected.httpStatus()==409);}
        try{call("rollback",true,edited,()->{edit("99.00");ShardScope.current().markRollbackOnly();return new JSONObject();});throw new AssertionError("Rollback accepted");}catch(ApiSupport.Failure expected){require(expected.httpStatus()==409);}
        require(read("read-rollback").getJSONArray("items").getJSONObject(0).getString("amount").equals("11.00"));
        require(service.status(user,operation("rollback").toString()).getString("state").equals("absent"));
        String retried=call("rollback",true,edited,()->edit("12.00")).getString("dataRevision");
        call("delete",true,retried,()->ApiSupport.result(Model.Controller.AssetsData.NonInvestmentAsset.DeleteAsset.deleteNonInvestmentAsset(user.getId(),assetId)));
        long statusStart=System.nanoTime();JSONObject deleted=service.status(user,operation("delete").toString());
        require(deleted.getString("state").equals("committed")&&deleted.getString("durability").equals("dual"));
        timings.put(new JSONObject().put("phase","delete-replay-status").put("applicationMillis",(System.nanoTime()-statusStart)/1_000_000.0).put("outcome","PASS"));
        require(read("read-deleted").getJSONArray("items").isEmpty());
    }
    private JSONObject inspect()throws Exception {
        stage="inspection";
        if(user==null)loadUser();
        try(Connection c=physical()){
            verifyTransport(c);c.setReadOnly(true);c.setAutoCommit(false);
            try {
                try(Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT @@session.transaction_read_only")){require(r.next()&&r.getInt(1)==1);}
                JSONArray rows=new JSONArray();
                for(String schema:List.of("finance_atomic_validation_a","finance_atomic_validation_b")){
                    try(PreparedStatement q=c.prepareStatement("SELECT ID,GENERATION,EMAIL_ADDRESS FROM "+schema+".USERS WHERE EMAIL_ADDRESS=?")){
                        q.setString(1,email);try(ResultSet r=q.executeQuery()){while(r.next())rows.put(new JSONArray().put(schema).put("user").put(r.getInt(1)).put(r.getString(2)).put(r.getString(3)));}
                    }
                    try(PreparedStatement q=c.prepareStatement("SELECT ID,GENERATION,NAME,AMOUNT,CURRENCY FROM "+schema+".ALL_NON_INVESTMENT_ASSETS WHERE USER_ID=? ORDER BY ID,GENERATION")){
                        q.setInt(1,user.getId());try(ResultSet r=q.executeQuery()){while(r.next()){JSONArray row=new JSONArray().put(schema).put("asset");for(int i=1;i<=5;i++)row.put(r.getString(i));rows.put(row);}}
                    }
                }
                for(String phase:List.of("register","create","edit","rollback","delete","stale")){
                    try(PreparedStatement q=c.prepareStatement("SELECT O.ID,O.STATE,O.REQUEST_HASH,R.PAYLOAD FROM CLUSTER_OPERATIONS O LEFT JOIN CLUSTER_RESULTS R ON R.OPERATION_ID=O.ID WHERE O.ID=? AND O.OWNER_KEY=?")){
                        q.setString(1,operation(phase).toString());q.setString(2,phase.equals("register")?"public":FinanceCluster.owner(user));try(ResultSet r=q.executeQuery()){while(r.next())rows.put(new JSONArray().put("receipt").put(r.getString(1)).put(r.getString(2)).put(r.getString(3)).put(r.getString(4)));}
                    }
                }
                return new JSONObject().put("syntheticRowsSha256",hash(rows.toString())).put("scopedRows",rows.length());
            }finally{c.rollback();}
        }
    }
    public static void main(String[] args){
        long mainStarted=System.nanoTime();
        try {
            require(args.length==3&&Set.of("run","inspect").contains(args[0]));UUID nonce=UUID.fromString(args[2]);require(nonce.toString().equals(args[2]));
            System.setProperty("iafm.base.currency","GBP");long initialized=System.nanoTime();
            var worker=new ApplicationCrudPerfWorker(Path.of(args[1]),nonce);double setup=(System.nanoTime()-initialized)/1_000_000.0;
            if(args[0].equals("run"))worker.run();
            System.out.println(new JSONObject().put("status","PASS").put("application","IAFM").put("namespace",nonce.toString()).put("setupMillis",setup).put("timings",worker.timings).put("inspection",worker.inspect()).put("mainMillis",(System.nanoTime()-mainStarted)/1_000_000.0).put("javaMajor",21).put("mySqlVersion","26.10.0").put("connectorVersion","26.10.0").put("tlsProtocol","TLSv1.3").put("sslMode","VERIFY_IDENTITY").put("retainedSyntheticActor",true).put("productionSchemasAccessed",false).put("ddlExecuted",false));
        }catch(Throwable failure){while(failure instanceof InvocationTargetException&&failure.getCause()!=null)failure=failure.getCause();JSONObject safe=new JSONObject().put("status","FAIL").put("stage",stage).put("exceptionClass",failure.getClass().getSimpleName()).put("syntheticRowsRetainedForInspection",true);if(failure instanceof SQLException sql)safe.put("sqlState",sql.getSQLState()==null?"":sql.getSQLState()).put("vendorCode",sql.getErrorCode());System.err.println(safe);System.exit(2);}
    }
}

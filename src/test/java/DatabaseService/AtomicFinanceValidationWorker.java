package DatabaseService;

import Controller.ApiSupport;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Explicit synthetic multi-JVM worker for a real two-member MySQL/controller
 * rehearsal. It creates/drops no database, never reads production configuration,
 * and refuses all schemas except finance_atomic_validation_* on loopback.
 */
public final class AtomicFinanceValidationWorker {
    private AtomicFinanceValidationWorker() { }
    public static void main(String[] args) {
        int exit = 1;
        try {
            require(args.length >= 2 && Set.of("--run", "--legacy-run", "--status", "--login", "--login-status", "--rotate-login", "--inspect").contains(args[0]), "Explicit validation action required");
            String operation = args[1];require(RequestIdentity.validGeneration(operation), "Synthetic operation UUID required");
            boolean legacy=args[0].equals("--legacy-run"),run=args[0].equals("--run")||legacy,login = args[0].equals("--login"),loginStatus=args[0].equals("--login-status"),rotate=args[0].equals("--rotate-login"),inspect=args[0].equals("--inspect");
            boolean businessMutation=run||rotate;
            require(legacy?args.length==3:run?args.length>=4&&args.length<=5:login?args.length>=3&&args.length<=4:rotate?args.length==4:args.length==2,"Unexpected validation arguments");
            String expectedRevision=businessMutation&&!legacy?args[3]:null;
            int amount = run ? Integer.parseInt(args[2]) : 0;require(!run || amount >= 1 && amount <= 1000, "Bounded synthetic amount required");
            require(!login||Set.of("valid","invalid","rotated").contains(args[2]),"Explicit synthetic credential variant required");
            require(!rotate||Set.of("original","rotated").contains(args[2]),"Explicit synthetic rotation variant required");
            String phase=run&&!legacy&&args.length==5?args[4]:login&&args.length==4?args[3]:"none";require(Set.of("none", "before-commit", "before-confirm").contains(phase), "Unknown pause phase");
            String location = System.getenv("IAFM_ATOMIC_VALIDATION_CONFIG");require(location != null && Path.of(location).isAbsolute(), "Explicit private validation configuration required");
            Path path = Path.of(location);require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "Private regular configuration required");
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            require(!permissions.contains(PosixFilePermission.GROUP_READ) && !permissions.contains(PosixFilePermission.GROUP_WRITE)
                    && !permissions.contains(PosixFilePermission.OTHERS_READ) && !permissions.contains(PosixFilePermission.OTHERS_WRITE), "Configuration must be private to its owner");
            Properties properties = new Properties();try(var reader = Files.newBufferedReader(path)){properties.load(reader);}
            require(AtomicFinanceTransactions.enabled(properties), "Real atomic backend required");
            for(int node:List.of(1,2))require(properties.getProperty("node"+node+".url", "").matches(
                    "jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/finance_atomic_validation_[a-z0-9_]+(?:[?].*)?"), "Disposable atomic validation schemas required");
            Class.forName("com.mysql.cj.jdbc.Driver");
            long started=System.currentTimeMillis();boolean newReceipt=false;
            try(Connection database=DriverManager.getConnection(AtomicFinanceTransactions.transactionUrl(properties.getProperty("node1.url")),properties.getProperty("node1.user"),properties.getProperty("node1.password"))){
                require(database.getMetaData().getDatabaseProductName().equals("MySQL") && database.getMetaData().getDatabaseProductVersion().startsWith("26.10."), "Actual MySQL 26.10 required");
                AtomicFinanceTransactions.verifyDurability(database);verifyUtc(database);
                if(businessMutation||login)try(PreparedStatement query=database.prepareStatement("SELECT COUNT(*) FROM "+(businessMutation?"CLUSTER_OPERATIONS WHERE ID=?":"LOGIN_ATTEMPTS WHERE OPERATION_ID=?"))){
                    query.setString(1,operation);try(ResultSet rows=query.executeQuery()){require(rows.next(),"Receipt existence check failed");newReceipt=rows.getInt(1)==0;}
                }
                System.out.println("VERSION mysql="+database.getMetaData().getDatabaseProductVersion().replaceAll("[^A-Za-z0-9._-]", "_")
                        +" jdbc="+database.getMetaData().getDriverVersion().replaceAll("[^A-Za-z0-9._-]", "_"));
            }
            AtomicReplicationPermit real = new AtomicReplicationPermit(properties);
            AtomicFinanceTransactions.Authority authority = new AtomicFinanceTransactions.Authority(){
                public AtomicFinanceTransactions.Proof capture()throws SQLException{return real.capture();}
                public void verify(AtomicFinanceTransactions.Proof proof)throws SQLException{
                    if(login&&phase.equals("before-commit"))try{barrier("before-commit");}catch(Exception failure){throw new SQLException("Validation pause interrupted");}
                    real.verify(proof);
                }
                public String confirm(Connection connection,AtomicFinanceTransactions.Proof proof,String id)throws SQLException{
                    if(phase.equals("before-confirm"))try{barrier("before-confirm");}catch(Exception failure){throw new SQLException("Validation pause interrupted");}
                    return real.confirm(connection,proof,id);
                }
            };
            // The regular production connection factory/identity checks remain
            // active; only a pause immediately before confirmation is injected.
            AtomicFinanceTransactions service = new AtomicFinanceTransactions(properties, authority);
            JSONObject result;
            if(run){
                String hash=legacy?HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("atomic-validation:"+amount).getBytes(StandardCharsets.UTF_8))):hash(properties.getProperty("idempotency.secret"),"atomic-validation:"+amount+"\n"+expectedRevision);
                result=service.run(operation.toLowerCase(Locale.ROOT),"public",hash,"/isolatedAtomicValidation",2,null,true,()->{
                    for(Connection database:ShardScope.current().connections().values())try(PreparedStatement update=database.prepareStatement("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+? WHERE ID=1")){
                        update.setInt(1,amount);require(update.executeUpdate()==1,"Missing synthetic balance");database.commit();
                    }
                    if(phase.equals("before-commit"))barrier("before-commit");
                    return new JSONObject().put("delta",amount);
                },expectedRevision);
            }else if(rotate){
                String email=properties.getProperty("validation.email","");require(email.equals("atomic-validation@finance.invalid"),"Exact synthetic login identity required");
                String original=properties.getProperty("validation.password","");require(original.length()>=12,"Private synthetic password required");
                String rotated=original+"!synthetic-rotation-v1",current=args[2].equals("rotated")?original:rotated,target=args[2].equals("rotated")?rotated:original;
                String requestHash=hash(properties.getProperty("idempotency.secret"),"rotate-login:"+email+"\n"+args[2]+"\n"+current+"\n"+expectedRevision);
                result=service.run(operation,"public",requestHash,"/isolatedLoginCredentialRotation",1,null,true,()->{
                    Connection database=ShardScope.current().connection(1);
                    Model.User user=AtomicLoginAttempts.authenticateCurrent(database,email,current);
                    if(user==null)throw new ApiSupport.Failure(401,"Current synthetic credential must be verified");
                    try(PreparedStatement query=ShardScope.current().connection(2).prepareStatement("SELECT GENERATION FROM USERS WHERE ID=?")){
                        query.setInt(1,user.getId());try(ResultSet rows=query.executeQuery()){require(rows.next()&&user.getGeneration().equals(rows.getString(1)),"Synthetic identity shadow changed");}
                    }
                    try(PreparedStatement update=database.prepareStatement("UPDATE USERS SET PASSWORD=? WHERE ID=? AND GENERATION=?")){
                        update.setString(1,Model.Tools.Passwords.encode(target));update.setInt(2,user.getId());update.setString(3,user.getGeneration());require(update.executeUpdate()==1,"Synthetic identity changed");
                    }
                    return new JSONObject().put("credentialVariant",args[2]);
                },expectedRevision);
            }else if(inspect){
                result=service.run(operation,"public","0".repeat(64),"/isolatedAtomicInspection",1,null,false,()->{
                    org.json.JSONArray balances=new org.json.JSONArray();
                    for(int logical:List.of(1,2))try(Statement query=ShardScope.current().connection(logical).createStatement();ResultSet rows=query.executeQuery("SELECT AMOUNT FROM TEST_BALANCE WHERE ID=1")){
                        require(rows.next(),"Synthetic balance missing");balances.put(new JSONObject().put("logicalNode",logical).put("amount",rows.getLong(1)));
                    }
                    JSONObject out=new JSONObject().put("balances",balances);
                    Connection database=ShardScope.current().connection(1);
                    for(String table:List.of("CLUSTER_OPERATIONS","LOGIN_ATTEMPTS"))try(Statement query=database.createStatement();ResultSet rows=query.executeQuery("SELECT COUNT(*) FROM "+table)){
                        require(rows.next(),"Synthetic receipt count missing");out.put(table.equals("CLUSTER_OPERATIONS")?"operationCount":"loginAttemptCount",rows.getLong(1));
                    }
                    try(PreparedStatement query=database.prepareStatement("SELECT ATTEMPTS,WINDOW_START_MS FROM LOGIN_LIMITS WHERE CLIENT_HASH=?")){
                        query.setString(1,hash(properties.getProperty("idempotency.secret"),"login-client:synthetic-loopback"));try(ResultSet rows=query.executeQuery()){
                            out.put("loginBudgetCount",rows.next()?rows.getInt(1):0);if(out.getInt("loginBudgetCount")>0)out.put("loginBudgetWindowStartMs",rows.getLong(2));
                        }
                    }
                    return out;
                });
            }else if(login||loginStatus){
                String email=properties.getProperty("validation.email","");require(email.equals("atomic-validation@finance.invalid"),"Exact synthetic login identity required");
                String secret=properties.getProperty("idempotency.secret","");require(secret.length()>=32,"Private validation hashing key required");
                String client=hash(secret,"login-client:synthetic-loopback"),emailHash=hash(secret,"login-email:"+email);
                if(login){
                    String password=properties.getProperty("validation.password","");require(password.length()>=12,"Private synthetic password required");
                    if(args[2].equals("invalid"))password=password+"!intentional-invalid-validation";
                    if(args[2].equals("rotated"))password=password+"!synthetic-rotation-v1";
                    var outcome=service.login(operation.toLowerCase(Locale.ROOT),client,emailHash,hash(secret,"login-attempt:"+email+"\n"+password),email,password);
                    result=outcome.response().put("sessionEligible",outcome.user()!=null);
                }else result=service.loginStatus(operation.toLowerCase(Locale.ROOT),client,emailHash);
            }else result=service.status(null,operation.toLowerCase(Locale.ROOT));
            if(newReceipt){
                try(Connection database=DriverManager.getConnection(AtomicFinanceTransactions.transactionUrl(properties.getProperty("node1.url")),properties.getProperty("node1.user"),properties.getProperty("node1.password"));
                    PreparedStatement query=database.prepareStatement("SELECT "+(businessMutation?"CREATED_AT FROM CLUSTER_OPERATIONS WHERE ID=?":"CREATED_AT_MS FROM LOGIN_ATTEMPTS WHERE OPERATION_ID=?"))){
                    verifyUtc(database);query.setString(1,operation);try(ResultSet rows=query.executeQuery()){
                        require(rows.next(),"New receipt timestamp missing");long created=businessMutation?rows.getTimestamp(1).getTime():rows.getLong(1);
                        require(created>=started-60000&&created<=System.currentTimeMillis()+60000,"New receipt timestamp is not near current UTC");
                    }
                }
                System.out.println("TIME newReceiptWithinCurrentUtcWindow=true");
            }
            System.out.println("PASS action="+args[0].substring(2)+" result="+result);
            exit=0;
        }catch(FinanceDataRevision.Rejected conflict){System.err.println("FAIL type=snapshot status="+conflict.httpStatus()+" code="+conflict.code()+" operationId="+conflict.operationId());exit=3;}
        catch(ApiSupport.Failure conflict){System.err.println("FAIL type=application-conflict status="+conflict.httpStatus());exit=2;}
        catch(Exception|AssertionError failure){
            System.err.println("FAIL type="+failure.getClass().getSimpleName()+(failure instanceof SQLException sql?" SQLState="+sql.getSQLState()+" errorCode="+sql.getErrorCode():"")+" reason="+reason(failure));
        }
        if(exit!=0)System.exit(exit);
    }

    private static void barrier(String phase)throws Exception{
        String location=System.getenv("IAFM_ATOMIC_VALIDATION_BARRIER");require(location!=null&&Path.of(location).isAbsolute(),"Explicit private pause directory required");
        Path directory=Path.of(location);require(Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS),"Existing private pause directory required");
        Path entered=directory.resolve("entered"),release=directory.resolve("release");require(!Files.exists(entered)&&!Files.exists(release),"Fresh pause directory required");
        Files.writeString(entered,phase+"\n",StandardOpenOption.CREATE_NEW);
        System.out.println("READY phase="+phase+" pid="+ProcessHandle.current().pid());System.out.flush();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        while(!Files.exists(release)&&System.nanoTime()<deadline)Thread.sleep(50);
        require(Files.isRegularFile(release,LinkOption.NOFOLLOW_LINKS),"Validation pause timed out");
    }

    private static void verifyUtc(Connection database)throws SQLException{
        try(Statement query=database.createStatement();ResultSet rows=query.executeQuery("SELECT CURRENT_TIMESTAMP(3),UTC_TIMESTAMP(3),@@SESSION.time_zone")){
            require(rows.next()&&Set.of("UTC","+00:00").contains(rows.getString(3)),"Explicit UTC SQL session required");
            long current=rows.getTimestamp(1).getTime(),utc=rows.getTimestamp(2).getTime();
            require(Math.abs(current-utc)<1000&&Math.abs(utc-System.currentTimeMillis())<60000,"SQL/JDBC UTC timestamp mismatch");
        }
    }

    /** Whitelisted constant categories only: never echo server/SQL/credential messages. */
    private static String reason(Throwable failure){
        return switch(String.valueOf(failure.getMessage())){
            case "Atomic writer authority is unavailable"->"AUTHORITY_UNAVAILABLE";
            case "Atomic writer epoch or membership changed"->"AUTHORITY_CHANGED";
            case "Committed operation has an unconfirmed outcome"->"CONFIRM_UNKNOWN";
            case "Atomic replicated durability or UTC session settings are not enabled"->"DURABILITY_OR_UTC";
            case "Atomic authority returned incomplete evidence"->"AUTHORITY_INCOMPLETE";
            case "Committed GTID evidence is unavailable"->"GTID_UNAVAILABLE";
            case "Invalid credential identity"->"INVALID_CREDENTIAL_GENERATION";
            case "Unknown durable sign-in outcome"->"INVALID_LOGIN_RECEIPT";
            default->"UNCLASSIFIED";
        };
    }

    private static String hash(String secret,String text)throws Exception{
        javax.crypto.Mac mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static void require(boolean condition,String detail){if(!condition)throw new IllegalArgumentException(detail);}
}

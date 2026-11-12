package DatabaseService;

import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Test-classes ONLY: create two fresh synthetic HTTP actors in the isolated clone.
 * No production flag, servlet, credential, DDL, cleanup or service operation.
 */
public final class FinanceHttpFixtureWorker {
    private static Properties privateConfig(String key)throws Exception{
        String name=System.getenv(key);if(name==null)throw new IllegalArgumentException();Path path=Path.of(name);
        if(!path.isAbsolute()||!path.startsWith("/var/lib/codex-validation/app-config")||Files.isSymbolicLink(path))throw new IllegalArgumentException();
        Properties p=new Properties();try(var reader=Files.newBufferedReader(path)){p.load(reader);}return p;
    }
    private static String hmac(String secret,String value)throws Exception{
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
    static String email(String suite,String actor){return "p2-"+suite.substring(0,12)+"-"+actor+"@finance.invalid";}
    public static void main(String[] args){
        try{
            if(args.length<2||!Set.of("--token","--provision").contains(args[0])||!RequestIdentity.validGeneration(args[1]))throw new IllegalArgumentException();
            boolean provision=args[0].equals("--provision");if(args.length!=(provision?4:2))throw new IllegalArgumentException();
            String suite=UUID.fromString(args[1]).toString(),operation=provision?UUID.fromString(args[2]).toString():UUID.randomUUID().toString();
            Properties p=privateConfig("IAFM_HTTP_VALIDATION_FULL_CONFIG"),validation=privateConfig("IAFM_HTTP_VALIDATION_PASSWORD_CONFIG");
            for(int node:List.of(1,2)){
                var uri=java.net.URI.create(p.getProperty("node"+node+".url").substring(5));
                if(!uri.getHost().equals("127.0.0.1")||!Set.of(331,332).contains(uri.getPort())||!uri.getPath().equals(node==1?"/IAFM":"/IAFM_peer"))throw new IllegalArgumentException();
            }
            try(Connection c=DriverManager.getConnection(AtomicFinanceTransactions.transactionUrl(p.getProperty("node1.url")),p.getProperty("node1.user"),p.getProperty("node1.password"));Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT @@datadir")){
                if(!r.next()||!r.getString(1).equals("/var/lib/codex-validation/data/"))throw new IllegalArgumentException();
                if(!c.getMetaData().getDatabaseProductName().equals("MySQL")||!c.getMetaData().getDatabaseProductVersion().startsWith("26.10."))throw new IllegalArgumentException();
            }
            String credential=validation.getProperty("validation.password","");if(credential.length()<12)throw new IllegalArgumentException();
            String password=hmac(credential,"iafm-http-p2:"+suite).substring(0,32);
            AtomicFinanceTransactions service=new AtomicFinanceTransactions(p);
            if(provision)service.initialize();
            String request=provision?hmac(p.getProperty("idempotency.secret"),"http-fixture-provision:"+suite+"\n"+args[3]):"0".repeat(64);
            JSONObject result=service.run(operation,"public",request,"/isolatedHttpFixtureProvision",1,null,provision,()->{
                JSONObject out=new JSONObject();
                if(provision)for(String actor:List.of("a","b"))if(!DatabaseService.Model.Controller.RegisterAndLogin.Register.userRegister(email(suite,actor),password,"Validation"+actor,"ProtocolTwo"))throw new SQLException("Synthetic actor provisioning did not complete");
                return out.put("suiteId",suite).put("syntheticActors",2);
            },provision?args[3]:null);
            System.out.println("PASS "+result);
        }catch(FinanceDataRevision.Rejected failure){System.err.println("FAIL snapshotStatus="+failure.httpStatus()+" code="+failure.code());System.exit(3);}
        catch(Exception|AssertionError failure){System.err.println("FAIL type="+failure.getClass().getSimpleName()+(failure instanceof SQLException sql?" SQLState="+sql.getSQLState()+" errorCode="+sql.getErrorCode():""));System.exit(1);}
    }
}

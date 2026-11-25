package DatabaseService;

import com.mysql.cj.jdbc.MysqlXADataSource;
import org.json.JSONObject;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.*;
import javax.transaction.xa.XAResource;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;

/** Separate-process, disposable-database validation. Never used by the application. */
public final class FinanceClusterCrashHarness {
    public static void main(String[] arguments)throws Exception {
        if(arguments.length!=2)throw new IllegalArgumentException("private configuration and crash/recover required");
        Properties config=new Properties();try(var reader=Files.newBufferedReader(Path.of(arguments[0]))){config.load(reader);}
        String phase=arguments[1];if(!Set.of("crash","recover").contains(phase))throw new IllegalArgumentException("Unknown phase");
        Map<Integer,XADataSource> sources=new TreeMap<>();
        for(int node:List.of(1,2)) {
            String url=config.getProperty("node"+node+".url");
            if(!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/finance_validation_[a-z0-9_]+[?].*"))throw new IllegalArgumentException("Disposable loopback schemas required");
            MysqlXADataSource source=new MysqlXADataSource();source.setUrl(FinanceCluster.boundedUrl(url));source.setUser(config.getProperty("node"+node+".user"));source.setPassword(config.getProperty("node"+node+".password"));
            sources.put(node,"crash".equals(phase)&&node==1?crashAfterCommit(source):source);
        }
        String operation=config.getProperty("test.operationId");
        HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(FinanceClusterCrashHarness.class.getClassLoader(),new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()){
            case "getServletPath"->"/crashValidation";
            case "getHeader"->switch((String)args[0]){case "X-IAFM-Ingress-Node"->"2";case "X-IAFM-Ingress-Token"->config.getProperty("ingress.token");case "X-IAFM-Operation-Id"->operation;default->null;};
            default->null;
        });
        HttpServer authority=null;
        if("jdbc".equals(config.getProperty("objectStore.backend"))) {
            String actualAuthority=config.getProperty("test.authority.url","");
            if(!actualAuthority.isEmpty()) {
                if(!actualAuthority.matches("http://127[.]0[.]0[.]1:[0-9]+/authority")
                        ||config.getProperty("test.authority.token","").length()<32)
                    throw new IllegalArgumentException("Validation authority must be authenticated loopback");
                System.setProperty("iafm.cluster.authority.url",actualAuthority);
                System.setProperty("iafm.cluster.health.token",config.getProperty("test.authority.token"));
            } else {
            // This crash harness models an already-fenced validation group.
            // The loopback stub is not evidence of cloud fencing.
            String id;
            try(Connection c=DriverManager.getConnection(config.getProperty("node1.url"),config.getProperty("node1.user"),config.getProperty("node1.password"));
                Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT @@server_uuid")){if(!r.next())throw new SQLException("missing test identity");id=r.getString(1);}
            String token="test-authority-token-for-isolated-schemas-only";
            authority=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            authority.createContext("/authority",exchange->{
                if(!("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(403,-1);exchange.close();return;}
                exchange.getResponseHeaders().set("X-Four-Project-MySQL-UUID",id);exchange.sendResponseHeaders(204,-1);exchange.close();
            });
            authority.start();
            System.setProperty("iafm.cluster.authority.url","http://127.0.0.1:"+authority.getAddress().getPort()+"/authority");
            System.setProperty("iafm.cluster.health.token",token);
            }
        }
        try(FinanceCluster cluster=new FinanceCluster(config,sources)) {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(80);
            while(!cluster.recoveryReady()&&System.nanoTime()<deadline)Thread.sleep(100);
            if(!cluster.recoveryReady())throw new IllegalStateException("Recovery did not reopen admission");
            JSONObject result=cluster.run(request,null,new JSONObject().put("amount",1),true,()->{
                if("recover".equals(phase))throw new AssertionError("Committed operation executed twice");
                for(Connection connection:ShardScope.current().connections().values())try(var statement=connection.createStatement()) {statement.executeUpdate("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+1 WHERE ID=1");}
                return new JSONObject().put("status","success").put("balance",1);
            });
            if(!"committed".equals(result.getString("operationStatus")))throw new AssertionError("Missing committed result");
            for(int node:List.of(1,2))try(Connection connection=DriverManager.getConnection(config.getProperty("node"+node+".url"),config.getProperty("node"+node+".user"),config.getProperty("node"+node+".password"));var statement=connection.createStatement();var rows=statement.executeQuery("SELECT AMOUNT FROM TEST_BALANCE WHERE ID=1")) {
                if(!rows.next()||rows.getInt(1)!=1)throw new AssertionError("Incorrect recovered balance");
            }
            System.out.println("PASS: fresh process recovered both MySQL participants and replayed once");
        } finally {if(authority!=null)authority.stop(0);}
    }
    private static XADataSource crashAfterCommit(XADataSource source) {
        return (XADataSource)Proxy.newProxyInstance(FinanceClusterCrashHarness.class.getClassLoader(),new Class[]{XADataSource.class},(proxy,method,args)->{
            try {
                Object value=method.invoke(source,args);
                if(value instanceof XAConnection connection)return Proxy.newProxyInstance(FinanceClusterCrashHarness.class.getClassLoader(),new Class[]{XAConnection.class},(p,m,a)->{
                    try {
                        Object returned=m.invoke(connection,a);
                        if(returned instanceof XAResource resource)return Proxy.newProxyInstance(FinanceClusterCrashHarness.class.getClassLoader(),new Class[]{XAResource.class},(rp,rm,ra)->{
                            try {Object answer=rm.invoke(resource,ra);if(rm.getName().equals("commit"))Runtime.getRuntime().halt(42);return answer;}
                            catch(InvocationTargetException error){throw error.getCause();}
                        });
                        return returned;
                    }catch(InvocationTargetException error){throw error.getCause();}
                });
                return value;
            }catch(InvocationTargetException error){throw error.getCause();}
        });
    }
}

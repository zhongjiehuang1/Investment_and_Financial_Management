package DatabaseService;

import Controller.ClusterAvailability;
import com.sun.net.httpserver.HttpServer;
import org.h2.jdbcx.JdbcDataSource;
import org.json.JSONObject;
import org.junit.Test;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.xa.XAResource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class PostCommitReadinessTest {
    @Test public void committedOperationIsNotAcknowledgedWhenReplicationViewBecomesUnavailable()throws Exception {
        AtomicBoolean ready=new AtomicBoolean(true),armed=new AtomicBoolean(false);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ready",exchange->{exchange.sendResponseHeaders(ready.get()?204:503,-1);exchange.close();});
        server.start();
        Properties config=new Properties();Map<Integer,XADataSource> sources=new TreeMap<>();
        config.setProperty("objectStore.absolutePath",Files.createTempDirectory("finance-postcommit-").toString());
        config.setProperty("nodeIdentifier","finance-postcommit");config.setProperty("ingress.token","test-ingress-token");
        config.setProperty("idempotency.secret","finance-test-idempotency-secret-not-production");
        for(int node:List.of(1,2)){
            String url="jdbc:h2:mem:finance_postcommit_"+node+"_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
            config.setProperty("node"+node+".url",url);config.setProperty("node"+node+".user","sa");config.setProperty("node"+node+".password","test-password");
            JdbcDataSource source=new JdbcDataSource();source.setURL(url);source.setUser("sa");source.setPassword("test-password");
            sources.put(node,node==1?afterCommit(source,armed,ready):source);
            try(Connection c=DriverManager.getConnection(url,"sa","test-password");var statement=c.createStatement()){
                String ddl=Files.readString(Path.of("ops/cluster/finance-schema.sql")).replaceAll("(?m)^--.*$","");
                for(String sql:ddl.split(";"))if(!sql.isBlank())statement.execute(sql);
                statement.execute("CREATE TABLE TEST_BALANCE(ID INT PRIMARY KEY,AMOUNT INT NOT NULL)");
                statement.execute("INSERT INTO TEST_BALANCE VALUES(1,0)");
            }
        }
        ClusterStoreProvision.provision(config);
        String oldRequired=System.getProperty("iafm.cluster.health.required");
        String oldUrl=System.getProperty("iafm.cluster.health.url");
        try(FinanceCluster cluster=new FinanceCluster(config,sources)){
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(!cluster.recoveryReady()&&System.nanoTime()<deadline)Thread.sleep(25);
            assertTrue(cluster.recoveryReady());
            System.setProperty("iafm.cluster.health.required","true");
            System.setProperty("iafm.cluster.health.url","http://127.0.0.1:"+server.getAddress().getPort()+"/ready");
            String id=UUID.randomUUID().toString();
            HttpServletRequest request=(HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{HttpServletRequest.class},
                (p,m,a)->switch(m.getName()){
                    case "getHeader"->switch((String)a[0]){case "X-IAFM-Operation-Id"->id;case "X-IAFM-Ingress-Node"->"2";case "X-IAFM-Ingress-Token"->"test-ingress-token";default->null;};
                    case "getServletPath"->"/testMutation";
                    default->null;
                });
            FinanceCluster.Work increment=()->{
                for(Connection c:ShardScope.current().connections().values())
                    try(var statement=c.createStatement()){statement.executeUpdate("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+1 WHERE ID=1");}
                return new JSONObject().put("status","success");
            };
            armed.set(true);
            try{cluster.run(request,null,new JSONObject(),true,increment);fail("unavailable replication view acknowledged");}
            catch(ClusterAvailability.Unavailable expected){ }
            for(int node:List.of(1,2))try(Connection c=DriverManager.getConnection(config.getProperty("node"+node+".url"),"sa","test-password");
                    var r=c.createStatement().executeQuery("SELECT AMOUNT FROM TEST_BALANCE WHERE ID=1")){
                assertTrue(r.next());assertEquals(1,r.getInt(1));
            }
            ready.set(true);
            JSONObject replay=cluster.run(request,null,new JSONObject(),true,()->{throw new AssertionError("operation executed twice");});
            assertEquals("committed",replay.getString("operationStatus"));
        }finally{
            if(oldRequired==null)System.clearProperty("iafm.cluster.health.required");else System.setProperty("iafm.cluster.health.required",oldRequired);
            if(oldUrl==null)System.clearProperty("iafm.cluster.health.url");else System.setProperty("iafm.cluster.health.url",oldUrl);
            server.stop(0);
        }
    }
    private static XADataSource afterCommit(XADataSource source,AtomicBoolean armed,AtomicBoolean ready){
        return (XADataSource)Proxy.newProxyInstance(PostCommitReadinessTest.class.getClassLoader(),new Class[]{XADataSource.class},(p,m,a)->{
            try{
                Object value=m.invoke(source,a);
                if(!(value instanceof XAConnection connection))return value;
                return Proxy.newProxyInstance(PostCommitReadinessTest.class.getClassLoader(),new Class[]{XAConnection.class},(pc,mc,ac)->{
                    try{
                        Object result=mc.invoke(connection,ac);
                        if(!(result instanceof XAResource resource))return result;
                        return Proxy.newProxyInstance(PostCommitReadinessTest.class.getClassLoader(),new Class[]{XAResource.class},(pr,mr,ar)->{
                            try{
                                Object answer=mr.invoke(resource,ar);
                                if(mr.getName().equals("commit")&&armed.compareAndSet(true,false))ready.set(false);
                                return answer;
                            }catch(InvocationTargetException error){throw error.getCause();}
                        });
                    }catch(InvocationTargetException error){throw error.getCause();}
                });
            }catch(InvocationTargetException error){throw error.getCause();}
        });
    }
}


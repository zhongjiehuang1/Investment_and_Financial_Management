package DatabaseService;

import Controller.ApiSupport;
import org.h2.jdbcx.JdbcDataSource;
import org.json.JSONObject;
import org.junit.Test;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.XADataSource;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import static org.junit.Assert.*;

public class FinanceClusterTest {
    private static HttpServletRequest request(String id) {
        return (HttpServletRequest)Proxy.newProxyInstance(FinanceClusterTest.class.getClassLoader(),new Class[]{HttpServletRequest.class},(proxy,method,args)->switch(method.getName()) {
            case "getHeader"->switch((String)args[0]){case "X-IAFM-Operation-Id"->id;case "X-IAFM-Ingress-Node"->"2";case "X-IAFM-Ingress-Token"->"test-ingress-token";default->null;};
            case "getServletPath"->"/testMutation";
            default->null;
        });
    }
    @Test public void durableGateProtectsTwoResourcesAndIdempotentResult()throws Exception {
        Properties configuration=new Properties();Map<Integer,XADataSource> sources=new TreeMap<>();
        String external=System.getProperty("finance.test.config");
        if(external!=null)try(var reader=Files.newBufferedReader(Path.of(external))){configuration.load(reader);}
        configuration.setProperty("objectStore.absolutePath",Files.createTempDirectory("finance-xa-test-").toString());
        configuration.setProperty("nodeIdentifier","finance-test");configuration.setProperty("ingress.token","test-ingress-token");
        configuration.setProperty("idempotency.secret","finance-test-idempotency-secret-not-production");
        for(int node:List.of(1,2)) {
            String url=external==null?"jdbc:h2:mem:finance_cluster_"+node+"_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1":configuration.getProperty("node"+node+".url");
            if(external!=null&&!url.matches("jdbc:mysql://127[.]0[.]0[.]1:[0-9]+/finance_validation_[a-z0-9_]+[?].*"))throw new IllegalArgumentException("Use disposable validation databases only.");
            if(external==null){configuration.setProperty("node"+node+".user","sa");configuration.setProperty("node"+node+".password","test-password");}
            configuration.setProperty("node"+node+".url",url);
            String username=configuration.getProperty("node"+node+".user"),password=configuration.getProperty("node"+node+".password");
            if(external==null){JdbcDataSource source=new JdbcDataSource();source.setURL(url);source.setUser(username);source.setPassword(password);sources.put(node,source);}
            else{com.mysql.cj.jdbc.MysqlXADataSource source=new com.mysql.cj.jdbc.MysqlXADataSource();source.setUrl(FinanceCluster.boundedUrl(url));source.setUser(username);source.setPassword(password);sources.put(node,source);}
            try(Connection connection=DriverManager.getConnection(url,username,password);var statement=connection.createStatement()) {
                String ddl=Files.readString(Path.of("ops/cluster/finance-schema.sql")).replaceAll("(?m)^--.*$","");
                for(String sql:ddl.split(";"))if(!sql.isBlank())statement.execute(sql);
                statement.execute("CREATE TABLE TEST_BALANCE(ID INT PRIMARY KEY,AMOUNT INT NOT NULL)");
                statement.execute("INSERT INTO TEST_BALANCE VALUES(1,0)");
            }
        }
        ClusterStoreProvision.provision(configuration);
        try(FinanceCluster cluster=new FinanceCluster(configuration,sources)) {
            long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while(!cluster.recoveryReady()&&System.nanoTime()<deadline)Thread.sleep(25);
            assertTrue("recovery initializes before admission",cluster.recoveryReady());
            String id=UUID.randomUUID().toString();JSONObject input=new JSONObject().put("amount",5);
            FinanceCluster.Work increment=()->{
                for(Connection connection:ShardScope.current().connections().values())try(var statement=connection.createStatement()) {
                    statement.executeUpdate("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+5 WHERE ID=1");connection.commit();
                }
                return new JSONObject().put("balance",5);
            };
            JSONObject first=cluster.run(request(id),null,input,true,increment);
            assertEquals("committed",first.getString("operationStatus"));
            assertEquals(first.toString(),cluster.run(request(id),null,input,true,()->{throw new AssertionError("Replay cannot reexecute work");}).toString());
            try { cluster.run(request(id),null,new JSONObject().put("amount",7),true,increment);fail("different payload accepted"); }
            catch(ApiSupport.Failure expected){assertEquals(409,expected.httpStatus());}
            String aborted=UUID.randomUUID().toString();
            try { cluster.run(request(aborted),null,input,true,()->{
                increment.run();ShardScope.current().markRollbackOnly();return new JSONObject();
            });fail("rollback-only work committed"); }catch(ApiSupport.Failure expected){assertEquals(409,expected.httpStatus());}
            for(int node:List.of(1,2))try(Connection connection=DriverManager.getConnection(configuration.getProperty("node"+node+".url"),configuration.getProperty("node"+node+".user"),configuration.getProperty("node"+node+".password"));var statement=connection.createStatement();var result=statement.executeQuery("SELECT AMOUNT FROM TEST_BALANCE WHERE ID=1")) {
                assertTrue(result.next());assertEquals(5,result.getInt(1));
            }
            assertEquals("committed",cluster.status(null,id).getString("state"));
            assertEquals("aborted",cluster.status(null,aborted).getString("state"));
            assertTrue(cluster.recoveryReady());
            java.lang.reflect.Method scan=FinanceCluster.class.getDeclaredMethod("recoverSafely");scan.setAccessible(true);
            Thread background=new Thread(()->{try{scan.invoke(cluster);}catch(Exception failure){throw new RuntimeException(failure);}});
            background.start();
            while(background.isAlive()){assertTrue("Routine recovery must not create a false outage",cluster.recoveryReady());Thread.sleep(10);}
            // A replicated coordinator must stop its background recovery when
            // local writer authority is missing, then resume once permitted.
            configuration.setProperty("objectStore.backend","jdbc");
            scan.invoke(cluster);
            assertFalse("No replicated recovery without local authority",cluster.recoveryReady());
            configuration.setProperty("objectStore.backend","file");
            scan.invoke(cluster);
            assertTrue("Legacy recovery resumes after authority is no longer required",cluster.recoveryReady());
        }
    }
    @Test public void canonicalPayloadIsIndependentOfObjectKeyOrder() {
        assertEquals(FinanceCluster.canonical(new JSONObject("{\"b\":2,\"a\":[1,{\"y\":2,\"x\":3}]}")),FinanceCluster.canonical(new JSONObject("{\"a\":[1,{\"x\":3,\"y\":2}],\"b\":2}")));
    }
}

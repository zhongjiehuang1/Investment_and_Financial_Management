package DatabaseService;

import Controller.ApiSupport;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Local atomicity tests. H2 is NOT evidence for MySQL GR certification/failover. */
public class AtomicFinanceTransactionsTest {
    private static final String ONE="CANONICAL", TWO="PEER";
    private static final class Gate implements AtomicFinanceTransactions.Authority {
        volatile String mode="dual";
        final AtomicBoolean rejectBefore=new AtomicBoolean(),rejectAfter=new AtomicBoolean();
        public AtomicFinanceTransactions.Proof capture(){return new AtomicFinanceTransactions.Proof("11111111-1111-1111-1111-111111111111","view-1","1",mode,"a".repeat(64),"22222222-2222-4222-8222-222222222222","permit-"+mode);}
        public void verify(AtomicFinanceTransactions.Proof proof)throws SQLException {if(rejectBefore.getAndSet(false)||!proof.equals(capture()))throw new SQLException("Test epoch changed");}
        public String confirm(Connection c,AtomicFinanceTransactions.Proof proof,String operation)throws SQLException {verify(proof);if(rejectAfter.getAndSet(false))throw new SQLException("Test confirmation response lost");return proof.mode();}
    }
    private static final class Fixture {
        final String url="jdbc:h2:mem:atomic_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        final Properties config=new Properties();
        final Gate gate=new Gate();
        Fixture()throws Exception{
            config.setProperty("atomic.migrationId",UUID.randomUUID().toString());
            config.setProperty("idempotency.secret","synthetic-deletion-capability-secret");
            try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){
                for(String schema:List.of(ONE,TWO)){
                    q.execute("CREATE SCHEMA "+schema);
                    q.execute("CREATE TABLE "+schema+".TEST_BALANCE(ID INT PRIMARY KEY,AMOUNT INT NOT NULL)");
                    q.execute("INSERT INTO "+schema+".TEST_BALANCE VALUES(1,0)");
                }
                q.execute("CREATE TABLE "+ONE+".CLUSTER_ATOMIC_GUARD(ID INT PRIMARY KEY,VERSION BIGINT NOT NULL,DATA_REVISION BIGINT NOT NULL DEFAULT 0)");q.execute("INSERT INTO "+ONE+".CLUSTER_ATOMIC_GUARD VALUES(1,0,0)");
                q.execute("CREATE TABLE "+ONE+".CLUSTER_ATOMIC_CONFIG(ID INT PRIMARY KEY,PROTOCOL_VERSION INT,MIGRATION_ID CHAR(36),PRIMARY_SCHEMA VARCHAR(64),PEER_SCHEMA VARCHAR(64))");
                q.execute("INSERT INTO "+ONE+".CLUSTER_ATOMIC_CONFIG VALUES(1,2,'"+config.getProperty("atomic.migrationId")+"','"+ONE+"','"+TWO+"')");
                q.execute("CREATE TABLE "+ONE+".CLUSTER_OPERATIONS(ID VARCHAR(36) PRIMARY KEY,OWNER_KEY VARCHAR(128),REQUEST_HASH CHAR(64),STATE VARCHAR(16),ENDPOINT VARCHAR(160),DELETE_RESULT_HASH CHAR(64),DELETE_RESULT_EXPIRES TIMESTAMP)");
                q.execute("CREATE TABLE "+ONE+".CLUSTER_RESULTS(OPERATION_ID VARCHAR(36) PRIMARY KEY,PAYLOAD CLOB)");
            }
        }
        Connection connection()throws SQLException{
            Connection c=DriverManager.getConnection(url);
            // H2 maps database names differently. This adapter is test-only;
            // production uses Connector/J setCatalog on one MySQL connection.
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("setCatalog")){c.setSchema((String)a[0]);return null;}
                if(m.getName().equals("getCatalog"))return c.getSchema();
                try{return m.invoke(c,a);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }
        AtomicFinanceTransactions service(){return service(proof->connection());}
        AtomicFinanceTransactions service(AtomicFinanceTransactions.Connections source){return new AtomicFinanceTransactions(config,new String[]{ONE,TWO},gate,source);}
        long count(String sql)throws Exception{try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement();ResultSet rows=q.executeQuery(sql)){rows.next();return rows.getLong(1);}}
        String token()throws Exception{return config.getProperty("atomic.migrationId")+":"+count("SELECT DATA_REVISION FROM "+ONE+".CLUSTER_ATOMIC_GUARD WHERE ID=1");}
        void check(long amount,long operations)throws Exception{
            for(String schema:List.of(ONE,TWO))assertEquals(amount,count("SELECT AMOUNT FROM "+schema+".TEST_BALANCE WHERE ID=1"));
            assertEquals(operations,count("SELECT COUNT(*) FROM "+ONE+".CLUSTER_OPERATIONS"));
            assertEquals(operations,count("SELECT COUNT(*) FROM "+ONE+".CLUSTER_RESULTS"));
        }
    }
    private static JSONObject run(Fixture f,AtomicFinanceTransactions service,String operation,FinanceCluster.Work work)throws Exception{
        return service.run(operation,"public","f".repeat(64),"/test",2,null,true,work,f.token());
    }
    private static JSONObject increment()throws Exception{
        for(Connection c:ShardScope.current().connections().values())try(Statement q=c.createStatement()){
            assertEquals(1,q.executeUpdate("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+5 WHERE ID=1"));c.commit();
        }
        return new JSONObject().put("amount",5);
    }

    @Test public void commitAndWholeOperationRollbackUseOneTransaction()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions service=f.service();String id=UUID.randomUUID().toString();
        JSONObject response=run(f,service,id,AtomicFinanceTransactionsTest::increment);assertEquals("dual",response.getString("durability"));assertFalse(response.getBoolean("degraded"));
        assertEquals(id,run(f,service,id,()->{throw new AssertionError("Replay executed business work");}).getString("operationId"));
        try{service.run(id,"public","e".repeat(64),"/test",1,null,true,AtomicFinanceTransactionsTest::increment);fail("Changed payload reused ID");}catch(ApiSupport.Failure expected){assertEquals(409,expected.httpStatus());}
        try{service.run(id,"another-owner","f".repeat(64),"/test",1,null,true,()->{throw new AssertionError("Wrong owner executed work");});fail("Different owner reused ID");}catch(ApiSupport.Failure expected){assertEquals(409,expected.httpStatus());}
        String rolledBack=UUID.randomUUID().toString();
        try{run(f,service,rolledBack,()->{increment();ShardScope.current().markRollbackOnly();return new JSONObject();});fail("Rollback-only committed");}catch(ApiSupport.Failure expected){assertEquals(409,expected.httpStatus());}
        assertEquals("absent",service.status(null,rolledBack).getString("state"));
        f.check(5,1);
    }

    @Test public void independentCoordinatorsCannotRecoverOrDuplicateLiveWork()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions first=f.service(),second=f.service();String id=UUID.randomUUID().toString();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger executed=new AtomicInteger();ExecutorService pool=Executors.newFixedThreadPool(2);
        try{
            Future<JSONObject> a=pool.submit(()->run(f,first,id,()->{executed.incrementAndGet();increment();entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new JSONObject();}));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            Future<JSONObject> b=pool.submit(()->run(f,second,id,()->{executed.incrementAndGet();return increment();}));
            Thread.sleep(150);assertFalse("Other coordinator must wait or lose certification, never abort live work",b.isDone());
            release.countDown();assertEquals(id,a.get(10,TimeUnit.SECONDS).getString("operationId"));assertEquals(id,b.get(10,TimeUnit.SECONDS).getString("operationId"));
            assertEquals(1,executed.get());f.check(5,1);
        }finally{release.countDown();pool.shutdownNow();}
    }

    @Test public void lostCommitAcknowledgementRemainsReplayableFromAnotherInstance()throws Exception{
        Fixture f=new Fixture();AtomicBoolean lost=new AtomicBoolean(true);
        AtomicFinanceTransactions uncertain=f.service(proof->{Connection c=f.connection();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try{Object result=m.invoke(c,a);if(m.getName().equals("commit")&&lost.getAndSet(false))throw new SQLException("Synthetic commit acknowledgment loss","08006");return result;}
            catch(InvocationTargetException error){throw error.getCause();}
        });});
        String id=UUID.randomUUID().toString();try{run(f,uncertain,id,AtomicFinanceTransactionsTest::increment);fail("Uncertain commit acknowledged");}catch(SQLException expected){ }
        f.check(5,1);f.gate.mode="single";
        JSONObject replay=run(f,f.service(),id,()->{throw new AssertionError("Unknown operation executed twice");});
        assertEquals("single",replay.getString("durability"));assertTrue(replay.getBoolean("degraded"));f.check(5,1);
    }

    @Test public void changedPermitRollsBackAndFailedPostCommitConfirmationDoesNotAbort()throws Exception{
        Fixture f=new Fixture();String before=UUID.randomUUID().toString();f.gate.rejectBefore.set(true);
        try{run(f,f.service(),before,AtomicFinanceTransactionsTest::increment);fail("Changed permit acknowledged");}catch(SQLException expected){ }
        f.check(0,0);
        String after=UUID.randomUUID().toString();f.gate.rejectAfter.set(true);
        try{run(f,f.service(),after,AtomicFinanceTransactionsTest::increment);fail("Unconfirmed commit acknowledged");}catch(SQLException expected){ }
        f.check(5,1);assertEquals("committed",f.service().status(null,after).getString("state"));
        run(f,f.service(),after,()->{throw new AssertionError("Confirmation failure caused a second write");});f.check(5,1);
    }

    @Test public void retainedStatementsBindCatalogAndCannotEscapeTransactionOwnership()throws Exception{
        Fixture f=new Fixture();Connection[] retained=new Connection[1];PreparedStatement[] expired=new PreparedStatement[1];
        run(f,f.service(),UUID.randomUUID().toString(),()->{
            Connection a=ShardScope.current().connection(1),b=ShardScope.current().connection(2);retained[0]=a;
            try(PreparedStatement one=a.prepareStatement("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+2 WHERE ID=1");PreparedStatement two=b.prepareStatement("UPDATE TEST_BALANCE SET AMOUNT=AMOUNT+7 WHERE ID=1")){
                two.executeUpdate();one.executeUpdate();
                one.getConnection().commit();one.getConnection().close();
                try{one.unwrap(PreparedStatement.class);fail("Physical prepared statement escaped");}catch(SQLException expected){ }
                try{one.execute("UPDATE TEST_BALANCE SET AMOUNT=999");fail("Prepared SQL replaced");}catch(SQLException expected){ }
            }
            try(Statement q=a.createStatement();ResultSet rows=q.executeQuery("SELECT AMOUNT FROM TEST_BALANCE WHERE ID=1")){
                assertTrue(rows.next());assertEquals(2,rows.getInt(1));rows.getStatement().getConnection().commit();
                try{q.execute("COMMIT");fail("DAO committed physical work");}catch(SQLException expected){ }
                try{q.execute("CREATE TABLE ESCAPE(ID INT)");fail("Implicit-commit DDL accepted");}catch(SQLException expected){ }
            }
            a.getMetaData().getConnection().commit();
            try{a.unwrap(Connection.class);fail("Physical connection escaped");}catch(SQLException expected){ }
            try{a.setCatalog(TWO);fail("Schema route mutated");}catch(SQLException expected){ }
            expired[0]=b.prepareStatement("UPDATE TEST_BALANCE SET AMOUNT=999 WHERE ID=1");
            return new JSONObject();
        });
        assertEquals(2,f.count("SELECT AMOUNT FROM "+ONE+".TEST_BALANCE WHERE ID=1"));assertEquals(7,f.count("SELECT AMOUNT FROM "+TWO+".TEST_BALANCE WHERE ID=1"));
        try{retained[0].createStatement();fail("Expired connection remained usable");}catch(SQLException expected){ }
        try{expired[0].executeUpdate();fail("Expired statement remained usable");}catch(SQLException expected){ }
    }

    @Test public void unsafeConnectionModesAreOverridden() {
        String url=AtomicFinanceTransactions.transactionUrl("jdbc:mysql://127.0.0.1:23516/a?autoReconnect=true&databaseTerm=SCHEMA&allowMultiQueries=true&sslMode=VERIFY_IDENTITY&serverTimezone=Asia/Shanghai&connectionTimeZone=LOCAL&forceConnectionTimeZoneToSession=false&preserveInstants=false");
        assertFalse(url.contains("autoReconnect=true"));assertTrue(url.contains("autoReconnect=false"));assertTrue(url.contains("databaseTerm=CATALOG"));
        assertTrue(url.contains("allowMultiQueries=false"));assertTrue(url.contains("sslMode=VERIFY_IDENTITY"));assertFalse(url.contains("databaseTerm=SCHEMA"));
        assertTrue(url.contains("connectionTimeZone=UTC"));assertTrue(url.contains("forceConnectionTimeZoneToSession=true"));assertTrue(url.contains("preserveInstants=true"));
        assertFalse(url.contains("serverTimezone="));assertFalse(url.contains("Asia/Shanghai"));assertFalse(url.contains("connectionTimeZone=LOCAL"));assertFalse(url.contains("preserveInstants=false"));
    }

    @Test public void cachedResultsCannotBypassIdentityShadowOrOwnedGenerationChecks()throws Exception{
        Fixture f=new Fixture();Model.User user=new Model.User();user.setId(1);user.setGeneration(UUID.randomUUID().toString());
        String record=UUID.randomUUID().toString();
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){
            for(String schema:List.of(ONE,TWO)){
                q.execute("CREATE TABLE "+schema+".USERS(ID INT PRIMARY KEY,GENERATION CHAR(36))");q.execute("INSERT INTO "+schema+".USERS VALUES(1,'"+user.getGeneration()+"')");
                q.execute("CREATE TABLE "+schema+".ALL_NON_INVESTMENT_ASSETS(ID INT PRIMARY KEY,USER_ID INT,GENERATION CHAR(36))");
            }
            q.execute("INSERT INTO "+ONE+".ALL_NON_INVESTMENT_ASSETS VALUES(1,1,'"+record+"')");
        }
        AtomicFinanceTransactions service=f.service();String id=UUID.randomUUID().toString();
        try(RequestIdentity identity=RequestIdentity.begin(user,true,List.of(),List.of())){
            service.run(id,FinanceCluster.owner(user),"f".repeat(64),"/test",1,user,true,AtomicFinanceTransactionsTest::increment,f.token());
        }
        try(RequestIdentity identity=RequestIdentity.begin(user,true,List.of(new RequestIdentity.Record("ALL_NON_INVESTMENT_ASSETS",1,UUID.randomUUID().toString())),List.of())){
            try{service.run(UUID.randomUUID().toString(),FinanceCluster.owner(user),"f".repeat(64),"/test",1,user,true,()->{throw new AssertionError("Owned generation was not checked before work");});fail("Stale owned identity accepted");}
            catch(RequestIdentity.StaleIdentity expected){assertFalse(expected.isUser());}
        }
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){q.executeUpdate("UPDATE "+TWO+".USERS SET GENERATION='"+UUID.randomUUID()+"' WHERE ID=1");}
        try{service.run(id,FinanceCluster.owner(user),"f".repeat(64),"/test",1,user,true,()->{throw new AssertionError("Cached operation reexecuted");});fail("Stale shadow bypassed by cached result");}
        catch(RequestIdentity.StaleIdentity expected){assertTrue(expected.isUser());}
        f.check(5,1);
    }

    @Test public void historicalCommittedReceiptWithoutPayloadRemainsQueryableButNeverReexecutes()throws Exception{
        Fixture f=new Fixture();String id=UUID.randomUUID().toString();
        try(Connection c=DriverManager.getConnection(f.url);PreparedStatement q=c.prepareStatement("INSERT INTO "+ONE+".CLUSTER_OPERATIONS(ID,OWNER_KEY,REQUEST_HASH,STATE) VALUES(?,'public',?,'COMMITTED')")){
            q.setString(1,id);q.setString(2,"f".repeat(64));q.executeUpdate();
        }
        JSONObject state=f.service().status(null,id);
        assertEquals("committed",state.getString("state"));assertTrue(state.getBoolean("resultUnavailable"));assertFalse(state.has("result"));
        try{run(f,f.service(),id,()->{throw new AssertionError("Historical committed operation executed again");});fail("Missing historical payload was invented");}catch(SQLException expected){ }
        assertEquals(0,f.count("SELECT AMOUNT FROM "+ONE+".TEST_BALANCE WHERE ID=1"));
        assertEquals(1,f.count("SELECT COUNT(*) FROM "+ONE+".CLUSTER_OPERATIONS"));assertEquals(0,f.count("SELECT COUNT(*) FROM "+ONE+".CLUSTER_RESULTS"));
    }

    @Test public void sequentialOldFormsAndDeletesCannotOverwriteALaterCommittedSnapshot()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions a=f.service(),b=f.service();String old=f.token();
        JSONObject initial=a.run(UUID.randomUUID().toString(),"public","r".repeat(64),"/read",1,null,false,()->new JSONObject().put("displayed",0));
        assertEquals(old,initial.getString("dataRevision"));
        String first=UUID.randomUUID().toString();a.run(first,"public","a".repeat(64),"/editNonInvestmentAsset",1,null,true,AtomicFinanceTransactionsTest::increment,old);
        for(String path:List.of("/editNonInvestmentAsset","/editIncomeOrExpense","/deleteNonInvestmentAsset","/deleteIncomeOrExpense","/editInflationRate","/deleteInflationRate")){
            String rejected=UUID.randomUUID().toString();
            try{b.run(rejected,"public","b".repeat(64),path,2,null,true,()->{throw new AssertionError("Stale form executed business work");},old);fail("Old snapshot accepted");}
            catch(FinanceDataRevision.Rejected expected){assertEquals(409,expected.httpStatus());assertEquals("SNAPSHOT_STALE",expected.code());}
            assertEquals("absent",b.status(null,rejected).getString("state"));
        }
        f.check(5,1);assertEquals(f.config.getProperty("atomic.migrationId")+":1",f.token());
    }

    @Test public void missingOrWrongSnapshotFailsBeforeAReceiptAndSessionActionsDoNotAdvanceData()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions service=f.service();
        for(String token:new String[]{null,"bad-token",UUID.randomUUID()+":0"}){
            try{service.run(UUID.randomUUID().toString(),"public","a".repeat(64),"/addInvestmentAsset",1,null,true,()->{throw new AssertionError("Missing snapshot executed work");},token);fail("Invalid snapshot accepted");}
            catch(FinanceDataRevision.Rejected expected){assertEquals(token==null?428:token.equals("bad-token")?400:409,expected.httpStatus());}
        }
        f.check(0,0);String before=f.token();
        service.run(UUID.randomUUID().toString(),"public","c".repeat(64),"/currencySwitch",1,null,true,()->new JSONObject().put("currencyCurrentChosen","USD"));
        assertEquals(before,f.token());f.check(0,1);
    }

    @Test public void sameIdReplayPrecedesRevisionCasAndKeepsTheOriginalResponseToken()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions service=f.service();String old=f.token(),first=UUID.randomUUID().toString();
        JSONObject original=service.run(first,"public","a".repeat(64),"/edit",1,null,true,AtomicFinanceTransactionsTest::increment,old);
        service.run(UUID.randomUUID().toString(),"public","b".repeat(64),"/edit",2,null,true,AtomicFinanceTransactionsTest::increment,f.token());
        JSONObject replay=service.run(first,"public","a".repeat(64),"/edit",2,null,true,()->{throw new AssertionError("Replay reexecuted");},old);
        assertEquals(original.getString("dataRevision"),replay.getString("dataRevision"));assertNotEquals(f.token(),replay.getString("dataRevision"));
        String legacy=UUID.randomUUID().toString();JSONObject payload=new JSONObject().put("operationId",legacy).put("operationStatus","committed");
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){
            q.execute("INSERT INTO CANONICAL.CLUSTER_OPERATIONS(ID,OWNER_KEY,REQUEST_HASH,STATE) VALUES('"+legacy+"','public','"+"l".repeat(64)+"','COMMITTED')");
            try(PreparedStatement insert=c.prepareStatement("INSERT INTO CANONICAL.CLUSTER_RESULTS VALUES(?,?)")){insert.setString(1,legacy);insert.setString(2,payload.toString());insert.executeUpdate();}
        }
        JSONObject historical=service.run(legacy,"public","l".repeat(64),"/historical",1,null,true,()->{throw new AssertionError("Historical replay executed");});
        assertFalse(historical.has("dataRevision"));f.check(10,3);
    }

    @Test public void everyTransactionRejectsAChangedProtocolEvenOnAnExistingService()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions service=f.service();service.status(null,UUID.randomUUID().toString());
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){q.executeUpdate("UPDATE CANONICAL.CLUSTER_ATOMIC_CONFIG SET PROTOCOL_VERSION=1 WHERE ID=1");}
        assertThrows(SQLException.class,()->service.run(UUID.randomUUID().toString(),"public","a".repeat(64),"/edit",1,null,true,AtomicFinanceTransactionsTest::increment,f.token()));
        f.check(0,0);
    }
    @Test public void deletedActorCanQueryOnlyItsExactCommittedDeleteEndpointWithoutFinancialPayload()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions service=f.service();Model.User actor=new Model.User();actor.setId(1);actor.setGeneration(UUID.randomUUID().toString());
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){
            for(String schema:List.of(ONE,TWO)){q.execute("CREATE TABLE "+schema+".USERS(ID INT PRIMARY KEY,GENERATION CHAR(36))");q.execute("INSERT INTO "+schema+".USERS VALUES(1,'"+actor.getGeneration()+"')");}
        }
        String operation=UUID.randomUUID().toString();
        assertTrue(service.deletedUserStatus(actor,operation).getBoolean("notDeleted"));
        JSONObject deleted=service.run(operation,FinanceCluster.owner(actor),"d".repeat(64),"/deleteUser",1,actor,true,()->{
            for(Connection database:ShardScope.current().connections().values())try(PreparedStatement q=database.prepareStatement("DELETE FROM USERS WHERE ID=? AND GENERATION=?")){
                q.setInt(1,actor.getId());q.setString(2,actor.getGeneration());assertEquals(1,q.executeUpdate());
            }
            return new JSONObject().put("privateFinancialPayload","must never be returned by deletion status");
        },f.token(),"0123456789abcdef0123456789abcdef.n1");
        assertTrue(deleted.getBoolean("accountDeleted"));JSONObject status=service.deletedUserStatus(actor,operation);
        assertEquals("committed",status.getString("state"));assertTrue(status.getJSONObject("result").getBoolean("accountDeleted"));
        assertFalse(status.toString().contains("privateFinancialPayload"));assertFalse(status.has("userId"));assertFalse(status.has("userGeneration"));
        // A fresh service/JVM needs neither the HttpSession nor its old principal.
        String cookie="0123456789abcdef0123456789abcdef.n2";
        JSONObject recovered=f.service().deletedUserCookieStatus(cookie,operation);
        assertEquals("dual",recovered.getString("durability"));assertTrue(recovered.getJSONObject("result").getBoolean("accountDeleted"));
        assertFalse(recovered.toString().contains("privateFinancialPayload"));assertFalse(recovered.toString().contains(actor.getGeneration()));
        assertNull(f.service().deletedUserCookieStatus("fedcba9876543210fedcba9876543210.n1",operation));
        assertNull(f.service().deletedUserCookieStatus(cookie,UUID.randomUUID().toString()));
        assertNull(f.service().deletedUserCookieStatus(null,operation));
        try(Connection c=DriverManager.getConnection(f.url);PreparedStatement q=c.prepareStatement("UPDATE CANONICAL.CLUSTER_OPERATIONS SET DELETE_RESULT_EXPIRES=? WHERE ID=?")){
            q.setTimestamp(1,Timestamp.from(java.time.Instant.now().minusSeconds(1)));q.setString(2,operation);q.executeUpdate();
        }
        assertNull(f.service().deletedUserCookieStatus(cookie,operation));
        assertEquals(404,assertThrows(ApiSupport.Failure.class,()->f.service().deletedUserStatus(actor,operation)).httpStatus());
        Model.User wrong=new Model.User();wrong.setId(1);wrong.setGeneration(UUID.randomUUID().toString());
        assertThrows(ApiSupport.Failure.class,()->service.deletedUserStatus(wrong,operation));
        assertThrows(ApiSupport.Failure.class,()->service.deletedUserStatus(actor,UUID.randomUUID().toString()));
        String other=UUID.randomUUID().toString();
        try(Connection c=DriverManager.getConnection(f.url);PreparedStatement q=c.prepareStatement("INSERT INTO CANONICAL.CLUSTER_OPERATIONS(ID,OWNER_KEY,REQUEST_HASH,STATE,ENDPOINT) VALUES(?,?,?,'COMMITTED','/editInvestmentAsset')")){
            q.setString(1,other);q.setString(2,FinanceCluster.owner(actor));q.setString(3,"e".repeat(64));q.executeUpdate();
        }
        assertThrows(ApiSupport.Failure.class,()->service.deletedUserStatus(actor,other));
        assertNull(f.service().deletedUserCookieStatus(cookie,other));
        assertThrows(RequestIdentity.StaleIdentity.class,()->service.run(UUID.randomUUID().toString(),FinanceCluster.owner(actor),"n".repeat(64),"/read",1,actor,false,()->{throw new AssertionError("Deletion query restored regular authentication");}));
    }

    @Test public void deleteCapabilityRequiresAuthenticatedCookieAndRejectsRestoredOldGeneration()throws Exception{
        Fixture f=new Fixture();Model.User actor=new Model.User();actor.setId(1);actor.setGeneration(UUID.randomUUID().toString());
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){
            for(String schema:List.of(ONE,TWO)){q.execute("CREATE TABLE "+schema+".USERS(ID INT PRIMARY KEY,GENERATION CHAR(36))");q.execute("INSERT INTO "+schema+".USERS VALUES(1,'"+actor.getGeneration()+"')");}
        }
        AtomicFinanceTransactions service=f.service();String op=UUID.randomUUID().toString();
        assertThrows(ApiSupport.Failure.class,()->service.run(op,FinanceCluster.owner(actor),"d".repeat(64),"/deleteUser",1,actor,true,()->{throw new AssertionError("Missing cookie executed deletion");},f.token()));
        assertEquals(0,f.count("SELECT COUNT(*) FROM CANONICAL.CLUSTER_OPERATIONS"));
        String cookie="0123456789abcdef0123456789abcdef";
        service.run(op,FinanceCluster.owner(actor),"d".repeat(64),"/deleteUser",1,actor,true,()->new JSONObject(),f.token(),cookie);
        assertThrows(SQLException.class,()->f.service().deletedUserCookieStatus(cookie,op));
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){
            // An ID recycled into a different generation must not resurrect the old identity.
            for(String schema:List.of(ONE,TWO))q.executeUpdate("UPDATE "+schema+".USERS SET GENERATION='"+UUID.randomUUID()+"' WHERE ID=1");
        }
        assertTrue(f.service().deletedUserCookieStatus(cookie,op).getJSONObject("result").getBoolean("accountDeleted"));
        try(Connection c=DriverManager.getConnection(f.url);PreparedStatement q=c.prepareStatement("UPDATE CANONICAL.CLUSTER_OPERATIONS SET OWNER_KEY=? WHERE ID=?")){
            q.setString(1,"1:1:"+UUID.randomUUID());q.setString(2,op);q.executeUpdate();
        }
        assertNull(f.service().deletedUserCookieStatus(cookie,op));
    }

}

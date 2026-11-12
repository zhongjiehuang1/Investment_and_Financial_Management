package DatabaseService;

import Model.Tools.Passwords;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Real H2 transaction/counter tests, not a claim of MySQL multi-primary acceptance. */
public class AtomicLoginAttemptsTest {
    private static final String EMAIL="atomic-test@finance.invalid",PASSWORD="synthetic-sign-in-only";
    private static final class Gate implements AtomicFinanceTransactions.Authority {
        final AtomicBoolean loseConfirmation=new AtomicBoolean();
        String mode="dual";
        public AtomicFinanceTransactions.Proof capture(){return new AtomicFinanceTransactions.Proof("11111111-1111-1111-1111-111111111111","view","1",mode,"a".repeat(64),"22222222-2222-4222-8222-222222222222","permit-"+mode);}
        public void verify(AtomicFinanceTransactions.Proof proof)throws SQLException{if(!proof.equals(capture()))throw new SQLException("Test permit changed");}
        public String confirm(Connection c,AtomicFinanceTransactions.Proof proof,String operation)throws SQLException{verify(proof);if(loseConfirmation.getAndSet(false))throw new SQLException("Synthetic confirmation loss");return mode;}
    }
    private static final class Fixture {
        final String url="jdbc:h2:mem:login_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        final String generation=UUID.randomUUID().toString();
        final AtomicLong now=new AtomicLong(1_000_000L);
        final AtomicInteger authentications=new AtomicInteger();
        final Gate gate=new Gate();
        final Properties properties=new Properties();
        Fixture()throws Exception{
            properties.setProperty("atomic.migrationId",UUID.randomUUID().toString());
            try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){
                q.execute("CREATE SCHEMA CANONICAL");q.execute("CREATE SCHEMA PEER");
                q.execute("CREATE TABLE CANONICAL.CLUSTER_ATOMIC_GUARD(ID INT PRIMARY KEY,VERSION BIGINT,DATA_REVISION BIGINT DEFAULT 0)");q.execute("INSERT INTO CANONICAL.CLUSTER_ATOMIC_GUARD VALUES(1,0,0)");
                q.execute("CREATE TABLE CANONICAL.CLUSTER_ATOMIC_CONFIG(ID INT PRIMARY KEY,PROTOCOL_VERSION INT,MIGRATION_ID CHAR(36),PRIMARY_SCHEMA VARCHAR(64),PEER_SCHEMA VARCHAR(64))");
                q.execute("INSERT INTO CANONICAL.CLUSTER_ATOMIC_CONFIG VALUES(1,2,'"+properties.getProperty("atomic.migrationId")+"','CANONICAL','PEER')");
                q.execute("CREATE TABLE CANONICAL.CLUSTER_OPERATIONS(ID CHAR(36),ENDPOINT VARCHAR(160),DELETE_RESULT_HASH CHAR(64),DELETE_RESULT_EXPIRES TIMESTAMP)");
                for(String schema:List.of("CANONICAL","PEER")){
                    q.execute("CREATE TABLE "+schema+".USERS(ID INT PRIMARY KEY,GENERATION CHAR(36),PASSWORD VARCHAR(50),FORENAME VARCHAR(50),SURNAME VARCHAR(50),EMAIL_ADDRESS VARCHAR(50))");
                    try(PreparedStatement insert=c.prepareStatement("INSERT INTO "+schema+".USERS VALUES(1,?,?, 'Atomic','Test',?)")){
                        insert.setString(1,generation);insert.setString(2,Passwords.encode(PASSWORD));insert.setString(3,EMAIL);insert.executeUpdate();
                    }
                }
                for(String table:List.of("LOGIN_LIMITS","LOGIN_STATUS_LIMITS"))q.execute("CREATE TABLE CANONICAL."+table+"(CLIENT_HASH CHAR(64) PRIMARY KEY,WINDOW_START_MS BIGINT NOT NULL,ATTEMPTS INT NOT NULL)");
                q.execute("CREATE TABLE CANONICAL.LOGIN_ATTEMPTS(OPERATION_ID CHAR(36) PRIMARY KEY,CLIENT_HASH CHAR(64),EMAIL_HASH CHAR(64),REQUEST_HASH CHAR(64),HTTP_STATUS INT,USER_ID INT,USER_GENERATION CHAR(36),CREATED_AT_MS BIGINT)");
            }
        }
        Connection connect()throws SQLException{
            Connection raw=DriverManager.getConnection(url);
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("setCatalog")){raw.setSchema((String)a[0]);return null;}
                if(m.getName().equals("getCatalog"))return raw.getSchema();
                try{return m.invoke(raw,a);}catch(InvocationTargetException error){throw error.getCause();}
            });
        }
        AtomicFinanceTransactions service(boolean realAuthentication){return service(realAuthentication,proof->connect());}
        AtomicFinanceTransactions service(boolean realAuthentication,AtomicFinanceTransactions.Connections connections){
            AtomicLoginAttempts auth=new AtomicLoginAttempts(c->now.get(),(c,e,p)->{
                authentications.incrementAndGet();return realAuthentication?AtomicLoginAttempts.authenticateCurrent(c,e,p):null;
            });
            return new AtomicFinanceTransactions(properties,new String[]{"CANONICAL","PEER"},gate,connections,auth);
        }
        long scalar(String sql)throws Exception{try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement();ResultSet rows=q.executeQuery(sql)){rows.next();return rows.getLong(1);}}
        FinanceCluster.LoginOutcome login(AtomicFinanceTransactions service,String id,String password)throws Exception{
            return service.login(id,"c".repeat(64),"e".repeat(64),hash(password),EMAIL,password);
        }
        void rotate(String password,String replacementGeneration)throws Exception{
            try(Connection c=DriverManager.getConnection(url);PreparedStatement q=c.prepareStatement("UPDATE CANONICAL.USERS SET PASSWORD=?,GENERATION=? WHERE ID=1")){
                q.setString(1,Passwords.encode(password));q.setString(2,replacementGeneration);q.executeUpdate();
            }
            try(Connection c=DriverManager.getConnection(url);PreparedStatement q=c.prepareStatement("UPDATE PEER.USERS SET GENERATION=? WHERE ID=1")){q.setString(1,replacementGeneration);q.executeUpdate();}
        }
    }
    private static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}

    @Test public void immutableReceiptsWorkWithSelectInsertOnlyRuntimePermissions()throws Exception{
        Fixture f=new Fixture();String operation=UUID.randomUUID().toString();
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement()){
            q.execute("CREATE USER receipt_runtime PASSWORD 'synthetic-test-only'");
            q.execute("GRANT SELECT,INSERT ON CANONICAL.LOGIN_ATTEMPTS TO receipt_runtime");
            q.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON CANONICAL.LOGIN_LIMITS TO receipt_runtime");
            q.execute("GRANT SELECT,UPDATE ON CANONICAL.CLUSTER_ATOMIC_GUARD TO receipt_runtime");
        }
        AtomicLoginAttempts attempts=new AtomicLoginAttempts(c->f.now.get(),(c,e,p)->null);
        for(int replay=0;replay<2;replay++)try(Connection c=DriverManager.getConnection(f.url.split(";",2)[0],"receipt_runtime","synthetic-test-only")){
            c.setSchema("CANONICAL");c.setAutoCommit(false);
            try(Statement q=c.createStatement()){q.executeUpdate("UPDATE CLUSTER_ATOMIC_GUARD SET VERSION=VERSION+1 WHERE ID=1");}
            assertEquals(401,attempts.login(c,operation,"c".repeat(64),"e".repeat(64),hash("wrong"),EMAIL,"wrong").response().getInt("httpStatus"));c.commit();
            try(Statement q=c.createStatement()){
                assertThrows(SQLException.class,()->q.executeUpdate("UPDATE LOGIN_ATTEMPTS SET HTTP_STATUS=200 WHERE OPERATION_ID='"+operation+"'"));
            }
        }
        assertEquals(1,f.scalar("SELECT COUNT(*) FROM CANONICAL.LOGIN_ATTEMPTS"));
        assertEquals(1,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));
    }

    @Test public void failureBudgetIsSharedAcrossInstancesAndDefiniteRetriesDoNotRecount()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions a=f.service(false),b=f.service(false);String first=UUID.randomUUID().toString();
        assertEquals(401,f.login(a,first,"wrong").httpStatus());assertEquals(401,f.login(b,first,"wrong").httpStatus());
        for(int i=1;i<20;i++)assertEquals(401,f.login(i%2==0?a:b,UUID.randomUUID().toString(),"wrong").httpStatus());
        FinanceCluster.LoginOutcome limited=f.login(b,UUID.randomUUID().toString(),"wrong");
        assertEquals(429,limited.httpStatus());assertEquals("COMMITTED",limited.response().getString("operationState"));assertEquals("dual",limited.response().getString("durability"));
        assertEquals(20,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));assertEquals(21,f.scalar("SELECT COUNT(*) FROM CANONICAL.LOGIN_ATTEMPTS"));assertEquals(20,f.authentications.get());
        f.now.addAndGet(300001);assertEquals(401,f.login(a,UUID.randomUUID().toString(),"wrong").httpStatus());assertEquals(1,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));
    }

    @Test public void commitAckLossAndConfirmationLossPreserveOneFailedAttempt()throws Exception{
        Fixture f=new Fixture();AtomicBoolean lost=new AtomicBoolean(true);
        AtomicFinanceTransactions uncertain=f.service(false,proof->{Connection raw=f.connect();return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
            try{Object result=m.invoke(raw,a);if(m.getName().equals("commit")&&lost.getAndSet(false))throw new SQLException("Synthetic ACK loss","08006");return result;}catch(InvocationTargetException error){throw error.getCause();}
        });});
        String id=UUID.randomUUID().toString();try{f.login(uncertain,id,"wrong");fail("Uncertain commit returned a definitive failure");}catch(SQLException expected){ }
        assertEquals(401,f.login(f.service(false),id,"wrong").httpStatus());assertEquals(1,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));assertEquals(1,f.authentications.get());
        f.gate.loseConfirmation.set(true);String second=UUID.randomUUID().toString();try{f.login(f.service(false),second,"wrong");fail("Lost confirmation acknowledged");}catch(SQLException expected){ }
        f.gate.mode="single";FinanceCluster.LoginOutcome replay=f.login(f.service(false),second,"wrong");
        assertEquals(401,replay.httpStatus());assertTrue(replay.response().getBoolean("degraded"));assertEquals(2,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));assertEquals(2,f.scalar("SELECT COUNT(*) FROM CANONICAL.LOGIN_ATTEMPTS"));
    }

    @Test public void successfulReplayRequiresCurrentPasswordAndOriginalGeneration()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions a=f.service(true),b=f.service(true);String id=UUID.randomUUID().toString();
        assertNotNull(f.login(a,id,PASSWORD).user());assertNotNull(f.login(b,id,PASSWORD).user());assertEquals(1,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));
        f.rotate("synthetic-new-password",f.generation);
        FinanceCluster.LoginOutcome old=f.login(b,id,PASSWORD);assertEquals(401,old.httpStatus());assertNull(old.user());assertEquals("COMMITTED",old.response().getString("operationState"));
        assertEquals(409,f.login(b,id,"synthetic-new-password").httpStatus());
        assertEquals(200,f.login(a,UUID.randomUUID().toString(),"synthetic-new-password").httpStatus());
        String generation=UUID.randomUUID().toString();String fresh=UUID.randomUUID().toString();assertEquals(200,f.login(a,fresh,"synthetic-new-password").httpStatus());
        f.rotate("synthetic-new-password",generation);FinanceCluster.LoginOutcome recycled=f.login(b,fresh,"synthetic-new-password");assertEquals(401,recycled.httpStatus());assertNull(recycled.user());
        assertEquals(3,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));
    }

    @Test public void aFailedReceiptNeverTurnsIntoSuccessAfterCredentialChange()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions service=f.service(true);String id=UUID.randomUUID().toString();
        assertEquals(401,f.login(service,id,"later-valid").httpStatus());f.rotate("later-valid",f.generation);
        assertEquals(401,f.login(f.service(true),id,"later-valid").httpStatus());assertEquals(1,f.authentications.get());
        assertEquals(200,f.login(service,UUID.randomUUID().toString(),"later-valid").httpStatus());assertEquals(2,f.scalar("SELECT ATTEMPTS FROM CANONICAL.LOGIN_LIMITS"));
    }

    @Test public void publicStatusIsBoundHasNoSessionIdentityAndHasASharedBudget()throws Exception{
        Fixture f=new Fixture();AtomicFinanceTransactions a=f.service(false),b=f.service(false);String id=UUID.randomUUID().toString();f.login(a,id,"wrong");
        JSONObject status=b.loginStatus(id,"s".repeat(64),"e".repeat(64));assertEquals("COMMITTED",status.getString("operationState"));assertEquals(401,status.getInt("loginHttpStatus"));
        assertFalse(status.has("userGeneration"));assertFalse(status.has("userId"));assertFalse(status.has("userAccount"));
        for(int i=1;i<20;i++)assertEquals(200,(i%2==0?a:b).loginStatus(id,"s".repeat(64),"e".repeat(64)).getInt("httpStatus"));
        assertEquals(429,a.loginStatus(id,"s".repeat(64),"e".repeat(64)).getInt("httpStatus"));
        JSONObject mismatch=a.loginStatus(id,"t".repeat(64),"x".repeat(64));assertEquals(409,mismatch.getInt("httpStatus"));assertEquals("UNKNOWN",mismatch.getString("operationState"));
        JSONObject absent=a.loginStatus(UUID.randomUUID().toString(),"u".repeat(64),"e".repeat(64));assertEquals("ABSENT",absent.getString("operationState"));
    }
}

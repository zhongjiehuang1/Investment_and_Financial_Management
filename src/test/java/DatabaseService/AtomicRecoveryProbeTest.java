package DatabaseService;

import org.junit.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

public class AtomicRecoveryProbeTest {
    private static final class Fixture {
        final String url="jdbc:h2:mem:probe_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1";
        final Properties properties=new Properties();boolean reject;
        final AtomicFinanceTransactions.Authority authority=new AtomicFinanceTransactions.Authority(){
            public AtomicFinanceTransactions.Proof capture(){return new AtomicFinanceTransactions.Proof("11111111-1111-4111-8111-111111111111","view","1","dual","a".repeat(64),"22222222-2222-4222-8222-222222222222","permit");}
            public void verify(AtomicFinanceTransactions.Proof proof)throws SQLException{if(reject)throw new SQLException("Changed authority");}
            public String confirm(Connection c,AtomicFinanceTransactions.Proof proof,String op){throw new AssertionError("Read-only recovery must not create a GTID or call commit confirmation");}
        };
        Fixture()throws Exception{
            properties.setProperty("atomic.migrationId",UUID.randomUUID().toString());
            execute("CREATE SCHEMA A;CREATE SCHEMA B");
            for(String schema:List.of("A","B")){
                execute("CREATE TABLE "+schema+".CLUSTER_GATE(ID INT PRIMARY KEY,OPERATION_ID CHAR(36));INSERT INTO "+schema+".CLUSTER_GATE VALUES(1,NULL)");
                for(String table:List.of("USERS","BASIC_VARIABLES_AND_DATA","ALL_INVESTMENT_ASSETS","ALL_NON_INVESTMENT_ASSETS","INCOME_AND_EXPENDITURE_DATA","LEDGER_JOURNALS","LEDGER_ENTRIES"))
                    execute("CREATE TABLE "+schema+"."+table+"(ID INT,USER_ID INT,GENERATION CHAR(36),EMAIL_ADDRESS VARCHAR(50),PASSWORD VARCHAR(50),JOURNAL_ID INT)");
            }
            execute("CREATE TABLE A.CLUSTER_ATOMIC_CONFIG(ID INT,PROTOCOL_VERSION INT,MIGRATION_ID CHAR(36),PRIMARY_SCHEMA VARCHAR(64),PEER_SCHEMA VARCHAR(64));INSERT INTO A.CLUSTER_ATOMIC_CONFIG VALUES(1,2,'"+properties.getProperty("atomic.migrationId")+"','A','B')");
            execute("CREATE TABLE A.CLUSTER_OPERATIONS(ID CHAR(36),ENDPOINT VARCHAR(160),DELETE_RESULT_HASH CHAR(64),DELETE_RESULT_EXPIRES TIMESTAMP)");
            execute("CREATE TABLE A.CLUSTER_ATOMIC_GUARD(ID INT PRIMARY KEY,VERSION BIGINT,DATA_REVISION BIGINT DEFAULT 0);INSERT INTO A.CLUSTER_ATOMIC_GUARD VALUES(1,9,0)");
            execute("CREATE TABLE A.LOGIN_LIMITS(CLIENT_HASH CHAR(64),WINDOW_START_MS BIGINT,ATTEMPTS INT);CREATE TABLE A.LOGIN_STATUS_LIMITS(CLIENT_HASH CHAR(64),WINDOW_START_MS BIGINT,ATTEMPTS INT)");
            execute("CREATE TABLE A.LOGIN_ATTEMPTS(OPERATION_ID CHAR(36),CLIENT_HASH CHAR(64),EMAIL_HASH CHAR(64),REQUEST_HASH CHAR(64),HTTP_STATUS INT,USER_ID INT,USER_GENERATION CHAR(36),CREATED_AT_MS BIGINT)");
        }
        void execute(String sql)throws Exception{try(Connection c=DriverManager.getConnection(url);Statement q=c.createStatement()){q.execute(sql);}}
        Connection connection()throws SQLException{
            Connection c=DriverManager.getConnection(url);
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("setCatalog")){c.setSchema((String)a[0]);return null;}
                if(m.getName().equals("getCatalog"))return c.getSchema();
                if(m.getName().equals("commit"))throw new AssertionError("Recovery must not commit a guard transaction");
                try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}
            });
        }
        AtomicFinanceTransactions service(){return new AtomicFinanceTransactions(properties,new String[]{"A","B"},authority,proof->connection());}
    }
    @Test public void actualRuntimeReadsStayIndependentAndDoNotWriteTheCertificationGuard()throws Exception{
        Fixture f=new Fixture();f.service().recoveryProbe();f.service().recoveryProbe();
        try(Connection c=DriverManager.getConnection(f.url);Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT VERSION FROM A.CLUSTER_ATOMIC_GUARD WHERE ID=1")){assertTrue(r.next());assertEquals(9,r.getLong(1));}
    }
    @Test public void missingRuntimeTableHeldGateOrChangedAuthorityFailsTheProbe()throws Exception{
        Fixture missing=new Fixture();missing.execute("DROP TABLE B.ALL_INVESTMENT_ASSETS");assertThrows(SQLException.class,()->missing.service().recoveryProbe());
        Fixture gate=new Fixture();gate.execute("UPDATE A.CLUSTER_GATE SET OPERATION_ID='"+UUID.randomUUID()+"' WHERE ID=1");assertThrows(SQLException.class,()->gate.service().recoveryProbe());
        Fixture stale=new Fixture();stale.reject=true;assertThrows(SQLException.class,()->stale.service().recoveryProbe());
        Fixture marker=new Fixture();marker.execute("DELETE FROM A.CLUSTER_ATOMIC_CONFIG");assertThrows(SQLException.class,()->marker.service().recoveryProbe());
    }
}

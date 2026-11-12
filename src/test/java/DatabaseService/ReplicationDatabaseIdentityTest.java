package DatabaseService;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class ReplicationDatabaseIdentityTest {
    private static Properties properties() {
        Properties p=new Properties();
        p.setProperty("node1.url","jdbc:mysql://127.0.0.1:3306/finance?useSSL=false");
        p.setProperty("node2.url","jdbc:mysql://localhost:3306/finance_peer?useSSL=false");
        p.setProperty("objectStore.jdbc.url","jdbc:mysql://127.0.0.1:3306/decisions?useSSL=false");
        return p;
    }
    @Test public void bothLogicalShardsAndDecisionsMustUseOnePrivateDatabaseServer() {
        Properties p=properties();ReplicationDatabaseIdentity.validateEndpoints(p);
        for(String invalid:new String[]{"jdbc:mysql://127.0.0.1:3307/finance_peer?x=1",
                "jdbc:mysql://127.0.0.1:3306/finance?x=1","jdbc:mysql://example.invalid:3306/finance_peer?x=1",
                "jdbc:mysql://127.0.0.1:3306/finance_peer/other?x=1"}) {
            p=properties();p.setProperty("node2.url",invalid);Properties rejected=p;
            assertThrows(IllegalArgumentException.class,()->ReplicationDatabaseIdentity.validateEndpoints(rejected));
        }
        p=properties();p.setProperty("objectStore.jdbc.url","jdbc:mysql://127.0.0.1:3307/decisions?x=1");
        Properties wrongDecision=p;
        assertThrows(IllegalArgumentException.class,()->ReplicationDatabaseIdentity.validateEndpoints(wrongDecision));
    }

    @Test public void wrongMysqlInstanceCannotPassTheAuthorityCheck() throws Exception {
        String expected="11111111-1111-4111-8111-111111111111";
        ReplicationDatabaseIdentity.verify(connection(expected,1,"AFTER"),expected);
        assertThrows(SQLException.class,()->ReplicationDatabaseIdentity.verify(connection("22222222-2222-4222-8222-222222222222"),expected));
        assertThrows(SQLException.class,()->ReplicationDatabaseIdentity.verify(connection("invalid"),expected));
        assertThrows(SQLException.class,()->ReplicationDatabaseIdentity.verify(connection(expected),null));
        assertThrows(SQLException.class,()->ReplicationDatabaseIdentity.verify(connection(expected,0,"AFTER"),expected));
        assertThrows(SQLException.class,()->ReplicationDatabaseIdentity.verify(connection(expected,1,"EVENTUAL"),expected));
    }

    private static Connection connection(String identity) {return connection(identity,1,"AFTER");}
    private static Connection connection(String identity,int binlog,String consistency) {
        AtomicBoolean first=new AtomicBoolean(true);
        ResultSet rows=(ResultSet)Proxy.newProxyInstance(ReplicationDatabaseIdentityTest.class.getClassLoader(),new Class[]{ResultSet.class},(p,m,a)->switch(m.getName()){
            case "next"->first.getAndSet(false);case "getString"->((Integer)a[0])==1?identity:consistency;case "getInt"->binlog;default->null;
        });
        Statement statement=(Statement)Proxy.newProxyInstance(ReplicationDatabaseIdentityTest.class.getClassLoader(),new Class[]{Statement.class},(p,m,a)->{
            if(m.getName().equals("executeQuery")){assertEquals("SELECT @@server_uuid,@@session.sql_log_bin,@@session.group_replication_consistency",a[0]);return rows;}return null;
        });
        return (Connection)Proxy.newProxyInstance(ReplicationDatabaseIdentityTest.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->m.getName().equals("createStatement")?statement:null);
    }
}

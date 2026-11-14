package DatabaseService;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class BoundedDatabaseConnectionsTest {
    private Connection fake(){return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Connection.class},(p,m,a)->{
        if(m.getName().equals("close"))return null;
        if(m.getName().equals("isClosed"))return false;
        throw new UnsupportedOperationException(m.getName());
    });}
    @Test public void limitsConnectionsRejectsPromptlyAndReleasesExactlyOnce()throws Exception{
        List<Connection> held=new ArrayList<>();
        try{
            for(int i=0;i<4;i++)held.add(BoundedDatabaseConnections.open(this::fake));
            long start=System.nanoTime();assertThrows(SQLTransientConnectionException.class,()->BoundedDatabaseConnections.open(this::fake));
            assertTrue((System.nanoTime()-start)/1_000_000<2500);
            held.get(0).close();held.get(0).close();
            try(Connection replacement=BoundedDatabaseConnections.open(this::fake)){
                assertThrows(SQLTransientConnectionException.class,()->BoundedDatabaseConnections.open(this::fake));
            }
        }finally{for(Connection c:held)c.close();}
        assertThrows(SQLException.class,()->BoundedDatabaseConnections.open(()->{throw new SQLException("synthetic connect failure");}));
        try(Connection c=BoundedDatabaseConnections.open(this::fake)){assertNotNull(c);}
    }
}

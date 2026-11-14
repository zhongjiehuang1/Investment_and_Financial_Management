package DatabaseService;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import static org.junit.Assert.*;

/** Synthetic JDBC tests only; actual MySQL 26.10/tunnel behavior requires integration verification. */
public class SingleDatabaseAuthorityTest {
    private static final String UUID="11111111-1111-4111-8111-111111111111";
    private static Properties properties(){Properties p=new Properties();p.setProperty("atomic.deployment","single-database");p.setProperty("atomic.mysqlUuid",UUID);p.setProperty("atomic.single.transport","local-or-verified-ssh-tunnel");return p;}
    private static Object proxy(Class<?> type,java.lang.reflect.InvocationHandler h){return Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},h);}
    private static Connection db(String uuid,int sessionLog,int binLog,int ro,int superRo,int flush,int sync,String doublewrite,String gtid,String zone,long delta){
        return (Connection)proxy(Connection.class,(p,m,a)->{
            if(m.getName().equals("createStatement")) return proxy(Statement.class,(sp,sm,sa)->{
                if(sm.getName().equals("executeQuery")) {
                    boolean identity=((String)sa[0]).contains("@@server_uuid");
                    boolean cipher=((String)sa[0]).startsWith("SHOW SESSION STATUS");
                    Object[] values=identity?new Object[]{uuid,sessionLog,binLog,ro,superRo}:cipher?new Object[]{"Ssl_cipher","TLS_AES_256_GCM_SHA384"}:new Object[]{flush,sync,doublewrite,gtid,zone,delta};
                    final int[] row={0};
                    return proxy(ResultSet.class,(rp,rm,ra)->{
                        if(rm.getName().equals("next"))return row[0]++==0;
                        if(rm.getName().equals("getString"))return String.valueOf(values[(Integer)ra[0]-1]);
                        if(rm.getName().equals("getInt"))return ((Number)values[(Integer)ra[0]-1]).intValue();
                        if(rm.getName().equals("getLong"))return ((Number)values[(Integer)ra[0]-1]).longValue();
                        if(rm.getName().equals("close"))return null;
                        throw new UnsupportedOperationException(rm.getName());
                    });
                }
                if(sm.getName().equals("close"))return null;
                throw new UnsupportedOperationException(sm.getName());
            });
            if(m.getName().equals("close"))return null;
            throw new UnsupportedOperationException(m.getName());
        });
    }
    private static Connection valid(){return db(UUID,1,1,0,0,1,1,"ON","ON","UTC",0);}
    @Test public void explicitModePinsUuidAndReportsOnlySingle()throws Exception{
        Properties p=properties();assertTrue(AtomicFinanceTransactions.singleDatabase(p));
        SingleDatabaseAuthority a=new SingleDatabaseAuthority(p);
        assertEquals("single",a.confirm(valid(),a.capture(),"synthetic-operation"));
        assertFalse(AtomicFinanceTransactions.singleDatabase(new Properties()));
        p.setProperty("atomic.deployment","typo");Properties invalidMode=p;assertThrows(IllegalArgumentException.class,()->AtomicFinanceTransactions.singleDatabase(invalidMode));
        p=properties();p.remove("atomic.single.transport");Properties bad=p;assertThrows(IllegalArgumentException.class,()->new SingleDatabaseAuthority(bad));
    }
    @Test public void tlsModeCannotBeDowngradedOrDuplicated() {
        String base="jdbc:mysql://127.0.0.1:3307/IAFM";
        AtomicFinanceTransactions.requireVerifiedIdentityTls(base+"?sslMode=VERIFY_IDENTITY");
        for(String suffix:new String[]{"", "?sslMode=DISABLED", "?sslMode=VERIFY_CA", "?sslMode=VERIFY_IDENTITY&sslMode=DISABLED", "?sslMode=VERIFY_IDENTITY&useSSL=false", "?sslMode=VERIFY_IDENTITY&%73slMode=DISABLED"})
            assertThrows(IllegalArgumentException.class,()->AtomicFinanceTransactions.requireVerifiedIdentityTls(base+suffix));
    }
    @Test public void wrongUuidUnsafeDatabaseOrTimeNeverConfirms()throws Exception{
        SingleDatabaseAuthority a=new SingleDatabaseAuthority(properties());
        Connection[] invalid={db("22222222-2222-4222-8222-222222222222",1,1,0,0,1,1,"ON","ON","UTC",0),
            db(UUID,0,1,0,0,1,1,"ON","ON","UTC",0),db(UUID,1,0,0,0,1,1,"ON","ON","UTC",0),
            db(UUID,1,1,1,0,1,1,"ON","ON","UTC",0),db(UUID,1,1,0,1,1,1,"ON","ON","UTC",0),
            db(UUID,1,1,0,0,2,1,"ON","ON","UTC",0),db(UUID,1,1,0,0,1,0,"ON","ON","UTC",0),
            db(UUID,1,1,0,0,1,1,"OFF","ON","UTC",0),db(UUID,1,1,0,0,1,1,"ON","ON","SYSTEM",3600)};
        for(Connection c:invalid)assertThrows(SQLException.class,()->a.confirm(c,a.capture(),"synthetic"));
    }
}

package DatabaseService;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import org.junit.After;
import org.junit.Test;
import javax.naming.Context;
import javax.naming.spi.InitialContextFactory;
import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Misconfigured old environments cannot bypass the atomic guard/receipts. */
public class LegacyDatabaseIsolationTest extends DatabaseInit {
    private final Map<String,String> previous=new HashMap<>();
    private void property(String key,String value){previous.putIfAbsent(key,System.getProperty(key));System.setProperty(key,value);}
    @After public void restore(){previous.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
    @Test public void onlyExplicitDisposableLoopbackOrMemoryEndpointsAreAccepted(){
        for(String url:new String[]{"jdbc:h2:mem:unit;MODE=MySQL", "jdbc:mysql://127.0.0.1:23516/finance_validation_unit", "jdbc:mysql://127.0.0.1:23516/finance_atomic_validation_a?useSSL=false"})assertTrue(LegacyDatabaseIsolation.allowed(url));
        for(String url:new String[]{"jdbc:mysql://localhost:3306/IAFM", "jdbc:mysql://127.0.0.1:23516/IAFM_peer", "jdbc:mysql://127.0.0.1:23516/finance_validation_", "jdbc:mysql://other:23516/finance_validation_unit", "jdbc:h2:file:/tmp/production"})assertFalse(LegacyDatabaseIsolation.allowed(url));
    }
    @Test public void canonicalSingleDatabasePathFailsBeforeConnectionOrCredentialLookup(){
        property("iafm.cluster.enabled","false");property("iafm.db.jndi","");property("iafm.db.url","jdbc:mysql://127.0.0.1:1/IAFM");
        IllegalStateException denied=assertThrows(IllegalStateException.class,DatabaseInit::openConnection);
        assertTrue(denied.getMessage().contains("production requires the atomic"));
    }
    @Test public void jndiActualCanonicalEndpointIsClosedBeforeAnyMutation(){
        property("iafm.cluster.enabled","false");property("iafm.db.jndi","test/managed-canonical");
        property(Context.INITIAL_CONTEXT_FACTORY,IsolatedNamingFactory.class.getName());
        AtomicInteger closed=new AtomicInteger(),mutations=new AtomicInteger();
        DatabaseMetaData metadata=(DatabaseMetaData)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DatabaseMetaData.class},(p,m,a)->{
            if(m.getName().equals("getURL"))return "jdbc:mysql://127.0.0.1:23516/IAFM";throw new AssertionError(m.getName());});
        Connection connection=(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->switch(m.getName()){
            case "getMetaData"->metadata;case "close"->{closed.incrementAndGet();yield null;}case "rollback"->null;
            default->{mutations.incrementAndGet();throw new AssertionError(m.getName());}});
        IsolatedNamingFactory.source=(DataSource)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{DataSource.class},(p,m,a)->{
            if(m.getName().equals("getConnection"))return connection;throw new AssertionError(m.getName());});
        assertThrows(IllegalStateException.class,DatabaseInit::openConnection);assertEquals(1,closed.get());assertEquals(0,mutations.get());
    }
    @Test public void omittedAtomicConfigurationKeepsStaticContextAvailableAndDataClosed(){
        property("iafm.cluster.enabled","false");property("iafm.db.jndi","");property("iafm.db.url","jdbc:mysql://127.0.0.1:1/IAFM");
        AtomicInteger logs=new AtomicInteger();
        ServletContext context=(ServletContext)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{ServletContext.class},(p,m,a)->{
            if(m.getName().equals("log")){logs.incrementAndGet();return null;}throw new AssertionError(m.getName());});
        new DatabaseInit().contextInitialized(new ServletContextEvent(context));assertEquals(1,logs.get());
        assertThrows(IllegalStateException.class,DatabaseInit::openConnection);
    }
    public static final class IsolatedNamingFactory implements InitialContextFactory {
        static DataSource source;
        public Context getInitialContext(java.util.Hashtable<?,?> environment){
            return (Context)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Context.class},(p,m,a)->switch(m.getName()){
                case "lookup"->source;case "close"->null;case "getEnvironment"->new java.util.Hashtable<>();default->throw new AssertionError(m.getName());});
        }
    }
}

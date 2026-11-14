package DatabaseService;

import Controller.ClusterAvailability;
import org.junit.Test;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** Old GR health endpoint must not gate explicit single mode; actual SQL failure still fails closed. */
public class SingleDatabaseReadinessTest {
    @Test public void staleOldEndpointIsIgnoredOnlyWhenSqlProbeSucceeds() throws Exception {
        AtomicRecoveryProbeTest.Fixture fixture=new AtomicRecoveryProbeTest.Fixture();
        fixture.properties.setProperty("atomic.deployment","single-database");
        FinanceCluster cluster=new FinanceCluster(fixture.properties,fixture.service());
        Field singleton=FinanceCluster.class.getDeclaredField("instance");singleton.setAccessible(true);
        Field ready=DatabaseInit.class.getDeclaredField("clusterSchemaReady");ready.setAccessible(true);
        String enabled=System.getProperty("iafm.cluster.enabled"),health=System.getProperty("iafm.cluster.health.url"),required=System.getProperty("iafm.cluster.health.required");
        try {
            System.setProperty("iafm.cluster.enabled","true");
            System.setProperty("iafm.cluster.health.url","http://127.0.0.1:1/old-gr-observer");
            System.setProperty("iafm.cluster.health.required","true");
            singleton.set(null,cluster);ready.setBoolean(null,true);
            assertTrue(ClusterAvailability.ready());
            fixture.reject=true;
            assertFalse(ClusterAvailability.ready());
        } finally {
            singleton.set(null,null);ready.setBoolean(null,false);cluster.close();
            restore("iafm.cluster.enabled",enabled);restore("iafm.cluster.health.url",health);restore("iafm.cluster.health.required",required);
        }
    }
    private static void restore(String name,String value){if(value==null)System.clearProperty(name);else System.setProperty(name,value);}
}

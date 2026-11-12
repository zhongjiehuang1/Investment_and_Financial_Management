package DatabaseService;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class StartupAuthorityTest {
    @Test public void startupDiagnosticsNeverCopyExceptionMessagesOrCredentialValues(){
        java.sql.SQLException sql=new java.sql.SQLException("private-value-must-not-appear","42000",1142);
        String safe=DatabaseInit.initializationDiagnostic(new IllegalStateException("sensitive-sql",sql));
        assertTrue(safe.contains("SQLState=42000 errno=1142"));assertFalse(safe.contains("private-value"));assertFalse(safe.contains("sensitive-sql"));
    }
    @After public void clear() {
        for(String key:new String[]{"iafm.cluster.enabled","iafm.cluster.config",
                "iafm.cluster.authority.url","iafm.cluster.health.token"}) System.clearProperty(key);
    }

    @Test public void replicatedStartupRefusesToRepairBeforeLocalAuthority() throws Exception {
        Path config=Files.createTempFile("finance-authority-test-", ".properties");
        try {
            Files.writeString(config,"objectStore.backend=jdbc\n");
            System.setProperty("iafm.cluster.enabled","true");
            System.setProperty("iafm.cluster.config",config.toString());
            var initialize=DatabaseInit.class.getDeclaredMethod("initialize",jakarta.servlet.ServletContextEvent.class);
            initialize.setAccessible(true);
            try {
                initialize.invoke(new DatabaseInit(),new Object[]{null});
                fail("Replicated ID repair must not start without writer authority");
            } catch(InvocationTargetException expected) {
                assertTrue(expected.getCause() instanceof IllegalStateException);
                assertEquals("Replicated recovery authority is unavailable.",expected.getCause().getMessage());
            }
        } finally { Files.deleteIfExists(config); }
    }
}

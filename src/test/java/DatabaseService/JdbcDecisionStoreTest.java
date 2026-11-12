package DatabaseService;

import org.junit.Test;
import java.util.Properties;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class JdbcDecisionStoreTest {
    @Test public void defaultUsesExistingFileStore()throws Exception {
        assertNull(JdbcDecisionStore.from(new Properties(),null));
    }
    @Test public void rejectsIncompleteOrNonLoopbackConfiguration()throws Exception {
        Properties p=new Properties();p.setProperty("objectStore.backend","jdbc");
        try { JdbcDecisionStore.from(p,null);fail("missing credentials accepted"); } catch(IllegalArgumentException expected) { }
        p.setProperty("objectStore.jdbc.user","test");p.setProperty("objectStore.jdbc.password","test");
        p.setProperty("objectStore.jdbc.url","jdbc:mysql://example.com:3306/finance?useSSL=true");
        try { JdbcDecisionStore.from(p,null);fail("public endpoint accepted"); } catch(IllegalArgumentException expected) { }
        p.setProperty("objectStore.backend","unknown");
        try { JdbcDecisionStore.from(p,null);fail("unknown backend accepted"); } catch(IllegalArgumentException expected) { }
    }
    @Test public void doesNotAbandonAFileStoreDecision()throws Exception {
        var path=Files.createTempDirectory("iafm-old-store-");
        Files.writeString(path.resolve("identity"),"marker");
        Files.writeString(path.resolve("coordinator.lock"),"");
        JdbcDecisionStore.requireEmptyLegacyStore(path);
        var actions=Files.createDirectories(path.resolve("ShadowNoFileLockStore/defaultStore/AtomicAction"));
        Files.writeString(actions.resolve("unresolved"),"durable decision");
        try { JdbcDecisionStore.requireEmptyLegacyStore(path);fail("orphaned decision ignored"); }
        catch(java.io.IOException expected) { }
    }
    @Test public void identifiesEveryPartOfTheDecisionStoreConfiguration() {
        var source=new com.mysql.cj.jdbc.MysqlDataSource();source.setURL("jdbc:mysql://127.0.0.1:3306/test");source.setUser("user");source.setPassword("password");
        var path=java.nio.file.Path.of("/tmp/finance-store");
        String original=JdbcDecisionStore.fingerprint(source,"node",path);
        assertEquals(original,JdbcDecisionStore.fingerprint(source,"node",path));
        assertNotEquals(original,JdbcDecisionStore.fingerprint(source,"other-node",path));
        assertNotEquals(original,JdbcDecisionStore.fingerprint(source,"node",java.nio.file.Path.of("/tmp/other-store")));
        source.setPassword("different");assertNotEquals(original,JdbcDecisionStore.fingerprint(source,"node",path));
    }
}

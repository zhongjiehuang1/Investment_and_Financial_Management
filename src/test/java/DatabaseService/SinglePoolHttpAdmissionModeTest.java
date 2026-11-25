package DatabaseService;
import org.junit.Test;import java.util.Properties;import static org.junit.Assert.*;
public class SinglePoolHttpAdmissionModeTest {
 @Test public void admissionRequiresExplicitAtomicSingleDatabaseAndEnabledPool(){
  Properties p=new Properties();assertFalse(FinanceCluster.boundedSingleDatabasePoolSelected(p));p.setProperty("transaction.backend","atomic");assertFalse(FinanceCluster.boundedSingleDatabasePoolSelected(p));p.setProperty("atomic.deployment","single-database");assertFalse(FinanceCluster.boundedSingleDatabasePoolSelected(p));p.setProperty("atomic.single.pool.enabled","true");assertTrue(FinanceCluster.boundedSingleDatabasePoolSelected(p));p.setProperty("atomic.single.pool.enabled","invalid");try{FinanceCluster.boundedSingleDatabasePoolSelected(p);fail();}catch(IllegalArgumentException expected){}
  p.setProperty("atomic.single.pool.enabled","true");p.setProperty("atomic.deployment","replicated");try{FinanceCluster.boundedSingleDatabasePoolSelected(p);fail();}catch(IllegalArgumentException expected){}
 }
}

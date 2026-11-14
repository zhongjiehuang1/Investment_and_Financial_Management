package DatabaseService;
import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class StartupWarmupTest {
 @Test public void transientColdAcquisitionCanWarmBeforeReady()throws Exception {
  AtomicInteger calls=new AtomicInteger();AtomicBoolean retired=new AtomicBoolean();
  StartupWarmup.run(()->{if(calls.getAndIncrement()==0)throw new SQLTransientConnectionException();},()->{retired.set(true);return ()->{};},2000,200);
  assertEquals(2,calls.get());assertFalse(retired.get());
 }
 @Test public void timeoutRetiresBeforeReturningAndDoesNotWaitForStuckProbe()throws Exception {
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean retired=new AtomicBoolean();long started=System.nanoTime();
  try{try{StartupWarmup.run(()->{entered.countDown();while(release.getCount()>0)try{release.await();}catch(InterruptedException ignored){}},()->{retired.set(true);return ()->{};},100,200);fail("Timeout must fail readiness");}catch(SQLException expected){}
   assertTrue(entered.await(1,TimeUnit.SECONDS));assertTrue(retired.get());assertTrue((System.nanoTime()-started)/1_000_000<1500);
  }finally{release.countDown();}
 }
 @Test public void failedRetirementIsReported(){
  try{StartupWarmup.run(()->{throw new SQLException("denied");},()->()->{throw new IllegalStateException();},1000,200);fail("Close failure must fail readiness");}
  catch(SQLException e){assertEquals("Pool retirement not confirmed",e.getMessage());}
 }
}

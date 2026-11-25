package DatabaseService;
import org.junit.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class BoundedAdmissionTest {
 @Test public void sameThreadReentersAndReleasesEveryLevel(){BoundedAdmission gate=new BoundedAdmission();try(var first=gate.enter();var second=gate.enter()){assertNotNull(second);}try(var next=gate.enter()){assertNotNull(next);}}
 @Test public void occupiedOwnerRejectsBeforeWorkAndCanRecover()throws Exception{
  BoundedAdmission gate=new BoundedAdmission();ExecutorService pool=Executors.newSingleThreadExecutor();AtomicBoolean entered=new AtomicBoolean();
  try{try(var owner=gate.internal()){
   long start=System.nanoTime();Future<Boolean> rejected=pool.submit(()->{try(var queued=gate.enter()){entered.set(true);return false;}catch(FinanceCluster.Busy busy){return true;}});
   assertTrue(rejected.get(2,TimeUnit.SECONDS));assertFalse(entered.get());assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)>=BoundedAdmission.WAIT_MILLIS-50);
  }assertTrue(pool.submit(()->{try(var next=gate.enter()){return true;}}).get(2,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
 }
 @Test public void interruptedWaitPreservesInterruptAndNeverEnters()throws Exception{
  BoundedAdmission gate=new BoundedAdmission();ExecutorService pool=Executors.newSingleThreadExecutor();
  try(var owner=gate.internal()){assertTrue(pool.submit(()->{Thread.currentThread().interrupt();try(var ignored=gate.enter()){return false;}catch(FinanceCluster.Busy busy){return Thread.currentThread().isInterrupted();}}).get(2,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
 }
 @Test public void immediateChoiceIsAtomicAndReentrantAndReleasesAfterFailure()throws Exception{
  BoundedAdmission gate=new BoundedAdmission();ExecutorService pool=Executors.newSingleThreadExecutor();
  try{try(var first=gate.tryImmediate()){assertNotNull(first);try(var nested=gate.tryImmediate()){assertNotNull(nested);}assertTrue(pool.submit(()->gate.tryImmediate()==null).get(1,TimeUnit.SECONDS));}
   assertTrue(pool.submit(()->{try(var entry=gate.tryImmediate()){assertNotNull(entry);throw new IllegalStateException("injected work failure");}catch(IllegalStateException expected){return true;}}).get(1,TimeUnit.SECONDS));
   try(var next=gate.tryImmediate()){assertNotNull(next);}
  }finally{pool.shutdownNow();}
 }
 @Test public void immediateInterruptedChoiceRefusesWithoutAcquisition(){
  BoundedAdmission gate=new BoundedAdmission();Thread.currentThread().interrupt();try{assertThrows(FinanceCluster.Busy.class,gate::tryImmediate);assertTrue(Thread.currentThread().isInterrupted());}finally{Thread.interrupted();}try(var next=gate.tryImmediate()){assertNotNull(next);}
 }
}

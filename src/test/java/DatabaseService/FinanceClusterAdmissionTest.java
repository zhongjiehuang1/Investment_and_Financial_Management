package DatabaseService;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;
public class FinanceClusterAdmissionTest {
 @Test public void allExternalBusinessEntrypointsRefuseBeforeSqlOrCallback()throws Exception{
  var fixture=new AtomicRecoveryProbeTest.Fixture();FinanceCluster cluster=new FinanceCluster(fixture.properties,fixture.service());
  var field=FinanceCluster.class.getDeclaredField("admission");field.setAccessible(true);var admission=(BoundedAdmission)field.get(cluster);AtomicBoolean work=new AtomicBoolean();ExecutorService pool=Executors.newFixedThreadPool(6);
  List<Callable<Object>> calls=List.of(()->cluster.run(null,null,null,false,()->{work.set(true);return null;}),()->cluster.login(null,null,null,null),()->cluster.loginStatus(null,null,null),()->cluster.deletedUserStatus(null,null),()->cluster.deletedUserCookieStatus(null,null),()->cluster.status(null,null));
  try{try(var owner=admission.internal()){
   List<Future<Boolean>> pending=new ArrayList<>();for(var call:calls)pending.add(pool.submit(()->{try{call.call();return false;}catch(FinanceCluster.Busy refused){return true;}}));
   for(var future:pending)assertTrue(future.get(2,TimeUnit.SECONDS));assertFalse(work.get());
  }}finally{pool.shutdownNow();cluster.close();}
 }
 @Test public void staticInitializationQueueAlsoRejectsBeforeInitialization()throws Exception{
  var field=FinanceCluster.class.getDeclaredField("startupAdmission");field.setAccessible(true);var admission=(BoundedAdmission)field.get(null);ExecutorService pool=Executors.newSingleThreadExecutor();
  try(var owner=admission.internal()){assertTrue(pool.submit(()->{try{FinanceCluster.get();return false;}catch(FinanceCluster.Busy busy){return true;}}).get(2,TimeUnit.SECONDS));}finally{pool.shutdownNow();}
 }
}

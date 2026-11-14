package DatabaseService;

import org.junit.Test;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class FinanceClusterProbeLifecycleTest {
    @Test public void atomicProbeCompletesWhileTheBusinessMonitorIsOccupied() throws Exception {
        AtomicRecoveryProbeTest.Fixture fixture=new AtomicRecoveryProbeTest.Fixture();
        FinanceCluster cluster=new FinanceCluster(fixture.properties,fixture.service());
        ExecutorService threads=Executors.newFixedThreadPool(2);
        var admissionField=FinanceCluster.class.getDeclaredField("admission");admissionField.setAccessible(true);
        BoundedAdmission businessAdmission=(BoundedAdmission)admissionField.get(cluster);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            Future<?> business=threads.submit(()->{try(var admissionEntry=businessAdmission.internal()){entered.countDown();try{assertTrue(release.await(5,TimeUnit.SECONDS));}catch(InterruptedException failure){throw new RuntimeException(failure);}}});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertTrue(threads.submit(cluster::recoveryProbe).get(2,TimeUnit.SECONDS));
            assertFalse("Independent recovery did not wait for the business monitor",business.isDone());
            release.countDown();business.get(2,TimeUnit.SECONDS);
        } finally {release.countDown();threads.shutdownNow();cluster.close();}
    }

    @Test public void closeRejectsNewProbesAndDrainsAnExistingProbeWithoutReturningReady() throws Exception {
        AtomicRecoveryProbeTest.Fixture fixture=new AtomicRecoveryProbeTest.Fixture();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger connections=new AtomicInteger();
        AtomicFinanceTransactions service=new AtomicFinanceTransactions(fixture.properties,new String[]{"A","B"},fixture.authority,proof->{
            connections.incrementAndGet();entered.countDown();
            try {if(!release.await(5,TimeUnit.SECONDS))throw new SQLException("Synthetic release timeout");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new SQLException("Interrupted synthetic probe");}
            return fixture.connection();
        });
        FinanceCluster cluster=new FinanceCluster(fixture.properties,service);ExecutorService threads=Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> probe=threads.submit(cluster::recoveryProbe);assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<?> close=threads.submit(()->{try{cluster.close();}catch(Exception failure){throw new RuntimeException(failure);}});
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(cluster.recoveryReady()&&System.nanoTime()<deadline)Thread.onSpinWait();
            assertFalse(cluster.recoveryReady());
            var field=FinanceCluster.class.getDeclaredField("atomicProbeLifecycle");field.setAccessible(true);
            var lifecycle=(java.util.concurrent.locks.ReentrantReadWriteLock)field.get(cluster);
            while(!lifecycle.hasQueuedThreads()&&System.nanoTime()<deadline)Thread.onSpinWait();
            assertTrue("Close is actually queued behind the active probe",lifecycle.hasQueuedThreads());
            assertFalse(cluster.recoveryProbe());assertFalse(close.isDone());
            assertEquals(1,connections.get());release.countDown();
            assertFalse("A probe overlapping shutdown cannot report ready",probe.get(2,TimeUnit.SECONDS));
            close.get(2,TimeUnit.SECONDS);assertFalse(cluster.recoveryProbe());assertEquals(1,connections.get());
        } finally {release.countDown();threads.shutdownNow();cluster.close();}
    }

    @Test public void failedProbeReleasesItsLifecycleLeaseAndRemainsFailClosed() throws Exception {
        AtomicRecoveryProbeTest.Fixture fixture=new AtomicRecoveryProbeTest.Fixture();fixture.reject=true;
        FinanceCluster cluster=new FinanceCluster(fixture.properties,fixture.service());
        assertThrows(SQLException.class,cluster::recoveryProbe);
        cluster.close();assertFalse(cluster.recoveryProbe());assertFalse(cluster.recoveryReady());
    }
}

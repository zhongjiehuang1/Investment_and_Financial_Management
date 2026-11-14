package DatabaseService;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** One fair, reentrant business owner. Refusal occurs before entering SQL work. */
final class BoundedAdmission {
    static final long WAIT_MILLIS = 500; // Provisional bounded queue budget, not a latency SLA.
    private final ReentrantLock lock = new ReentrantLock(true);
    static final class Entry implements AutoCloseable {
        private final ReentrantLock lock;
        private Entry(ReentrantLock lock) { this.lock = lock; }
        @Override public void close() { lock.unlock(); }
    }
    /** Atomic fair, nonwaiting choice; never inspect availability then acquire. */
    Entry tryImmediate() {
        try { return lock.tryLock(0, TimeUnit.MILLISECONDS) ? new Entry(lock) : null; }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new FinanceCluster.Busy();
        }
    }
    Entry enter() {
        try {
            if (!lock.tryLock(WAIT_MILLIS, TimeUnit.MILLISECONDS)) throw new FinanceCluster.Busy();
            return new Entry(lock);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new FinanceCluster.Busy();
        }
    }
    Entry internal() { lock.lock(); return new Entry(lock); }
}

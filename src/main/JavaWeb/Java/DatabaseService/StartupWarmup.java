package DatabaseService;
import java.sql.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicReference;

/** Only used before readiness, for read-only startup checks. */
final class StartupWarmup {
    @FunctionalInterface interface Check { void run() throws Exception; }
    private StartupWarmup() { }
    static void run(Check check,Supplier<Runnable> retire,long warmMillis,long closeMillis)throws SQLException {
        ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"iafm-pool-warmup");t.setDaemon(true);return t;});
        Future<?> result=executor.submit(()->{
            while(!Thread.currentThread().isInterrupted()){
                try{check.run();return null;}
                catch(SQLTransientConnectionException cold){Thread.sleep(100);}
            }
            throw new InterruptedException("Warmup interrupted");
        });
        try{result.get(warmMillis,TimeUnit.MILLISECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();stop(result,retire,closeMillis);throw new SQLException("Startup warmup interrupted",interrupted);}
        catch(TimeoutException timeout){stop(result,retire,closeMillis);throw new SQLException("Startup warmup timed out",timeout);}
        catch(ExecutionException failure){stop(result,retire,closeMillis);throw new SQLException("Startup readiness verification failed",failure.getCause());}
        finally{executor.shutdownNow();}
    }
    private static void stop(Future<?> task,Supplier<Runnable> retire,long closeMillis)throws SQLException {
        task.cancel(true);Runnable closing=retire.get();AtomicReference<Throwable> failure=new AtomicReference<>();Thread cleanup=new Thread(()->{try{closing.run();}catch(Throwable e){failure.set(e);}},"iafm-pool-warmup-close");cleanup.setDaemon(true);cleanup.start();
        try{cleanup.join(closeMillis);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new SQLException("Pool retirement not confirmed",interrupted);}
        if(cleanup.isAlive()||failure.get()!=null)throw new SQLException("Pool retirement not confirmed");
    }
}

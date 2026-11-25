package DatabaseService;
import java.lang.reflect.*;
import java.sql.*;
import java.util.concurrent.*;

/** Bounds physical connections including health probes; never reuses a session. */
final class BoundedDatabaseConnections {
    private static final Semaphore SLOTS=new Semaphore(4,true);
    private BoundedDatabaseConnections() { }
    @FunctionalInterface interface Open { Connection connect() throws SQLException; }
    static Connection open(Open source)throws SQLException {
        boolean acquired;
        try { acquired=SLOTS.tryAcquire(500,TimeUnit.MILLISECONDS); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new SQLTransientConnectionException("Database admission interrupted","08004");}
        if(!acquired)throw new SQLTransientConnectionException("Database connection capacity is busy","08004");
        Connection physical;
        try { physical=source.connect();if(physical==null)throw new SQLException("Database driver returned no connection"); }
        catch(SQLException|RuntimeException|Error failure){SLOTS.release();throw failure;}
        final boolean[] released={false};
        try {
            return (Connection)Proxy.newProxyInstance(BoundedDatabaseConnections.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
                if(method.getName().equals("close"))synchronized(released){
                    if(!released[0]){physical.close();released[0]=true;SLOTS.release();}return null;
                }
                try{return method.invoke(physical,args);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        }catch(RuntimeException|Error failure){try{physical.close();SLOTS.release();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
    }
}

package DatabaseService;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** Opt-in single-database pool, shared by live operations and recovery probes. */
final class SingleDatabasePool implements AutoCloseable {
    private final String url,user,password,catalog;
    private HikariDataSource pool;
    private boolean closed;
    SingleDatabasePool(String url,String user,String password){this(url,user,password,null);}
    SingleDatabasePool(String url,String user,String password,String catalog){this.url=url;this.user=user;this.password=password;this.catalog=catalog;}
    boolean matches(String u,String n,String p,String c){return Objects.equals(url,u)&&Objects.equals(user,n)&&Objects.equals(password,p)&&Objects.equals(catalog,c);}
    private synchronized HikariDataSource source()throws SQLException {
        if(closed)throw new SQLException("Single database pool is closed");
        if(pool==null){
            HikariConfig c=new HikariConfig();c.setPoolName("iafm-single-database");
            c.setJdbcUrl(url);c.setUsername(user);c.setPassword(password);
            if(catalog!=null)c.setCatalog(catalog);
            c.setMaximumPoolSize(4);c.setMinimumIdle(1);c.setConnectionTimeout(500);c.setValidationTimeout(250);
            c.setInitializationFailTimeout(-1);c.setAutoCommit(false);c.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            pool=new HikariDataSource(c);
        }
        return pool;
    }
    Connection open()throws SQLException{return source().getConnection();}
    synchronized void discard(Connection c){if(pool!=null&&c!=null)pool.evictConnection(c);}
    synchronized Runnable retire(){closed=true;HikariDataSource old=pool;pool=null;return ()->{if(old!=null)old.close();};}
    @Override public void close(){retire().run();}
}

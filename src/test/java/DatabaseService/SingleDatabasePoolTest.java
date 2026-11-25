package DatabaseService;
import java.sql.*;
import java.lang.reflect.*;
import java.util.UUID;
import org.junit.Test;
import static org.junit.Assert.*;

public class SingleDatabasePoolTest {
 private static long session(Connection c)throws SQLException {try(Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT SESSION_ID()")){assertTrue(r.next());return r.getLong(1);}}
 @Test public void rollsBackAndReusesCleanConnection()throws Exception {
  try(SingleDatabasePool pool=new SingleDatabasePool("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")){
   long first;
   try(Connection c=pool.open();Statement s=c.createStatement()){
    assertFalse(c.getAutoCommit());first=session(c);s.execute("CREATE TABLE T(ID INT)");c.commit();c.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);s.execute("INSERT INTO T VALUES(1)");
   }
   try(Connection c=pool.open();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM T")){
    assertEquals(first,session(c));assertTrue(r.next());assertEquals(0,r.getInt(1));assertFalse(c.getAutoCommit());assertEquals(Connection.TRANSACTION_READ_COMMITTED,c.getTransactionIsolation());
   }
  }
 }
 @Test public void limitsConnectionsAndDiscardsUncertainSession()throws Exception {
  try(SingleDatabasePool pool=new SingleDatabasePool("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","")){
   try(Connection a=pool.open();Connection b=pool.open();Connection c=pool.open();Connection d=pool.open()){
    long start=System.nanoTime();try{pool.open();fail("Expected bounded acquisition failure");}catch(SQLException expected){}
    long ms=(System.nanoTime()-start)/1_000_000;assertTrue(ms>=350&&ms<3000);
   }
   long old;try(Connection c=pool.open()){old=session(c);pool.discard(c);}
   try(Connection c=pool.open()){assertNotEquals(old,session(c));}
  }
 }
 @Test public void initializerAndRuntimeShareExactlyOneConfigurationBoundPool()throws Exception {
  Method lookup=AtomicFinanceTransactions.class.getDeclaredMethod("sharedPool",String.class,String.class,String.class,String.class);lookup.setAccessible(true);Field field=AtomicFinanceTransactions.class.getDeclaredField("sharedSinglePool");field.setAccessible(true);assertNull(field.get(null));
  try {
   Object first=lookup.invoke(null,"jdbc:h2:mem:bootstrap","u","p","canonical");Object second=lookup.invoke(null,"jdbc:h2:mem:bootstrap","u","p","canonical");assertSame(first,second);
   try{lookup.invoke(null,"jdbc:h2:mem:other","u","p","canonical");fail("Different config must fail closed");}catch(InvocationTargetException e){assertTrue(e.getCause() instanceof IllegalStateException);}
   ((SingleDatabasePool)first).retire().run();try{((SingleDatabasePool)first).open();fail("Retired pool must not reopen");}catch(SQLException expected){}
  }finally{field.set(null,null);}
 }
}

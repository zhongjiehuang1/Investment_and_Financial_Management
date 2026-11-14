package DatabaseService;
import Model.User;
import org.json.JSONObject;
import org.junit.Test;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
public class PageSnapshotTest {
 final String generation="11111111-1111-4111-8111-111111111111";
 class Fixture {
  final AtomicRecoveryProbeTest.Fixture db=new AtomicRecoveryProbeTest.Fixture();final AtomicBoolean closed=new AtomicBoolean();boolean revoke,changeEpoch,changeAtConfirmation,failClose;int confirmations,forbiddenWrites;
  Fixture()throws Exception{for(String s:List.of("A","B")){db.execute("INSERT INTO "+s+".USERS(ID,GENERATION) VALUES(1,'"+generation+"')");db.execute("INSERT INTO "+s+".BASIC_VARIABLES_AND_DATA(ID,USER_ID,EMAIL_ADDRESS) VALUES(1,1,'old')");}}
  Connection connection()throws SQLException{Connection c=DriverManager.getConnection(db.url);return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
   if(m.getName().equals("setCatalog")){c.setSchema((String)a[0]);return null;}if(m.getName().equals("getCatalog"))return c.getSchema();
   if(m.getName().equals("close")){closed.set(true);c.close();if(failClose)throw new SQLException("injected close failure");return null;}
   if((m.getName().equals("prepareStatement"))&&((String)a[0]).contains("FOR UPDATE"))throw new AssertionError("Read acquired a row lock");
   try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});}
  AtomicFinanceTransactions service(){return new AtomicFinanceTransactions(db.properties,new String[]{"A","B"},new AtomicFinanceTransactions.Authority(){
   public AtomicFinanceTransactions.Proof capture()throws SQLException{return db.authority.capture();}
   public void verify(AtomicFinanceTransactions.Proof p)throws SQLException{if(changeEpoch)throw new SQLException("Epoch changed");}
   public String confirm(Connection c,AtomicFinanceTransactions.Proof p,String id)throws SQLException{confirmations++;if(changeAtConfirmation)changeEpoch=true;try{if(revoke)for(String s:List.of("A","B"))db.execute("DELETE FROM "+s+".USERS WHERE ID=1");}catch(Exception e){throw new SQLException(e);}return "dual";}
  },proof->connection());}
  User user(){User user=new User();user.setId(1);user.setGeneration(generation);return user;}
  JSONObject read(FinanceCluster.Work work)throws Exception{try(RequestIdentity context=RequestIdentity.begin(user(),false,List.of(),List.of())){return service().pageSnapshot(user(),1,work);}}
  String value(int node)throws Exception{try(Connection c=ShardScope.current().connection(node);Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT EMAIL_ADDRESS FROM BASIC_VARIABLES_AND_DATA WHERE ID=1")){assertTrue(r.next());return r.getString(1);}}
 }
 @Test public void actualRepeatableReadKeepsTwoSchemaPayloadAndRevisionTogetherAndOldCasFails()throws Exception{
  Fixture f=new Fixture();JSONObject result=f.read(()->{assertEquals("old",f.value(1));assertEquals("old",f.value(2));try(Connection writer=DriverManager.getConnection(f.db.url);Statement q=writer.createStatement()){
   writer.setAutoCommit(false);q.executeUpdate("UPDATE A.BASIC_VARIABLES_AND_DATA SET EMAIL_ADDRESS='new'");q.executeUpdate("UPDATE B.BASIC_VARIABLES_AND_DATA SET EMAIL_ADDRESS='new'");q.executeUpdate("UPDATE A.CLUSTER_ATOMIC_GUARD SET DATA_REVISION=1");writer.commit();}
   return new JSONObject().put("a",f.value(1)).put("b",f.value(2));});
  assertEquals("old",result.getString("a"));assertEquals("old",result.getString("b"));assertTrue(result.getString("dataRevision").endsWith(":0"));assertEquals(1,f.confirmations);assertTrue(f.closed.get());
  String current=f.db.properties.getProperty("atomic.migrationId")+":1";assertThrows(FinanceDataRevision.Rejected.class,()->FinanceDataRevision.require(result.getString("dataRevision"),current,UUID.randomUUID().toString()));
  try(Connection c=DriverManager.getConnection(f.db.url);Statement q=c.createStatement();ResultSet r=q.executeQuery("SELECT VERSION FROM A.CLUSTER_ATOMIC_GUARD WHERE ID=1")){assertTrue(r.next());assertEquals(9,r.getInt(1));}
 }
 @Test public void finalRevocationEpochChangeAndCloseFailureReturnNoPage()throws Exception{
  Fixture revoked=new Fixture();revoked.revoke=true;assertThrows(RequestIdentity.StaleIdentity.class,()->revoked.read(JSONObject::new));assertTrue(revoked.closed.get());
  Fixture epoch=new Fixture();assertThrows(SQLException.class,()->epoch.read(()->{epoch.changeEpoch=true;return new JSONObject();}));assertTrue(epoch.closed.get());
  Fixture lateEpoch=new Fixture();lateEpoch.changeAtConfirmation=true;assertThrows(SQLException.class,()->lateEpoch.read(JSONObject::new));assertTrue(lateEpoch.closed.get());
  Fixture closing=new Fixture();closing.failClose=true;assertThrows(SQLException.class,()->closing.read(JSONObject::new));assertTrue(closing.closed.get());
 }
 @Test public void onlyExplicitReadIdentityMayEnter()throws Exception{
  Fixture f=new Fixture();assertThrows(SQLException.class,()->f.service().pageSnapshot(f.user(),1,JSONObject::new));
  try(RequestIdentity context=RequestIdentity.begin(f.user(),true,List.of(),List.of())){assertThrows(SQLException.class,()->f.service().pageSnapshot(f.user(),1,JSONObject::new));}
 }
 @Test public void concurrentSnapshotsActuallyOverlapWithoutGuardUpdates()throws Exception{
  Fixture f=new Fixture();CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
  try{List<Future<JSONObject>> jobs=new ArrayList<>();for(int i=0;i<2;i++)jobs.add(pool.submit(()->f.read(()->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return new JSONObject().put("value",f.value(1));})));assertTrue(entered.await(2,TimeUnit.SECONDS));release.countDown();for(var job:jobs)assertEquals("old",job.get(3,TimeUnit.SECONDS).getString("value"));}finally{release.countDown();pool.shutdownNow();}
 }
 @Test public void selectProxyRejectsEffectsEscapesAndStaleBorrowers()throws Exception{
  for(String sql:List.of("UPDATE USERS SET ID=2","SELECT * FROM USERS FOR SHARE","SELECT * FROM USERS FOR UPDATE","SELECT * FROM other.USERS","SELECT * FROM USERS a, other.USERS b","SELECT * FROM `other`.`USERS`","SELECT GET_LOCK('x',1)","SELECT * FROM USERS; DELETE FROM USERS"))assertThrows(SQLException.class,()->SnapshotSql.verify(sql));
  Fixture f=new Fixture();AtomicReference<Connection> saved=new AtomicReference<>();f.read(()->{Connection c=ShardScope.current().connection(1);saved.set(c);assertThrows(SQLException.class,()->c.unwrap(Connection.class));assertThrows(SQLException.class,()->c.getMetaData().getTables("other",null,null,null));assertThrows(SQLException.class,()->c.prepareStatement("DELETE FROM USERS"));assertThrows(SQLException.class,()->c.createStatement().executeUpdate("SELECT * FROM USERS"));return new JSONObject();});assertThrows(SQLException.class,()->saved.get().createStatement());
 }
 @Test public void routeAdmitsExactlyTwoReadersAndRefusesThirdWithoutWork()throws Exception{
  Fixture f=new Fixture();Properties config=new Properties();config.putAll(f.db.properties);config.setProperty("ingress.token","synthetic-local-test-only");FinanceCluster cluster=new FinanceCluster(config,f.service());
  jakarta.servlet.http.HttpServletRequest request=(jakarta.servlet.http.HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->{
   if(m.getName().equals("getServletPath"))return "/pageData";if(m.getName().equals("getHeader"))return "X-IAFM-Ingress-Node".equals(a[0])?"1":"synthetic-local-test-only";return null;});
  CountDownLatch entered=new CountDownLatch(2),release=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(3);AtomicBoolean thirdWork=new AtomicBoolean();
  Callable<JSONObject> read=()->{try(RequestIdentity context=RequestIdentity.begin(f.user(),false,List.of(),List.of())){return cluster.run(request,f.user(),new JSONObject(),false,()->{entered.countDown();assertTrue(release.await(4,TimeUnit.SECONDS));return new JSONObject();});}};
  Field admissionField=FinanceCluster.class.getDeclaredField("admission");admissionField.setAccessible(true);BoundedAdmission gate=(BoundedAdmission)admissionField.get(cluster);
  try(var ordinaryOwner=gate.internal()){Future<JSONObject> a=pool.submit(read),b=pool.submit(read);assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(pool.submit(()->{try(RequestIdentity context=RequestIdentity.begin(f.user(),false,List.of(),List.of())){try{cluster.run(request,f.user(),new JSONObject(),false,()->{thirdWork.set(true);return new JSONObject();});return false;}catch(FinanceCluster.Busy expected){return true;}}}).get(3,TimeUnit.SECONDS));assertFalse(thirdWork.get());release.countDown();a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);}finally{release.countDown();pool.shutdownNow();cluster.close();}
 }
 @Test public void mutableSessionObjectCannotReplaceTheSnapshotGeneration()throws Exception{
  Fixture f=new Fixture();Model.User user=f.user();try(RequestIdentity context=RequestIdentity.begin(user,false,List.of(),List.of())){
   assertThrows(RequestIdentity.StaleIdentity.class,()->f.service().pageSnapshot(user,1,()->{String next=UUID.randomUUID().toString();user.setGeneration(next);for(String schema:List.of("A","B"))f.db.execute("UPDATE "+schema+".USERS SET GENERATION='"+next+"' WHERE ID=1");return new JSONObject();}));
  }
 }
 @Test public void adaptiveFailureNeverReplaysAndReleasesOrdinaryPermit()throws Exception{
  Fixture f=new Fixture();AtomicReference<String> choice=new AtomicReference<>();AtomicInteger opens=new AtomicInteger();
  AtomicFinanceTransactions service=new AtomicFinanceTransactions(f.db.properties,new String[]{"A","B"},f.db.authority,proof->{opens.incrementAndGet();choice.set(StackWalker.getInstance().walk(s->s.anyMatch(x->x.getMethodName().equals("pageSnapshot")))?"rr":"ordinary");throw new SQLException("before SQL");});
  Properties config=new Properties();config.putAll(f.db.properties);config.setProperty("ingress.token","synthetic");config.setProperty("idempotency.secret","synthetic");FinanceCluster cluster=new FinanceCluster(config,service);
  jakarta.servlet.http.HttpServletRequest req=(jakarta.servlet.http.HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{jakarta.servlet.http.HttpServletRequest.class},(p,m,a)->m.getName().equals("getServletPath")?"/pageData":m.getName().equals("getHeader")?("X-IAFM-Ingress-Node".equals(a[0])?"1":"synthetic"):null);
  Field field=FinanceCluster.class.getDeclaredField("admission");field.setAccessible(true);BoundedAdmission gate=(BoundedAdmission)field.get(cluster);ExecutorService pool=Executors.newSingleThreadExecutor();
  Callable<Void> read=()->{try(RequestIdentity identity=RequestIdentity.begin(f.user(),false,List.of(),List.of())){assertThrows(SQLException.class,()->cluster.run(req,f.user(),new JSONObject(),false,JSONObject::new));return null;}};
  try{read.call();assertEquals("ordinary",choice.get());assertEquals(1,opens.get());try(var owner=gate.internal()){pool.submit(read).get(2,TimeUnit.SECONDS);assertEquals("rr",choice.get());assertEquals(2,opens.get());}pool.submit(read).get(2,TimeUnit.SECONDS);assertEquals("ordinary",choice.get());assertEquals(3,opens.get());}finally{pool.shutdownNow();cluster.close();}
 }
}

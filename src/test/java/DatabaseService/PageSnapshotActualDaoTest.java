package DatabaseService;
import org.json.JSONObject;
import org.junit.Test;
import java.sql.*;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;
/** Actual page components and unchanged table definitions, local H2 only. */
public class PageSnapshotActualDaoTest {
 @Test public void allThreeViewsExecuteTheirRealSixReadComponentsThroughTheProxy()throws Exception{
  String url="jdbc:h2:mem:real_page_"+UUID.randomUUID()+";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1",generation=UUID.randomUUID().toString(),migration=UUID.randomUUID().toString();Map<String,String> prior=new HashMap<>();
  Map<String,String> settings=Map.of("iafm.db.user","sa","iafm.db.password","","iafm.cluster.enabled","false","iafm.cluster.health.required","false","iafm.cluster.health.url","","iafm.coordinator.mode","active","iafm.db.jndi","","iafm.base.currency","GBP");
  for(var e:settings.entrySet()){prior.put(e.getKey(),System.getProperty(e.getKey()));System.setProperty(e.getKey(),e.getValue());}prior.put("iafm.db.url",System.getProperty("iafm.db.url"));
  try{
   try(Connection c=DriverManager.getConnection(url,"sa","");Statement q=c.createStatement()){q.execute("CREATE SCHEMA A;CREATE SCHEMA B");}
   System.setProperty("iafm.db.url",url);new CreateAllTables().createAllTables();
   List<String> tables=new ArrayList<>();try(Connection c=DriverManager.getConnection(url,"sa","");ResultSet rows=c.getMetaData().getTables(null,"PUBLIC",null,new String[]{"TABLE"})){while(rows.next())tables.add(rows.getString("TABLE_NAME"));}
   for(String schema:List.of("A","B")){
    try(Connection c=DriverManager.getConnection(url,"sa","");Statement q=c.createStatement()){
     for(String table:tables)q.execute("CREATE TABLE "+schema+"."+table+" AS SELECT * FROM PUBLIC."+table+" WHERE 1=0");
     q.executeUpdate("INSERT INTO "+schema+".USERS(ID,GENERATION,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES(1,'"+generation+"','Synthetic','Reader','synthetic@example.invalid','disabled')");
     q.executeUpdate("INSERT INTO "+schema+".BASIC_VARIABLES_AND_DATA(ID,USER_ID,ACCUMULATED_AMOUNT,ACTUAL_EARNINGS_BEFORE,OTHER_ACCUMULATED_PASSIVE_INCOME) VALUES(1,1,0,0,0)");
     q.executeUpdate("INSERT INTO "+schema+".ALL_NON_INVESTMENT_ASSETS(ID,USER_ID,GENERATION,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES(1,1,'"+UUID.randomUUID()+"','Cash',10.00,'DEMAND_DEPOSIT','VERY_LOW_AND_LOW_RISK_TYPE','GBP',CURRENT_DATE)");
    }
   }
   try(Connection c=DriverManager.getConnection(url,"sa","");Statement q=c.createStatement()){
    q.execute("CREATE TABLE A.CLUSTER_ATOMIC_CONFIG(ID INT,PROTOCOL_VERSION INT,MIGRATION_ID CHAR(36),PRIMARY_SCHEMA VARCHAR(64),PEER_SCHEMA VARCHAR(64));INSERT INTO A.CLUSTER_ATOMIC_CONFIG VALUES(1,2,'"+migration+"','A','B')");q.execute("CREATE TABLE A.CLUSTER_OPERATIONS(ENDPOINT VARCHAR(160),DELETE_RESULT_HASH VARCHAR(64),DELETE_RESULT_EXPIRES TIMESTAMP)");q.execute("CREATE TABLE A.CLUSTER_ATOMIC_GUARD(ID INT,VERSION BIGINT,DATA_REVISION BIGINT);INSERT INTO A.CLUSTER_ATOMIC_GUARD VALUES(1,4,0)");
   }
   Properties props=new Properties();props.setProperty("atomic.migrationId",migration);var authority=new AtomicFinanceTransactions.Authority(){public AtomicFinanceTransactions.Proof capture(){return new AtomicFinanceTransactions.Proof("id","view","1","dual","membership","boot","permit");}public void verify(AtomicFinanceTransactions.Proof p){}public String confirm(Connection c,AtomicFinanceTransactions.Proof p,String id){return "dual";}};
   var service=new AtomicFinanceTransactions(props,new String[]{"A","B"},authority,proof->{Connection c=DriverManager.getConnection(url,"sa","");return(Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{if(m.getName().equals("setCatalog")){c.setSchema((String)a[0]);return null;}if(m.getName().equals("getCatalog"))return c.getSchema();try{return m.invoke(c,a);}catch(InvocationTargetException e){throw e.getCause();}});});
   Model.User user=new Model.User();user.setId(1);user.setGeneration(generation);user.setCurrencyCurrentChosen("GBP");
   for(String view:List.of("overview","assets","transactions"))try(RequestIdentity identity=RequestIdentity.begin(user,false,List.of(),List.of())){
    JSONObject request=new JSONObject().put("view",view).put("assets",new JSONObject().put("kind","nonInvestment").put("q","Cash")).put("transactions",new JSONObject().put("sortBy","amount").put("filterMonth",new org.json.JSONArray().put(1)).put("q","cash"));JSONObject out=service.pageSnapshot(user,1,()->Controller.PageData.read(user,request));assertEquals(view,out.getString("view"));assertEquals("dual",out.getString("durability"));assertEquals(migration+":0",out.getString("dataRevision"));
   }
  }finally{prior.forEach((k,v)->{if(v==null)System.clearProperty(k);else System.setProperty(k,v);});}
 }
}

package DatabaseService;

import Model.User;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Counts real isolated SQL while retaining the original transaction/confirmation protocol. */
public class AtomicIdentityValidationTest extends DatabaseInit {
    private static final class Case {
        final AtomicFinanceTransactionsTest.Fixture db=new AtomicFinanceTransactionsTest.Fixture();
        final AtomicInteger identityReads=new AtomicInteger(),connections=new AtomicInteger();
        final User user=new User();
        Case()throws Exception {
            user.setId(1);user.setGeneration(UUID.randomUUID().toString());
            try(Connection c=db.connection();Statement s=c.createStatement()){
                for(String schema:List.of("CANONICAL","PEER")){
                    s.execute("CREATE TABLE "+schema+".USERS(ID INT PRIMARY KEY,GENERATION CHAR(36))");
                    try(PreparedStatement q=c.prepareStatement("INSERT INTO "+schema+".USERS VALUES(?,?)")){q.setInt(1,1);q.setString(2,user.getGeneration());q.executeUpdate();}
                }
            }
        }
        AtomicFinanceTransactions service(){return db.service(proof->{
            connections.incrementAndGet();Connection c=db.connection();
            return (Connection)Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{Connection.class},(p,m,a)->{
                if(m.getName().equals("prepareStatement")&&((String)a[0]).startsWith("SELECT GENERATION FROM USERS WHERE ID"))identityReads.incrementAndGet();
                try{return m.invoke(c,a);}catch(InvocationTargetException failure){throw failure.getCause();}
            });
        });}
        JSONObject read(AtomicFinanceTransactions service)throws Exception {
            return service.run(UUID.randomUUID().toString(),"test-owner","a".repeat(64),"/pageData",1,user,false,()->{
                for(int i=0;i<5;i++)try(Connection c=openPrimaryConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT COUNT(*) FROM USERS")){assertTrue(r.next());assertEquals(1,r.getInt(1));}
                return new JSONObject().put("reads",5);
            });
        }
    }
    @Test public void sameReadTransactionLocksEachIdentityOnceAndNextTransactionRevalidates()throws Exception {
        Case f=new Case();AtomicFinanceTransactions service=f.service();
        try(RequestIdentity identity=RequestIdentity.begin(f.user,false,List.of(),List.of())){
            assertEquals("dual",f.read(service).getString("durability"));assertEquals(2,f.identityReads.get());
            f.read(service);assertEquals(4,f.identityReads.get());assertEquals(2,f.connections.get());assertEquals(2,f.db.gate.confirmations.get());
        }
        assertFalse(RequestIdentity.isActive());
    }
    @Test public void generationChangeAfterCommitCannotReuseAnEarlierReadValidation()throws Exception {
        Case f=new Case();AtomicFinanceTransactions service=f.service();
        try(RequestIdentity identity=RequestIdentity.begin(f.user,false,List.of(),List.of())){
            f.read(service);
            try(Connection c=f.db.connection();Statement s=c.createStatement()){s.executeUpdate("UPDATE CANONICAL.USERS SET GENERATION='"+UUID.randomUUID()+"' WHERE ID=1");}
            assertThrows(RequestIdentity.StaleIdentity.class,()->f.read(service));assertEquals(1,f.db.gate.confirmations.get());
        }
    }
    @Test public void mutationsNeverCacheIdentityOrAllowSelfRevocationToBeIgnored()throws Exception {
        Case f=new Case();AtomicFinanceTransactions service=f.service();
        try(RequestIdentity identity=RequestIdentity.begin(f.user,true,List.of(),List.of())){
            assertThrows(RequestIdentity.StaleIdentity.class,()->service.run(UUID.randomUUID().toString(),"owner","a".repeat(64),"/editAsset",1,f.user,true,()->{
                try(Statement s=ShardScope.current().connection(1).createStatement()){s.executeUpdate("UPDATE USERS SET GENERATION='"+UUID.randomUUID()+"' WHERE ID=1");}
                try(Connection ignored=openPrimaryConnection()){}return new JSONObject();
            },f.db.token()));
        }
        assertEquals(0,f.db.gate.confirmations.get());assertEquals(0,f.db.count("SELECT VERSION FROM CANONICAL.CLUSTER_ATOMIC_GUARD"));
        try(Connection c=f.db.connection();Statement s=c.createStatement();ResultSet r=s.executeQuery("SELECT GENERATION FROM CANONICAL.USERS")){assertTrue(r.next());assertEquals(f.user.getGeneration(),r.getString(1));}
    }
    @Test public void mismatchedRequestContextAndChangedAuthorityRemainFailClosed()throws Exception {
        Case f=new Case();User wrong=new User();wrong.setId(2);wrong.setGeneration(UUID.randomUUID().toString());
        try(RequestIdentity identity=RequestIdentity.begin(wrong,false,List.of(),List.of())){assertThrows(SQLException.class,()->f.read(f.service()));}
        try(RequestIdentity identity=RequestIdentity.begin(f.user,false,List.of(),List.of())){
            f.db.gate.rejectBefore.set(true);assertThrows(SQLException.class,()->f.read(f.service()));
            assertEquals("dual",f.read(f.service()).getString("durability"));
        }
        assertEquals(1,f.db.gate.confirmations.get());
    }
}

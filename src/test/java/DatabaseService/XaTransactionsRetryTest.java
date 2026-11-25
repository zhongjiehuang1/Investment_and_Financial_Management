package DatabaseService;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import javax.transaction.xa.Xid;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class XaTransactionsRetryTest {
    private static String key(int node,Xid xid) {
        return node+":"+xid.getFormatId()+":"+Base64.getEncoder().encodeToString(xid.getGlobalTransactionId())+":"+Base64.getEncoder().encodeToString(xid.getBranchQualifier());
    }
    @Test public void aCommittedBranchRetriesItsJournalInsteadOfBecomingRollback()throws Exception {
        JdbcDataSource a=new JdbcDataSource(),b=new JdbcDataSource();
        a.setURL("jdbc:h2:mem:retry_a_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        b.setURL("jdbc:h2:mem:retry_b_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");
        for(var source:java.util.List.of(a,b))try(var c=source.getConnection();var s=c.createStatement()){s.execute("CREATE TABLE RECEIPTS(ID VARCHAR(36) PRIMARY KEY)");}
        var branches=new HashMap<String,String>();var failOnce=new AtomicBoolean(true);
        XaTransactions.Journal journal=new XaTransactions.Journal(){
            public void uid(String op,String uid) { }
            public void started(String op,int node,Xid xid){branches.put(key(node,xid),op);}
            public void ended(int node,Xid xid,String terminal)throws Exception {
                if(node==2&&"COMMITTED".equals(terminal)&&failOnce.compareAndSet(true,false))throw new SQLException("injected journal failure","40001");
            }
            public boolean committed(int node,Xid xid)throws Exception {
                try(var c=(node==1?a:b).getConnection();var q=c.prepareStatement("SELECT COUNT(*) FROM RECEIPTS WHERE ID=?")){
                    q.setString(1,branches.get(key(node,xid)));try(var rows=q.executeQuery()){rows.next();return rows.getInt(1)==1;}
                }
            }
        };
        try(var tx=new XaTransactions(Map.of(1,a,2,b),journal,Files.createTempDirectory("iafm-retry-"),"iafm-retry-test")) {
            String operation=UUID.randomUUID().toString();
            var outcome=tx.execute(operation,Set.of(1,2),connections->{for(Connection c:connections.values())try(var q=c.prepareStatement("INSERT INTO RECEIPTS VALUES(?)")){q.setString(1,operation);q.executeUpdate();}});
            assertNotEquals(XaTransactions.Outcome.ABORTED,outcome);assertFalse(failOnce.get());
            for(int i=0;i<5&&tx.hasOutstandingDecisions();i++)tx.recover();
            assertFalse("A committed branch must not become a false rollback",tx.hasOutstandingDecisions());
        }
    }
}

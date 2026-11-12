package DatabaseService;

import DatabaseService.Model.Controller.AssetsData.NonInvestmentAsset.EditAsset;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.AddIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.DeleteIncomeOrExpense;
import DatabaseService.Model.Controller.IncomeAndExpenditureData.TransferBetweenAccounts;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.*;
import java.util.*;
import static org.junit.Assert.*;

public class AccountRenameLedgerTest extends DatabaseInit {
    private final Map<String,String> before = new HashMap<>();
    private static final String LOW = "VERY_LOW_AND_LOW_RISK_TYPE";
    @Before public void setup() throws Exception {
        setting("iafm.db.url", "jdbc:h2:mem:rename_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        setting("iafm.db.user","sa"); setting("iafm.db.password",""); setting("iafm.db.jndi","");
        new CreateAllTables().createAllTables();
        for(int user=1;user<=2;user++) {
            execute("INSERT INTO USERS (ID,FORENAME,SURNAME,EMAIL_ADDRESS,PASSWORD) VALUES (?,'Test','Owner',?,'synthetic')",user,"owner"+user+"@example.invalid");
            execute("INSERT INTO ALL_NON_INVESTMENT_ASSETS (ID,USER_ID,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES (?,?,'Income','100.00','DEMAND_DEPOSIT',?,'GBP','2026-01-01')",user,user,LOW);
            assertTrue(AddIncomeOrExpense.addIncomeOrExpense(user,"income","Income","Synthetic external receipt","10.00","2026-01-15"));
        }
        execute("INSERT INTO ALL_NON_INVESTMENT_ASSETS (ID,USER_ID,NAME,AMOUNT,TYPE,RISK_TYPE,CURRENCY,DATE) VALUES (3,1,'Savings','0.00','DEMAND_DEPOSIT',?,'GBP','2026-01-01')",LOW);
    }
    private void setting(String key,String value) { before.put(key,System.getProperty(key)); System.setProperty(key,value); }
    @After public void restore() { before.forEach((key,value)->{if(value==null)System.clearProperty(key);else System.setProperty(key,value);}); }
    private static void execute(String sql,Object...values) throws Exception {
        try(Connection c=openConnection();PreparedStatement s=c.prepareStatement(sql)) {
            for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]); s.executeUpdate();c.commit();
        }
    }
    private static int count(int owner,String kind,String name) throws Exception {
        try(Connection c=openConnection();PreparedStatement s=c.prepareStatement("SELECT COUNT(*) FROM LEDGER_ENTRIES E JOIN LEDGER_JOURNALS J ON J.ID=E.JOURNAL_ID WHERE J.USER_ID=? AND E.ACCOUNT_KIND=? AND E.ACCOUNT_NAME=?")) {
            s.setInt(1,owner);s.setString(2,kind);s.setString(3,name);
            try(ResultSet r=s.executeQuery()){assertTrue(r.next());return r.getInt(1);}
        }
    }
    @Test public void renameUpdatesOnlyOwnedAssetEntriesAndTransferCanStillReverse() throws Exception {
        assertTrue(TransferBetweenAccounts.transferBetweenAccounts(1,"Income","Savings","5.00",null,"2026-01-16","Synthetic move"));
        assertTrue(EditAsset.editNonInvestmentAsset(1,1,"Current","DEMAND_DEPOSIT",LOW,"105.00","GBP"));
        assertEquals(0,count(1,"asset","Income"));assertEquals(2,count(1,"asset","Current"));
        assertEquals(1,count(1,"external_income","Income"));assertEquals(1,count(2,"asset","Income"));
        int transferId;
        try(Connection c=openConnection();PreparedStatement s=c.prepareStatement("SELECT ID,PLATFORM FROM INCOME_AND_EXPENDITURE_DATA WHERE USER_ID=1 AND TYPE='transfer_out'");ResultSet r=s.executeQuery()) {
            assertTrue(r.next());transferId=r.getInt(1);assertEquals("Current",r.getString(2));
        }
        assertTrue(DeleteIncomeOrExpense.deleteIncomeOrExpense(1,transferId,"ignored","ignored","0.00"));
        try(Connection c=openConnection();PreparedStatement s=c.prepareStatement("SELECT AMOUNT FROM ALL_NON_INVESTMENT_ASSETS WHERE ID=1");ResultSet r=s.executeQuery()) {
            assertTrue(r.next());assertEquals("110.00",r.getString(1));
        }
        assertEquals(1,count(1,"asset","Current"));assertEquals(1,count(2,"asset","Income"));
    }
}

package DatabaseService;

import org.junit.Test;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import static org.junit.Assert.*;

/** Synthetic schemas only; database-specific migration tests are also required. */
public class ReplicationSchemaSafetyTest {
    private static Connection database() throws SQLException {
        return DriverManager.getConnection("jdbc:h2:mem:fk_" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
    }

    @Test public void rejectsDeleteAndUpdateCascadesAndSetNullAcrossAllTables() throws Exception {
        for (String action : new String[]{"ON DELETE CASCADE", "ON UPDATE CASCADE", "ON DELETE SET NULL", "ON UPDATE SET NULL"}) {
            try (Connection connection = database(); Statement sql = connection.createStatement()) {
                sql.execute("CREATE TABLE USERS(ID INT PRIMARY KEY)");
                sql.execute("CREATE TABLE SECONDARY_CHILD(ID INT PRIMARY KEY, USER_ID INT, FOREIGN KEY(USER_ID) REFERENCES USERS(ID) " + action + ")");
                try { ReplicationSchemaSafety.verify(connection); fail("Cascading action accepted: " + action); }
                catch (SQLException expected) { assertTrue(expected.getMessage().contains("cascading foreign keys")); }
                try (var count = sql.executeQuery("SELECT COUNT(*) FROM SECONDARY_CHILD")) { assertTrue(count.next()); assertEquals(0, count.getInt(1)); }
            }
        }
    }

    @Test public void newLedgerUsesExplicitChildRemovalAndRestrictsDirectParentDeletion() throws Exception {
        try (Connection connection = database(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE USERS(ID INT PRIMARY KEY)");
            sql.execute("CREATE TABLE INCOME_AND_EXPENDITURE_DATA(ID INT PRIMARY KEY, USER_ID INT)");
            DatabaseService.Model.Controller.IncomeAndExpenditureData.Ledger.ensureSchema(connection);
            ReplicationSchemaSafety.verify(connection);
            sql.execute("INSERT INTO USERS VALUES(1)");
            sql.execute("INSERT INTO LEDGER_JOURNALS VALUES('journal',1,'income',CURRENT_DATE,'GBP','test','synthetic')");
            sql.execute("INSERT INTO LEDGER_ENTRIES VALUES('journal',1,NULL,'asset','test','GBP','1.00',100,0,NULL)");
            try { sql.execute("DELETE FROM LEDGER_JOURNALS WHERE ID='journal'"); fail("Parent deletion should require explicit child deletion"); }
            catch (SQLException expected) { assertEquals("23503", expected.getSQLState()); }
            sql.execute("DELETE FROM LEDGER_ENTRIES WHERE JOURNAL_ID='journal'");
            assertEquals(1, sql.executeUpdate("DELETE FROM LEDGER_JOURNALS WHERE ID='journal'"));
        }
    }

    @Test public void rejectsEmptySchemaInsteadOfTreatingMissingMetadataAsSafe() throws Exception {
        try (Connection connection = database()) {
            try { ReplicationSchemaSafety.verify(connection); fail("Empty schema accepted"); }
            catch (SQLException expected) { assertTrue(expected.getMessage().contains("empty")); }
        }
    }
}

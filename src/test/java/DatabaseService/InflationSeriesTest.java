package DatabaseService;

import DatabaseService.Model.Controller.InflationRatesData.InflationSeries;
import DatabaseService.Model.Controller.ReadData;
import Model.Controller.InflationRatesData.InflationRateException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Synthetic H2 data only: interval meaning, precision, isolation and concurrent writes. */
public class InflationSeriesTest extends DatabaseInit {
    private final Map<String, String> previous = new HashMap<>();
    @Before public void setup() {
        property("iafm.db.url", "jdbc:h2:mem:inflation_" + UUID.randomUUID() + ";MODE=MySQL;IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
        property("iafm.db.user", "sa"); property("iafm.db.password", ""); property("iafm.db.jndi", "");
        new CreateAllTables().createAllTables();
    }
    private void property(String name, String value) { previous.put(name, System.getProperty(name)); System.setProperty(name, value); }
    @After public void teardown() throws Exception {
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS"); connection.commit();
        } finally {
            previous.forEach((name, value) -> { if (value == null) System.clearProperty(name); else System.setProperty(name, value); });
        }
    }
    private static List<String> rows() throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = openConnection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT ID,VARIOUS_CURRENCIES,DATE,COALESCE(OBSERVED_THROUGH,DATE),AIR_IN_VARIOUS_CURRENCIES FROM " + InflationSeries.TABLE + " ORDER BY VARIOUS_CURRENCIES,DATE")) {
            while (result.next()) rows.add(result.getInt(1) + ":" + result.getString(2) + ":" + result.getDate(3) + ":" + result.getDate(4) + ":" + result.getBigDecimal(5));
        }
        return rows;
    }
    private static int firstId() throws Exception { return Integer.parseInt(rows().get(0).split(":")[0]); }

    @Test public void equalMonthsKeepEarliestIdAndExtendObservedThrough() throws Exception {
        assertTrue(InflationSeries.add(2.1, "GBP", "2026-01-01")); int earliest = firstId();
        assertTrue(InflationSeries.add(2.1, "GBP", "2026-02-28"));
        assertTrue(InflationSeries.add(2.1, "GBP", "2026-03-31"));
        assertEquals(List.of(earliest + ":GBP:2026-01-15:2026-03-15:2.10"), rows());
        assertEquals("2026-03-15", ReadData.inflationPage(1, 100, "GBP", "date", "asc").getJSONArray("items").getJSONObject(0).getString("observedThrough"));
    }

    @Test public void missingMonthBreaksRunAndAddingItCanBridgeTwoRuns() throws Exception {
        InflationSeries.add(0, "CHF", "2026-01-15"); int earliest = firstId();
        InflationSeries.add(0, "CHF", "2026-03-15"); assertEquals(2, rows().size());
        InflationSeries.add(0, "CHF", "2026-02-15");
        assertEquals(List.of(earliest + ":CHF:2026-01-15:2026-03-15:0.00"), rows());
        InflationSeries.add(0, "JPY", "2026-02-15"); assertEquals(2, rows().size());
    }

    @Test public void coveredMonthIsIdempotentButDifferentRateConflictsWithoutChangingData() throws Exception {
        InflationSeries.add(-0.2, "CHF", "2026-01-15"); InflationSeries.add(-0.2, "CHF", "2026-02-15");
        List<String> before = rows();
        assertTrue(InflationSeries.add(-0.2, "CHF", "2026-02-09")); assertEquals(before, rows());
        InflationRateException failure = assertThrows(InflationRateException.class, () -> InflationSeries.add(0.2, "CHF", "2026-02-15"));
        assertEquals(409, failure.getStatus()); assertEquals(before, rows());
    }

    @Test public void editChangesWholeIntervalAndRejectsMovingIt() throws Exception {
        InflationSeries.add(1.1, "CNY", "2026-01-15"); InflationSeries.add(1.1, "CNY", "2026-02-15"); int id = firstId();
        assertTrue(InflationSeries.edit(id, 1.2, "CNY", "2026-01-20"));
        assertEquals(List.of(id + ":CNY:2026-01-15:2026-02-15:1.20"), rows());
        assertEquals(400, assertThrows(InflationRateException.class, () -> InflationSeries.edit(id, 1.2, "USD", "2026-01-15")).getStatus());
        assertEquals(400, assertThrows(InflationRateException.class, () -> InflationSeries.edit(id, 1.2, "CNY", "2026-02-15")).getStatus());
        assertEquals(List.of(id + ":CNY:2026-01-15:2026-02-15:1.20"), rows());
    }

    @Test public void editCanMergeNeighboursAndDeleteDoesNotInventMissingMonths() throws Exception {
        InflationSeries.add(2, "USD", "2026-01-15"); int first = firstId();
        InflationSeries.add(3, "USD", "2026-02-15"); int middle = Integer.parseInt(rows().get(1).split(":")[0]);
        InflationSeries.add(2, "USD", "2026-03-15");
        assertTrue(InflationSeries.delete(middle)); assertEquals(2, rows().size());
        InflationSeries.add(3, "USD", "2026-02-15"); middle = Integer.parseInt(rows().get(1).split(":")[0]);
        assertTrue(InflationSeries.edit(middle, 2, "USD", "2026-02-15"));
        assertEquals(List.of(first + ":USD:2026-01-15:2026-03-15:2.00"), rows());
        assertTrue(InflationSeries.delete(first)); assertTrue(rows().isEmpty());
    }

    @Test public void ratesAreExactAndNeverSilentlyRounded() throws Exception {
        assertThrows(InflationRateException.class, () -> InflationSeries.add(1.005, "GBP", "2026-01-15"));
        assertThrows(InflationRateException.class, () -> InflationSeries.add(Double.NaN, "GBP", "2026-01-15"));
        assertThrows(InflationRateException.class, () -> InflationSeries.add(1000, "GBP", "2026-01-15"));
        assertTrue(rows().isEmpty());
        InflationSeries.add(-0.01, "GBP", "2026-01-15"); assertTrue(rows().get(0).endsWith(":-0.01"));
    }

    @Test public void failedMergeRollsBackRowsDeletedBeforeTheUpdate() throws Exception {
        InflationSeries.add(2, "GBP", "2026-01-15");
        InflationSeries.add(1, "GBP", "2026-02-15");
        InflationSeries.add(2, "GBP", "2026-03-15");
        List<String> before = rows(); int middle = Integer.parseInt(before.get(1).split(":")[0]);
        try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + InflationSeries.TABLE + " ADD CONSTRAINT TEST_REJECT_SPANS CHECK (OBSERVED_THROUGH=DATE)");
            connection.commit();
        }
        assertThrows(IllegalStateException.class, () -> InflationSeries.edit(middle, 2, "GBP", "2026-02-15"));
        assertEquals(before, rows());
    }

    @Test public void concurrentAddsSerializeWithoutLosingMonthsOrDeadlockingIdAllocation() throws Exception {
        var pool = Executors.newFixedThreadPool(6);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int month = 1; month <= 12; month++) { final int number = month; tasks.add(pool.submit(() -> InflationSeries.add(1.5, "EUR", String.format("2025-%02d-15", number)))); }
            for (var task : tasks) assertTrue(task.get(25, TimeUnit.SECONDS));
            assertEquals(1, rows().size()); assertTrue(rows().get(0).endsWith(":EUR:2025-01-15:2025-12-15:1.50"));
        } finally { pool.shutdownNow(); }
    }
}

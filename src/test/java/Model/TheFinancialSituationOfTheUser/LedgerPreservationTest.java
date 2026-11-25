package Model.TheFinancialSituationOfTheUser;

import org.junit.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class LedgerPreservationTest {
    private static final YearMonth DECEMBER = YearMonth.of(2025, 12);

    @Test public void missingRateForHeldMonthLeavesTheEstimateUnavailable() {
        var cash = new LedgerPreservation.Holding("Cash", "GBP", false, LocalDate.of(2025, 11, 1), null, 10_000);
        var estimate = new LedgerPreservation("GBP", DECEMBER, List.of(cash), Map.of("GBP", Map.of(DECEMBER, BigDecimal.TEN)));
        var result = estimate.result();
        assertEquals("MISSING_INFLATION", result.getString("status"));
        assertFalse(result.has("requiredPreservationProfit"));
        var missing = result.getJSONArray("missingInflation").getJSONObject(0);
        assertEquals("2025-11", missing.getString("month"));
        assertEquals("GBP", missing.getString("currency"));
    }

    @Test public void archivedAssetsAccrueThroughTheirClosingDay() {
        var cash = new LedgerPreservation.Holding("Cash", "GBP", false, LocalDate.of(2025, 11, 1),
                LocalDate.of(2025, 11, 10), 10_000);
        var estimate = new LedgerPreservation("GBP", DECEMBER, List.of(cash),
                Map.of("GBP", Map.of(DECEMBER.minusMonths(1), new BigDecimal("36.50"))));
        var result = estimate.result();
        assertEquals("AVAILABLE", result.getString("status"));
        assertEquals("1.00", result.getString("requiredPreservationProfit"));
    }
}

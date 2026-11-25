import Model.Tools.Computing;
import Model.Tools.DecimalFormat;
import org.junit.Test;
import static org.junit.Assert.*;

public class MoneyComputingTest {
    @Test public void parsingAndFormattingAreExactAtBothLongLimits() {
        assertEquals("0.00", Computing.normalize("-0"));
        assertEquals("1.20", Computing.normalize(" 0001.2 "));
        assertEquals("-0.01", Computing.normalize("-0.01"));
        assertEquals(Long.MAX_VALUE, Computing.toMinorUnits("92233720368547758.07"));
        assertEquals(Long.MIN_VALUE, Computing.toMinorUnits("-92233720368547758.08"));
        assertEquals("92233720368547758.07", Computing.fromMinorUnits(Long.MAX_VALUE));
        assertEquals("-92233720368547758.08", Computing.fromMinorUnits(Long.MIN_VALUE));
    }

    @Test public void rejectsMalformedOverPreciseAndOutOfRangeInputs() {
        for (String invalid : new String[]{null, "", "NaN", "Infinity", "1e3", "1,000.00", "+2", ".2", "1.", "1.001",
                "1.000", "92233720368547758.08", "-92233720368547758.09"}) {
            assertThrows(IllegalArgumentException.class, () -> Computing.normalize(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> Computing.toMinorUnits("1.230000001"));
    }

    @Test public void acceptsOnlyZeroPaddingInLegacyDatabaseRows() {
        assertEquals("1.23", Computing.normalizeStored("1.230000000"));
        assertEquals("0.00", Computing.normalizeStored("-0.000000000"));
        assertEquals("4.20", DecimalFormat.decimalFormat("4.200000000"));
        assertThrows(IllegalArgumentException.class, () -> Computing.normalizeStored("4.200000001"));
    }

    @Test public void repeatedSmallAmountsAndLargeAmountsDoNotLoseCents() {
        String amount = "0.00";
        for (int i = 0; i < 10000; i++) amount = Computing.add(amount, "0.01");
        assertEquals("100.00", amount);
        assertEquals("0.30", Computing.add("0.10", "0.20"));
        assertEquals("90071992547409.92", Computing.add("90071992547409.91", "0.01"));
        assertEquals("-0.01", Computing.subtract("0.29", "0.30"));
    }

    @Test public void arithmeticOverflowIsNeverSilentlyWrapped() {
        assertThrows(ArithmeticException.class, () -> Computing.add("92233720368547758.07", "0.01"));
        assertThrows(ArithmeticException.class, () -> Computing.subtract("-92233720368547758.08", "0.01"));
        assertThrows(ArithmeticException.class, () -> Computing.scale(Long.MAX_VALUE, 2, 1));
    }

    @Test public void integerRatiosRoundHalfAwayFromZero() {
        assertEquals(1, Computing.scale(1, 1, 2));
        assertEquals(-1, Computing.scale(-1, 1, 2));
        assertEquals(0, Computing.scale(1, 1, 3));
        assertEquals(95, Computing.scale(100, 95, 100));
        assertEquals(Long.MAX_VALUE, Computing.scale(Long.MAX_VALUE, 100, 100));
        assertEquals(Long.MIN_VALUE, Computing.scale(Long.MIN_VALUE, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> Computing.scale(1, 1, 0));
    }

    @Test public void sameCurrencyConversionNeverUsesFloatingPoint() {
        assertEquals("92233720368547758.07", Computing.convert("92233720368547758.07", "GBP", "GBP"));
        assertEquals("0.00", Computing.convert("0", "GBP", "CNY"));
        assertEquals(0.0, Computing.percentage("10.00", "0.00"), 0.0);
        assertEquals(50.0, Computing.percentage("0.15", "0.30"), 0.0);
    }
}

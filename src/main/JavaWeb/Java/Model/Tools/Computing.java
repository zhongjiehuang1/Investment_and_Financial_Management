package Model.Tools;

import Model.FixedTypes.Currencies;
import java.util.regex.Pattern;

/** Two-decimal money: Strings at the boundary, checked integer minor units while calculating. */
public final class Computing {
    private static final Pattern AMOUNT = Pattern.compile("-?[0-9]+(?:\\.[0-9]{1,2})?");
    private static final Pattern STORED_AMOUNT = Pattern.compile("-?[0-9]+(?:\\.[0-9]{1,2}0*)?");

    private Computing() {}

    public static final class ExchangeRateUnavailableException extends IllegalStateException {
        public ExchangeRateUnavailableException() { super("Exchange rate is unavailable"); }
    }

    public static long toMinorUnits(String amount) {
        if (amount == null) throw new IllegalArgumentException("Amount is required");
        String value = amount.trim();
        if (value.length() > 64 || !STORED_AMOUNT.matcher(value).matches()) {
            throw new IllegalArgumentException("Amount must be a decimal String with at most two decimal places");
        }
        int dot = value.indexOf('.');
        String fraction = dot < 0 ? "00" : (value.substring(dot + 1) + "0").substring(0, 2);
        String minor = (dot < 0 ? value : value.substring(0, dot)) + fraction;
        try {
            return Long.parseLong(minor);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Amount exceeds the supported integer range", exception);
        }
    }

    /** Inserts the decimal point without passing through float, double, or a decimal arithmetic class. */
    public static String fromMinorUnits(long minor) {
        String value = Long.toString(minor);
        boolean negative = minor < 0;
        String digits = negative ? value.substring(1) : value;
        if (digits.length() < 3) digits = "0".repeat(3 - digits.length()) + digits;
        int dot = digits.length() - 2;
        return (negative ? "-" : "") + digits.substring(0, dot) + "." + digits.substring(dot);
    }

    public static String normalize(String amount) {
        if (amount == null || amount.trim().length() > 64 || !AMOUNT.matcher(amount.trim()).matches()) {
            throw new IllegalArgumentException("Amount must have at most two decimal places");
        }
        return fromMinorUnits(toMinorUnits(amount));
    }

    /** Legacy rows may have zero padding from the former nine-decimal writer. Never discard nonzero digits. */
    public static String normalizeStored(String amount) {
        return fromMinorUnits(toMinorUnits(amount));
    }

    public static String add(String left, String right) {
        return fromMinorUnits(Math.addExact(toMinorUnits(left), toMinorUnits(right)));
    }

    public static String subtract(String left, String right) {
        return fromMinorUnits(Math.subtractExact(toMinorUnits(left), toMinorUnits(right)));
    }

    public static String sum(String... amounts) {
        long total = 0;
        for (String amount : amounts) total = Math.addExact(total, toMinorUnits(amount));
        return fromMinorUnits(total);
    }

    /** Amounts stay integral; ratios are dimensionless and may be displayed as doubles. */
    public static double percentage(String numerator, String denominator) {
        long denominatorMinor = toMinorUnits(denominator);
        return denominatorMinor == 0 ? 0.0 : (double) toMinorUnits(numerator) / denominatorMinor * 100.0;
    }

    /** Integer proportional amount, rounded half away from zero to the nearest minor unit. */
    public static long scale(long minor, long numerator, long denominator) {
        if (denominator <= 0) throw new IllegalArgumentException("Denominator must be positive");
        long whole = Math.multiplyExact(minor / denominator, numerator);
        long remainderProduct = Math.multiplyExact(minor % denominator, numerator);
        long result = Math.addExact(whole, remainderProduct / denominator);
        long remainder = remainderProduct % denominator;
        long half = denominator / 2 + denominator % 2;
        if (remainder >= half) result = Math.addExact(result, 1);
        else if (remainder <= -half) result = Math.subtractExact(result, 1);
        return result;
    }

    public static String convert(String amount, String fromCurrency, String toCurrency) {
        return fromMinorUnits(convertMinorUnits(toMinorUnits(amount), fromCurrency, toCurrency));
    }

    /** The sole money exception: exchange rates are approximate; round once at this boundary. */
    public static long convertMinorUnits(long minor, String fromCurrency, String toCurrency) {
        Currencies.valueOf(fromCurrency);
        Currencies.valueOf(toCurrency);
        if (fromCurrency.equals(toCurrency) || minor == 0) return minor;
        double rate = ExchangeRateForVariousCurrencies.currencyConversion(toCurrency, 1.0, fromCurrency);
        if (!Double.isFinite(rate) || rate <= 0) {
            throw new ExchangeRateUnavailableException();
        }
        double convertedMinor = minor * rate;
        if (!Double.isFinite(convertedMinor) || convertedMinor >= 0x1.0p63 || convertedMinor <= -0x1.0p63) {
            throw new ArithmeticException("Converted amount exceeds the supported integer range");
        }
        return convertedMinor < 0 ? -Math.round(-convertedMinor) : Math.round(convertedMinor);
    }
}

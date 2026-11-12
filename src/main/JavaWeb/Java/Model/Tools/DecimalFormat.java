package Model.Tools;

public class DecimalFormat {

    public static double decimalFormat (double amount) {
        // Only rates and percentages use this overload; it has no shared mutable formatter.
        if (!Double.isFinite(amount)) return 0.0;
        return Math.round(amount * 100.0) / 100.0;
    }

    public static String decimalFormat(String amount) {
        return Computing.normalizeStored(amount);
    }

}

package Model.Tools;

import java.text.SimpleDateFormat;

public class DateFormat {

    private static final ThreadLocal<SimpleDateFormat> simpleDateFormatThreadLocal = ThreadLocal.withInitial(() -> new SimpleDateFormat("yyyy-MM-dd"));

    public static java.util.Date setDate(java.lang.String date) {
        if (date == null || date.trim().isEmpty()) {
            return null;
        }
        try {
            return simpleDateFormatThreadLocal.get().parse(date);
        } catch (Exception exception) {
            System.out.println(exception.getMessage() + " (Model/Tools/DateFormate.java)");
        }
        return null;
    }

    public static java.lang.String setToString(java.util.Date date) {
        return date != null ? simpleDateFormatThreadLocal.get().format(date) : null;
    }

}

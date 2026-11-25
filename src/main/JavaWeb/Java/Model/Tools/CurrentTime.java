package Model.Tools;

import java.util.Calendar;
import java.time.Year;

// Shared clock values for all users.
public class CurrentTime {

    private static java.lang.String dayOfWeek = null, monthOfYear = null;
    public static java.lang.String getDayOfWeek() {
        return dayOfWeek;
    }
    public static java.lang.String getMonthOfYear() {
        return monthOfYear;
    }

    private static int dateOfMonth = 0, hourOfDay = 0, minuteOfHour = 0, secondOfMinute = 0, year = 0;
    public static int getDateOfMonth() {
        return dateOfMonth;
    }
    public static int getHourOfDay() {
        return hourOfDay;
    }
    public static int getMinuteOfHour() {
        return minuteOfHour;
    }
    public static int getSecondOfMinute() {
        return secondOfMinute;
    }
    public static int getYear() {
        return year;
    }

    public static void getCurrentTimeInformation() {
        Calendar calendar = Calendar.getInstance();

        java.lang.String string1 = calendar.getTime().toString();
        java.lang.String[] strings1 = string1.split(" ");
        java.lang.String string2 = strings1[3];
        java.lang.String[] strings2 = string2.split(":");

        dayOfWeek = strings1[0];
        monthOfYear = strings1[1];
        dateOfMonth = Integer.parseInt(strings1[2]);
        hourOfDay = Integer.parseInt(strings2[0]);
        minuteOfHour = Integer.parseInt(strings2[1]);
        secondOfMinute = Integer.parseInt(strings2[2]);
        year = Integer.parseInt(strings1[5]);
    }

    public static int calculatePositionInTheYearToday_value() {
        getCurrentTimeInformation();

        int positionInTheYearToday = 0, daysInFeb;

        boolean isLeap = Year.of(CurrentTime.getYear()).isLeap();
        if (isLeap) {
            daysInFeb = 29;
        } else {
            daysInFeb = 28;
        }

        switch (monthOfYear) {
            case "Jan" -> positionInTheYearToday = dateOfMonth;
            case "Feb" -> positionInTheYearToday = 31 + dateOfMonth;
            case "Mar" -> positionInTheYearToday = 31 + daysInFeb + dateOfMonth;
            case "Apr" -> positionInTheYearToday = 62 + daysInFeb + dateOfMonth;
            case "May" -> positionInTheYearToday = 92 + daysInFeb + dateOfMonth;
            case "Jun" -> positionInTheYearToday = 123 + daysInFeb + dateOfMonth;
            case "Jul" -> positionInTheYearToday = 153 + daysInFeb + dateOfMonth;
            case "Aug" -> positionInTheYearToday = 184 + daysInFeb + dateOfMonth;
            case "Sep" -> positionInTheYearToday = 215 + daysInFeb + dateOfMonth;
            case "Oct" -> positionInTheYearToday = 245 + daysInFeb + dateOfMonth;
            case "Nov" -> positionInTheYearToday = 276 + daysInFeb + dateOfMonth;
            case "Dec" -> positionInTheYearToday = 306 + daysInFeb + dateOfMonth;
            default -> System.out.println("货币种类出现遗漏或数据错乱!");
        }
        return positionInTheYearToday;
    }

}

package Model.Tools;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.util.TimerTask;

@WebListener
public class AnnualInflationRatesInVariousCurrencies extends TimerTask implements ServletContextListener {

    private java.util.Timer timer;

    private static double[] annualInflationRatesOfGBP, annualInflationRatesOfCNY;
    private static java.lang.String[] annualInflationRateDatesOfGBP, annualInflationRateDatesOfCNY;
    public static double[] getAnnualInflationRatesOfGBP() {
        return annualInflationRatesOfGBP;
    }
    public static double[] getAnnualInflationRatesOfCNY() {
        return annualInflationRatesOfCNY;
    }
    public static java.lang.String[] getAnnualInflationRateDatesOfGBP() {
        return annualInflationRateDatesOfGBP;
    }
    public static java.lang.String[] getAnnualInflationRateDatesOfCNY() {
        return annualInflationRateDatesOfCNY;
    }
    public static void setAnnualInflationRatesOfGBP(double[] annualInflationRatesOfGBP) {
        AnnualInflationRatesInVariousCurrencies.annualInflationRatesOfGBP = annualInflationRatesOfGBP;
    }
    public static void setAnnualInflationRatesOfCNY(double[] annualInflationRatesOfCNY) {
        AnnualInflationRatesInVariousCurrencies.annualInflationRatesOfCNY = annualInflationRatesOfCNY;
    }
    public static void setAnnualInflationRateDatesOfGBP(java.lang.String[] annualInflationRateDatesOfGBP) {
        AnnualInflationRatesInVariousCurrencies.annualInflationRateDatesOfGBP = annualInflationRateDatesOfGBP;
    }
    public static void setAnnualInflationRateDatesOfCNY(java.lang.String[] annualInflationRateDatesOfCNY) {
        AnnualInflationRatesInVariousCurrencies.annualInflationRateDatesOfCNY = annualInflationRateDatesOfCNY;
    }

    private static double currentAnnualInflationRateOfGBP = 0.0, currentAnnualInflationRateOfCNY = 0.0;
    public static double getCurrentAnnualInflationRateOfGBP() {
        return currentAnnualInflationRateOfGBP;
    }
    public static double getCurrentAnnualInflationRateOfCNY() {
        return currentAnnualInflationRateOfCNY;
    }

    private static double[] lastedAIRInAnnualInflationRatesInVariousCurrencies = null;
    private static java.lang.String[] variousCurrencies_lastedAIR = null;
    public static double[] getLastedAIRInAnnualInflationRatesInVariousCurrencies() {
        return lastedAIRInAnnualInflationRatesInVariousCurrencies;
    }
    public static java.lang.String[] getVariousCurrencies_lastedAIR() {
        return variousCurrencies_lastedAIR;
    }
    public static void setLastedAIRInAnnualInflationRatesInVariousCurrencies(double[] lastedAIRInAnnualInflationRatesInVariousCurrencies) {
        AnnualInflationRatesInVariousCurrencies.lastedAIRInAnnualInflationRatesInVariousCurrencies = lastedAIRInAnnualInflationRatesInVariousCurrencies;
    }
    public static void setVariousCurrencies_lastedAIR(java.lang.String[] variousCurrencies_lastedAIR) {
        AnnualInflationRatesInVariousCurrencies.variousCurrencies_lastedAIR = variousCurrencies_lastedAIR;
    }

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent) {
        if (DatabaseService.FinanceCluster.enabled()) return; // Request-local reads use the coordinated reference dataset.
        timer = new java.util.Timer();
        timer.schedule(new AnnualInflationRatesInVariousCurrencies(), 0, 86400000);
    }
    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent) {
        if (timer != null) {
            timer.cancel();
        }
    }
    @Override
    public void run() {
        getAllDataFromAIRIVC();
    }

    public static void getAllDataFromAIRIVC() {
        DatabaseService.Model.Tools.AnnualInflationRatesInVariousCurrencies.importData();
    }

    public static void setLatestAnnualInflationRatesInVariousCurrencies() {
        int length = variousCurrencies_lastedAIR.length;
        for (int i = 0; i < length; i++) {
            switch (variousCurrencies_lastedAIR[i]) {
                case "GBP" -> currentAnnualInflationRateOfGBP = lastedAIRInAnnualInflationRatesInVariousCurrencies[i];
                case "CNY" -> currentAnnualInflationRateOfCNY = lastedAIRInAnnualInflationRatesInVariousCurrencies[i];
                default -> System.out.println("货币种类出现遗漏或数据错乱!");
            }
        }
    }

}

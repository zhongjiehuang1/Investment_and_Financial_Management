package Controller.InflationRatesData;

import Controller.ApiSupport;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet("/addInflationRate")
public class AddInflationRate extends HttpServlet {
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.serve(request, response, true, (user, input) -> {
            ApiSupport.requireAdmin(user);
            String rate = input.has("rate") ? ApiSupport.text(input, "rate") : ApiSupport.text(input, "annualInflationRate");
            String currency = input.has("currency") ? ApiSupport.text(input, "currency") : ApiSupport.text(input, "variousCurrencies");
            Model.FixedTypes.Currencies.valueOf(currency);
            String date = java.time.LocalDate.parse(ApiSupport.text(input, "date")).withDayOfMonth(15).toString();
            double parsedRate;
            try { parsedRate = new java.math.BigDecimal(rate).setScale(2, java.math.RoundingMode.UNNECESSARY).doubleValue(); }
            catch (NumberFormatException | ArithmeticException invalid) { throw new ApiSupport.Failure(400, "Inflation rate must be a number with at most two decimal places."); }
            try { return ApiSupport.result(Model.Controller.InflationRatesData.AddInflationRate.addInflationRate(parsedRate, currency, date)); }
            catch (Model.Controller.InflationRatesData.InflationRateException failure) { throw new ApiSupport.Failure(failure.getStatus(), failure.getMessage()); }
        });
    }
}

package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@WebServlet({"/getExchangeRate", "/api/v1/exchange-rate"})
public class GetExchangeRate extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.servePublicRead(request, response, (user, input) -> {
            double rate = Model.Tools.ExchangeRateForVariousCurrencies.getGbpToCny();
            if (!Double.isFinite(rate) || rate <= 0) throw new ApiSupport.Failure(503, "Exchange rate is unavailable.");
            return new JSONObject().put("rate", rate).put("fromCurrency", "GBP").put("toCurrency", "CNY")
                    .put("source", Model.Tools.ExchangeRateForVariousCurrencies.getRateSource())
                    .put("lastUpdatedAt", Model.Tools.ExchangeRateForVariousCurrencies.getLastUpdatedAt());
        });
    }
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) { doGet(request, response); }
}

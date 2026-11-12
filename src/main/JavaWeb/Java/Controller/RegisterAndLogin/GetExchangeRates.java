package Controller.RegisterAndLogin;

import Controller.ApiSupport;
import Model.ExternalAPI.ExchangeRate;
import org.json.JSONObject;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;

@WebServlet("/getExchangeRates")
public class GetExchangeRates extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) {
        ApiSupport.servePublicRead(request, response, (user, input) -> {
            java.util.Map<String, Double> values = ExchangeRate.getAllRates();
            if (values.isEmpty()) throw new ApiSupport.Failure(503, "Daily exchange rates are unavailable.");
            String updatedAt = ExchangeRate.getUpdatedAt();
            return new JSONObject().put("baseCurrency", "USD").put("rates", values)
                    .put("source", "exchangerate-api").put("lastUpdatedAt", updatedAt)
                    .put("stale", Instant.parse(updatedAt).isBefore(Instant.now().minusSeconds(172800)))
                    .put("refreshIntervalHours", 24);
        });
    }
}

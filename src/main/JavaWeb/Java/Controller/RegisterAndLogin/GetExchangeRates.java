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
            ExchangeRate.DailySnapshot snapshot = Model.Tools.ExchangeRateForVariousCurrencies.getProviderSnapshot();
            if (snapshot.values().isEmpty()) throw new ApiSupport.Failure(503, "Daily exchange rates are unavailable.");
            return new JSONObject().put("baseCurrency", "USD").put("rates", snapshot.values())
                    .put("source", snapshot.source()).put("asOf", snapshot.asOf()).put("fetchedAt", snapshot.fetchedAt())
                    .put("lastUpdatedAt", snapshot.updatedAt()).put("lastUpdatedPrecision", "ecb".equals(snapshot.source()) ? "date" : "instant")
                    .put("referenceOnly", true).put("referenceSha256", snapshot.sha256())
                    .put("stale", java.time.LocalDate.parse(snapshot.asOf()).isBefore(java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(2)))
                    .put("refreshIntervalHours", ExchangeRate.refreshIntervalHours(snapshot.source()));
        });
    }
}

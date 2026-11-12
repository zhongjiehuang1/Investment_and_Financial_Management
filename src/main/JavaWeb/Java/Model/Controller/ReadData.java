package Model.Controller;

import Model.User;
import Model.FixedTypes.Currencies;
import org.json.JSONObject;
import java.sql.SQLException;

/** Thin model entry points: the existing three layers and database schema stay intact. */
public final class ReadData {
    private ReadData() { }

    private static int integer(JSONObject input, String key, int defaultValue) {
        Object value = input.opt(key);
        if (value == null) return defaultValue;
        String text = String.valueOf(value);
        if (!text.matches("[1-9][0-9]{0,9}")) throw new IllegalArgumentException("Invalid pagination");
        return Integer.parseInt(text);
    }
    private static int page(JSONObject input) { return integer(input, "page", 1); }
    private static int size(JSONObject input) {
        int size = integer(input, "pageSize", 100);
        if (size > 100) throw new IllegalArgumentException("Maximum page size is 100");
        return size;
    }
    private static String query(JSONObject input) {
        String q = input.optString("q", "").trim();
        if (q.length() > 150) throw new IllegalArgumentException("Search too long");
        return q;
    }
    private static String currency(User user, JSONObject input) {
        String currency = input.optString("currency", user.getCurrencyCurrentChosen());
        return Currencies.valueOf(currency).name();
    }
    private static JSONObject filters(JSONObject input) {
        for (String name : new String[]{"filterType", "filterPlatform", "filterMonth", "filterDateOfMonth", "filterYear"})
            if (input.has(name) && !(input.get(name) instanceof org.json.JSONArray))
                throw new IllegalArgumentException("Filters must be arrays");
        JSONObject result = new JSONObject(input.toString());
        result.put("page", page(input)).put("pageSize", size(input)).put("q", query(input));
        return result;
    }

    public static JSONObject assetsPage(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.assetsPage(user.getId(),
                input.optString("kind", "investment"), page(input), size(input), query(input), input.optString("risk", "all"));
    }
    public static JSONObject transactionsPage(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.transactionsPage(user.getId(), filters(input));
    }
    public static JSONObject inflationPage(User user, JSONObject input) throws SQLException {
        String currency = input.optString("currency", "all");
        if (!"all".equals(currency)) Currencies.valueOf(currency);
        return DatabaseService.Model.Controller.ReadData.inflationPage(page(input), size(input), currency,
                input.optString("sortBy", "date"), input.optString("sortDirection", "desc"))
                .put("currencies", Currencies.supported());
    }
    public static JSONObject platformsPage(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.platformsPage(user.getId(), page(input), size(input), query(input));
    }
    public static JSONObject assetSummary(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.assetSummary(user.getId(), currency(user, input));
    }
    public static JSONObject cashflowSummary(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.cashflowSummary(user.getId(), currency(user, input), filters(input));
    }
    public static JSONObject earningsSummary(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.earningsSummary(user.getId(), currency(user, input));
    }
    public static JSONObject riskSummary(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.riskSummary(user.getId(), currency(user, input));
    }
    public static JSONObject profile(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.profile(user.getId());
    }
    public static JSONObject basicVariables(User user, JSONObject input) throws SQLException {
        return DatabaseService.Model.Controller.ReadData.basicVariables(user.getId());
    }
    /** The current UI loads one report inside a single database and exchange-rate snapshot. */
    public static JSONObject financialReport(User user, JSONObject input) throws SQLException {
        JSONObject report = assetSummary(user, input);
        for (JSONObject part : new JSONObject[]{cashflowSummary(user, input), earningsSummary(user, input), riskSummary(user, input)})
            for (String key : part.keySet()) report.put(key, part.get(key));
        return report;
    }
}

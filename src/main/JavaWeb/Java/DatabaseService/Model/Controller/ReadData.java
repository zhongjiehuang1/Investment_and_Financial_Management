package DatabaseService.Model.Controller;

import DatabaseService.DatabaseInit;
import Model.Tools.Computing;
import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owner-scoped, bounded reads and exact integer-minor-unit summaries. */
public class ReadData extends DatabaseInit {
    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> MONEY_FIELDS = Set.of("amount", "investmentAmount", "cashWithdrawal",
            "managementFee", "handlingFee", "buyInAmount", "amountSold", "realTimeAmount", "initialEarnings");
    private static final String NON_INVESTMENT_FIELDS = "ID AS id, GENERATION AS generation, NAME AS assetName, AMOUNT AS amount, "
            + "TYPE AS assetType, RISK_TYPE AS riskType, CURRENCY AS currency, DATE AS date";
    private static final String INVESTMENT_FIELDS = "ID AS id, GENERATION AS generation, INVESTMENT_PLATFORM AS investmentPlatform, "
            + "INVESTMENT_AMOUNT AS investmentAmount, CASH_WITHDRAWAL AS cashWithdrawal, "
            + "MANAGEMENT_FEE AS managementFee, HANDLING_FEE AS handlingFee, BUY_IN_AMOUNT AS buyInAmount, "
            + "AMOUNT_SOLD AS amountSold, REAL_TIME_AMOUNT AS realTimeAmount, INITIAL_EARNINGS AS initialEarnings, "
            + "INVESTMENT_TYPE AS investmentType, RISK_TYPE AS riskType, CURRENCY AS currency, DATE AS date";

    public static JSONObject assetsPage(int userId, String kind, int page, int pageSize, String q, String risk)
            throws SQLException {
        requireUser(userId);
        boolean investment = "investment".equals(kind);
        if (!investment && !"nonInvestment".equals(kind)) throw new IllegalArgumentException("Invalid asset kind.");
        String table = investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS";
        String name = investment ? "INVESTMENT_PLATFORM" : "NAME";
        StringBuilder where = new StringBuilder(" FROM " + table + " WHERE USER_ID = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        if (risk != null && !risk.isBlank() && !"all".equals(risk)) {
            Model.FixedTypes.RiskTypes.valueOf(risk);
            where.append(" AND RISK_TYPE = ?"); parameters.add(risk);
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND LOWER(").append(name).append(") LIKE ? ESCAPE '!'");
            parameters.add(search(q));
        }
        try (Connection connection = readConnection()) {
            JSONObject result = page(connection, investment ? INVESTMENT_FIELDS : NON_INVESTMENT_FIELDS,
                    where.toString(), parameters, "DATE DESC, ID DESC", page, pageSize);
            result.put("kind", kind);
            JSONArray items = result.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (investment) item.put("earnings", earnings(item)).put("fees", Computing.add(item.getString("managementFee"), item.getString("handlingFee")));
                for (String field : item.keySet()) result.put(kind + "_" + field + i, item.get(field));
            }
            result.put(kind + "_size", items.length());
            connection.commit();
            return result;
        }
    }

    public static JSONObject transactionsPage(int userId, JSONObject filters) throws SQLException {
        requireUser(userId);
        if (filters == null) filters = new JSONObject();
        Query query = transactionFilter(userId, filters);
        try (Connection connection = readConnection()) {
            JSONObject result = page(connection, "ID AS id, GENERATION AS generation, TYPE AS type, PLATFORM AS platform, NAME AS name, "
                            + "AMOUNT AS amount, DATE AS date, CURRENCY AS currency, JOURNAL_ID AS journalId", query.sql, query.parameters,
                    transactionOrder(filters), filters.optInt("page", 1), filters.optInt("pageSize", DEFAULT_PAGE_SIZE));
            JSONArray items = result.getJSONArray("items");
            Map<String, JSONObject> counterparts = transferCounterparts(connection, userId, items);
            Map<String, String> generations = accountGenerations(connection, userId, items);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                item.put("platformGeneration", generations.getOrDefault(item.getString("platform"), ""));
                item.put("isTransfer", item.getString("type").startsWith("transfer_"));
                item.put("internalTransfer", item.getBoolean("isTransfer"));
                String journal = item.optString("journalId", "");
                JSONObject counterpart = counterparts.get(item.getInt("databaseId") + ":" + item.getInt("id") + ":" + journal);
                boolean paired = item.getBoolean("isTransfer") && counterpart != null;
                item.put("journalId", journal).put("pairedTransfer", paired)
                        .put("pairStatus", paired ? "paired" : item.getBoolean("isTransfer") ? "legacy_unpaired" : "external")
                        .put("canEdit", !item.getBoolean("isTransfer")).put("canDelete", !item.getBoolean("isTransfer") || paired);
                if (paired) for (String field : counterpart.keySet()) item.put(field, counterpart.get(field));
                for (String field : item.keySet()) result.put(field + i, item.get(field));
            }
            result.put("size", items.length());
            connection.commit();
            return result;
        }
    }

    private static Map<String, String> accountGenerations(Connection connection, int userId, JSONArray items) throws SQLException {
        Map<String, String> generations = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            String platform = items.getJSONObject(i).getString("platform");
            if (generations.containsKey(platform)) continue;
            String generation = null;
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
                try (PreparedStatement statement = participant.prepareStatement(
                        "SELECT GENERATION FROM ALL_NON_INVESTMENT_ASSETS WHERE USER_ID=? AND NAME=? UNION ALL "
                                + "SELECT GENERATION FROM ALL_INVESTMENT_ASSETS WHERE USER_ID=? AND INVESTMENT_PLATFORM=?")) {
                    statement.setInt(1, userId); statement.setString(2, platform);
                    statement.setInt(3, userId); statement.setString(4, platform);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            if (generation != null) throw new SQLException("Account names are ambiguous across nodes.");
                            generation = result.getString(1);
                        }
                    }
                }
            }
            generations.put(platform, generation == null ? "" : generation);
        }
        return generations;
    }

    private static String transactionOrder(JSONObject filters) {
        Map<String, String> columns = Map.of("date", "DATE", "type", "TYPE", "platform", "PLATFORM", "name", "NAME", "amount", "CAST(AMOUNT AS DECIMAL(15,2))", "currency", "CURRENCY");
        String column = columns.get(filters.optString("sortBy", "date"));
        String direction = filters.optString("sortDirection", "desc").toLowerCase(java.util.Locale.ROOT);
        if (column == null || !Set.of("asc", "desc").contains(direction)) throw new IllegalArgumentException("Invalid transaction sort.");
        return column + " " + direction + ", ID " + direction;
    }

    private static Map<String, JSONObject> transferCounterparts(Connection connection, int userId, JSONArray items) throws SQLException {
        Map<String, JSONObject> found = new LinkedHashMap<>();
        if (items.isEmpty()) return found;
        for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
        int node = DatabaseService.ShardScope.nodeOf(participant);
        java.util.List<Integer> ids = new java.util.ArrayList<>();
        for (int i = 0; i < items.length(); i++) if (items.getJSONObject(i).getInt("databaseId") == node) ids.add(items.getJSONObject(i).getInt("id"));
        if (ids.isEmpty()) continue;
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        String sql = "SELECT T.ID AS SOURCE_ID,T.JOURNAL_ID,C.ID,C.GENERATION,C.PLATFORM,C.AMOUNT,C.CURRENCY FROM INCOME_AND_EXPENDITURE_DATA T JOIN LEDGER_JOURNALS J ON J.ID=T.JOURNAL_ID AND J.USER_ID=T.USER_ID AND J.KIND='transfer' JOIN INCOME_AND_EXPENDITURE_DATA C ON C.JOURNAL_ID=T.JOURNAL_ID AND C.USER_ID=T.USER_ID AND C.ID<>T.ID WHERE T.USER_ID=? AND T.ID IN (" + placeholders + ")";
        try (PreparedStatement statement = participant.prepareStatement(sql)) {
            statement.setInt(1, userId);
            for (int i = 0; i < ids.size(); i++) statement.setInt(i + 2, ids.get(i));
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String key = node + ":" + result.getInt("SOURCE_ID") + ":" + result.getString("JOURNAL_ID");
                    if (found.containsKey(key)) throw new SQLException("A transfer has more than two records.");
                    found.put(key, new JSONObject().put("counterpartDatabaseId", node).put("counterpartGeneration", result.getString("GENERATION")).put("counterpartId", result.getInt("ID")).put("counterpartPlatform", result.getString("PLATFORM"))
                            .put("counterpartAmount", Computing.normalizeStored(result.getString("AMOUNT"))).put("counterpartCurrency", result.getString("CURRENCY")));
                }
            }
        }
        }
        return found;
    }

    public static JSONObject inflationPage(int page, int pageSize) throws SQLException {
        return inflationPage(page, pageSize, "all", "date", "desc");
    }

    public static JSONObject inflationPage(int page, int pageSize, String currency, String sortBy, String direction) throws SQLException {
        String column = switch (sortBy) {
            case "date" -> "DATE";
            case "rate", "annualInflationRate" -> "AIR_IN_VARIOUS_CURRENCIES";
            case "currency" -> "VARIOUS_CURRENCIES";
            default -> throw new IllegalArgumentException("Invalid inflation sort field");
        };
        if (!Set.of("asc", "desc").contains(direction)) throw new IllegalArgumentException("Invalid sort direction");
        String from = " FROM ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES";
        List<Object> parameters = new ArrayList<>();
        if (!"all".equals(currency)) {
            requireCurrency(currency); from += " WHERE VARIOUS_CURRENCIES = ?"; parameters.add(currency);
        }
        try (Connection connection = readConnection()) {
            JSONObject result = page(connection, "ID AS id, GENERATION AS generation, AIR_IN_VARIOUS_CURRENCIES AS annualInflationRate, COALESCE(OBSERVED_THROUGH, DATE) AS observedThrough, "
                            + "VARIOUS_CURRENCIES AS currency, DATE AS date",
                    from, parameters, column + " " + direction.toUpperCase(java.util.Locale.ROOT) + ", ID "
                            + direction.toUpperCase(java.util.Locale.ROOT), page, pageSize);
            result.put("sortBy", sortBy).put("sortDirection", direction).put("currency", currency);
            connection.commit();
            return result;
        }
    }

    public static JSONObject platformsPage(int userId, int page, int pageSize, String q) throws SQLException {
        requireUser(userId);
        String from = " FROM (SELECT ID AS id, GENERATION AS generation, NAME AS name, CURRENCY AS currency, 'nonInvestment' AS kind "
                + "FROM ALL_NON_INVESTMENT_ASSETS WHERE USER_ID = ? UNION ALL SELECT ID AS id, GENERATION AS generation, "
                + "INVESTMENT_PLATFORM AS name, CURRENCY AS currency, 'investment' AS kind "
                + "FROM ALL_INVESTMENT_ASSETS WHERE USER_ID = ?) P";
        List<Object> parameters = new ArrayList<>(List.of(userId, userId));
        if (q != null && !q.isBlank()) { from += " WHERE LOWER(name) LIKE ? ESCAPE '!'"; parameters.add(search(q)); }
        try (Connection connection = readConnection()) {
            JSONObject result = page(connection, "id, generation, name, currency, kind", from, parameters,
                    "name, kind, id", page, pageSize);
            connection.commit();
            return result;
        }
    }

    public static JSONObject profile(int userId) throws SQLException {
        requireUser(userId);
        try (Connection connection = readConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ID, GENERATION, FORENAME, SURNAME, EMAIL_ADDRESS FROM USERS WHERE ID = ?")) {
                statement.setInt(1, userId);
                JSONObject output;
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("User does not exist.");
                    output = new JSONObject().put("id", result.getInt("ID")).put("generation", result.getString("GENERATION"))
                            .put("forename", result.getString("FORENAME")).put("surname", result.getString("SURNAME"))
                            .put("emailAddress", result.getString("EMAIL_ADDRESS"));
                }
                connection.commit();
                return output;
            } catch (SQLException | RuntimeException failure) { rollback(connection); throw failure; }
        }
    }

    public static JSONObject basicVariables(int userId) throws SQLException {
        requireUser(userId);
        try (Connection connection = readConnection()) {
            try {
                JSONObject output = basicVariables(connection, userId);
                connection.commit();
                return output;
            } catch (SQLException | RuntimeException failure) { rollback(connection); throw failure; }
        }
    }

    private static JSONObject basicVariables(Connection connection, int userId) throws SQLException {
        JSONObject output = new JSONObject().put("accumulatedAmount", "0.00").put("actualEarningsBefore", "0.00")
                .put("otherAccumulatedPassiveIncome", "0.00").put("currency", basicCurrency()).put("basicCurrency", basicCurrency());
        try (PreparedStatement statement = connection.prepareStatement("SELECT ACCUMULATED_AMOUNT, "
                + "ACTUAL_EARNINGS_BEFORE, OTHER_ACCUMULATED_PASSIVE_INCOME FROM BASIC_VARIABLES_AND_DATA WHERE USER_ID = ?")) {
            statement.setInt(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    output.put("accumulatedAmount", Computing.normalizeStored(result.getString("ACCUMULATED_AMOUNT")))
                            .put("actualEarningsBefore", Computing.normalizeStored(result.getString("ACTUAL_EARNINGS_BEFORE")))
                            .put("otherAccumulatedPassiveIncome", Computing.normalizeStored(result.getString("OTHER_ACCUMULATED_PASSIVE_INCOME")));
                    if (result.next()) throw new SQLException("Multiple basic variable rows exist for this user.");
                }
            }
        }
        return output;
    }

    public static JSONObject assetSummary(int userId, String baseCurrency) throws SQLException {
        requireUser(userId); requireCurrency(baseCurrency);
        Map<String, Long> amounts = new LinkedHashMap<>();
        long cashCount = 0, investmentCount = 0;
        try (Connection connection = readConnection()) {
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            for (boolean investment : new boolean[]{false, true}) {
                String sql = "SELECT " + (investment ? "REAL_TIME_AMOUNT" : "AMOUNT")
                        + " AS AMOUNT, CURRENCY FROM "
                        + (investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS") + " WHERE USER_ID = ?";
                try (PreparedStatement statement = stream(participant, sql, userId); ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        merge(amounts, result.getString("CURRENCY"), Computing.toMinorUnits(result.getString("AMOUNT")));
                        if (investment) investmentCount++; else cashCount++;
                    }
                }
            }
            }
            connection.commit();
        }
        long total = 0;
        JSONArray exposure = new JSONArray();
        JSONObject output = new JSONObject();
        int index = 0;
        for (Map.Entry<String, Long> entry : amounts.entrySet()) {
            String amount = Computing.fromMinorUnits(entry.getValue());
            String converted = Computing.convert(amount, entry.getKey(), baseCurrency);
            total = Math.addExact(total, Computing.toMinorUnits(converted));
            exposure.put(new JSONObject().put("currency", entry.getKey()).put("amount", amount).put("value", converted));
            output.put("currency" + index++, entry.getKey());
            output.put("Amount of liquid net assets held in " + entry.getKey(), converted);
        }
        for (int i = 0; i < exposure.length(); i++) {
            JSONObject item = exposure.getJSONObject(i);
            String percentage = percentage(Computing.toMinorUnits(item.getString("value")), total);
            item.put("percentage", percentage);
            output.put("Percentage of amount of liquid net assets held in " + item.getString("currency"), percentage);
        }
        String netWorth = Computing.fromMinorUnits(total);
        return output.put("netWorth", netWorth).put("Total current net assets", netWorth)
                .put("assetCount", cashCount + investmentCount).put("cashCount", cashCount)
                .put("investmentCount", investmentCount).put("currencyExposure", exposure)
                .put("size", amounts.size()).put("currencyCurrentChosen", baseCurrency);
    }

    public static JSONObject earningsSummary(int userId, String baseCurrency) throws SQLException {
        requireUser(userId); requireCurrency(baseCurrency);
        Map<String, Long> earnings = new LinkedHashMap<>(), fees = new LinkedHashMap<>();
        JSONObject basic;
        try (Connection connection = readConnection()) {
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            try (PreparedStatement statement = stream(participant,
                "SELECT REAL_TIME_AMOUNT, INVESTMENT_AMOUNT, CASH_WITHDRAWAL, INITIAL_EARNINGS, "
                        + "MANAGEMENT_FEE, HANDLING_FEE, CURRENCY FROM ALL_INVESTMENT_ASSETS WHERE USER_ID = ?", userId)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String currency = result.getString("CURRENCY");
                    Model.UserAssets.InvestmentAsset asset = new Model.UserAssets.InvestmentAsset();
                    asset.setRealTimeAmount(result.getString("REAL_TIME_AMOUNT")); asset.setInvestmentAmount(result.getString("INVESTMENT_AMOUNT"));
                    asset.setCashWithdrawal(result.getString("CASH_WITHDRAWAL")); asset.setInitialEarnings(result.getString("INITIAL_EARNINGS"));
                    merge(earnings, currency, Computing.toMinorUnits(asset.getEarnings()));
                    merge(fees, currency, Math.addExact(Computing.toMinorUnits(result.getString("MANAGEMENT_FEE")),
                            Computing.toMinorUnits(result.getString("HANDLING_FEE"))));
                }
            }
            }
            }
            basic = basicVariables(connection, userId);
            connection.commit();
        }
        String investment = convertTotal(earnings, baseCurrency);
        String other = Computing.convert(basic.getString("otherAccumulatedPassiveIncome"), basic.getString("currency"), baseCurrency);
        String prior = Computing.convert(basic.getString("actualEarningsBefore"), basic.getString("currency"), baseCurrency);
        String total = Computing.add(investment, other);
        String cycle = Computing.subtract(total, prior);
        return new JSONObject().put("earnings", total).put("investmentEarnings", investment).put("fees", convertTotal(fees, baseCurrency))
                .put("otherPassiveIncome", other).put("priorEarnings", prior).put("cycleEarnings", cycle)
                .put("Actual earnings", total).put("Cycle earnings", cycle).put("Actual earnings before", prior)
                .put("otherAccumulatedPassiveIncomeInBasicVariablesAndData", other).put("basicCurrency", basic.getString("currency"))
                .put("currencyCurrentChosen", baseCurrency);
    }

    public static JSONObject cashflowSummary(int userId, String baseCurrency, JSONObject filters) throws SQLException {
        requireUser(userId); requireCurrency(baseCurrency);
        JSONObject summaryFilters = filters == null ? new JSONObject() : new JSONObject(filters.toString());
        if (!summaryFilters.has("section")) summaryFilters.put("section", "all");
        Query query = transactionFilter(userId, summaryFilters);
        Map<String, Long> income = new LinkedHashMap<>(), expense = new LinkedHashMap<>();
        Map<String, Long> transferIn = new LinkedHashMap<>(), transferOut = new LinkedHashMap<>();
        Map<String, Map<String, Long>> monthlyIncome = new LinkedHashMap<>(), monthlyExpense = new LinkedHashMap<>();
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        for (int month = 11; month >= 0; month--) {
            String label = currentMonth.minusMonths(month).toString();
            monthlyIncome.put(label, new LinkedHashMap<>()); monthlyExpense.put(label, new LinkedHashMap<>());
        }
        long count = 0, transferCount = 0, unclassifiedCount = 0;
        try (Connection connection = readConnection()) {
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            try (PreparedStatement statement = stream(participant,
                "SELECT TYPE, AMOUNT, CURRENCY, DATE" + query.sql, query.parameters)) {
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    count++;
                    String type = result.getString("TYPE");
                    if ("transfer_in".equals(type) || "transfer_out".equals(type)) {
                        transferCount++;
                        merge("transfer_in".equals(type) ? transferIn : transferOut, result.getString("CURRENCY"), Computing.toMinorUnits(result.getString("AMOUNT")));
                        continue;
                    }
                    if (!"income".equals(type) && !"expense".equals(type)) { unclassifiedCount++; continue; }
                    String currency = result.getString("CURRENCY");
                    long amount = Computing.toMinorUnits(result.getString("AMOUNT"));
                    boolean incoming = "income".equals(type);
                    merge(incoming ? income : expense, currency, amount);
                    String month = result.getDate("DATE").toLocalDate().toString().substring(0, 7);
                    Map<String, Long> bucket = (incoming ? monthlyIncome : monthlyExpense).get(month);
                    if (bucket != null) merge(bucket, currency, amount);
                }
            }
            }
            }
            connection.commit();
        }
        String incomeTotal = convertTotal(income, baseCurrency), expenseTotal = convertTotal(expense, baseCurrency);
        String currentIncome = convertTotal(monthlyIncome.get(currentMonth.toString()), baseCurrency);
        String currentExpense = convertTotal(monthlyExpense.get(currentMonth.toString()), baseCurrency);
        JSONArray monthly = new JSONArray();
        for (String month : monthlyIncome.keySet()) monthly.put(new JSONObject().put("month", month)
                .put("income", convertTotal(monthlyIncome.get(month), baseCurrency))
                .put("expense", convertTotal(monthlyExpense.get(month), baseCurrency)));
        return new JSONObject().put("income", incomeTotal).put("expense", expenseTotal).put("recordCount", count)
                .put("transferCount", transferCount).put("unclassifiedCount", unclassifiedCount)
                .put("transferIn", convertTotal(transferIn, baseCurrency)).put("transferOut", convertTotal(transferOut, baseCurrency))
                .put("monthlyIncome", currentIncome).put("monthlyExpense", currentExpense)
                .put("monthlySavingsRate", percentage(Computing.toMinorUnits(Computing.subtract(currentIncome, currentExpense)), Computing.toMinorUnits(currentIncome)))
                .put("savingsRate", percentage(Computing.toMinorUnits(Computing.subtract(incomeTotal, expenseTotal)), Computing.toMinorUnits(incomeTotal)))
                .put("monthly", monthly).put("currencyCurrentChosen", baseCurrency);
    }

    public static JSONObject riskSummary(int userId, String baseCurrency) throws SQLException {
        requireUser(userId); requireCurrency(baseCurrency);
        Map<String, Map<String, Long>> groups = new LinkedHashMap<>();
        for (Model.FixedTypes.RiskTypes risk : Model.FixedTypes.RiskTypes.values()) groups.put(risk.name(), new LinkedHashMap<>());
        try (Connection connection = readConnection()) {
            for (Connection participant : DatabaseService.ShardScope.connections(connection)) {
            for (boolean investment : new boolean[]{false, true}) {
                String sql = "SELECT " + (investment ? "REAL_TIME_AMOUNT AS AMOUNT, INVESTMENT_AMOUNT, CASH_WITHDRAWAL, MANAGEMENT_FEE, HANDLING_FEE, BUY_IN_AMOUNT, AMOUNT_SOLD"
                        : "AMOUNT") + ", CURRENCY, RISK_TYPE FROM "
                        + (investment ? "ALL_INVESTMENT_ASSETS" : "ALL_NON_INVESTMENT_ASSETS") + " WHERE USER_ID = ?";
                try (PreparedStatement statement = stream(participant, sql, userId); ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        String risk = Model.FixedTypes.RiskTypes.valueOf(result.getString("RISK_TYPE")).name();
                        String currency = result.getString("CURRENCY");
                        long amount = Computing.toMinorUnits(result.getString("AMOUNT"));
                        if (investment && !"VERY_LOW_AND_LOW_RISK_TYPE".equals(risk)) {
                            Model.UserAssets.InvestmentAsset asset = new Model.UserAssets.InvestmentAsset();
                            asset.setInvestmentAmount(result.getString("INVESTMENT_AMOUNT"));
                            asset.setCashWithdrawal(result.getString("CASH_WITHDRAWAL"));
                            asset.setManagementFee(result.getString("MANAGEMENT_FEE"));
                            asset.setHandlingFee(result.getString("HANDLING_FEE"));
                            asset.setBuyInAmount(result.getString("BUY_IN_AMOUNT")); asset.setAmountSold(result.getString("AMOUNT_SOLD"));
                            long cash = Computing.toMinorUnits(asset.getUninvestedAmount());
                            merge(groups.get("VERY_LOW_AND_LOW_RISK_TYPE"), currency, cash);
                            amount = Math.subtractExact(amount, cash);
                        }
                        merge(groups.get(risk), currency, amount);
                    }
                }
            }
            }
            connection.commit();
        }
        Map<String, Long> values = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, Map<String, Long>> entry : groups.entrySet()) {
            long value = Computing.toMinorUnits(convertTotal(entry.getValue(), baseCurrency));
            values.put(entry.getKey(), value); total = Math.addExact(total, value);
        }
        JSONArray distribution = new JSONArray();
        JSONObject output = new JSONObject();
        for (Map.Entry<String, Long> entry : values.entrySet()) distribution.put(new JSONObject().put("riskType", entry.getKey())
                .put("amount", Computing.fromMinorUnits(entry.getValue())).put("percentage", percentage(entry.getValue(), total)));
        legacyRisk(output, values, total, "very low and low", "VeryLow", "Low");
        legacyRisk(output, values, total, "medium low and medium", "MediumLow", "Medium");
        legacyRisk(output, values, total, "medium high and high", "MediumHigh", "High");
        return output.put("riskDistribution", distribution).put("currencyCurrentChosen", baseCurrency);
    }

    private static void legacyRisk(JSONObject output, Map<String, Long> groups, long total, String name, String first, String second) {
        long amount = 0;
        for (Map.Entry<String, Long> entry : groups.entrySet()) {
            String compact = entry.getKey().replace("_", "").replace(" ", "");
            if (compact.equalsIgnoreCase(first) || compact.equalsIgnoreCase(second)
                    || entry.getKey().equalsIgnoreCase(name.replace(" ", "_") + "_RISK_TYPE")) amount = Math.addExact(amount, entry.getValue());
        }
        output.put("Total " + name + " risk liquid assets", Computing.fromMinorUnits(amount));
        output.put("Percentage of total " + name + " risk liquid assets", percentage(amount, total));
    }

    private static JSONObject page(Connection connection, String columns, String from, List<Object> parameters,
                                   String order, int page, int pageSize) throws SQLException {
        return ShardedPage.read(connection, columns, from, parameters, order, page, pageSize, (result, publicColumns) -> {
            java.sql.ResultSetMetaData metadata = result.getMetaData();
            JSONObject item = new JSONObject();
            for (int column = 1; column <= publicColumns; column++) {
                String field = canonical(metadata.getColumnLabel(column));
                if ("id".equals(field)) item.put(field, result.getInt(column));
                else if ("date".equals(field)) {
                    LocalDate date = result.getDate(column).toLocalDate();
                    item.put("date", date.toString()).put("year", date.getYear())
                            .put("monthOfYear", date.getMonthValue()).put("dateOfMonth", date.getDayOfMonth());
                } else if (MONEY_FIELDS.contains(field)) item.put(field, Computing.normalizeStored(result.getString(column)));
                else item.put(field, result.getString(column));
            }
            return item;
        });
    }

    private static String canonical(String field) {
        for (String known : List.of("id", "generation", "assetName", "amount", "assetType", "riskType", "currency", "date", "investmentPlatform",
                "investmentAmount", "cashWithdrawal", "managementFee", "handlingFee", "buyInAmount", "amountSold", "realTimeAmount",
                "initialEarnings", "investmentType", "type", "platform", "name", "kind", "annualInflationRate", "observedThrough", "journalId")) {
            if (known.equalsIgnoreCase(field)) return known;
        }
        return field;
    }

    private static String earnings(JSONObject item) {
        Model.UserAssets.InvestmentAsset asset = new Model.UserAssets.InvestmentAsset();
        asset.setRealTimeAmount(item.getString("realTimeAmount")); asset.setInvestmentAmount(item.getString("investmentAmount"));
        asset.setCashWithdrawal(item.getString("cashWithdrawal")); asset.setInitialEarnings(item.getString("initialEarnings"));
        return asset.getEarnings();
    }

    private static Query transactionFilter(int userId, JSONObject filters) {
        StringBuilder sql = new StringBuilder(" FROM INCOME_AND_EXPENDITURE_DATA WHERE USER_ID = ?");
        List<Object> values = new ArrayList<>(); values.add(userId);
        String section = filters.optString("section", "cashflow");
        if ("cashflow".equals(section)) sql.append(" AND TYPE NOT IN ('transfer_in','transfer_out')");
        else if ("transfers".equals(section)) sql.append(" AND TYPE IN ('transfer_in','transfer_out')");
        else if (!"all".equals(section)) throw new IllegalArgumentException("Invalid transaction section.");
        in(sql, values, "TYPE", filters.optJSONArray("filterType"), 0, 0);
        in(sql, values, "PLATFORM", filters.optJSONArray("filterPlatform"), 0, 0);
        in(sql, values, "EXTRACT(MONTH FROM DATE)", filters.optJSONArray("filterMonth"), 1, 12);
        in(sql, values, "EXTRACT(DAY FROM DATE)", filters.optJSONArray("filterDateOfMonth"), 1, 31);
        in(sql, values, "EXTRACT(YEAR FROM DATE)", filters.optJSONArray("filterYear"), 1, 9999);
        String q = filters.optString("q", "");
        if (!q.isBlank()) {
            sql.append(" AND (LOWER(NAME) LIKE ? ESCAPE '!' OR LOWER(PLATFORM) LIKE ? ESCAPE '!')");
            String pattern = search(q); values.add(pattern); values.add(pattern);
        }
        for (String dateKey : List.of("dateFrom", "dateTo")) {
            String date = filters.optString(dateKey, "");
            if (!date.isBlank()) {
                sql.append(" AND DATE ").append("dateFrom".equals(dateKey) ? ">=" : "<=").append(" ?");
                values.add(recordDate(date));
            }
        }
        return new Query(sql.toString(), values);
    }

    private static void in(StringBuilder sql, List<Object> values, String column, JSONArray array, int minimum, int maximum) {
        if (array == null || array.isEmpty()) return;
        if (array.length() > 100) throw new IllegalArgumentException("Too many filter values.");
        sql.append(" AND ").append(column).append(" IN (");
        for (int i = 0; i < array.length(); i++) {
            if (i > 0) sql.append(','); sql.append('?');
            String value = String.valueOf(array.get(i)).trim();
            if (maximum > 0) {
                int number = Integer.parseInt(value);
                if (number < minimum || number > maximum) throw new IllegalArgumentException("Invalid date filter.");
                values.add(number);
            } else {
                if (value.length() > 50) throw new IllegalArgumentException("Invalid filter.");
                values.add(value);
            }
        }
        sql.append(')');
    }

    private static String search(String value) {
        if (value.length() > 150) throw new IllegalArgumentException("Search is too long.");
        return "%" + value.trim().toLowerCase(java.util.Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static Connection readConnection() throws SQLException {
        // The HTTP identity guard starts the transaction, after selecting its isolation level.
        Connection connection = openPrimaryConnection();
        if (!DatabaseService.RequestIdentity.isActive()) {
            try { connection.setReadOnly(true); connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); }
            catch (SQLException failure) { connection.close(); throw failure; }
        }
        return connection;
    }

    private static PreparedStatement stream(Connection connection, String sql, int userId) throws SQLException {
        return stream(connection, sql, List.of(userId));
    }

    private static PreparedStatement stream(Connection connection, String sql, List<Object> parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        try {
            // MySQL streams without retaining the full ledger in the JDBC driver or session.
            statement.setFetchSize(connection.getMetaData().getDatabaseProductName().contains("MySQL") ? Integer.MIN_VALUE : 100);
            bind(statement, parameters);
            return statement;
        } catch (SQLException exception) { statement.close(); throw exception; }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters) throws SQLException {
        for (int i = 0; i < parameters.size(); i++) statement.setObject(i + 1, parameters.get(i));
    }

    private static void merge(Map<String, Long> amounts, String currency, long amount) {
        requireCurrency(currency);
        amounts.put(currency, Math.addExact(amounts.getOrDefault(currency, 0L), amount));
    }

    private static String convertTotal(Map<String, Long> amounts, String baseCurrency) {
        long total = 0;
        for (Map.Entry<String, Long> entry : amounts.entrySet()) total = Math.addExact(total,
                Computing.toMinorUnits(Computing.convert(Computing.fromMinorUnits(entry.getValue()), entry.getKey(), baseCurrency)));
        return Computing.fromMinorUnits(total);
    }

    private static String percentage(long numerator, long denominator) {
        double value = Computing.percentage(Computing.fromMinorUnits(numerator), Computing.fromMinorUnits(denominator));
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static void requireUser(int userId) {
        if (userId <= 0) throw new IllegalArgumentException("A valid user is required.");
    }

    private static void requireCurrency(String currency) {
        try { Model.FixedTypes.Currencies.valueOf(currency); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("Unsupported currency."); }
    }

    private static String basicCurrency() {
        String currency = System.getProperty("iafm.base.currency", System.getenv().getOrDefault("IAFM_BASE_CURRENCY", "GBP"));
        requireCurrency(currency); return currency;
    }

    private record Query(String sql, List<Object> parameters) {}
}

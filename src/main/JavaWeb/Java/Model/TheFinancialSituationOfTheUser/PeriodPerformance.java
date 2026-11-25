package Model.TheFinancialSituationOfTheUser;

import Model.FixedTypes.Currencies;
import Model.User;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Historical performance using owner-reconciled, versioned inputs. */
public final class PeriodPerformance {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final String METHOD = "cash-flow-cpi-index-v1";
    private static final String CALCULATION_VERSION = "period-performance-v1";

    private record RateKey(LocalDate date, Currencies currency) {}
    private record Valuation(BigDecimal total, Set<String> accounts) {}
    private static final class MissingRate extends RuntimeException {}

    private PeriodPerformance() {}

    public static JSONObject evaluate(JSONObject input, User user) {
        input.put("version", 1).put("userGeneration", user.getGeneration())
                .put("reconciliationReference", "Owner reviewed in Finance Workspace");
        Currencies currency = Currencies.valueOf(input.getString("reportingCurrency"));
        return calculate(input, user.getGeneration(), currency, LocalDate.now(ZoneOffset.UTC));
    }

    static JSONObject calculate(JSONObject input, String generation, Currencies currency, LocalDate today) {
        require(Integer.valueOf(1).equals(input.get("version")) && generation.equals(input.getString("userGeneration"))
                && currency.name().equals(input.getString("reportingCurrency")), "Input identity does not match");
        LocalDate start = LocalDate.parse(input.getString("startDate"));
        LocalDate end = LocalDate.parse(input.getString("endDate"));
        require(start.isBefore(end) && !end.isAfter(today), "Invalid report period");
        String scope = label(input, "scope");
        String reconciliation = label(input, "reconciliationReference");

        Map<RateKey, BigDecimal> rates = new HashMap<>();
        JSONArray rateRows = input.getJSONArray("fxRates");
        String fxSource = rateRows.isEmpty() ? null : label(input, "fxSource");
        for (int i = 0; i < rateRows.length(); i++) {
            JSONObject row = rateRows.getJSONObject(i);
            LocalDate date = LocalDate.parse(row.getString("date"));
            LocalDate observed = LocalDate.parse(row.getString("observedOn"));
            Currencies nativeCurrency = Currencies.valueOf(row.getString("currency"));
            require(nativeCurrency != currency && !date.isBefore(start) && !date.isAfter(end)
                    && !observed.isAfter(date) && !observed.isBefore(date.minusDays(7)), "Invalid FX observation");
            BigDecimal rate = positive(row, "rate");
            require(rates.putIfAbsent(new RateKey(date, nativeCurrency), rate) == null, "Duplicate FX observation");
        }

        JSONObject result = new JSONObject().put("method", METHOD).put("currency", currency.name())
                .put("startDate", start.toString()).put("endDate", end.toString())
                .put("dataThrough", end.toString()).put("calculationVersion", CALCULATION_VERSION)
                .put("fxBasis", "historical-date-reference").put("scope", scope).put("reconciliationReference", reconciliation)
                .put("fxSource", fxSource == null ? JSONObject.NULL : fxSource);
        try {
            Valuation opening = valuation(input.getJSONArray("openingValues"), start, currency, rates);
            Valuation closing = valuation(input.getJSONArray("closingValues"), end, currency, rates);
            require(!opening.accounts().isEmpty() && opening.accounts().equals(closing.accounts()), "Valuation scopes differ");
            JSONArray flows = input.getJSONArray("externalFlows");
            BigDecimal netFlows = BigDecimal.ZERO;
            Map<YearMonth, BigDecimal> flowsByMonth = new HashMap<>();
            var flowsByDate = new TreeMap<LocalDate, BigDecimal>();
            Set<String> references = new HashSet<>();
            for (int i = 0; i < flows.length(); i++) {
                JSONObject flow = flows.getJSONObject(i);
                LocalDate date = LocalDate.parse(flow.getString("date"));
                require(date.isAfter(start) && !date.isAfter(end), "Cash flow is outside the period");
                require(references.add(label(flow, "reference")), "Duplicate cash flow");
                require(opening.accounts().contains(label(flow, "account")), "Cash flow account is outside the scope");
                BigDecimal value = value(flow, date, currency, rates);
                netFlows = netFlows.add(value);
                if (value.signum() != 0) flowsByDate.merge(date, value, BigDecimal::add);
                flowsByMonth.merge(YearMonth.from(date), value, BigDecimal::add);
            }
            result.put("openingValue", money(opening.total())).put("closingValue", money(closing.total()))
                    .put("netExternalFlows", money(netFlows))
                    .put("nominalProfit", money(closing.total().subtract(opening.total()).subtract(netFlows)));
            result.put("moneyWeightedReturn", PerformanceReturns.moneyWeighted(start, end, opening.total(), closing.total(), flowsByDate));
            result.put("timeWeightedReturn", timeWeighted(input, end, currency, rates, opening, closing, flowsByDate));
            if (!input.isNull("ledgerExportSha256")) {
                String digest = input.getString("ledgerExportSha256");
                require(digest.matches("[0-9a-f]{64}"), "Invalid ledger export digest");
                result.put("ledgerExportSha256", digest);
            }
            return addBenchmark(result, input.isNull("cpi") ? null : input.getJSONObject("cpi"), start, end, opening.total(),
                    closing.total(), netFlows, flowsByMonth);
        } catch (MissingRate missing) {
            return result.put("status", "MISSING_FX");
        }
    }

    private static JSONObject timeWeighted(JSONObject input, LocalDate end, Currencies currency,
                                           Map<RateKey, BigDecimal> rates, Valuation opening, Valuation closing,
                                           TreeMap<LocalDate, BigDecimal> flows) {
        Map<LocalDate, BigDecimal> beforeFlow = new HashMap<>();
        try {
            if (!input.isNull("preFlowValuations")) {
                JSONArray rows = input.getJSONArray("preFlowValuations");
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    LocalDate date = LocalDate.parse(row.getString("date"));
                    require(date.isBefore(end) && flows.containsKey(date), "Unexpected pre-flow valuation date");
                    Valuation captured = valuation(row.getJSONArray("values"), date, currency, rates);
                    require(captured.accounts().equals(opening.accounts()), "Pre-flow valuation scope differs");
                    require(beforeFlow.putIfAbsent(date, captured.total()) == null, "Duplicate pre-flow valuation date");
                }
            }
            return PerformanceReturns.timeWeighted(end, opening.total(), closing.total(), flows, beforeFlow);
        } catch (MissingRate missing) {
            return PerformanceReturns.status("MISSING_FX");
        } catch (JSONException | IllegalArgumentException | DateTimeException | ArithmeticException invalid) {
            return PerformanceReturns.status("INVALID_FLOW_VALUATION");
        }
    }

    private static Valuation valuation(JSONArray rows, LocalDate date, Currencies currency, Map<RateKey, BigDecimal> rates) {
        BigDecimal total = BigDecimal.ZERO;
        Set<String> accounts = new HashSet<>();
        Map<String, Set<Currencies>> positions = new HashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String account = label(row, "account");
            Currencies nativeCurrency = Currencies.valueOf(row.getString("currency"));
            require(positions.computeIfAbsent(account, key -> new HashSet<>()).add(nativeCurrency),
                    "Duplicate valuation position");
            accounts.add(account);
            total = total.add(value(row, date, currency, rates));
        }
        return new Valuation(total, accounts);
    }

    private static JSONObject addBenchmark(JSONObject result, JSONObject cpi, LocalDate start, LocalDate end,
                                           BigDecimal opening, BigDecimal closing, BigDecimal netFlows,
                                           Map<YearMonth, BigDecimal> flowsByMonth) {
        if (cpi == null) return result.put("status", "MISSING_CPI");
        result.put("cpiSeries", label(cpi, "series"))
                .put("cpiSource", label(cpi, "source"))
                .put("consumptionRegion", label(cpi, "region"));
        Map<YearMonth, BigDecimal> indices = new HashMap<>();
        JSONArray observations = cpi.getJSONArray("observations");
        for (int i = 0; i < observations.length(); i++) {
            JSONObject observation = observations.getJSONObject(i);
            YearMonth month = YearMonth.parse(observation.getString("month"));
            require(indices.putIfAbsent(month, positive(observation, "index")) == null, "Duplicate CPI observation");
        }
        YearMonth startMonth = YearMonth.from(start);
        YearMonth endMonth = YearMonth.from(end);
        BigDecimal startIndex = indices.get(startMonth);
        BigDecimal endIndex = indices.get(endMonth);
        if (startIndex == null || endIndex == null || !indices.keySet().containsAll(flowsByMonth.keySet())) {
            return result.put("status", "MISSING_CPI");
        }

        BigDecimal target = adjust(opening, startIndex, endIndex);
        for (var entry : flowsByMonth.entrySet()) {
            target = target.add(adjust(entry.getValue(), indices.get(entry.getKey()), endIndex));
        }
        return result.put("status", "AVAILABLE").put("cpiThroughMonth", endMonth.toString())
                .put("preservationTarget", money(target))
                .put("requiredPreservationProfit", money(target.subtract(opening).subtract(netFlows)))
                .put("abovePreservationBenchmark", money(closing.subtract(target)));
    }

    private static BigDecimal adjust(BigDecimal amount, BigDecimal from, BigDecimal to) {
        return amount.multiply(to).divide(from, PRECISION);
    }

    private static BigDecimal value(JSONObject row, LocalDate date, Currencies reporting, Map<RateKey, BigDecimal> rates) {
        BigDecimal amount = decimal(row, "amount", 2);
        Currencies currency = Currencies.valueOf(row.getString("currency"));
        if (currency == reporting || amount.signum() == 0) return amount;
        BigDecimal rate = rates.get(new RateKey(date, currency));
        if (rate == null) throw new MissingRate();
        return amount.multiply(rate);
    }

    private static BigDecimal positive(JSONObject row, String key) {
        BigDecimal value = decimal(row, key, 18);
        require(value.signum() > 0, "Expected positive decimal");
        return value;
    }

    private static BigDecimal decimal(JSONObject row, String key, int maximumScale) {
        String value = row.getString(key);
        require(value.length() <= 40 && value.matches("-?[0-9]{1,18}(\\.[0-9]{1,18})?"), "Invalid decimal");
        BigDecimal decimal = new BigDecimal(value);
        require(decimal.scale() <= maximumScale, "Invalid decimal precision");
        return decimal;
    }

    private static String label(JSONObject row, String key) {
        String value = row.getString(key);
        require(!value.isBlank() && value.length() <= 150, "Invalid label");
        return value;
    }

    private static String money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static JSONObject unavailable(String status, String currency) {
        return new JSONObject().put("status", status).put("currency", currency).put("method", METHOD);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}

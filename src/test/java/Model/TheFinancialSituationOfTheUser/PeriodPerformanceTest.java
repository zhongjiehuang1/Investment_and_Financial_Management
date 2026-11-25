package Model.TheFinancialSituationOfTheUser;

import Model.FixedTypes.Currencies;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.*;

public class PeriodPerformanceTest {
    private static final String GENERATION = "12345678-1234-1234-1234-123456789abc";
    private static final LocalDate TODAY = LocalDate.parse("2026-05-01");

    @Test public void flowIsAdjustedOnlyFromItsOwnMonth() {
        JSONObject report = calculate(fixture());
        assertEquals("AVAILABLE", report.getString("status"));
        assertEquals("800.00", report.getString("nominalProfit"));
        assertEquals("12439.22", report.getString("preservationTarget"));
        assertEquals("439.22", report.getString("requiredPreservationProfit"));
        assertEquals("360.78", report.getString("abovePreservationBenchmark"));
    }

    @Test public void sameCurrencyTransfersAndClosingAccountsKeepPortfolioHistory() {
        JSONObject evidence = fixture();
        var closing = evidence.getJSONArray("closingValues");
        closing.getJSONObject(0).put("amount", "0.00");
        closing.getJSONObject(1).put("amount", "12800.00");
        assertEquals(calculate(fixture()).toString(), calculate(evidence).toString());
    }

    @Test public void withdrawalsAndDeflationRemainSigned() {
        JSONObject evidence = fixture();
        evidence.getJSONArray("externalFlows").getJSONObject(0).put("amount", "-2000.00");
        evidence.getJSONArray("closingValues").getJSONObject(0).put("amount", "8000.00");
        evidence.getJSONObject("cpi").getJSONArray("observations").getJSONObject(2).put("index", "98");
        JSONObject report = calculate(evidence);
        assertEquals("0.00", report.getString("nominalProfit"));
        assertEquals("7878.43", report.getString("preservationTarget"));
        assertEquals("121.57", report.getString("abovePreservationBenchmark"));
    }

    @Test public void zeroInflationProducesZeroRequiredProfit() {
        JSONObject evidence = fixture();
        var observations = evidence.getJSONObject("cpi").getJSONArray("observations");
        for (int i = 0; i < observations.length(); i++) observations.getJSONObject(i).put("index", "100");
        JSONObject report = calculate(evidence);
        assertEquals("0.00", report.getString("requiredPreservationProfit"));
        assertEquals("800.00", report.getString("abovePreservationBenchmark"));
    }

    @Test public void missingIndexKeepsNominalProfitAndOmitsBenchmark() {
        JSONObject evidence = fixture();
        evidence.getJSONObject("cpi").getJSONArray("observations").remove(1);
        JSONObject report = calculate(evidence);
        assertEquals("MISSING_CPI", report.getString("status"));
        assertEquals("800.00", report.getString("nominalProfit"));
        assertFalse(report.has("requiredPreservationProfit"));
    }

    @Test public void historicalFxUsesDatesOfValuationsAndFlows() {
        JSONObject evidence = fixture();
        evidence.getJSONArray("openingValues").getJSONObject(0).put("currency", "USD");
        evidence.getJSONArray("closingValues").getJSONObject(0).put("currency", "USD");
        evidence.getJSONArray("externalFlows").getJSONObject(0).put("currency", "USD");
        evidence.put("fxSource", "Synthetic rates");
        var rates = evidence.getJSONArray("fxRates");
        rates.put(rate("2026-01-31", "2026-01-30", "0.75"));
        rates.put(rate("2026-02-15", "2026-02-13", "0.80"));
        rates.put(rate("2026-03-31", "2026-03-31", "0.85"));
        JSONObject report = calculate(evidence);
        assertEquals("7500.00", report.getString("openingValue"));
        assertEquals("10880.00", report.getString("closingValue"));
        assertEquals("1600.00", report.getString("netExternalFlows"));
        assertEquals("1780.00", report.getString("nominalProfit"));
        rates.remove(1);
        report = calculate(evidence);
        assertEquals("MISSING_FX", report.getString("status"));
        assertFalse(report.has("nominalProfit"));
    }

    @Test public void alteredScopeDuplicateFlowAndLookAheadAreRejected() {
        JSONObject evidence = fixture();
        evidence.getJSONArray("closingValues").remove(1);
        assertThrows(IllegalArgumentException.class, () -> calculate(evidence));
        JSONObject duplicate = fixture();
        var flows = duplicate.getJSONArray("externalFlows");
        flows.put(flows.getJSONObject(0));
        assertThrows(IllegalArgumentException.class, () -> calculate(duplicate));
        JSONObject lookAhead = fixture().put("fxSource", "Synthetic rates");
        lookAhead.getJSONArray("fxRates").put(rate("2026-01-31", "2026-02-02", "0.75"));
        assertThrows(IllegalArgumentException.class, () -> calculate(lookAhead));
    }

    @Test public void moneyWeightedAndTimeWeightedUseDifferentCashFlowTreatment() {
        JSONObject evidence = fixture().put("startDate", "2026-01-01").put("endDate", "2026-04-01");
        // 45 days before and 45 days after the deposit. Growth is 10%, then 20%.
        evidence.getJSONArray("closingValues").getJSONObject(0).put("amount", "15600.00");
        evidence.put("preFlowValuations", new org.json.JSONArray("""
                [{"date":"2026-02-15","values":[
                  {"account":"cash","currency":"GBP","amount":"11000.00"},
                  {"account":"broker","currency":"GBP","amount":"0.00"}
                ]}]
                """));
        JSONObject result = calculate(evidence);
        assertEquals("32.0000", result.getJSONObject("timeWeightedReturn").getString("percent"));
        double growth = (Math.sqrt(628000000.0) - 2000) / 20000;
        double expected = (growth * growth - 1) * 100;
        assertEquals(expected, result.getJSONObject("moneyWeightedReturn").getDouble("percent"), 0.00005);
        assertEquals("MISSING_CPI", result.getString("status"));
        evidence.remove("preFlowValuations");
        result = calculate(evidence);
        assertEquals("MISSING_FLOW_VALUATION", result.getJSONObject("timeWeightedReturn").getString("status"));
        assertEquals(expected, result.getJSONObject("moneyWeightedReturn").getDouble("percent"), 0.00005);
    }

    @Test public void closingDateContributionDoesNotCountAsReturn() {
        JSONObject evidence = fixture();
        evidence.getJSONArray("externalFlows").getJSONObject(0).put("date", "2026-03-31");
        JSONObject report = calculate(evidence);
        assertEquals("8.0000", report.getJSONObject("timeWeightedReturn").getString("percent"));
        assertEquals("8.0000", report.getJSONObject("moneyWeightedReturn").getString("percent"));
    }

    @Test public void unusualCashFlowsDoNotChooseAnArbitraryIrr() {
        JSONObject evidence = fixture();
        var flows = evidence.getJSONArray("externalFlows");
        flows.getJSONObject(0).put("amount", "-23000.00");
        flows.put(new JSONObject().put("date", "2026-03-31").put("account", "cash")
                .put("reference", "new deposit").put("currency", "GBP").put("amount", "26000.00"));
        assertEquals("IRR_NOT_UNIQUE_GUARANTEE", calculate(evidence)
                .getJSONObject("moneyWeightedReturn").getString("status"));
        evidence.getJSONArray("closingValues").getJSONObject(0).put("amount", "0.00");
        evidence.put("externalFlows", new org.json.JSONArray());
        JSONObject loss = calculate(evidence);
        assertEquals("NO_IRR", loss.getJSONObject("moneyWeightedReturn").getString("status"));
        assertEquals("-100.0000", loss.getJSONObject("timeWeightedReturn").getString("percent"));
    }

    @Test public void unverifiableIntermediateValuationDoesNotHideNominalOrMoneyWeightedReturn() {
        JSONObject evidence = fixture().put("preFlowValuations", new org.json.JSONArray("""
                [{"date":"2026-02-15","values":[
                  {"account":"cash","currency":"GBP","amount":"11000.00"}
                ]}]
                """));
        JSONObject report = calculate(evidence);
        assertEquals("800.00", report.getString("nominalProfit"));
        assertEquals("AVAILABLE", report.getJSONObject("moneyWeightedReturn").getString("status"));
        assertEquals("INVALID_FLOW_VALUATION", report.getJSONObject("timeWeightedReturn").getString("status"));
    }

    private static JSONObject rate(String date, String observedOn, String rate) {
        return new JSONObject().put("date", date).put("observedOn", observedOn).put("currency", "USD").put("rate", rate);
    }

    private static JSONObject calculate(JSONObject evidence) {
        return PeriodPerformance.calculate(evidence, GENERATION, Currencies.GBP, TODAY);
    }

    private static JSONObject fixture() {
        return new JSONObject("""
                {
                  "version": 1,
                  "userGeneration": "%s",
                  "reportingCurrency": "GBP",
                  "scope": "Test cash and investments",
                  "reconciliationReference": "Test-1",
                  "startDate": "2026-01-31",
                  "endDate": "2026-03-31",
                  "openingValues": [
                    {"account": "cash", "currency": "GBP", "amount": "10000.00"},
                    {"account": "broker", "currency": "GBP", "amount": "0.00"}
                  ],
                  "closingValues": [
                    {"account": "cash", "currency": "GBP", "amount": "12800.00"},
                    {"account": "broker", "currency": "GBP", "amount": "0.00"}
                  ],
                  "externalFlows": [
                    {"reference": "deposit", "date": "2026-02-15", "account": "cash", "currency": "GBP", "amount": "2000.00"}
                  ],
                  "fxRates": [],
                  "cpi": {
                    "source": "Synthetic", "series": "Test CPI", "region": "GB",
                    "observations": [
                      {"month": "2026-01", "index": "100"},
                      {"month": "2026-02", "index": "102"},
                      {"month": "2026-03", "index": "104"}
                    ]
                  }
                }
                """.formatted(GENERATION));
    }
}

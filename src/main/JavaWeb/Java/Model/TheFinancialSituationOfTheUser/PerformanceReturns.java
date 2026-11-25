package Model.TheFinancialSituationOfTheUser;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

final class PerformanceReturns {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private record Term(double time, double logAmount, int sign) {}

    private PerformanceReturns() {}

    static JSONObject moneyWeighted(LocalDate start, LocalDate end, BigDecimal opening, BigDecimal closing,
                                    Map<LocalDate, BigDecimal> flows) {
        if (opening.signum() < 0 || closing.signum() < 0) return status("INVALID_CAPITAL");
        // Investor perspective: contributions are negative and distributions are positive.
        var cash = new TreeMap<LocalDate, BigDecimal>();
        cash.put(start, opening.negate());
        flows.forEach((date, amount) -> cash.put(date, amount.negate()));
        cash.merge(end, closing, BigDecimal::add);
        var terms = new ArrayList<Term>();
        double days = ChronoUnit.DAYS.between(start, end);
        int changes = 0;
        int previous = 0;
        for (var entry : cash.entrySet()) {
            BigDecimal amount = entry.getValue();
            int sign = amount.signum();
            if (sign == 0) continue;
            if (previous != 0 && sign != previous) changes++;
            previous = sign;
            terms.add(new Term(ChronoUnit.DAYS.between(start, entry.getKey()) / days,
                    Math.log(amount.abs().doubleValue()), sign));
        }
        if (changes == 0) return status("NO_IRR");
        // One sign transition guarantees a unique root; more transitions need
        // root isolation or a documented selection rule.
        if (changes > 1) return status("IRR_NOT_UNIQUE_GUARANTEE");
        if (terms.getFirst().sign() != -1) return status("INVALID_CAPITAL");
        double low = -1;
        double high = 1;
        while (low > -1024 && npv(terms, low) < 0) low *= 2;
        while (high < 1024 && npv(terms, high) > 0) high *= 2;
        if (npv(terms, low) < 0 || npv(terms, high) > 0) return status("IRR_OUT_OF_RANGE");
        for (int i = 0; i < 100; i++) {
            double mid = (low + high) / 2;
            if (npv(terms, mid) > 0) low = mid;
            else high = mid;
        }
        double rate = Math.expm1((low + high) / 2);
        if (!Double.isFinite(rate) || rate <= -1) return status("IRR_OUT_OF_RANGE");
        return available(BigDecimal.valueOf(rate));
    }

    private static double npv(ArrayList<Term> terms, double logGrowth) {
        double maximum = Double.NEGATIVE_INFINITY;
        for (Term term : terms) maximum = Math.max(maximum, term.logAmount() - term.time() * logGrowth);
        double total = 0;
        for (Term term : terms) {
            total += term.sign() * Math.exp(term.logAmount() - term.time() * logGrowth - maximum);
        }
        return total;
    }

    static JSONObject timeWeighted(LocalDate end, BigDecimal opening, BigDecimal closing,
                                   NavigableMap<LocalDate, BigDecimal> flows, Map<LocalDate, BigDecimal> beforeFlow) {
        BigDecimal previous = opening;
        BigDecimal growth = BigDecimal.ONE;
        for (var entry : flows.headMap(end, false).entrySet()) {
            BigDecimal current = beforeFlow.get(entry.getKey());
            if (current == null) return status("MISSING_FLOW_VALUATION");
            if (previous.signum() <= 0 || current.signum() < 0) return status("INVALID_CAPITAL");
            growth = growth.multiply(current.divide(previous, PRECISION), PRECISION);
            previous = current.add(entry.getValue());
        }
        // End-of-day convention: the closing value includes the end day's flows.
        BigDecimal terminal = closing.subtract(flows.getOrDefault(end, BigDecimal.ZERO));
        if (previous.signum() <= 0 || terminal.signum() < 0 || closing.signum() < 0)
            return status("INVALID_CAPITAL");
        return available(growth.multiply(terminal.divide(previous, PRECISION), PRECISION).subtract(BigDecimal.ONE));
    }

    static JSONObject status(String status) {
        return new JSONObject().put("status", status);
    }

    private static JSONObject available(BigDecimal fraction) {
        return status("AVAILABLE").put("percent", fraction.movePointRight(2)
                .setScale(4, RoundingMode.HALF_UP).toPlainString());
    }
}

package Model.TheFinancialSituationOfTheUser;

import Model.Tools.Computing;
import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Estimates the inflation allowance on capital recorded in the ledger. */
public final class LedgerPreservation {
    public record Holding(String name, String currency, boolean investment, LocalDate openedOn,
                          LocalDate closedOn, long recordedCapital) {}

    private record CurrencyMonth(String currency, YearMonth month) {}

    private static final class Position {
        private final Holding holding;
        private final TreeMap<LocalDate, Long> changes = new TreeMap<>();
        private long openingCapital;

        private Position(Holding holding) {
            this.holding = holding;
            openingCapital = holding.recordedCapital();
        }
    }

    private final String reportingCurrency;
    private final YearMonth endMonth;
    private final LocalDate start;
    private final LocalDate endExclusive;
    private final Map<String, Map<YearMonth, BigDecimal>> rates;
    private final Map<String, Position> positions = new LinkedHashMap<>();
    private boolean inconsistentLedger;

    public LedgerPreservation(String reportingCurrency, YearMonth lastCompleteMonth, List<Holding> holdings,
                              Map<String, Map<YearMonth, BigDecimal>> rates) {
        this.reportingCurrency = reportingCurrency;
        this.rates = rates;
        endMonth = holdings.stream()
                .filter(h -> overlaps(h, lastCompleteMonth.atDay(1), lastCompleteMonth.plusMonths(1).atDay(1)))
                .map(Holding::currency).distinct()
                .flatMap(currency -> rates.getOrDefault(currency, Map.of()).keySet().stream()
                        .filter(month -> !month.isAfter(lastCompleteMonth)).max(Comparator.naturalOrder()).stream())
                .min(Comparator.naturalOrder()).orElse(lastCompleteMonth);
        start = endMonth.minusMonths(11).atDay(1);
        endExclusive = endMonth.plusMonths(1).atDay(1);
        for (Holding holding : holdings) {
            if (overlaps(holding, start, endExclusive)
                    && positions.putIfAbsent(key(holding.name()), new Position(holding)) != null) {
                inconsistentLedger = true;
            }
        }
    }

    public LocalDate startDate() {
        return start;
    }

    public void addMovement(String account, String currency, String balanceType, LocalDate date, long amount) {
        Position position = positions.get(key(account));
        if (position == null) return;
        Holding holding = position.holding;
        if (!holding.currency().equals(currency) || (holding.closedOn() != null && date.isAfter(holding.closedOn()))) {
            inconsistentLedger = true;
            return;
        }
        long change = switch (balanceType) {
            case "transfer_in" -> amount;
            case "transfer_out" -> Math.negateExact(amount);
            case "income" -> holding.investment() ? 0 : amount;
            case "expense" -> holding.investment() ? 0 : Math.negateExact(amount);
            default -> {
                inconsistentLedger = true;
                yield 0;
            }
        };
        position.openingCapital = Math.subtractExact(position.openingCapital, change);
        if (date.isBefore(endExclusive) && change != 0) {
            // Entries change the balance at the end of their recorded day.
            LocalDate effective = date.isBefore(holding.openedOn()) ? holding.openedOn() : date.plusDays(1);
            position.changes.merge(effective, change, Math::addExact);
        }
    }

    public JSONObject result() {
        JSONObject result = new JSONObject().put("method", "ledger-capital-annual-inflation-v1")
                .put("currency", reportingCurrency).put("startDate", start.toString())
                .put("endDate", endExclusive.minusDays(1).toString()).put("fxBasis", "report-reference-snapshot");
        if (positions.isEmpty()) return result.put("status", "NO_LEDGER");
        if (inconsistentLedger) return result.put("status", "INCONSISTENT_LEDGER");

        var exposure = new TreeMap<CurrencyMonth, BigDecimal>(Comparator.comparing(CurrencyMonth::currency)
                .thenComparing(CurrencyMonth::month));
        for (Position position : positions.values()) addExposure(exposure, position);
        Map<String, BigDecimal> allowances = new LinkedHashMap<>();
        JSONArray missing = new JSONArray();
        exposure.forEach((key, capitalDays) -> {
            if (capitalDays.signum() == 0) return;
            BigDecimal rate = rates.getOrDefault(key.currency(), Map.of()).get(key.month());
            if (rate == null) {
                missing.put(new JSONObject().put("currency", key.currency()).put("month", key.month().toString()));
                return;
            }
            BigDecimal fraction = rate.movePointLeft(2)
                    .divide(BigDecimal.valueOf(key.month().atDay(1).lengthOfYear()), MathContext.DECIMAL128);
            allowances.merge(key.currency(), capitalDays.multiply(fraction), BigDecimal::add);
        });
        if (!missing.isEmpty()) return result.put("status", "MISSING_INFLATION").put("missingInflation", missing);

        long total = 0;
        for (var entry : allowances.entrySet()) {
            long minor = entry.getValue().setScale(0, RoundingMode.HALF_UP).longValueExact();
            total = Math.addExact(total, Computing.convertMinorUnits(minor, entry.getKey(), reportingCurrency));
        }
        return result.put("status", "AVAILABLE").put("requiredPreservationProfit", Computing.fromMinorUnits(total));
    }

    private void addExposure(Map<CurrencyMonth, BigDecimal> exposure, Position position) {
        Holding holding = position.holding;
        LocalDate day = holding.openedOn().isAfter(start) ? holding.openedOn() : start;
        LocalDate end = holding.closedOn() != null && holding.closedOn().isBefore(endExclusive)
                ? holding.closedOn().plusDays(1) : endExclusive;
        long capital = position.openingCapital;
        while (day.isBefore(end)) {
            capital = Math.addExact(capital, position.changes.getOrDefault(day, 0L));
            YearMonth month = YearMonth.from(day);
            LocalDate next = month.plusMonths(1).atDay(1);
            LocalDate nextChange = position.changes.higherKey(day);
            if (nextChange != null && nextChange.isBefore(next)) next = nextChange;
            if (end.isBefore(next)) next = end;
            long exposedCapital = holding.investment() ? Math.max(0, capital) : capital;
            if (exposedCapital != 0) {
                BigDecimal capitalDays = BigDecimal.valueOf(exposedCapital)
                        .multiply(BigDecimal.valueOf(ChronoUnit.DAYS.between(day, next)));
                exposure.merge(new CurrencyMonth(holding.currency(), month), capitalDays, BigDecimal::add);
            }
            day = next;
        }
    }

    private static boolean overlaps(Holding holding, LocalDate start, LocalDate endExclusive) {
        return holding.openedOn().isBefore(endExclusive)
                && (holding.closedOn() == null || !holding.closedOn().isBefore(start));
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}

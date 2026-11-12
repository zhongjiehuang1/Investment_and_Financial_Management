package DatabaseService.Model.Controller.InflationRatesData;

import DatabaseService.DatabaseInit;
import Model.Controller.InflationRatesData.InflationRateException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Known monthly observations compressed into contiguous, equal-rate spans. */
public final class InflationSeries extends DatabaseInit {
    public static final String TABLE = "ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES";
    private record Span(int id, String currency, LocalDate start, LocalDate through, BigDecimal rate) {}

    private InflationSeries() {}

    /** Development bootstrap; production applies the same migration with a maintenance identity. */
    public static void ensureSchema(Connection connection) throws SQLException {
        boolean hasThrough;
        try (ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, TABLE, "OBSERVED_THROUGH")) {
            hasThrough = columns.next();
        }
        try (Statement statement = connection.createStatement()) {
            if (!hasThrough) statement.executeUpdate("ALTER TABLE " + TABLE + " ADD COLUMN OBSERVED_THROUGH DATE NULL");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS INFLATION_SERIES_LOCK (ID INT PRIMARY KEY) ENGINE=InnoDB");
            statement.executeUpdate("INSERT INTO INFLATION_SERIES_LOCK (ID) VALUES (1) ON DUPLICATE KEY UPDATE ID=ID");
        }
        boolean hasUnique = false;
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, TABLE, true, false)) {
            while (indexes.next()) if ("UQ_INFLATION_CURRENCY_DATE".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) hasUnique = true;
        }
        if (!hasUnique) try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE UNIQUE INDEX UQ_INFLATION_CURRENCY_DATE ON " + TABLE + " (VARIOUS_CURRENCIES, DATE)");
        }
    }

    private static BigDecimal rate(double value) {
        if (!Double.isFinite(value) || Math.abs(value) > 999.99) throw new InflationRateException(400, "Inflation rate must be between -999.99 and 999.99.");
        try { return BigDecimal.valueOf(value).setScale(2, RoundingMode.UNNECESSARY); }
        catch (ArithmeticException invalid) { throw new InflationRateException(400, "Inflation rate must have at most two decimal places."); }
    }

    private static LocalDate date(String value) {
        return recordDate(LocalDate.parse(value).withDayOfMonth(15).toString()).toLocalDate();
    }

    private static void lock(Connection connection) throws SQLException {
        // Acquire the shared-series lock before its allocator; startup never takes this lock.
        try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM INFLATION_SERIES_LOCK WHERE ID=1 FOR UPDATE");
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) throw new SQLException("Inflation schema migration is required.");
        }
        lockIdTables(connection, TABLE);
        DatabaseService.RequestIdentity.validateSharedRecords(connection);
    }

    private static List<Span> read(Connection connection) throws SQLException {
        List<Span> spans = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT ID,VARIOUS_CURRENCIES,DATE,COALESCE(OBSERVED_THROUGH,DATE),AIR_IN_VARIOUS_CURRENCIES FROM " + TABLE + " ORDER BY VARIOUS_CURRENCIES,DATE,ID FOR UPDATE");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                Span span = new Span(rows.getInt(1), rows.getString(2), rows.getDate(3).toLocalDate(), rows.getDate(4).toLocalDate(), rows.getBigDecimal(5));
                if (span.start().getDayOfMonth() != 15 || span.through().getDayOfMonth() != 15 || span.through().isBefore(span.start()))
                    throw new SQLException("Inflation dates require migration before editing.");
                spans.add(span);
            }
        }
        return spans;
    }

    private static boolean contains(Span span, String currency, LocalDate date) {
        return span.currency().equals(currency) && !date.isBefore(span.start()) && !date.isAfter(span.through());
    }

    private static List<Span> compact(List<Span> spans) {
        spans.sort(Comparator.comparing(Span::currency).thenComparing(Span::start).thenComparingInt(Span::id));
        List<Span> result = new ArrayList<>();
        for (Span next : spans) {
            Span previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous != null && previous.currency().equals(next.currency())) {
                if (!next.start().isAfter(previous.through())) throw new InflationRateException(409, "This month already has an inflation observation. Edit or delete the existing interval first.");
                if (next.start().equals(previous.through().plusMonths(1)) && previous.rate().compareTo(next.rate()) == 0) {
                    result.set(result.size() - 1, new Span(previous.id(), previous.currency(), previous.start(), next.through(), previous.rate()));
                    continue;
                }
            }
            result.add(next);
        }
        return result;
    }

    private static void save(Connection connection, List<Span> before, List<Span> after) throws SQLException {
        Map<Integer, Span> old = new HashMap<>(), current = new HashMap<>();
        for (Span span : before) old.put(span.id(), span);
        for (Span span : after) current.put(span.id(), span);
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + TABLE + " WHERE ID=?")) {
            for (Span span : before) if (!current.containsKey(span.id())) {
                delete.setInt(1, span.id());
                if (delete.executeUpdate() != 1) throw new SQLException("Inflation deletion did not affect one row.");
                recycleId(connection, TABLE, span.id());
            }
        }
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + TABLE + " SET VARIOUS_CURRENCIES=?,DATE=?,OBSERVED_THROUGH=?,AIR_IN_VARIOUS_CURRENCIES=? WHERE ID=?");
             PreparedStatement insert = connection.prepareStatement("INSERT INTO " + TABLE + " (VARIOUS_CURRENCIES,DATE,OBSERVED_THROUGH,AIR_IN_VARIOUS_CURRENCIES,ID) VALUES (?,?,?,?,?)")) {
            for (Span span : after) {
                if (span.equals(old.get(span.id()))) continue;
                PreparedStatement statement = old.containsKey(span.id()) ? update : insert;
                statement.setString(1, span.currency()); statement.setDate(2, java.sql.Date.valueOf(span.start()));
                statement.setDate(3, java.sql.Date.valueOf(span.through())); statement.setBigDecimal(4, span.rate()); statement.setInt(5, span.id());
                if (statement.executeUpdate() != 1) throw new SQLException("Inflation mutation did not affect one row.");
            }
        }
    }

    @FunctionalInterface
    private interface Mutation { boolean run(Connection connection) throws SQLException; }

    private static boolean transact(Mutation mutation) throws SQLException {
        try (Connection connection = openPrimaryConnection()) {
            try {
                boolean changed = mutation.run(connection);
                connection.commit();
                return changed;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); }
                catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        }
    }

    public static boolean add(double value, String currency, String dateValue) {
        BigDecimal valueExact = rate(value); Model.FixedTypes.Currencies.valueOf(currency); LocalDate month = date(dateValue);
        try {
            return transact(connection -> {
                lock(connection); List<Span> before = read(connection);
                for (Span span : before) if (contains(span, currency, month)) {
                    if (span.rate().compareTo(valueExact) == 0) return true;
                    throw new InflationRateException(409, "This month already has a different inflation rate. Edit or delete the existing interval first.");
                }
                int id = nextId(connection, TABLE);
                List<Span> after = new ArrayList<>(before); after.add(new Span(id, currency, month, month, valueExact));
                List<Span> compacted = compact(after);
                save(connection, before, compacted);
                // Compaction may absorb the allocated row into a pre-existing interval.
                if (compacted.stream().noneMatch(span -> span.id() == id)) recycleId(connection, TABLE, id);
                return true;
            });
        } catch (SQLException failure) { throw new IllegalStateException("Inflation rate could not be added.", failure); }
    }

    public static boolean edit(int id, double value, String currency, String dateValue) {
        BigDecimal valueExact = rate(value); Model.FixedTypes.Currencies.valueOf(currency); LocalDate month = date(dateValue);
        try { return transact(connection -> {
            lock(connection); List<Span> before = read(connection); Span selected = before.stream().filter(row -> row.id() == id).findFirst().orElse(null);
            if (selected == null) return false;
            boolean moved = !selected.currency().equals(currency) || !selected.start().equals(month);
            if (moved && !selected.start().equals(selected.through())) throw new InflationRateException(400, "A multi-month interval cannot change its date or currency. Edit its rate, or delete the interval and add the required months.");
            for (Span other : before) if (other.id() != id && contains(other, currency, month)) throw new InflationRateException(409, "The destination month already has an inflation observation.");
            List<Span> after = new ArrayList<>(before); after.remove(selected); after.add(new Span(id, currency, month, moved ? month : selected.through(), valueExact));
            save(connection, before, compact(after)); return true;
        });
        } catch (SQLException failure) { throw new IllegalStateException("Inflation rate could not be edited.", failure); }
    }

    public static boolean delete(int id) {
        try { return transact(connection -> {
            lock(connection); List<Span> before = read(connection); List<Span> after = new ArrayList<>(before);
            if (!after.removeIf(row -> row.id() == id)) return false;
            save(connection, before, compact(after)); return true;
        });
        } catch (SQLException failure) { throw new IllegalStateException("Inflation rate could not be deleted.", failure); }
    }
}

package DatabaseService.Model.Controller;

import DatabaseService.ShardScope;
import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.*;
import java.util.*;

/** Bounded-window merge of both logical schemas inside their original transaction. */
final class ShardedPage {
    // Both schemas can share one physical MySQL connection. Never keep a
    // streaming ResultSet open while switching to the other schema.
    static final int FETCH_WINDOW = 64;
    @FunctionalInterface interface Decoder { JSONObject decode(ResultSet result, int publicColumns) throws SQLException; }
    private record Order(String expression, boolean descending, boolean numeric, boolean text) { }
    private static List<Order> order(String value) {
        List<String> parts = new ArrayList<>(); int start = 0, depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '(') depth++; else if (c == ')') depth--;
            else if (c == ',' && depth == 0) { parts.add(value.substring(start, i)); start = i + 1; }
        }
        parts.add(value.substring(start));
        List<Order> result = new ArrayList<>();
        for (String part : parts) {
            String expression = part.trim().replaceFirst("(?i)\\s+(ASC|DESC)$", "");
            boolean numeric = expression.equalsIgnoreCase("ID") || expression.toUpperCase(Locale.ROOT).contains("DECIMAL")
                    || expression.equalsIgnoreCase("AIR_IN_VARIOUS_CURRENCIES");
            result.add(new Order(expression, part.trim().toUpperCase(Locale.ROOT).endsWith(" DESC"), numeric,
                    !numeric && !expression.equalsIgnoreCase("DATE")));
        }
        return result;
    }
    private static void bind(PreparedStatement statement, List<Object> values) throws SQLException {
        for (int i = 0; i < values.size(); i++) statement.setObject(i + 1, values.get(i));
    }
    private static final class Cursor implements AutoCloseable {
        private record Row(JSONObject item, Object[] keys) { }
        final int node;
        final Connection connection;
        final String sql;
        final List<Object> parameters;
        final boolean mysql;
        final List<Order> order;
        final Decoder decoder;
        List<Row> window = List.of();
        int next;
        long fetched;
        boolean exhausted, closed;
        Object[] keys;
        JSONObject item;
        Cursor(Connection connection, String columns, String from, List<Object> parameters, List<Order> order, Decoder decoder) throws SQLException {
            node = ShardScope.nodeOf(connection); this.connection = connection;
            this.order = order; this.decoder = decoder; this.parameters = new ArrayList<>(parameters);
            mysql = connection.getMetaData().getDatabaseProductName().contains("MySQL");
            StringBuilder projection = new StringBuilder(columns), ordering = new StringBuilder();
            for (int i = 0; i < order.size(); i++) {
                Order term = order.get(i);
                String expression = term.text ? (mysql ? "CAST(" + term.expression + " AS BINARY)" : "STRINGTOUTF8(" + term.expression + ")") : term.expression;
                projection.append(", ").append(expression).append(" AS __shard_sort_").append(i);
                if (i > 0) ordering.append(", ");
                ordering.append("__shard_sort_").append(i).append(term.descending ? " DESC" : " ASC");
            }
            sql = "SELECT " + projection + from + " ORDER BY " + ordering + " LIMIT ? OFFSET ?";
            advance();
        }
        private void fetchWindow() throws SQLException {
            List<Row> rows = new ArrayList<>(FETCH_WINDOW);
            try (PreparedStatement statement = connection.prepareStatement(sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                statement.setFetchSize(mysql ? Integer.MIN_VALUE : 100);
                bind(statement, parameters);
                statement.setInt(parameters.size() + 1, FETCH_WINDOW);
                statement.setLong(parameters.size() + 2, fetched);
                try (ResultSet result = statement.executeQuery()) {
                    int publicColumns = result.getMetaData().getColumnCount() - order.size();
                    while (result.next()) {
                        JSONObject decoded = decoder.decode(result, publicColumns).put("databaseId", node);
                        if (decoded.has("generation")) decoded.put("recordKey", node + ":" + decoded.getInt("id") + ":" + decoded.getString("generation"));
                        Object[] values = new Object[order.size()];
                        for (int i = 0; i < order.size(); i++) values[i] = order.get(i).text ? result.getBytes(publicColumns + i + 1)
                                : order.get(i).numeric ? result.getBigDecimal(publicColumns + i + 1) : result.getString(publicColumns + i + 1);
                        rows.add(new Row(decoded, values));
                    }
                }
            }
            // Both JDBC resources are closed before this cursor can enter the
            // global merge or another logical schema can prepare a statement.
            fetched = Math.addExact(fetched, rows.size());
            exhausted = rows.size() < FETCH_WINDOW;
            window = rows; next = 0;
        }
        boolean advance() throws SQLException {
            if (closed) throw new SQLException("Pagination cursor is closed");
            if (next == window.size()) {
                window = List.of();
                if (!exhausted) fetchWindow();
                if (window.isEmpty()) { item = null; keys = null; return false; }
            }
            Row row = window.get(next++);
            item = row.item; keys = row.keys;
            return true;
        }
        @Override public void close() throws SQLException { closed = true; window = List.of(); item = null; keys = null; }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static int compare(Cursor left, Cursor right) {
        for (int i = 0; i < left.order.size(); i++) {
            Object a = left.keys[i], b = right.keys[i];
            int value = a == null ? (b == null ? 0 : -1) : b == null ? 1
                    : a instanceof byte[] bytes ? Arrays.compareUnsigned(bytes, (byte[]) b) : ((Comparable) a).compareTo(b);
            if (value != 0) return left.order.get(i).descending ? -Integer.signum(value) : Integer.signum(value);
        }
        return Integer.compare(left.node, right.node);
    }
    static JSONObject read(Connection connection, String columns, String from, List<Object> parameters,
                           String order, int page, int pageSize, Decoder decoder) throws SQLException {
        if (page < 1 || pageSize < 1 || pageSize > ReadData.MAX_PAGE_SIZE)
            throw new IllegalArgumentException("Page must be positive; pageSize must be 1–100.");
        long offset = Math.multiplyExact((long) page - 1, pageSize), total = 0;
        List<Connection> participants = from.contains("ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES")
                ? List.of(connection) : ShardScope.connections(connection);
        for (Connection participant : participants) {
            try (PreparedStatement statement = participant.prepareStatement("SELECT COUNT(*)" + from)) {
                bind(statement, parameters);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) throw new SQLException("Count query returned no result.");
                    total = Math.addExact(total, result.getLong(1));
                }
            }
        }
        JSONArray items = new JSONArray();
        List<Cursor> cursors = new ArrayList<>();
        PriorityQueue<Cursor> queue = new PriorityQueue<>(ShardedPage::compare);
        try {
            if (offset < total) {
                List<Order> terms = order(order);
                for (Connection participant : participants) {
                    Cursor cursor = new Cursor(participant, columns, from, parameters, terms, decoder);
                    cursors.add(cursor); if (cursor.item != null) queue.add(cursor);
                }
                long seen = 0;
                while (!queue.isEmpty() && items.length() < pageSize) {
                    Cursor cursor = queue.remove();
                    if (seen++ >= offset) items.put(cursor.item);
                    if (cursor.advance()) queue.add(cursor);
                }
            }
        } finally {
            SQLException failure = null;
            for (Cursor cursor : cursors) try { cursor.close(); } catch (SQLException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            if (failure != null) throw failure;
        }
        return new JSONObject().put("items", items).put("page", page).put("pageSize", pageSize)
                .put("total", total).put("hasNext", offset + items.length() < total).put("status", "success");
    }
}

package DatabaseService.Model.Controller;

import DatabaseService.ShardScope;
import org.json.JSONArray;
import org.json.JSONObject;
import java.sql.*;
import java.util.*;

/** Bounded-memory merge of both node cursors; pagination is applied after the global ordering. */
final class ShardedPage {
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
        final int node, publicColumns;
        final PreparedStatement statement;
        final ResultSet result;
        final List<Order> order;
        final Decoder decoder;
        Object[] keys;
        JSONObject item;
        Cursor(Connection connection, String columns, String from, List<Object> parameters, List<Order> order, Decoder decoder) throws SQLException {
            node = ShardScope.nodeOf(connection); this.order = order; this.decoder = decoder;
            boolean mysql = connection.getMetaData().getDatabaseProductName().contains("MySQL");
            StringBuilder projection = new StringBuilder(columns), ordering = new StringBuilder();
            for (int i = 0; i < order.size(); i++) {
                Order term = order.get(i);
                String expression = term.text ? (mysql ? "CAST(" + term.expression + " AS BINARY)" : "STRINGTOUTF8(" + term.expression + ")") : term.expression;
                projection.append(", ").append(expression).append(" AS __shard_sort_").append(i);
                if (i > 0) ordering.append(", ");
                ordering.append("__shard_sort_").append(i).append(term.descending ? " DESC" : " ASC");
            }
            statement = connection.prepareStatement("SELECT " + projection + from + " ORDER BY " + ordering,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            try {
                statement.setFetchSize(mysql ? Integer.MIN_VALUE : 100);
                bind(statement, parameters); result = statement.executeQuery();
                publicColumns = result.getMetaData().getColumnCount() - order.size();
                advance();
            } catch (SQLException | RuntimeException failure) { statement.close(); throw failure; }
        }
        boolean advance() throws SQLException {
            if (!result.next()) { item = null; return false; }
            item = decoder.decode(result, publicColumns).put("databaseId", node);
            if (item.has("generation")) item.put("recordKey", node + ":" + item.getInt("id") + ":" + item.getString("generation"));
            keys = new Object[order.size()];
            for (int i = 0; i < order.size(); i++) keys[i] = order.get(i).text ? result.getBytes(publicColumns + i + 1)
                    : order.get(i).numeric ? result.getBigDecimal(publicColumns + i + 1) : result.getString(publicColumns + i + 1);
            return true;
        }
        @Override public void close() throws SQLException { try { result.close(); } finally { statement.close(); } }
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

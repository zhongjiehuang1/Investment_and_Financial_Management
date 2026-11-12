package DatabaseService;

import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Creates or migrates schema during maintenance or development auto-initialization. */
public class CreateAllTables extends DatabaseInit {
    /** Run only against a backed-up maintenance database while incompatible writers are stopped. */
    public static void main(String[] args) {
        new RegisterDriver().registerDriver();
        new CreateAllTables().createAllTables();
        new SetIds().setAllIds();
    }


    void createAllTables() {
        java.lang.String createUSERS = "CREATE TABLE IF NOT EXISTS USERS (" +
                "ID INT PRIMARY KEY, " +
                "FORENAME VARCHAR(50) NOT NULL, " +
                "SURNAME VARCHAR(50) NOT NULL, " +
                "EMAIL_ADDRESS VARCHAR(50) NOT NULL, " +
                "PASSWORD VARCHAR(50) NOT NULL) " +
                "ENGINE=INNODB;",
                createID_RECORDER_users = "CREATE TABLE IF NOT EXISTS ID_RECORDER_USERS (" +
                        "TABLE_ID INT PRIMARY KEY NOT NULL) " +
                        "ENGINE=INNODB;",
                // Inflation observations are entered monthly and compacted into equal-rate spans.
                createANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES = "CREATE TABLE IF NOT EXISTS ANNUAL_INFLATION_RA" +
                        "TES_IN_VARIOUS_CURRENCIES (" +
                        "ID INT PRIMARY KEY, " +
                        "AIR_IN_VARIOUS_CURRENCIES DECIMAL(5,2) NOT NULL, " +
                        "VARIOUS_CURRENCIES VARCHAR(50) NOT NULL, " +
                        "DATE DATE NOT NULL) " +
                        "ENGINE=INNODB;",
                createID_RECORDER_annualInflationRatesInVariousCurrencies = "CREATE TABLE IF NOT EXISTS ID_RECORDER_" +
                        "ANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES (" +
                        "TABLE_ID INT PRIMARY KEY NOT NULL) " +
                        "ENGINE=INNODB;",
                createALL_NON_INVESTMENT_ASSETS = "CREATE TABLE IF NOT EXISTS ALL_NON_INVESTMENT_ASSETS (" +
                        "ID INT PRIMARY KEY, " +
                        "USER_ID INT NOT NULL, " +
                        "NAME VARCHAR(50) NOT NULL, " +
                        "AMOUNT VARCHAR(15) NOT NULL, " +
                        "TYPE VARCHAR(50) NOT NULL, " +
                        "RISK_TYPE VARCHAR(50) NOT NULL, " +
                        "CURRENCY VARCHAR(50) NOT NULL, " +
                        "DATE DATE NOT NULL, " +
                        "FOREIGN KEY(USER_ID) REFERENCES USERS(ID)) " +
                        "ENGINE=INNODB;",
                createID_RECORDER_allNonInvestmentAssets = "CREATE TABLE IF NOT EXISTS ID_RECORDER_ALL_NON_INVESTMEN" +
                        "T_ASSETS (" +
                        "TABLE_ID INT PRIMARY KEY NOT NULL) " +
                        "ENGINE=INNODB;",
                createALL_INVESTMENT_ASSETS = "CREATE TABLE IF NOT EXISTS ALL_INVESTMENT_ASSETS (" +
                        "ID INT PRIMARY KEY, " +
                        "USER_ID INT NOT NULL, " +
                        "INVESTMENT_AMOUNT VARCHAR(15) NOT NULL, " +
                        "CASH_WITHDRAWAL VARCHAR(15) NOT NULL, " +
                        "MANAGEMENT_FEE VARCHAR(15) NOT NULL, " +
                        "HANDLING_FEE VARCHAR(15) NOT NULL, " +
                        "BUY_IN_AMOUNT VARCHAR(15) NOT NULL, " +
                        "AMOUNT_SOLD VARCHAR(15) NOT NULL, " +
                        "REAL_TIME_AMOUNT VARCHAR(15) NOT NULL, " +
                        "INITIAL_EARNINGS DECIMAL(12,2) NOT NULL, " +
                        "INVESTMENT_PLATFORM VARCHAR(50) NOT NULL, " +
                        "RISK_TYPE VARCHAR(50) NOT NULL, " +
                        "INVESTMENT_TYPE VARCHAR(50) NOT NULL, " +
                        "CURRENCY VARCHAR(50) NOT NULL, " +
                        "DATE DATE NOT NULL, " +
                        "FOREIGN KEY(USER_ID) REFERENCES USERS(ID)) " +
                        "ENGINE=INNODB;",
                createID_RECORDER_allInvestmentAssets = "CREATE TABLE IF NOT EXISTS ID_RECORDER_ALL_INVESTMENT_ASSET" +
                        "S (" +
                        "TABLE_ID INT PRIMARY KEY NOT NULL) " +
                        "ENGINE=INNODB;",
                createBASIC_VARIABLES_AND_DATA = "CREATE TABLE IF NOT EXISTS BASIC_VARIABLES_AND_DATA (" +
                        "ID INT PRIMARY KEY, " +
                        "USER_ID INT NOT NULL, " +
                        "ACCUMULATED_AMOUNT DECIMAL(12,2) NOT NULL DEFAULT 0.0, " +
                        "ACTUAL_EARNINGS_BEFORE DECIMAL(12,2) NOT NULL DEFAULT 0.0, " +
                        "OTHER_ACCUMULATED_PASSIVE_INCOME DECIMAL(12,2) NOT NULL DEFAULT 0.0, " +
                        "FOREIGN KEY(USER_ID) REFERENCES USERS(ID)) " +
                        "ENGINE=INNODB;",
                createID_RECORDER_basicVariablesAndData = "CREATE TABLE IF NOT EXISTS ID_RECORDER_BASIC_VARIABLES_AN" +
                        "D_DATA (" +
                        "TABLE_ID INT PRIMARY KEY NOT NULL) " +
                        "ENGINE=INNODB;",
                createINCOME_AND_EXPENDITURE_DATA = "CREATE TABLE IF NOT EXISTS INCOME_AND_EXPENDITURE_DATA (" +
                        "ID INT PRIMARY KEY, " +
                        "USER_ID INT NOT NULL, " +
                        "TYPE VARCHAR(50) NOT NULL, " +
                        "PLATFORM VARCHAR(50) NOT NULL, " +
                        "NAME VARCHAR(150) NOT NULL, " +
                        "AMOUNT VARCHAR(15) NOT NULL, " +
                        "DATE DATE NOT NULL, " +
                        "CURRENCY VARCHAR(50) NOT NULL, " +
                        "FOREIGN KEY(USER_ID) REFERENCES USERS(ID)) " +
                        "ENGINE=INNODB;",
                createID_RECORDER_incomeAndExpenditureData = "CREATE TABLE IF NOT EXISTS ID_RECORDER_INCOME_AND_EXPE" +
                        "NDITURE_DATA (" +
                        "TABLE_ID INT PRIMARY KEY NOT NULL) " +
                        "ENGINE=INNODB;";

        String[] commands = {createUSERS, createID_RECORDER_users,
                createANNUAL_INFLATION_RATES_IN_VARIOUS_CURRENCIES, createID_RECORDER_annualInflationRatesInVariousCurrencies,
                createALL_NON_INVESTMENT_ASSETS, createID_RECORDER_allNonInvestmentAssets,
                createALL_INVESTMENT_ASSETS, createID_RECORDER_allInvestmentAssets,
                createBASIC_VARIABLES_AND_DATA, createID_RECORDER_basicVariablesAndData,
                createINCOME_AND_EXPENDITURE_DATA, createID_RECORDER_incomeAndExpenditureData};
        try (java.sql.Connection connection = openConnection()) {
            for (String command : commands) {
                try (PreparedStatement statement = connection.prepareStatement(command)) { statement.executeUpdate(); }
            }
            DatabaseService.Model.Controller.IncomeAndExpenditureData.Ledger.ensureSchema(connection);
            DatabaseService.Model.Controller.InflationRatesData.InflationSeries.ensureSchema(connection);
            ensureIdSchema(connection);
            verifyIdSchema(connection);
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Database initialization failed.", exception);
        }
    }

    static void ensureIdSchema(java.sql.Connection connection) throws SQLException {
        boolean h2 = "H2".equals(connection.getMetaData().getDatabaseProductName());
        String uuid = h2 ? "RANDOM_UUID()" : "UUID()";
        try (java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ID_ALLOCATION_STATE (TABLE_NAME VARCHAR(64) PRIMARY KEY, HIGH_WATER INT NOT NULL DEFAULT 0) ENGINE=InnoDB");
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS ID_RECOVERY_RANGES (TABLE_NAME VARCHAR(64) NOT NULL, FIRST_ID INT NOT NULL, LAST_ID INT NOT NULL, PRIMARY KEY(TABLE_NAME,FIRST_ID), CHECK(FIRST_ID>0 AND LAST_ID>=FIRST_ID)) ENGINE=InnoDB");
        }
        for (String table : ID_TABLES) {
            boolean generation, nullable = true;
            String defaultValue = null;
            try (java.sql.ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "GENERATION")) {
                generation = columns.next();
                if (generation) {
                    nullable = columns.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls;
                    defaultValue = columns.getString("COLUMN_DEF");
                }
            }
            try (java.sql.Statement statement = connection.createStatement()) {
                if (!generation) statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN GENERATION CHAR(36) NULL");
                statement.executeUpdate("UPDATE " + table + " SET GENERATION=" + uuid + " WHERE GENERATION IS NULL");
                if (!generation || nullable || defaultValue == null) {
                    if (h2) {
                        statement.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN GENERATION SET DEFAULT (" + uuid + ")");
                        statement.executeUpdate("ALTER TABLE " + table + " ALTER COLUMN GENERATION SET NOT NULL");
                    } else {
                        statement.executeUpdate("ALTER TABLE " + table + " MODIFY COLUMN GENERATION CHAR(36) NOT NULL DEFAULT (" + uuid + ")");
                    }
                }
            }
            boolean indexed = false;
            String indexName = "UQ_GEN_" + table;
            try (java.sql.ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
                while (indexes.next()) if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) indexed = true;
            }
            if (!indexed) try (java.sql.Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE UNIQUE INDEX " + indexName + " ON " + table + " (GENERATION)");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO ID_ALLOCATION_STATE (TABLE_NAME,HIGH_WATER) VALUES (?,0) ON DUPLICATE KEY UPDATE TABLE_NAME=TABLE_NAME")) {
                statement.setString(1, table); statement.executeUpdate();
            }
        }
        boolean recordIndex = false;
        try (java.sql.ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, "LEDGER_ENTRIES", false, false)) {
            while (indexes.next()) if ("RECORD_ID".equalsIgnoreCase(indexes.getString("COLUMN_NAME"))
                    && indexes.getInt("ORDINAL_POSITION") == 1) recordIndex = true;
        }
        if (!recordIndex) try (java.sql.Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE INDEX IDX_LEDGER_RECORD_ID ON LEDGER_ENTRIES (RECORD_ID)");
        }
        SetIds.clearReferenceCache();
    }

    /** Runtime validation is read-only and rejects incomplete maintenance migrations before requests start. */
    public static void verifyIdSchema(java.sql.Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT TABLE_NAME,FIRST_ID,LAST_ID FROM ID_RECOVERY_RANGES WHERE 1=0")) {
            statement.executeQuery().close();
        }
        for (String table : ID_TABLES) {
            try (java.sql.ResultSet columns = connection.getMetaData().getColumns(connection.getCatalog(), null, table, "GENERATION")) {
                if (!columns.next() || columns.getInt("NULLABLE") != java.sql.DatabaseMetaData.columnNoNulls)
                    throw new SQLException("Generation must be present and NOT NULL: " + table);
                String expression = columns.getString("COLUMN_DEF");
                if (expression == null || !expression.replaceAll("[()\\s]", "").matches("(?i)(UUID|RANDOM_UUID)"))
                    throw new SQLException("Generation requires a UUID default: " + table);
            }
            java.util.Map<String, java.util.List<String>> unique = new java.util.HashMap<>();
            try (java.sql.ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), null, table, true, false)) {
                while (indexes.next()) {
                    String name = indexes.getString("INDEX_NAME"), column = indexes.getString("COLUMN_NAME");
                    if (name != null) unique.computeIfAbsent(name, ignored -> new java.util.ArrayList<>())
                            .add(column == null ? "" : column);
                }
            }
            if (unique.values().stream().noneMatch(columns -> columns.size() == 1 && "GENERATION".equalsIgnoreCase(columns.get(0))))
                throw new SQLException("Generation requires a single-column unique index: " + table);
            try (PreparedStatement statement = connection.prepareStatement("SELECT ID FROM " + table
                    + " WHERE GENERATION IS NULL OR GENERATION NOT REGEXP '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$' LIMIT 1");
                 java.sql.ResultSet invalid = statement.executeQuery()) {
                if (invalid.next()) throw new SQLException("Invalid generation identity: " + table);
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT HIGH_WATER FROM ID_ALLOCATION_STATE WHERE TABLE_NAME=?")) {
                statement.setString(1, table);
                try (java.sql.ResultSet state = statement.executeQuery()) {
                    if (!state.next() || state.getInt(1) < 0 || state.wasNull()) throw new SQLException("Missing or invalid ID coordination state: " + table);
                }
            }
        }
    }

}

package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.DatabaseSessionFactory;
import com.bgsoftware.common.databasebridge.logger.ILogger;
import com.bgsoftware.common.databasebridge.session.IDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.query.Column;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.common.databasebridge.sql.session.MariaDBDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.session.MySQLDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.session.SQLDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.session.SQLiteDatabaseSession;
import com.bgsoftware.common.databasebridge.sql.transaction.CustomSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.DatabaseTransactionsExecutor;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.api.config.SettingsManager;
import com.bgsoftware.superiorskyblock.api.data.DatabaseFilter;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.core.logging.Debug;
import com.bgsoftware.superiorskyblock.core.logging.Log;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DBSession {

    private static final ILogger LOGGER = new ILogger() {
        @Override
        public void error(String message, Throwable error) {
            Log.error(error, message);
        }

        @Override
        public boolean hasDebugEnabled() {
            return Log.isDebugged(Debug.DATABASE_QUERY);
        }

        @Override
        public void debug(String message) {
            if (hasDebugEnabled())
                Log.debug(Debug.DATABASE_QUERY, message);
        }

        @Override
        public void info(String message) {
            Log.info(message);
        }
    };

    private static SQLDatabaseSession<?> globalSession = null;
    private static SQLDatabaseDialect databaseDialect = SQLDatabaseDialect.MYSQL_LIKE;

    private DBSession() {

    }

    public static boolean isReady() {
        return globalSession != null;
    }

    public static void waitForConnection() {
        if (isReady())
            globalSession.waitForConnection();
    }

    public static boolean createConnection(SuperiorSkyblockPlugin plugin) {
        SQLDatabaseSession<?> session;
        try {
            session = createSessionInternal(plugin, true);
        } catch (Throwable error) {
            LOGGER.error("Failed to create the database session:", error);
            DatabaseTransactionsExecutor.stopActiveExecutors();
            return false;
        }

        if (session.connect()) {
            globalSession = session;
            return true;
        }

        DatabaseTransactionsExecutor.stopActiveExecutors();
        return false;
    }

    public static CompletableFuture<Void> execute(IDatabaseTransaction transaction) {
        return globalSession.execute(transaction);
    }

    public static CompletableFuture<Void> execute(IDatabaseTransaction... transactions) {
        return globalSession.execute(transactions);
    }

    public static CompletableFuture<Void> execute(Collection<IDatabaseTransaction> transactions) {
        return globalSession.execute(transactions);
    }

    public static void createTable(String tableName, Column... columns) {
        if (isReady())
            globalSession.createTable(tableName, columns, getDDLQueryResult("create table " + tableName));
    }

    public static void createIndex(String indexName, String tableName, String... columns) {
        if (isReady())
            globalSession.createIndex(indexName, tableName, columns,
                    getDDLQueryResult("create index " + indexName + " on " + tableName));
    }

    public static void modifyColumnType(String tableName, String columnName, String newType) {
        if (isReady())
            globalSession.modifyColumnType(tableName, columnName, newType,
                    getDDLQueryResult("modify column " + tableName + "." + columnName));
    }

    public static void addColumn(String tableName, String columnName, String type) {
        if (isReady())
            globalSession.addColumn(tableName, columnName, type,
                    getDDLQueryResult("add column " + tableName + "." + columnName));
    }

    public static void removePrimaryKey(String tableName, String columnName) {
        if (isReady())
            globalSession.removePrimaryKey(tableName, columnName,
                    getDDLQueryResult("remove primary key from " + tableName));
    }

    public static void select(String tableName, String filters, QueryResult<ResultSet> queryResult) {
        if (isReady())
            globalSession.select(tableName, filters, queryResult);
    }

    public static void select(String tableName, DatabaseFilter filter, QueryResult<ResultSet> queryResult) {
        if (!isReady())
            return;

        Collection<Pair<String, Object>> databaseFilters = filter == null ?
                Collections.emptyList() : filter.getFilters();
        List<String> filteredColumns = new ArrayList<>(databaseFilters.size());
        List<Object> values = new ArrayList<>(databaseFilters.size());
        for (Pair<String, Object> databaseFilter : databaseFilters) {
            filteredColumns.add(databaseFilter.getKey());
            values.add(databaseFilter.getValue());
        }

        String query = String.format("SELECT * FROM {prefix}%s%s;", tableName,
                databaseDialect.createColumnFilter(filteredColumns));
        globalSession.customQuery(query, new QueryResult<PreparedStatement>().onSuccess(preparedStatement -> {
            for (int i = 0; i < values.size(); ++i)
                preparedStatement.setObject(i + 1, values.get(i));

            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                queryResult.complete(resultSet);
            }
        }).onFail(queryResult::fail));
    }

    public static void setJournalMode(String jounralMode, QueryResult<ResultSet> queryResult) {
        if (isReady())
            globalSession.setJournalMode(jounralMode, queryResult);
    }

    public static void customQuery(String query, QueryResult<PreparedStatement> queryResult) {
        if (isReady())
            globalSession.customQuery(query, queryResult);
    }

    public static void close() {
        if (isReady()) {
            try {
                DatabaseTransactionsExecutor.stopActiveExecutors();
                globalSession.close();
            } finally {
                globalSession = null;
                databaseDialect = SQLDatabaseDialect.MYSQL_LIKE;
            }
        }
    }

    public static String getColumnFilter(DatabaseFilter filter) {
        if (filter == null)
            return "";

        List<String> filteredColumns = new ArrayList<>();
        filter.forEach((column, value) -> filteredColumns.add(column));
        return databaseDialect.createColumnFilter(filteredColumns);
    }

    public static CustomSQLDatabaseTransaction createInsertTransaction(String tableName, String... columnNames) {
        return createInsertTransaction(tableName, Arrays.asList(columnNames));
    }

    public static CustomSQLDatabaseTransaction createInsertTransaction(String tableName,
                                                                        Collection<String> columnNames) {
        return databaseDialect.createInsertTransaction(tableName, columnNames);
    }

    public static CustomSQLDatabaseTransaction createUpdateTransaction(String tableName,
                                                                        Collection<String> columnNames,
                                                                        Collection<String> filteredColumns) {
        return databaseDialect.createUpdateTransaction(tableName, columnNames, filteredColumns);
    }

    public static CustomSQLDatabaseTransaction createDeleteTransaction(String tableName,
                                                                        Collection<String> filteredColumns) {
        return databaseDialect.createDeleteTransaction(tableName, filteredColumns);
    }

    public static String quoteIdentifier(String identifier) {
        return databaseDialect.quoteIdentifier(identifier);
    }

    public static boolean isPostgreSQL() {
        return databaseDialect.isPostgreSQL();
    }

    public static void updateDatabaseVersion(int databaseVersion) {
        if (!isPostgreSQL() || !(globalSession instanceof PostgreSQLDatabaseSession))
            throw new IllegalStateException("The active database session is not PostgreSQL.");

        ((PostgreSQLDatabaseSession) globalSession).updateDatabaseVersion(databaseVersion);
    }

    private static QueryResult<Void> getDDLQueryResult(String operation) {
        if (!isPostgreSQL())
            return QueryResult.EMPTY_VOID_QUERY_RESULT;

        return new QueryResult<Void>().onFail(error -> {
            throw new IllegalStateException("Failed to " + operation + " in PostgreSQL.", error);
        });
    }

    private static SQLDatabaseSession<?> createSessionInternal(SuperiorSkyblockPlugin plugin, boolean logging) {
        SettingsManager.Database database = plugin.getSettings().getDatabase();

        IDatabaseSession.Args args;
        SQLDatabaseSession<?> session;
        switch (database.getType()) {
            case "MYSQL":
                databaseDialect = SQLDatabaseDialect.MYSQL_LIKE;
                args = new MySQLDatabaseSession.Args(database.getAddress(), database.getPort(), database.getDBName(),
                        database.getUsername(), database.getPassword(), database.getPrefix(), database.hasSSL(),
                        database.hasPublicKeyRetrieval(), database.getWaitTimeout(), database.getMaxLifetime(),
                        "SuperiorSkyblock Database Thread", LOGGER);
                break;
            case "MARIADB":
                databaseDialect = SQLDatabaseDialect.MYSQL_LIKE;
                args = new MariaDBDatabaseSession.Args(database.getAddress(), database.getPort(), database.getDBName(),
                        database.getUsername(), database.getPassword(), database.getPrefix(), database.hasSSL(),
                        database.hasPublicKeyRetrieval(), database.getWaitTimeout(), database.getMaxLifetime(),
                        "SuperiorSkyblock Database Thread", LOGGER);
                break;
            case "POSTGRES":
            case "POSTGRESQL":
                databaseDialect = SQLDatabaseDialect.POSTGRESQL;
                session = new PostgreSQLDatabaseSession(new PostgreSQLDatabaseSession.Args(database.getAddress(),
                        database.getPort(), database.getDBName(), database.getUsername(), database.getPassword(),
                        database.getPrefix(), database.hasSSL(), database.getWaitTimeout(), database.getMaxLifetime(),
                        "SuperiorSkyblock Database Thread", LOGGER));
                if (logging)
                    session.setLogging(true);
                return session;
            default:
                databaseDialect = SQLDatabaseDialect.MYSQL_LIKE;
                File databaseFile = new File(plugin.getDataFolder(), "datastore/database.db");
                args = new SQLiteDatabaseSession.Args(databaseFile,
                        "SuperiorSkyblock Database Thread", LOGGER);
                break;
        }

        session = (SQLDatabaseSession<?>) DatabaseSessionFactory.createSession(args);
        if (logging)
            session.setLogging(true);
        return session;
    }

}


package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.logger.ILogger;
import com.bgsoftware.common.databasebridge.sql.query.Column;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.common.databasebridge.sql.session.SQLDatabaseSession;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;

final class PostgreSQLDatabaseSession extends SQLDatabaseSession<PostgreSQLDatabaseSession.Args> {

    PostgreSQLDatabaseSession(Args args) {
        super(args);
    }

    @Override
    public boolean connect() {
        if (this.logging)
            this.args.getLogger().info("Trying to connect to remote database (PostgreSQL)...");

        HikariDataSource createdDataSource = null;
        try {
            HikariConfig config = new HikariConfig();
            config.setConnectionTestQuery("SELECT 1");
            config.setPoolName("SuperiorSkyblock PostgreSQL Pool");
            config.setDriverClassName("org.postgresql.Driver");
            config.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s?sslmode=%s",
                    this.args.address, this.args.port, this.args.dbName,
                    this.args.useSSL ? "require" : "disable"));
            config.setUsername(this.args.userName);
            config.setPassword(this.args.password);
            // The database role allows few connections, and SuperiorSkyblock writes from a single thread.
            config.setMaximumPoolSize(this.args.poolSize);
            config.setMinimumIdle(Math.min(2, this.args.poolSize));
            config.setConnectionTimeout(10000);
            config.setIdleTimeout(this.args.waitTimeout);
            config.setMaxLifetime(this.args.maxLifetime);
            // Seconds. Writes run on one thread, so a stalled connection without a socket timeout would block
            // every later write.
            config.addDataSourceProperty("connectTimeout", "10");
            config.addDataSourceProperty("socketTimeout", "30");
            config.addDataSourceProperty("ApplicationName", "SuperiorSkyblock2");

            createdDataSource = new HikariDataSource(config);
            this.dataSource = createdDataSource;

            if (this.logging)
                this.args.getLogger().info("Successfully established connection with remote PostgreSQL database!");

            this.ready.complete(null);
            return true;
        } catch (Throwable error) {
            if (createdDataSource != null)
                createdDataSource.close();
            this.dataSource = null;
            this.args.getLogger().error("An unexpected error occurred while connecting to PostgreSQL database:", error);
            return false;
        }
    }

    @Override
    public void createTable(String tableName, Column[] columns, QueryResult<Void> queryResult) {
        StringBuilder columnsSection = new StringBuilder();
        for (Column column : columns) {
            if (columnsSection.length() != 0)
                columnsSection.append(',');
            columnsSection.append(SQLDatabaseDialect.POSTGRESQL.quoteIdentifier(column.getName()))
                    .append(' ')
                    .append(prepareColumnType(column.getValue()));
        }

        executeUpdate(String.format("CREATE TABLE IF NOT EXISTS %s%s (%s);",
                this.args.getDbPrefix(), tableName, columnsSection), queryResult);
    }

    @Override
    public void customQuery(String statement, QueryResult<PreparedStatement> queryResult) {
        withPreparedStatement(statement, queryResult, queryResult::complete);
    }

    @Override
    public void executeUpdate(String statement, QueryResult<Void> queryResult) {
        withPreparedStatement(statement, queryResult, preparedStatement -> {
            preparedStatement.executeUpdate();
            queryResult.complete(null);
        });
    }

    @Override
    public void executeQuery(String statement, QueryResult<ResultSet> queryResult) {
        withPreparedStatement(statement, queryResult, preparedStatement -> {
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                queryResult.complete(resultSet);
            }
        });
    }

    @Override
    public void renameTable(String tableName, String newName, QueryResult<Void> queryResult) {
        executeUpdate(String.format("ALTER TABLE %s%s RENAME TO %s%s;", this.args.getDbPrefix(), tableName,
                this.args.getDbPrefix(), newName), queryResult);
    }

    @Override
    public void createIndex(String indexName, String tableName, String[] columns, QueryResult<Void> queryResult) {
        StringBuilder columnsSection = new StringBuilder();
        for (String column : columns) {
            if (columnsSection.length() != 0)
                columnsSection.append(',');
            columnsSection.append(SQLDatabaseDialect.POSTGRESQL.quoteIdentifier(column));
        }

        String prefixedIndexName = this.args.getDbPrefix() + indexName;
        executeUpdate(String.format("CREATE UNIQUE INDEX IF NOT EXISTS %s ON %s%s (%s);",
                SQLDatabaseDialect.POSTGRESQL.quoteIdentifier(prefixedIndexName), this.args.getDbPrefix(), tableName,
                columnsSection), queryResult);
    }

    @Override
    public void modifyColumnType(String tableName, String columnName, String newType, QueryResult<Void> queryResult) {
        String quotedColumnName = SQLDatabaseDialect.POSTGRESQL.quoteIdentifier(columnName);
        String columnType = prepareColumnType(newType);
        executeUpdate(String.format("ALTER TABLE %s%s ALTER COLUMN %s TYPE %s USING %s::%s;",
                this.args.getDbPrefix(), tableName, quotedColumnName, columnType, quotedColumnName, columnType), queryResult);
    }

    @Override
    public void addColumn(String tableName, String columnName, String type, QueryResult<Void> queryResult) {
        executeUpdate(String.format("ALTER TABLE %s%s ADD COLUMN IF NOT EXISTS %s %s;", this.args.getDbPrefix(), tableName,
                SQLDatabaseDialect.POSTGRESQL.quoteIdentifier(columnName), prepareColumnType(type)), queryResult);
    }

    @Override
    public void removePrimaryKey(String tableName, String columnName, QueryResult<Void> queryResult) {
        if (this.dataSource == null)
            throw new IllegalStateException("Session was not initialized.");

        String actualTableName = (this.args.getDbPrefix() + tableName).toLowerCase(Locale.ENGLISH);
        try (Connection connection = this.dataSource.getConnection()) {
            DatabaseMetaData databaseMetaData = connection.getMetaData();
            try (ResultSet primaryKeys = databaseMetaData.getPrimaryKeys(connection.getCatalog(), connection.getSchema(),
                    actualTableName)) {
                if (!primaryKeys.next()) {
                    queryResult.complete(null);
                    return;
                }

                String primaryKeyName = primaryKeys.getString("PK_NAME");
                if (primaryKeyName == null || primaryKeyName.isEmpty())
                    primaryKeyName = actualTableName + "_pkey";
                executeUpdate(String.format("ALTER TABLE %s DROP CONSTRAINT IF EXISTS %s;", actualTableName,
                        SQLDatabaseDialect.POSTGRESQL.quoteIdentifier(primaryKeyName)), queryResult);
            }
        } catch (SQLException error) {
            queryResult.fail(error);
        }
    }

    @Override
    public void setJournalMode(String journalMode, QueryResult<ResultSet> queryResult) {
        try {
            queryResult.complete(null);
        } catch (SQLException error) {
            queryResult.fail(error);
        }
    }

    void updateDatabaseVersion(int databaseVersion) {
        if (this.dataSource == null)
            throw new IllegalStateException("Session was not initialized.");

        String tableName = this.args.getDbPrefix() + "ssb_metadata";
        try (Connection connection = this.dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            try {
                connection.setAutoCommit(false);
                try (PreparedStatement deleteStatement = connection.prepareStatement(
                        "DELETE FROM " + tableName + ";");
                     PreparedStatement insertStatement = connection.prepareStatement(
                             "INSERT INTO " + tableName + " (\"version\") VALUES (?);")) {
                    deleteStatement.executeUpdate();
                    insertStatement.setInt(1, databaseVersion);
                    insertStatement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Failed to update the PostgreSQL database version.", error);
        }
    }

    static String prepareQuery(String statement, String databasePrefix) {
        return statement.replace("{prefix}", databasePrefix);
    }

    static String prepareColumnType(String columnDefinition) {
        int tokenEnd = 0;
        while (tokenEnd < columnDefinition.length() && !Character.isWhitespace(columnDefinition.charAt(tokenEnd)))
            ++tokenEnd;

        String logicalType = columnDefinition.substring(0, tokenEnd);
        String mappedType;
        switch (logicalType) {
            case "BIG_DECIMAL":
            case "LONGTEXT":
                mappedType = "TEXT";
                break;
            case "LONG_UNIQUE_TEXT":
                mappedType = "VARCHAR(255)";
                break;
            case "UNIQUE_TEXT":
                // MySQL keeps these short to fit its index key limit. PostgreSQL has no such limit, and a
                // namespaced block or privilege name longer than 30 characters must not fail the write.
                mappedType = "VARCHAR(255)";
                break;
            case "LONGBLOB":
            case "BLOB":
                mappedType = "BYTEA";
                break;
            case "UUID":
                mappedType = "VARCHAR(36)";
                break;
            case "DECIMAL":
                mappedType = "DECIMAL(10, 2)";
                break;
            default:
                mappedType = logicalType;
                break;
        }

        return mappedType + columnDefinition.substring(tokenEnd);
    }

    private void withPreparedStatement(String statement, QueryResult<?> queryResult,
                                       PreparedStatementCallback callback) {
        if (this.dataSource == null)
            throw new IllegalStateException("Session was not initialized.");

        String query = prepareQuery(statement, this.args.getDbPrefix());

        if (this.args.getLogger().hasDebugEnabled())
            this.args.getLogger().debug(query);

        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {
            callback.accept(preparedStatement);
        } catch (SQLException error) {
            queryResult.fail(error);
        }
    }

    private interface PreparedStatementCallback {

        void accept(PreparedStatement preparedStatement) throws SQLException;

    }

    static final class Args extends SQLDatabaseSession.Args {

        private final String address;
        private final String dbName;
        private final String userName;
        private final String password;
        private final int port;
        private final boolean useSSL;
        private final long waitTimeout;
        private final long maxLifetime;
        private final int poolSize;

        Args(String address, int port, String dbName, String userName, String password, String prefix,
             boolean useSSL, long waitTimeout, long maxLifetime, int poolSize, String databaseThreadName,
             ILogger logger) {
            super(validatePrefix(prefix), databaseThreadName, logger);
            this.address = address;
            this.dbName = dbName;
            this.userName = userName;
            this.password = password;
            this.port = port;
            this.useSSL = useSSL;
            this.waitTimeout = waitTimeout;
            this.maxLifetime = maxLifetime;
            this.poolSize = Math.max(1, poolSize);
        }

        private static String validatePrefix(String prefix) {
            if (prefix == null)
                throw new IllegalArgumentException("PostgreSQL database prefix cannot be null.");

            if (!prefix.isEmpty() && !prefix.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("PostgreSQL database prefix must be empty or a valid SQL " +
                        "identifier prefix containing only letters, digits and underscores.");
            }

            return prefix;
        }

    }

}

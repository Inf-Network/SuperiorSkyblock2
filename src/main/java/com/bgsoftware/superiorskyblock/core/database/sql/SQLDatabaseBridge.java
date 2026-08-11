package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.annotations.Nullable;
import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.common.databasebridge.sql.transaction.CustomSQLDatabaseTransaction;
import com.bgsoftware.common.databasebridge.transaction.IDatabaseTransaction;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridge;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridgeMode;
import com.bgsoftware.superiorskyblock.api.data.DatabaseFilter;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.core.logging.Log;

import java.sql.ResultSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public class SQLDatabaseBridge implements DatabaseBridge {

    private DatabaseBridgeMode databaseBridgeMode = DatabaseBridgeMode.IDLE;
    private List<IDatabaseTransaction> batchTransactions = null;

    public SQLDatabaseBridge() {

    }

    @Override
    public void loadAllObjects(String table, Consumer<Map<String, Object>> resultConsumer) {
        DBSession.select(table, "", new QueryResult<ResultSet>().onSuccess(resultSet -> {
            while (resultSet.next()) {
                try {
                    resultConsumer.accept(new ResultSetMapBridge(resultSet));
                } catch (Exception error) {
                    Log.entering("ENTER", table);
                    Log.error(error, "An unexpected error occurred while loading data from database:");
                }
            }
        }).onFail(QueryResult.PRINT_ERROR));
    }

    @Override
    public void batchOperations(boolean batchOperations) {
        if (batchOperations) {
            batchTransactions = new LinkedList<>();
        } else if (batchTransactions != null) {
            DBSession.execute(batchTransactions);
            batchTransactions = null;
        }
    }

    @Override
    public void updateObject(String table, @Nullable DatabaseFilter filter, Pair<String, Object>[] columns) {
        if (databaseBridgeMode != DatabaseBridgeMode.SAVE_DATA)
            return;

        List<String> filteredColumns = filter == null ? null : new LinkedList<>();
        List<String> columnNames = new LinkedList<>();
        List<Object> values = new LinkedList<>();

        for (Pair<String, Object> column : columns) {
            columnNames.add(column.getKey());
            values.add(column.getValue());
        }

        if (filter != null) {
            filter.forEach((column, value) -> {
                filteredColumns.add(column);
                values.add(value);
            });
        }

        CustomSQLDatabaseTransaction transaction = DBSession.createUpdateTransaction(table, columnNames,
                filteredColumns);
        transaction.bindObjects(values);
        submitTransaction(transaction);
    }

    @Override
    public void insertObject(String table, Pair<String, Object>... columns) {
        if (databaseBridgeMode != DatabaseBridgeMode.SAVE_DATA)
            return;

        List<String> columnNames = new LinkedList<>();
        List<Object> values = new LinkedList<>();

        for (Pair<String, Object> column : columns) {
            columnNames.add(column.getKey());
            values.add(column.getValue());
        }

        CustomSQLDatabaseTransaction transaction = DBSession.createInsertTransaction(table, columnNames);
        transaction.bindObjects(values);
        submitTransaction(transaction);
    }

    @Override
    public void deleteObject(String table, @Nullable DatabaseFilter filter) {
        if (databaseBridgeMode != DatabaseBridgeMode.SAVE_DATA)
            return;

        List<String> filteredColumns = filter == null ? null : new LinkedList<>();
        List<Object> values = new LinkedList<>();

        if (filter != null) {
            filter.forEach((column, value) -> {
                filteredColumns.add(column);
                values.add(value);
            });
        }

        CustomSQLDatabaseTransaction transaction = DBSession.createDeleteTransaction(table, filteredColumns);
        transaction.bindObjects(values);

        submitTransaction(transaction);
    }

    @Override
    public void loadObject(String table, DatabaseFilter filter, Consumer<Map<String, Object>> resultConsumer) {
        DBSession.select(table, filter, new QueryResult<ResultSet>().onSuccess(resultSet -> {
            while (resultSet.next()) {
                try {
                    resultConsumer.accept(new ResultSetMapBridge(resultSet));
                } catch (Exception error) {
                    Log.entering("ENTER", table, filter);
                    Log.error(error, "An unexpected error occurred while loading data from database:");
                }
            }
        }).onFail(QueryResult.PRINT_ERROR));
    }

    @Override
    public void setDatabaseBridgeMode(DatabaseBridgeMode databaseBridgeMode) {
        this.databaseBridgeMode = databaseBridgeMode;
    }

    @Override
    public DatabaseBridgeMode getDatabaseBridgeMode() {
        return this.databaseBridgeMode;
    }

    private void submitTransaction(IDatabaseTransaction transaction) {
        if (batchTransactions != null) {
            batchTransactions.add(transaction);
        } else {
            DBSession.execute(transaction);
        }
    }

}

package com.bgsoftware.superiorskyblock.core.database.loader.sql;

import com.bgsoftware.common.databasebridge.sql.query.QueryResult;
import com.bgsoftware.superiorskyblock.SuperiorSkyblockPlugin;
import com.bgsoftware.superiorskyblock.core.database.bridge.GridDatabaseBridge;
import com.bgsoftware.superiorskyblock.core.database.loader.MachineStateDatabaseLoader;
import com.bgsoftware.superiorskyblock.core.database.sql.DBSession;
import com.bgsoftware.superiorskyblock.core.errors.ManagerLoadException;

import java.sql.ResultSet;

public class SQLDatabaseLoader extends MachineStateDatabaseLoader {

    private final SuperiorSkyblockPlugin plugin;

    public SQLDatabaseLoader(SuperiorSkyblockPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void setState(State state) throws ManagerLoadException {
        if (plugin.getFactory().hasCustomDatabaseBridge())
            return;

        try {
            super.setState(state);
        } catch (ManagerLoadException error) {
            if (DBSession.isReady())
                DBSession.close();
            throw error;
        }
    }

    @Override
    protected void handleInitialize() throws ManagerLoadException {
        if (!DBSession.createConnection(plugin)) {
            throw new ManagerLoadException("Couldn't connect to the database.\nMake sure all information is correct.",
                    ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN);
        }

        try {
            DBSession.select("ssb_metadata", "", new QueryResult<ResultSet>().onSuccess(resultSet -> {
                if (!resultSet.next())
                    return;

                int databaseVersion = resultSet.getInt("version");

                if (databaseVersion > SQLDatabase.getCurrentDatabaseVersion())
                    throw new IllegalStateException("Database is in newer version: " + databaseVersion + " > " + SQLDatabase.getCurrentDatabaseVersion());
            }).onFail(error -> {
            }));
        } catch (IllegalStateException error) {
            DBSession.close();
            throw new ManagerLoadException(error.getMessage(), ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN);
        }

        SQLDatabase.UpgradeResult databaseUpgradeResult;
        try {
            databaseUpgradeResult = SQLDatabase.upgradeDatabase();
            SQLDatabase.initializeDatabase();
        } catch (IllegalStateException error) {
            DBSession.close();
            throw new ManagerLoadException(error.getMessage(), ManagerLoadException.ErrorLevel.SERVER_SHUTDOWN);
        }

        if (databaseUpgradeResult.isUpgraded()) {
            if (DBSession.isPostgreSQL())
                DBSession.updateDatabaseVersion(databaseUpgradeResult.getDatabaseVersion());
            else
                GridDatabaseBridge.updateVersion(plugin.getGrid(), databaseUpgradeResult.getDatabaseVersion());
        }

        DBSession.select("grid", "", new QueryResult<ResultSet>()
                .onSuccess(resultSet -> {
                    if (!resultSet.next())
                        GridDatabaseBridge.insertGrid(plugin.getGrid());
                })
                .onFail(error -> GridDatabaseBridge.insertGrid(plugin.getGrid())));
    }

    @Override
    protected void handlePostInitialize() {
        SQLDatabase.createIndexes();
    }

    @Override
    protected void handlePreLoadData() {
        DBSession.setJournalMode("MEMORY", QueryResult.EMPTY_QUERY_RESULT);
    }

    @Override
    protected void handlePostLoadData() {
        DBSession.setJournalMode("DELETE", QueryResult.EMPTY_QUERY_RESULT);
    }

    @Override
    protected void handleShutdown() {

        DBSession.close();
    }

}

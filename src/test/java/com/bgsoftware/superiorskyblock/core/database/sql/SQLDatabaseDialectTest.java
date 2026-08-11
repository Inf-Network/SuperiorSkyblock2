package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.sql.transaction.CustomSQLDatabaseTransaction;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SQLDatabaseDialectTest {

    @Test
    public void testMySQLLikeInsertKeepsReplaceSemantics() {
        CustomSQLDatabaseTransaction transaction = SQLDatabaseDialect.MYSQL_LIKE.createInsertTransaction(
                "players", Arrays.asList("uuid", "last_used_name"));

        assertEquals("REPLACE INTO {prefix}players (`uuid`,`last_used_name`) VALUES(?,?);",
                transaction.buildQuery());
    }

    @Test
    public void testPostgreSQLCompositeUpsert() {
        CustomSQLDatabaseTransaction transaction = SQLDatabaseDialect.POSTGRESQL.createInsertTransaction(
                "islands_chests", Arrays.asList("island", "index", "contents"));

        assertEquals("INSERT INTO {prefix}islands_chests (\"island\",\"index\",\"contents\") VALUES(?,?,?) " +
                        "ON CONFLICT (\"island\",\"index\") DO UPDATE SET \"contents\"=EXCLUDED.\"contents\";",
                transaction.buildQuery());
    }

    @Test
    public void testPostgreSQLAppendOnlyInsertHasNoConflictClause() {
        CustomSQLDatabaseTransaction transaction = SQLDatabaseDialect.POSTGRESQL.createInsertTransaction(
                "bank_transactions", Arrays.asList("island", "amount"));

        assertEquals("INSERT INTO {prefix}bank_transactions (\"island\",\"amount\") VALUES(?,?);",
                transaction.buildQuery());
        assertFalse(transaction.buildQuery().contains("ON CONFLICT"));
    }

    @Test
    public void testPostgreSQLUpdateAndDeleteQuoteIdentifiers() {
        CustomSQLDatabaseTransaction updateTransaction = SQLDatabaseDialect.POSTGRESQL.createUpdateTransaction(
                "islands_block_limits", Collections.singletonList("limit"),
                Arrays.asList("island", "block"));
        CustomSQLDatabaseTransaction deleteTransaction = SQLDatabaseDialect.POSTGRESQL.createDeleteTransaction(
                "islands_chests", Arrays.asList("island", "index"));

        assertEquals("UPDATE {prefix}islands_block_limits SET \"limit\"=? WHERE \"island\"=? AND \"block\"=?;",
                updateTransaction.buildQuery());
        assertEquals("DELETE FROM {prefix}islands_chests WHERE \"island\"=? AND \"index\"=?;",
                deleteTransaction.buildQuery());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPostgreSQLUpsertRequiresAllConflictColumns() {
        SQLDatabaseDialect.POSTGRESQL.createInsertTransaction("islands_chests",
                Arrays.asList("island", "contents"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPostgreSQLRejectsUnregisteredInsertMode() {
        SQLDatabaseDialect.POSTGRESQL.createInsertTransaction("extension_table",
                Arrays.asList("id", "value"));
    }

    @Test
    public void testPostgreSQLQueryPreparationOnlyReplacesPrefix() {
        assertEquals("SELECT * FROM UUID_BLOB_example WHERE marker='UUID BLOB DECIMAL';",
                PostgreSQLDatabaseSession.prepareQuery(
                        "SELECT * FROM {prefix}example WHERE marker='UUID BLOB DECIMAL';", "UUID_BLOB_"));
    }

    @Test
    public void testPostgreSQLColumnTypeTokens() {
        assertEquals("VARCHAR(36) PRIMARY KEY", PostgreSQLDatabaseSession.prepareColumnType("UUID PRIMARY KEY"));
        assertEquals("TEXT", PostgreSQLDatabaseSession.prepareColumnType("BIG_DECIMAL"));
        assertEquals("TEXT", PostgreSQLDatabaseSession.prepareColumnType("LONGTEXT"));
        assertEquals("BYTEA", PostgreSQLDatabaseSession.prepareColumnType("LONGBLOB"));
        assertEquals("BYTEA", PostgreSQLDatabaseSession.prepareColumnType("BLOB"));
        assertEquals("VARCHAR(255)", PostgreSQLDatabaseSession.prepareColumnType("LONG_UNIQUE_TEXT"));
        assertEquals("VARCHAR(30)", PostgreSQLDatabaseSession.prepareColumnType("UNIQUE_TEXT"));
        assertEquals("DECIMAL(10, 2)", PostgreSQLDatabaseSession.prepareColumnType("DECIMAL"));
        assertEquals("INTEGER DEFAULT 4", PostgreSQLDatabaseSession.prepareColumnType("INTEGER DEFAULT 4"));
        assertEquals("VARCHAR(7)", PostgreSQLDatabaseSession.prepareColumnType("VARCHAR(7)"));
    }

    @Test
    public void testPostgreSQLIdentifierCaseSemantics() {
        assertEquals("\"mixedcase\"", SQLDatabaseDialect.POSTGRESQL.quoteIdentifier("MixedCase"));
        assertEquals("\"MixedCase\"", SQLDatabaseDialect.POSTGRESQL.quoteIdentifier("\"MixedCase\""));
        assertEquals("`MixedCase`", SQLDatabaseDialect.MYSQL_LIKE.quoteIdentifier("MixedCase"));
    }

}

package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.sql.transaction.CustomSQLDatabaseTransaction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SQLDatabaseDialect {

    static final SQLDatabaseDialect MYSQL_LIKE = new SQLDatabaseDialect(false, '`');
    static final SQLDatabaseDialect POSTGRESQL = new SQLDatabaseDialect(true, '"');

    private static final Map<String, List<String>> POSTGRESQL_CONFLICT_COLUMNS = createConflictColumns();

    private final boolean postgresql;
    private final char identifierQuote;

    private SQLDatabaseDialect(boolean postgresql, char identifierQuote) {
        this.postgresql = postgresql;
        this.identifierQuote = identifierQuote;
    }

    CustomSQLDatabaseTransaction createInsertTransaction(String tableName, Collection<String> columnNames) {
        if (columnNames.isEmpty())
            throw new IllegalArgumentException("Cannot create an insert transaction without columns.");

        List<String> columns = new ArrayList<>(columnNames);
        StringBuilder query = new StringBuilder();

        query.append(this.postgresql ? "INSERT INTO " : "REPLACE INTO ")
                .append("{prefix}").append(tableName).append(" (")
                .append(joinQuoted(columns)).append(") VALUES(")
                .append(createPlaceholders(columns.size())).append(")");

        if (this.postgresql) {
            String canonicalTableName = canonicalIdentifier(tableName);
            if (!POSTGRESQL_CONFLICT_COLUMNS.containsKey(canonicalTableName)) {
                throw new IllegalArgumentException("Table " + tableName + " is not supported by the built-in " +
                        "PostgreSQL dialect. Add an APPEND or UPSERT strategy to SQLDatabaseDialect first.");
            }

            List<String> conflictColumns = POSTGRESQL_CONFLICT_COLUMNS.get(canonicalTableName);
            if (!conflictColumns.isEmpty()) {
                Set<String> normalizedColumnSet = new HashSet<>();
                for (String columnName : columns)
                    normalizedColumnSet.add(canonicalIdentifier(columnName));

                if (!normalizedColumnSet.containsAll(conflictColumns)) {
                    throw new IllegalArgumentException("Missing conflict columns for table " + tableName + ": " + conflictColumns);
                }

                query.append(" ON CONFLICT (").append(joinQuoted(conflictColumns)).append(") ");

                List<String> updatedColumns = new ArrayList<>();
                for (String columnName : columns) {
                    if (!conflictColumns.contains(canonicalIdentifier(columnName)))
                        updatedColumns.add(columnName);
                }

                if (updatedColumns.isEmpty()) {
                    query.append("DO NOTHING");
                } else {
                    query.append("DO UPDATE SET ");
                    for (int i = 0; i < updatedColumns.size(); ++i) {
                        if (i != 0)
                            query.append(',');

                        String columnName = updatedColumns.get(i);
                        query.append(quoteIdentifier(columnName)).append("=EXCLUDED.")
                                .append(quoteIdentifier(columnName));
                    }
                }
            }
        }

        return new CustomSQLDatabaseTransaction(query.append(';').toString());
    }

    CustomSQLDatabaseTransaction createUpdateTransaction(String tableName, Collection<String> columnNames,
                                                           Collection<String> filteredColumns) {
        StringBuilder query = new StringBuilder("UPDATE {prefix}").append(tableName).append(" SET ");

        int index = 0;
        for (String columnName : columnNames) {
            if (index++ != 0)
                query.append(',');
            query.append(quoteIdentifier(columnName)).append("=?");
        }

        appendFilters(query, filteredColumns);
        return new CustomSQLDatabaseTransaction(query.append(';').toString());
    }

    CustomSQLDatabaseTransaction createDeleteTransaction(String tableName, Collection<String> filteredColumns) {
        StringBuilder query = new StringBuilder("DELETE FROM {prefix}").append(tableName);
        appendFilters(query, filteredColumns);
        return new CustomSQLDatabaseTransaction(query.append(';').toString());
    }

    String createColumnFilter(Collection<String> filteredColumns) {
        StringBuilder filter = new StringBuilder();
        appendFilters(filter, filteredColumns);
        return filter.toString();
    }

    String quoteIdentifier(String identifier) {
        boolean explicitlyQuoted = isExplicitlyQuoted(identifier, '"');
        String normalizedIdentifier = stripIdentifierQuotes(identifier);
        if (this.postgresql && !explicitlyQuoted)
            normalizedIdentifier = normalizedIdentifier.toLowerCase(Locale.ENGLISH);

        String quote = String.valueOf(this.identifierQuote);
        return this.identifierQuote + normalizedIdentifier.replace(quote, quote + quote) + this.identifierQuote;
    }

    boolean isPostgreSQL() {
        return this.postgresql;
    }

    private void appendFilters(StringBuilder query, Collection<String> filteredColumns) {
        if (filteredColumns == null || filteredColumns.isEmpty())
            return;

        int index = 0;
        for (String columnName : filteredColumns) {
            query.append(index++ == 0 ? " WHERE " : " AND ")
                    .append(quoteIdentifier(columnName)).append("=?");
        }
    }

    private String joinQuoted(Collection<String> identifiers) {
        StringBuilder joined = new StringBuilder();
        for (String identifier : identifiers) {
            if (joined.length() != 0)
                joined.append(',');
            joined.append(quoteIdentifier(identifier));
        }
        return joined.toString();
    }

    private static String stripIdentifierQuotes(String identifier) {
        if (identifier == null || identifier.isEmpty())
            throw new IllegalArgumentException("Database identifiers cannot be empty.");

        if (isExplicitlyQuoted(identifier, '`') || isExplicitlyQuoted(identifier, '"'))
            identifier = identifier.substring(1, identifier.length() - 1);

        if (identifier.isEmpty())
            throw new IllegalArgumentException("Database identifiers cannot be empty.");

        return identifier;
    }

    private static boolean isExplicitlyQuoted(String identifier, char quote) {
        return identifier != null && identifier.length() >= 2 && identifier.charAt(0) == quote &&
                identifier.charAt(identifier.length() - 1) == quote;
    }

    private static String canonicalIdentifier(String identifier) {
        return stripIdentifierQuotes(identifier).toLowerCase(Locale.ENGLISH);
    }

    private static String createPlaceholders(int amount) {
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < amount; ++i) {
            if (i != 0)
                placeholders.append(',');
            placeholders.append('?');
        }
        return placeholders.toString();
    }

    private static Map<String, List<String>> createConflictColumns() {
        Map<String, List<String>> conflictColumns = new HashMap<>();

        addConflictColumns(conflictColumns, "islands", "uuid");
        addConflictColumns(conflictColumns, "islands_banks", "island");
        addConflictColumns(conflictColumns, "islands_bans", "island", "player");
        addConflictColumns(conflictColumns, "islands_block_limits", "island", "block");
        addConflictColumns(conflictColumns, "islands_chests", "island", "index");
        addConflictColumns(conflictColumns, "islands_custom_data", "island");
        addConflictColumns(conflictColumns, "islands_effects", "island", "effect_type");
        addConflictColumns(conflictColumns, "islands_entity_limits", "island", "entity");
        addConflictColumns(conflictColumns, "islands_flags", "island", "name");
        addConflictColumns(conflictColumns, "islands_generators", "island", "environment", "block");
        addConflictColumns(conflictColumns, "islands_homes", "island", "environment");
        addConflictColumns(conflictColumns, "islands_members", "island", "player");
        addConflictColumns(conflictColumns, "islands_missions", "island", "name");
        addConflictColumns(conflictColumns, "islands_player_permissions", "island", "player", "permission");
        addConflictColumns(conflictColumns, "islands_ratings", "island", "player");
        addConflictColumns(conflictColumns, "islands_role_limits", "island", "role");
        addConflictColumns(conflictColumns, "islands_role_permissions", "island", "permission");
        addConflictColumns(conflictColumns, "islands_settings", "island");
        addConflictColumns(conflictColumns, "islands_upgrades", "island", "upgrade");
        addConflictColumns(conflictColumns, "islands_visitor_homes", "island", "environment");
        addConflictColumns(conflictColumns, "islands_visitors", "island", "player");
        addConflictColumns(conflictColumns, "islands_warp_categories", "island", "name");
        addConflictColumns(conflictColumns, "islands_warps", "island", "name");
        addConflictColumns(conflictColumns, "players", "uuid");
        addConflictColumns(conflictColumns, "players_custom_data", "player");
        addConflictColumns(conflictColumns, "players_missions", "player", "name");
        addConflictColumns(conflictColumns, "players_settings", "player");
        addConflictColumns(conflictColumns, "stacked_blocks", "location");

        addAppendTable(conflictColumns, "ssb_metadata");
        addAppendTable(conflictColumns, "grid");
        addAppendTable(conflictColumns, "bank_transactions");

        return Collections.unmodifiableMap(conflictColumns);
    }

    private static void addConflictColumns(Map<String, List<String>> conflictColumns, String tableName,
                                           String... columnNames) {
        conflictColumns.put(tableName, Collections.unmodifiableList(Arrays.asList(columnNames)));
    }

    private static void addAppendTable(Map<String, List<String>> conflictColumns, String tableName) {
        conflictColumns.put(tableName, Collections.emptyList());
    }

}

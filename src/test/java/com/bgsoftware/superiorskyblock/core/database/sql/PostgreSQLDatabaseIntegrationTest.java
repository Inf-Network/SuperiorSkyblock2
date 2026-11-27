package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.logger.ILogger;
import com.bgsoftware.common.databasebridge.sql.transaction.CustomSQLDatabaseTransaction;
import com.bgsoftware.superiorskyblock.api.data.DatabaseBridgeMode;
import com.bgsoftware.superiorskyblock.api.data.DatabaseFilter;
import com.bgsoftware.superiorskyblock.api.objects.Pair;
import com.bgsoftware.superiorskyblock.core.database.DatabaseResult;
import com.bgsoftware.superiorskyblock.core.database.loader.sql.SQLDatabase;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runs the SQL layer against an embedded PostgreSQL server, connected the way the server is: a role whose
 * search_path names its own schema.
 */
public class PostgreSQLDatabaseIntegrationTest {

    private static final AtomicInteger SCHEMAS = new AtomicInteger();

    /**
     * A namespaced key longer than the 30 characters MySQL allows for UNIQUE_TEXT.
     */
    private static final String LONG_BLOCK_KEY = "CRAFTENGINE:inf_skyblock_enchanted_diamond_ore";

    private static EmbeddedPostgres postgres;

    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final String island = UUID.randomUUID().toString();
    private final String player = UUID.randomUUID().toString();
    private String role;
    private SQLDatabaseBridge bridge;

    @BeforeClass
    public static void start() throws IOException {
        postgres = EmbeddedPostgres.start();
    }

    @AfterClass
    public static void stop() throws IOException {
        postgres.close();
    }

    @Before
    public void connect() throws SQLException {
        role = "ssb2_" + SCHEMAS.incrementAndGet();
        execute("CREATE ROLE " + role + " LOGIN",
                "CREATE SCHEMA " + role + " AUTHORIZATION " + role,
                "ALTER ROLE " + role + " SET search_path = " + role);

        PostgreSQLDatabaseSession session = new PostgreSQLDatabaseSession(new PostgreSQLDatabaseSession.Args(
                "localhost", postgres.getPort(), "postgres", role, "unused", "", false,
                600000, 1800000, 4, "SSB2 Test Database Thread", logger()));
        assertTrue(session.connect());
        DBSession.useSession(session, SQLDatabaseDialect.POSTGRESQL);

        bridge = new SQLDatabaseBridge();
        bridge.setDatabaseBridgeMode(DatabaseBridgeMode.SAVE_DATA);
    }

    @After
    public void close() {
        DBSession.close();
        assertEquals(Collections.emptyList(), errors);
    }

    @Test
    public void freshDatabaseStartsAtTheCurrentVersionAndRestartsCleanly() throws Exception {
        SQLDatabase.UpgradeResult first = createSchema();
        assertTrue(first.isUpgraded());
        assertEquals(SQLDatabase.getCurrentDatabaseVersion(), first.getDatabaseVersion());

        SQLDatabase.UpgradeResult restart = createSchema();
        assertFalse(restart.isUpgraded());
        assertEquals(SQLDatabase.getCurrentDatabaseVersion(), restart.getDatabaseVersion());
        assertEquals(Collections.singletonList(Collections.<String, Object>singletonMap("version",
                SQLDatabase.getCurrentDatabaseVersion())), rows("ssb_metadata", "version"));
    }

    @Test
    public void everyTableTakesTheValuesTheBridgesWriteAndUpsertsByItsKey() throws Exception {
        createSchema();
        for (Map.Entry<String, List<Pair<String, Object>>> row : sampleRows(1).entrySet())
            insert(row.getKey(), row.getValue());
        for (Map.Entry<String, List<Pair<String, Object>>> row : sampleRows(2).entrySet())
            insert(row.getKey(), row.getValue());
        flush();

        for (String table : sampleRows(1).keySet()) {
            int expected = table.equals("grid") || table.equals("bank_transactions") ? 2 : 1;
            assertEquals(table, expected, rows(table).size());
        }

        DatabaseResult islandRow = result("islands", "locked", "worth_bonus", "creation_time");
        assertEquals(Boolean.TRUE, islandRow.getBoolean("locked").get());
        assertEquals(new BigDecimal("2.5"), islandRow.getBigDecimal("worth_bonus").get());
        assertEquals(Long.valueOf(2000L), islandRow.getLong("creation_time").get());
        DatabaseResult settings = result("islands_settings", "crop_growth_multiplier", "bank_limit");
        assertEquals(2.5D, settings.getDouble("crop_growth_multiplier").get(), 0D);
        assertEquals(new BigDecimal("2000.5"), settings.getBigDecimal("bank_limit").get());
        assertArrayEquals(new byte[]{2, 0, -1}, result("islands_chests", "contents").getBlob("contents").get());
        assertEquals(Integer.valueOf(2), result("islands_block_limits", "limit").getInt("limit").get());
        assertEquals(LONG_BLOCK_KEY, result("islands_generators", "block").getString("block").get());
        assertEquals(Boolean.FALSE, result("players_settings", "island_fly").getBoolean("island_fly").get());
    }

    @Test
    public void updatesAndDeletesUseTheBridgeFilters() throws Exception {
        createSchema();
        for (Map.Entry<String, List<Pair<String, Object>>> row : sampleRows(1).entrySet())
            insert(row.getKey(), row.getValue());

        DatabaseFilter islandFilter = DatabaseFilter.fromFilter("island", island);
        bridge.updateObject("islands", DatabaseFilter.fromFilter("uuid", island), new Pair<>("locked", false));
        // Synced upgrade values are written as int into DECIMAL and TEXT columns.
        bridge.updateObject("islands_settings", islandFilter, new Pair<>("crop_growth_multiplier", -2),
                new Pair<>("bank_limit", BigDecimal.valueOf(-2).toString()));
        bridge.updateObject("islands_chests", DatabaseFilter.fromFilters(Arrays.asList(
                new Pair<>("island", island), new Pair<>("index", 1))), new Pair<>("contents", new byte[]{7}));
        bridge.deleteObject("islands_members", DatabaseFilter.fromFilters(Arrays.asList(
                new Pair<>("island", island), new Pair<>("player", player))));
        String renamed = UUID.randomUUID().toString();
        bridge.updateObject("players", DatabaseFilter.fromFilter("uuid", player), new Pair<>("uuid", renamed));
        flush();

        assertEquals(Boolean.FALSE, result("islands", "locked").getBoolean("locked").get());
        assertEquals(-2D, result("islands_settings", "crop_growth_multiplier")
                .getDouble("crop_growth_multiplier").get(), 0D);
        assertArrayEquals(new byte[]{7}, result("islands_chests", "contents").getBlob("contents").get());
        assertEquals(Collections.emptyList(), rows("islands_members"));
        List<Map<String, Object>> loaded = new ArrayList<>();
        bridge.loadObject("players", DatabaseFilter.fromFilter("uuid", renamed),
                row -> loaded.add(Collections.singletonMap("uuid", row.get("uuid"))));
        assertEquals(Collections.singletonList(Collections.<String, Object>singletonMap("uuid", renamed)), loaded);
    }

    @Test
    public void coopWritesAreAwaitedAndTheStoredRosterIsReadBack() throws Exception {
        createSchema();
        String character = UUID.randomUUID().toString();
        boolean completed = CoopDatabaseWrites.captureCoopWrites(() -> {
            bridge.insertObject("islands_members", new Pair<>("island", island), new Pair<>("player", character),
                    new Pair<>("role", 1), new Pair<>("join_time", 1000L));
            return true;
        }).get(10, TimeUnit.SECONDS);

        assertTrue(completed);
        assertTrue(CoopDatabaseWrites.storedCoopMembership(UUID.fromString(island), UUID.fromString(character))
                .get(10, TimeUnit.SECONDS));
        assertFalse(CoopDatabaseWrites.storedCoopMembership(UUID.fromString(island), UUID.randomUUID())
                .get(10, TimeUnit.SECONDS));
        try {
            CoopDatabaseWrites.captureCoopWrites(() -> true).get(10, TimeUnit.SECONDS);
            fail("A completed Coop change without SQL writes must fail");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void legacyDatabaseWithoutMetadataIsRefused() throws SQLException {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET ROLE " + role);
            statement.execute("CREATE TABLE " + role + ".islands (uuid VARCHAR(36) PRIMARY KEY)");
        }
        try {
            SQLDatabase.upgradeDatabase();
            fail("A PostgreSQL database with islands but no ssb_metadata must be refused");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("legacy PostgreSQL database"));
        }
    }

    /**
     * Runs the startup steps of SQLDatabaseLoader: upgrade check, tables, version, indexes.
     */
    private SQLDatabase.UpgradeResult createSchema() {
        SQLDatabase.UpgradeResult result = SQLDatabase.upgradeDatabase();
        SQLDatabase.initializeDatabase();
        if (result.isUpgraded())
            DBSession.updateDatabaseVersion(result.getDatabaseVersion());
        SQLDatabase.createIndexes();
        return result;
    }

    private void insert(String table, List<Pair<String, Object>> columns) {
        @SuppressWarnings("unchecked")
        Pair<String, Object>[] values = columns.toArray(new Pair[0]);
        bridge.insertObject(table, values);
    }

    /**
     * Waits for the writes queued so far: the transactions run in order on one thread.
     */
    private void flush() throws InterruptedException, ExecutionException, TimeoutException {
        DBSession.execute(new CustomSQLDatabaseTransaction("SELECT 1")).get(10, TimeUnit.SECONDS);
    }

    /**
     * One row per table with the Java types the bridges bind, keyed the same for every generation.
     */
    private Map<String, List<Pair<String, Object>>> sampleRows(int generation) {
        String other = UUID.nameUUIDFromBytes(island.getBytes()).toString();
        Map<String, List<Pair<String, Object>>> rows = new LinkedHashMap<>();
        rows.put("islands", columns("uuid", island, "owner", player, "center", "world,0,100,0,0,0",
                "creation_time", 1000L * generation, "island_type", "normal", "discord", null, "paypal", null,
                "worth_bonus", generation + 0.5 + "", "levels_bonus", "0", "locked", generation == 2,
                "ignored", false, "name", "island " + generation, "description", "", "generated_schematics", "normal",
                "unlocked_worlds", "", "last_time_updated", 1_700_000_000L, "dirty_chunks", "",
                "block_counts", "", "entity_counts", ""));
        rows.put("islands_banks", columns("island", island, "balance", "100.5", "last_interest_time", 1000L * generation));
        rows.put("islands_bans", columns("island", island, "player", other, "banned_by", player,
                "banned_time", 1000L * generation));
        rows.put("islands_block_limits", columns("island", island, "block", LONG_BLOCK_KEY, "limit", generation));
        rows.put("islands_chests", columns("island", island, "index", 1, "contents", new byte[]{(byte) generation, 0, -1}));
        rows.put("islands_custom_data", columns("island", island, "data", new byte[]{(byte) generation}));
        rows.put("islands_effects", columns("island", island, "effect_type", "SPEED", "level", generation));
        rows.put("islands_entity_limits", columns("island", island, "entity", "ZOMBIE", "limit", generation));
        rows.put("islands_flags", columns("island", island, "name", "NATURAL_ANIMALS_SPAWN", "status", generation));
        rows.put("islands_generators", columns("island", island, "environment", "NORMAL", "block", LONG_BLOCK_KEY,
                "rate", generation));
        rows.put("islands_homes", columns("island", island, "environment", "NORMAL", "location", "home " + generation));
        rows.put("islands_members", columns("island", island, "player", player, "role", generation,
                "join_time", 1000L * generation));
        rows.put("islands_missions", columns("island", island, "name", "farmer", "finish_count", generation));
        rows.put("islands_player_permissions", columns("island", island, "player", player, "permission", "BREAK",
                "status", generation == 2));
        rows.put("islands_ratings", columns("island", island, "player", player, "rating", generation,
                "rating_time", 1000L * generation));
        rows.put("islands_role_limits", columns("island", island, "role", 1, "limit", generation));
        rows.put("islands_role_permissions", columns("island", island, "role", generation, "permission", "BUILD"));
        rows.put("islands_settings", columns("island", island, "size", 100 * generation,
                "bank_limit", generation * 1000 + 0.5 + "", "coops_limit", generation, "members_limit", generation,
                "warps_limit", generation, "crop_growth_multiplier", generation + 0.5D,
                "spawner_rates_multiplier", 1D, "mob_drops_multiplier", 1D));
        rows.put("islands_upgrades", columns("island", island, "upgrade", "generator-rates", "level", generation));
        rows.put("islands_visitor_homes", columns("island", island, "environment", "NORMAL",
                "location", "visit " + generation));
        rows.put("islands_visitors", columns("island", island, "player", other, "visit_time", 1000L * generation));
        rows.put("islands_warp_categories", columns("island", island, "name", "default", "slot", generation,
                "icon", "icon " + generation));
        rows.put("islands_warps", columns("island", island, "name", "spawn", "category", "default",
                "location", "warp " + generation, "private", generation == 2, "icon", ""));
        rows.put("players", columns("uuid", player, "last_used_name", "player" + generation, "last_used_skin", "",
                "disbands", generation, "last_time_updated", 1000L * generation));
        rows.put("players_custom_data", columns("player", player, "data", new byte[]{(byte) generation}));
        rows.put("players_missions", columns("player", player, "name", "miner", "finish_count", generation));
        rows.put("players_settings", columns("player", player, "language", "zh-CN", "toggled_panel", false,
                "border_color", "BLUE", "toggled_border", true, "island_fly", generation == 1));
        rows.put("grid", columns("last_island", "0,100,0", "max_island_size", 200, "world", "SuperiorWorld"));
        rows.put("bank_transactions", columns("island", island, "player", player, "bank_action", "DEPOSIT_COMPLETED",
                "position", generation, "time", 1000L * generation, "failure_reason", null, "amount", "10"));
        rows.put("stacked_blocks", columns("location", "SuperiorWorld, 0, 100, 0", "amount", generation,
                "block_type", "DIAMOND_BLOCK"));
        return rows;
    }

    private static List<Pair<String, Object>> columns(Object... namesAndValues) {
        List<Pair<String, Object>> columns = new ArrayList<>();
        for (int i = 0; i < namesAndValues.length; i += 2)
            columns.add(new Pair<>((String) namesAndValues[i], namesAndValues[i + 1]));
        return columns;
    }

    private DatabaseResult result(String table, String... columns) {
        List<Map<String, Object>> rows = rows(table, columns);
        assertEquals(table, 1, rows.size());
        return new DatabaseResult(rows.get(0));
    }

    /**
     * Reads the rows through the bridge, copying the columns while the result set is open.
     */
    private List<Map<String, Object>> rows(String table, String... columns) {
        List<Map<String, Object>> rows = new ArrayList<>();
        bridge.loadAllObjects(table, row -> {
            Map<String, Object> copy = new HashMap<>();
            for (String column : columns)
                copy.put(column, row.get(column));
            rows.add(copy);
        });
        return rows;
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection connection = postgres.getPostgresDatabase().getConnection();
             Statement statement = connection.createStatement()) {
            for (String sql : statements)
                statement.execute(sql);
        }
    }

    private ILogger logger() {
        return new ILogger() {
            @Override
            public void error(String message, Throwable error) {
                errors.add(message + " " + error);
            }

            @Override
            public boolean hasDebugEnabled() {
                return false;
            }

            @Override
            public void debug(String message) {
            }

            @Override
            public void info(String message) {
            }
        };
    }

}

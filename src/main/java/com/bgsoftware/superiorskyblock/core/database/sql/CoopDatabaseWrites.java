package com.bgsoftware.superiorskyblock.core.database.sql;

import com.bgsoftware.common.databasebridge.sql.query.QueryResult;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class CoopDatabaseWrites {
    private static final ThreadLocal<List<CompletableFuture<Void>>> COOP_WRITES = new ThreadLocal<>();

    private CoopDatabaseWrites() {
    }

    /** Waits for Superior's actual SQL writes submitted by a main-thread Coop operation. */
    public static CompletableFuture<Boolean> captureCoopWrites(BooleanSupplier change) {
        if (COOP_WRITES.get() != null) {
            throw new IllegalStateException("Nested Coop database operation");
        }
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        COOP_WRITES.set(writes);
        boolean completed;
        try {
            completed = change.getAsBoolean();
        } finally {
            COOP_WRITES.remove();
        }
        if (completed && writes.isEmpty()) {
            return failed(new IllegalStateException("No Coop SQL writes were submitted"));
        }
        return CompletableFuture.allOf(writes.toArray(new CompletableFuture<?>[0])).thenApply(ignored -> completed);
    }

    /** Reads the durable roster independently of the island's in-memory member list. */
    public static CompletableFuture<Boolean> storedCoopMembership(UUID island, UUID character) {
        if (!DBSession.isReady()) {
            return failed(new IllegalStateException("Superior database is not ready"));
        }
        CompletableFuture<Boolean> owner = rowExists("islands", " WHERE uuid='" + island + "' AND owner='" + character + "'");
        CompletableFuture<Boolean> member = rowExists("islands_members",
                " WHERE island='" + island + "' AND player='" + character + "'");
        return owner.thenCombine(member, (ownsIsland, isMember) -> ownsIsland || isMember);
    }

    private static CompletableFuture<Boolean> rowExists(String table, String filter) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        DBSession.select(table, filter, new QueryResult<ResultSet>()
                .onSuccess(rows -> result.complete(rows.next())).onFail(result::completeExceptionally));
        return result;
    }

    // CompletableFuture.failedFuture is Java 9+; the plugin is compiled for Java 8.
    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    static void track(CompletableFuture<Void> write) {
        List<CompletableFuture<Void>> writes = COOP_WRITES.get();
        if (writes != null) writes.add(write);
    }
}

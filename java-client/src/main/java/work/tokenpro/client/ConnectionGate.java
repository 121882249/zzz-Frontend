package work.tokenpro.client;

import java.util.*;
import java.util.function.LongSupplier;

/** One in-flight operation per application; repeated clicks are never queued. */
final class ConnectionGate {
    static final long COOLDOWN_MS = 3000;
    private final Set<String> running = new HashSet<>();
    private final Map<String,Long> readyAt = new HashMap<>();
    private final LongSupplier clock;
    ConnectionGate() { this(() -> System.nanoTime() / 1_000_000); }
    ConnectionGate(LongSupplier clock) { this.clock = clock; }
    synchronized boolean begin(String client) {
        if (blocked(client)) return false;
        running.add(client); return true;
    }
    synchronized boolean blocked(String client) {
        return running.contains(client) || clock.getAsLong() < readyAt.getOrDefault(client, Long.MIN_VALUE);
    }
    synchronized void finish(String client) {
        running.remove(client); readyAt.put(client, clock.getAsLong() + COOLDOWN_MS);
    }
}

package work.tokenpro.client;

import java.net.URI;
import java.util.Map;
import java.util.function.LongSupplier;

/** One independent cooldown per management, documentation, or purchase entry point. */
final class BrowserOpenGate {
    static final long INTERVAL_MS = 15000;
    private final LongSupplier clock;
    private boolean pending;
    private long readyAt = Long.MIN_VALUE;
    BrowserOpenGate() { this(() -> System.nanoTime() / 1_000_000); }
    BrowserOpenGate(LongSupplier clock) { this.clock = clock; }
    static Map<String,BrowserOpenGate> independentGates() { return independentGates(() -> System.nanoTime() / 1_000_000); }
    static Map<String,BrowserOpenGate> independentGates(LongSupplier clock) {
        return Map.of("admin", new BrowserOpenGate(clock), "docs", new BrowserOpenGate(clock), "purchase", new BrowserOpenGate(clock));
    }
    static boolean protects(String url) {
        return key(url) != null;
    }
    static String key(String url) {
        try {
            URI target = URI.create(url);
            if (!"https".equalsIgnoreCase(target.getScheme()) || !"tokenpro.work".equalsIgnoreCase(target.getHost())) return null;
            String path = target.getPath();
            for (String key : new String[]{"admin", "docs", "purchase"})
                if(path.equals("/" + key) || path.startsWith("/" + key + "/")) return key;
            return null;
        } catch (Exception ignored) { return null; }
    }
    synchronized boolean begin() {
        if (pending || secondsRemaining() > 0) return false;
        pending = true; return true;
    }
    synchronized void opened() { pending = false; readyAt = clock.getAsLong() + INTERVAL_MS; }
    synchronized void failed() { pending = false; readyAt = Long.MIN_VALUE; }
    synchronized boolean pending() { return pending; }
    synchronized int secondsRemaining() {
        long now = clock.getAsLong();
        return readyAt <= now ? 0 : (int) ((readyAt - now + 999) / 1000);
    }
}

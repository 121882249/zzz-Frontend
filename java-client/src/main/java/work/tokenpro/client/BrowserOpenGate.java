package work.tokenpro.client;

import java.net.URI;
import java.util.function.LongSupplier;

/** Shared cooldown for management, documentation, and purchase entry points. */
final class BrowserOpenGate {
    static final long INTERVAL_MS = 15000;
    private final LongSupplier clock;
    private boolean pending;
    private long readyAt = Long.MIN_VALUE;
    BrowserOpenGate() { this(() -> System.nanoTime() / 1_000_000); }
    BrowserOpenGate(LongSupplier clock) { this.clock = clock; }
    static boolean protects(String url) {
        try {
            URI target = URI.create(url);
            if (!"https".equalsIgnoreCase(target.getScheme()) || !"tokenpro.work".equalsIgnoreCase(target.getHost())) return false;
            String path = target.getPath();
            return path.equals("/admin") || path.startsWith("/admin/") || path.equals("/docs") || path.startsWith("/docs/")
                || path.equals("/purchase") || path.startsWith("/purchase/");
        } catch (Exception ignored) { return false; }
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

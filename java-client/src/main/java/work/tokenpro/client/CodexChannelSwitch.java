package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class CodexChannelSwitch {
    static int run(SecureStore store, Path config, String provider, List<String> models,
                   ClientReconnect.Action stop, ClientReconnect.Action write,
                   ClientReconnect.Action start, ClientReconnect.Action repair) throws Exception {
        CodexHistoryRepair.cancel(config);
        stop.run();
        // A channel switch is intentionally one-way. Configuration writes are
        // atomic at their owners; if startup fails, keep the new channel and
        // tell the user to launch Codex manually. Never restore the old route.
        write.run();
        try {
            start.run();
        } catch (Exception failure) {
            throw new ManualStartRequiredException("Codex", failure);
        } finally {
            try { repair.run(); } catch (Exception ignored) { /* Repair never blocks activation. */ }
        }
        return models.size();
    }
}

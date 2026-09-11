package work.tokenpro.client;

import java.util.*;

/** Local routing evidence, not a claim that the billing ledger has settled. */
final class ConnectionEvidence {
    static final String FILE = "codex-connection-state.json";
    private ConnectionEvidence() {}
    static void received(SecureStore store, String revision, String model, int status, String requestId) {
        if (revision == null || revision.isBlank()) return;
        try {
            store.write(FILE, Json.stringify(Map.of("revision", revision, "model", model,
                "http_status", status, "request_id", requestId, "received_at", System.currentTimeMillis(),
                "upstream", "https://tokenpro.work/v1/responses")));
        } catch (Exception ignored) { /* Telemetry must not break inference. */ }
    }
    static boolean verified(SecureStore store) {
        try {
            Map<String,Object> config = Json.object(Json.parse(store.read(CodexImageBridge.FILE).orElse("{}")));
            Map<String,Object> evidence = Json.object(Json.parse(store.read(FILE).orElse("{}")));
            return config.get("revision") instanceof String revision && !revision.isBlank()
                && revision.equals(evidence.get("revision"))
                && evidence.get("http_status") instanceof Number status && status.intValue() == 200
                && "https://tokenpro.work/v1/responses".equals(evidence.get("upstream"));
        } catch (Exception ignored) { return false; }
    }
}

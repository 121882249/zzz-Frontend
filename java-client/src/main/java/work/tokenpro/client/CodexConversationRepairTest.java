package work.tokenpro.client;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class CodexConversationRepairTest {
    static int run() throws Exception {
        Map<String,String> providers = new LinkedHashMap<>(Map.of(
            "already", "openai", "custom", "custom", "direct", "tokenpro_direct", "uppercase", "OpenAI"));
        Map<String,String> models = new HashMap<>();
        AtomicInteger closed = new AtomicInteger();
        CodexConversationRepair.Factory factory = () -> new CodexConversationRepair.Rpc() {
            String pendingId;
            public Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
                String id = Objects.toString(params.get("threadId"), "");
                if (method.equals("thread/list")) {
                    boolean archived = Boolean.TRUE.equals(params.get("archived"));
                    List<String> ids = archived ? List.of("direct", "uppercase") : List.of("already", "custom");
                    return Map.of("data", ids.stream().map(value -> Map.of(
                        "id", value, "modelProvider", providers.get(value))).toList());
                }
                if (method.equals("thread/resume") && params.containsKey("modelProvider")) {
                    if (id.equals("uppercase")) throw new IOException("fixture failure");
                    pendingId = id;
                    return Map.of("modelProvider", params.get("modelProvider"), "model", params.get("model"));
                }
                if (method.equals("thread/settings/update")) {
                    providers.put(pendingId, "openai");
                    models.put(pendingId, Objects.toString(params.get("model"), ""));
                    return Map.of();
                }
                if (method.equals("thread/resume")) return Map.of(
                    "modelProvider", providers.getOrDefault(id, ""), "model", models.getOrDefault(id, "old"));
                return Map.of();
            }
            public void close() { closed.incrementAndGet(); }
        };
        CodexConversationRepair.Result result = CodexConversationRepair.repair(factory, "openai", "tp-g57-fixture");
        require(result.discovered() == 4 && result.alreadyTarget() == 1 && result.attempted() == 3,
            "all archived and active non-openai providers are selected");
        require(result.repaired() == 2 && result.failed() == 1 && result.failedThreadIds().equals(List.of("uppercase")),
            "individual failures do not block other conversations");
        require("openai".equals(providers.get("custom")) && "openai".equals(providers.get("direct"))
            && "tp-g57-fixture".equals(models.get("custom")), "provider and current route model are persisted");
        require("openai".equals(result.provider()), "selected target provider is reported");
        require(closed.get() == 4, "batch app-server sessions are reused and closed");
        return 4;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

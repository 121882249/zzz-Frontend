package work.tokenpro.client;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

final class CodexConversationRepairTest {
    static int run() throws Exception {
        Map<String,String> providers = new LinkedHashMap<>();
        providers.put("already", "openai"); providers.put("custom", "custom");
        providers.put("broken", "custom"); providers.put("guardian", "custom");
        providers.put("direct", "tokenpro_direct"); providers.put("uppercase", "OpenAI");
        Map<String,String> models = new HashMap<>(Map.of("already", "tp-g57-fixture"));
        Set<String> archivedIds = new LinkedHashSet<>(List.of("direct", "uppercase"));
        AtomicInteger closed = new AtomicInteger();
        CodexConversationRepair.Factory factory = () -> new CodexConversationRepair.Rpc() {
            String pendingId;
            public Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
                String id = Objects.toString(params.get("threadId"), "");
                if (method.equals("thread/list")) {
                    boolean archived = Boolean.TRUE.equals(params.get("archived"));
                    List<String> ids = providers.keySet().stream().filter(value -> archivedIds.contains(value) == archived).toList();
                    return Map.of("data", ids.stream().map(value -> {
                        Map<String,Object> row = new LinkedHashMap<>();
                        row.put("id", value); row.put("modelProvider", providers.get(value));
                        row.put("model", models.getOrDefault(value, "old"));
                        row.put("source", value.equals("guardian")
                            ? Map.of("subAgent", Map.of("other", "guardian")) : "vscode");
                        if (value.equals("guardian")) row.put("parentThreadId", "custom");
                        return row;
                    }).toList());
                }
                if (method.equals("thread/unarchive")) { archivedIds.remove(id); return Map.of(); }
                if (method.equals("thread/archive")) { archivedIds.add(id); return Map.of(); }
                if (method.equals("thread/resume") && params.containsKey("modelProvider")) {
                    if (id.equals("broken")) throw new IOException("fixture failure");
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
        require(result.discovered() == 6 && result.visibleDiscovered() == 3 && result.alreadyTarget() == 1
            && result.attempted() == 2, "only active visible non-target conversations are selected");
        require(result.repaired() == 1 && result.failed() == 1 && result.failedThreadIds().equals(List.of("broken")),
            "individual failures do not block other conversations");
        require(result.visibleRepaired() == 1 && result.internalRepaired() == 0
            && result.archivedDiscovered() == 2 && result.internalDiscovered() == 1,
            "archived conversations and internal tasks are reported as skipped");
        require("openai".equals(providers.get("custom")) && "tokenpro_direct".equals(providers.get("direct"))
            && "custom".equals(providers.get("guardian"))
            && "tp-g57-fixture".equals(models.get("custom")), "provider and current route model are persisted");
        require(archivedIds.equals(Set.of("direct", "uppercase")), "archived conversations keep their original state");
        require("openai".equals(result.provider()), "selected target provider is reported");
        require(closed.get() == 5, "batch app-server sessions are reused and closed");
        return 6;
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

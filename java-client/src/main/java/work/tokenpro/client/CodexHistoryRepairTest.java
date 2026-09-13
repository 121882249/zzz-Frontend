package work.tokenpro.client;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.*;

final class CodexHistoryRepairTest {
    static int run() {
        Map<String,String> persisted = new HashMap<>();
        Set<String> attempted = new HashSet<>();
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closed = new AtomicInteger();
        CodexHistoryRepair.Factory factory = () -> {
            opens.incrementAndGet();
            return new CodexHistoryRepair.Session() {
                public Map<String,Object> call(String method, Map<String,Object> params) throws Exception {
                    String id = Objects.toString(params.get("threadId"), "");
                    if (method.equals("thread/list")) {
                        require(params.get("modelProviders").equals(List.of()), "all providers explicitly included");
                        require(((List<?>)params.get("sourceKinds")).contains("appServer"), "desktop source included");
                        return Map.of("data", ((boolean)params.get("archived") ? List.of("archived") : List.of("bad", "good", "lost"))
                            .stream().map(value -> Map.of("id", value)).toList());
                    }
                    if (method.equals("thread/resume")) {
                        if (params.containsKey("modelProvider")) {
                            attempted.add(id);
                            if (id.equals("bad")) throw new IOException("stale thread");
                            return Map.of("modelProvider", "custom", "model", "new-model");
                        }
                        return Map.of("modelProvider", persisted.containsKey(id) ? "custom" : "openai",
                            "model", persisted.getOrDefault(id, "old-model"));
                    }
                    if (method.equals("thread/settings/update") && !id.equals("lost")) persisted.put(id, (String) params.get("model"));
                    return Map.of();
                }
                public void close() { closed.incrementAndGet(); }
            };
        };
        var result = CodexHistoryRepair.repair(factory, "custom", List.of("new-model"), () -> false);
        require(result.attempted() == 4 && result.repaired() == 2 && result.failed() == 2, "count failures independently and verify persistence");
        require(attempted.equals(Set.of("bad", "good", "lost", "archived")), "one failure does not prevent later or archived threads");
        require(opens.get() == closed.get(), "all RPC sessions closed");
        int before = opens.get();
        var cancelled = CodexHistoryRepair.repair(factory, "custom", List.of("new-model"), () -> true);
        require(cancelled.attempted() == 0 && opens.get() == before, "superseded repair never opens a session");
        var unavailable = CodexHistoryRepair.repair(() -> { throw new IOException("no app server"); }, "openai", List.of(), () -> false);
        require(unavailable.repaired() == 0 && unavailable.issues().size() == 2, "list failure reported without claiming success");
        return 5;
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}

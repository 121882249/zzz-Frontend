package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class ModelSelectionReconcilerTest {
    static int run() throws Exception {
        int passed = 0;
        Path root = Files.createTempDirectory("tokenpro-model-reconcile-");
        try {
            SecureStore store = new SecureStore(root);
            PricedModel old = new PricedModel("GPT-Old", "openai", "Before", 7);
            PricedModel live = new PricedModel("GPT-Live", "openai", "Current", 7);
            PricedModel image = new PricedModel("GPT-Image-Old", "openai", "Before", 7);
            PricedModel fresh = new PricedModel("GPT-New", "openai", "Current", 7);
            List<PricedModel> catalog = List.of(live, fresh, new PricedModel(old.name(), "openai", "Different Group", 8));
            Map<String,Object> document = new LinkedHashMap<>(Map.of("account_id", "18", "key_id", 91, "custom", "preserve", "models", rows(List.of(old, live, image)), "model", old.name(), "group_id", 7, "default_model", old.name(), "image_model", image.name()));
            store.write("codex-selected.json", Json.stringify(document));
            String before = store.read("codex-selected.json").orElseThrow();
            check(ModelSelectionReconciler.reconcileAll(store, "18", List.of()) == 0 && store.read("codex-selected.json").orElseThrow().equals(before), "empty response never destroys selections"); passed++;
            check(ModelSelectionReconciler.reconcileAll(store, "99", catalog) == 0 && store.read("codex-selected.json").orElseThrow().equals(before), "another account's saved configuration preserved"); passed++;
            check(ModelSelectionReconciler.reconcileAll(store, "18", catalog) == 2, "remove obsolete and image models by group plus exact name"); passed++;
            Map<String,Object> after = Json.object(Json.parse(store.read("codex-selected.json").orElseThrow()));
            check(((List<?>)after.get("models")).size() == 1 && after.get("default_model").equals(live.name()), "default uses only a retained selection, never a new catalog model"); passed++;
            check(!after.containsKey("model") && !after.containsKey("group_id") && after.get("image_model").equals(""), "legacy and image fallbacks cannot resurrect removed models"); passed++;
            check(after.get("custom").equals("preserve") && ((Number)after.get("key_id")).longValue() == 91, "unrelated fields and key reference preserved"); passed++;
            check(ModelSelectionReconciler.reconcileAll(store, "18", catalog) == 0, "reconciliation idempotent"); passed++;
            List<PricedModel> selected = ModelSelectionReconciler.currentModels(List.of(old, live, live), catalog, "Codex");
            check(selected.equals(List.of(live)), "connection preflight prunes duplicates and cannot choose another group's same-name model"); passed++;
            check(ModelSelectionReconciler.currentModels(List.of(image), List.of(image, fresh), "Claude").isEmpty(), "Claude never retains image-only models"); passed++;
            ClaudeBridgeConfig bridge = ClaudeBridgeConfig.create("18", "fixture-access", new ApiClient.ManagedKey(91, "fixture-upstream"), List.of(old, live));
            bridge.save(store);
            String alias = bridge.routes().get(1).alias();
            check(ModelSelectionReconciler.reconcileAll(store, "18", catalog) == 1, "Claude routes pruned"); passed++;
            ClaudeBridgeConfig retained = ClaudeBridgeConfig.load(store);
            check(retained.routes().size() == 1 && retained.routes().getFirst().alias().equals(alias), "existing route alias retained until explicit reconnect"); passed++;
            check(retained.key().equals(bridge.key()) && retained.localToken().equals(bridge.localToken()) && retained.port() == bridge.port(), "credentials and bridge port unchanged"); passed++;
            SecureStore codexCli = store.cli("codex"), claudeCli = store.cli("claude");
            codexCli.write("codex-selected.json", Json.stringify(document)); bridge.save(claudeCli);
            check(ModelSelectionReconciler.reconcileAll(store, "18", catalog) == 3, "both CLI profiles receive independent cleanup"); passed++;
            check(ClaudeBridgeConfig.load(store).routes().size() == 1 && ClaudeBridgeConfig.load(claudeCli).routes().size() == 1, "desktop and CLI remain separate"); passed++;
            store.write("codex-selected.json", "{\"model\":\"GPT-Old\",\"group_id\":7,\"key_id\":91}");
            check(ModelSelectionReconciler.reconcileAll(store, "18", catalog) == 1 && TokenProFrame.savedCodexModels(store).isEmpty(), "legacy single-model selection removed without substituting a new model"); passed++;
            store.write("codex-selected.json", "{\"models\":[{\"name\":\"missing group\"}]}");
            before = store.read("codex-selected.json").orElseThrow();
            check(ModelSelectionReconciler.reconcileAll(store, "18", catalog) == 0 && store.read("codex-selected.json").orElseThrow().equals(before), "unknown record shape preserved"); passed++;
            SecureStore secondView = new SecureStore(root);
            secondView.write("codex-selected.json", "new user selection");
            check(!store.compareAndWrite("codex-selected.json", before, "stale cleanup") && secondView.read("codex-selected.json").orElseThrow().equals("new user selection"), "stale catalog cleanup cannot overwrite a newer selection from another store instance"); passed++;
            secondView.delete("codex-selected.json");
            check(!store.compareAndWrite("codex-selected.json", "new user selection", "resurrected") && store.read("codex-selected.json").isEmpty(), "restore/deletion cannot be undone by stale reconciliation"); passed++;
        } finally {
            try(var paths=Files.walk(root)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        return passed;
    }
    private static List<Map<String,Object>> rows(List<PricedModel> models) {
        return models.stream().map(m -> Map.<String,Object>of("name", m.name(), "platform", m.platform(), "group_name", m.groupName(), "group_id", m.groupId())).toList();
    }
    private static void check(boolean value, String message) { if(!value) throw new AssertionError(message); }
}

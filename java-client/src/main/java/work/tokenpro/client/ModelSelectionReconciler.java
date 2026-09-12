package work.tokenpro.client;

import java.util.*;

/** Prunes only saved model selections after a successful nonempty catalog response. */
final class ModelSelectionReconciler {
    private ModelSelectionReconciler() {}

    static int reconcileAll(SecureStore root, String account, List<PricedModel> catalog) throws Exception {
        if(account == null || account.isBlank() || catalog == null || catalog.isEmpty()) return 0;
        int removed = reconcile(root, "codex-selected.json", "Codex", account, catalog);
        removed += reconcile(root, ClaudeBridgeConfig.FILE, "Claude", account, catalog);
        removed += reconcile(root.cli("codex"), "codex-selected.json", "Codex", account, catalog);
        return removed + reconcile(root.cli("claude"), ClaudeBridgeConfig.FILE, "Claude", account, catalog);
    }

    static List<PricedModel> currentModels(List<PricedModel> selected, List<PricedModel> catalog, String client) {
        Map<String,PricedModel> available = index(catalog, client);
        Map<String,PricedModel> kept = new LinkedHashMap<>();
        for(PricedModel model : selected) {
            String id = id(model.groupId(), model.name());
            if(available.containsKey(id)) kept.putIfAbsent(id, available.get(id));
        }
        return List.copyOf(kept.values());
    }

    static int reconcile(SecureStore store, String file, String client, String account, List<PricedModel> catalog) throws Exception {
        if(catalog == null || catalog.isEmpty() || account == null || account.isBlank()) return 0;
        Optional<String> before = store.read(file);
        if(before.isEmpty()) return 0;
        Map<String,Object> root = new LinkedHashMap<>(Json.object(Json.parse(before.get())));
        String savedOwner = Objects.toString(root.get("account_id"), "");
        if(!savedOwner.isBlank() && !savedOwner.equals(account)) return 0;
        boolean claude = client.equals("Claude");
        String field = claude ? "routes" : "models";
        Object raw = root.get(field);
        List<?> rows;
        if(raw instanceof List<?> list) rows = list;
        else if(!claude && root.get("group_id") instanceof Number && root.get("model") instanceof String model && !model.isBlank()) {
            rows = List.of(Map.of("name", model, "group_id", root.get("group_id")));
        } else return 0; // Unknown formats are not evidence of obsolete selections.
        Map<String,PricedModel> available = index(catalog, client);
        List<Map<String,Object>> kept = new ArrayList<>();
        List<PricedModel> keptModels = new ArrayList<>();
        for(Object value : rows) {
            if(!(value instanceof Map<?,?>)) return 0;
            Map<String,Object> row = Json.object(value);
            if(!(row.get("group_id") instanceof Number group) || !(row.get("name") instanceof String name)) return 0;
            PricedModel model = available.get(id(group.longValue(), name));
            if(model == null) continue;
            Map<String,Object> current = new LinkedHashMap<>(row);
            current.put("platform", model.platform()); current.put("group_name", model.groupName());
            kept.add(current); keptModels.add(model);
        }
        root.put(field, kept);
        if(!claude) {
            root.remove("model"); root.remove("group_id"); // Legacy single-model fallback must not resurrect a removed selection.
            Set<String> names = new HashSet<>(); keptModels.forEach(m -> names.add(m.name()));
            String previousDefault = Objects.toString(root.get("default_model"), "");
            root.put("default_model", names.contains(previousDefault) ? previousDefault : keptModels.stream().filter(m -> !m.isImageGeneration()).findFirst().or(() -> keptModels.stream().findFirst()).map(PricedModel::name).orElse(""));
            List<String> images = keptModels.stream().filter(PricedModel::isImageGeneration).map(PricedModel::name).distinct().toList();
            String previousImage = Objects.toString(root.get("image_model"), "");
            root.put("image_models", images);
            root.put("image_model", images.contains(previousImage) ? previousImage : images.isEmpty() ? "" : images.getFirst());
        }
        String after = Json.stringify(root);
        // Do not overwrite a selection that was changed while reconciling this document.
        if(!after.equals(before.get()) && !store.compareAndWrite(file, before.get(), after)) return 0;
        return rows.size() - kept.size();
    }

    private static Map<String,PricedModel> index(List<PricedModel> catalog, String client) {
        Map<String,PricedModel> result = new LinkedHashMap<>();
        for(PricedModel model : catalog) if(ModelPickerDialog.supportsClient(model, client)) result.put(id(model.groupId(), model.name()), model);
        return result;
    }
    private static String id(long group, String name) { return group + "\u0000" + name; }
}

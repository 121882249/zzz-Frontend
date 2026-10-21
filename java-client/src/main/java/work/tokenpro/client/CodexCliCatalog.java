package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

/** A per-launch view; never overwrites the desktop catalog or shared config. */
final class CodexCliCatalog {
    static List<String> arguments(SecureStore store, Path config) throws Exception {
        if (!CodexConfig.tokenProActive(config)) return List.of();
        String source = Files.readString(config);
        String catalog = CodexSwitchConfig.rootValue(source, "model_catalog_json").orElseThrow();
        Set<String> selectedSlugs = new HashSet<>();
        for (PricedModel model : TokenProFrame.savedCodexModels(store))
            selectedSlugs.add(CodexConfig.routedModelId(model));
        Map<String,Object> root = new LinkedHashMap<>(Json.object(Json.parse(Files.readString(Path.of(catalog)))));
        List<Map<String,Object>> entries = new ArrayList<>();
        for (Object raw : ClaudeAdapter.list(root.get("models"))) {
            Map<String,Object> entry = new LinkedHashMap<>(Json.object(raw));
            if (!selectedSlugs.contains(entry.get("slug"))) continue;
            // The native TUI drops the opening CJK quote in descriptions. Use a
            // stable separator while leaving the desktop display name unchanged.
            String display = Objects.toString(entry.getOrDefault("display_name", entry.get("slug")));
            entry.put("description", cliDescription(display));
            entries.add(entry);
        }
        if (entries.isEmpty()) throw new IllegalStateException("当前没有可用于 Codex 命令行的模型，请先选择模型");
        root.put("models", entries);
        Path target = store.root().resolve("codex-cli-models").resolve(UUID.randomUUID() + ".json");
        CodexChannelState.writeAtomic(target, Json.stringify(root));
        String current = CodexSwitchConfig.rootValue(source, "model").orElse("");
        String active = current;
        if (entries.stream().noneMatch(entry -> active.equals(entry.get("slug"))))
            current = Objects.toString(entries.getFirst().get("slug"));
        return List.of("-c", "model_catalog_json=" + target.toAbsolutePath().toString().replace('\\', '/'),
            "-c", "model=" + current);
    }

    static String cliDescription(String display) {
        return display.replaceFirst("「([^」]+)」$", " · $1");
    }
}

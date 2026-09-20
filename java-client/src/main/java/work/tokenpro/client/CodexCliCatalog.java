package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

/** A per-launch view; never overwrites the desktop catalog or shared config. */
final class CodexCliCatalog {
    static List<String> arguments(SecureStore store, Path config) throws Exception {
        if (!CodexConfig.tokenProActive(config)) return List.of();
        String source = Files.readString(config);
        String catalog = CodexSwitchConfig.rootValue(source, "model_catalog_json").orElseThrow();
        Set<String> textSlugs = new HashSet<>();
        for (PricedModel model : TokenProFrame.savedCodexModels(store))
            if (!model.isImageGeneration()) textSlugs.add(CodexConfig.routedModelId(model));
        Map<String,Object> root = new LinkedHashMap<>(Json.object(Json.parse(Files.readString(Path.of(catalog)))));
        List<Map<String,Object>> entries = new ArrayList<>();
        for (Object raw : ClaudeAdapter.list(root.get("models"))) {
            Map<String,Object> entry = new LinkedHashMap<>(Json.object(raw));
            if (!textSlugs.contains(entry.get("slug"))) continue;
            // Native CLI renders description alongside its request slug.
            entry.put("description", entry.getOrDefault("display_name", entry.get("slug")));
            entries.add(entry);
        }
        if (entries.isEmpty()) throw new IllegalStateException("当前没有可用于 Codex 命令行的对话模型，请先选择普通模型");
        root.put("models", entries);
        Path target = store.root().resolve("codex-cli-models").resolve(UUID.randomUUID() + ".json");
        CodexChannelState.writeAtomic(target, Json.stringify(root));
        String current = CodexSwitchConfig.rootValue(source, "model").orElse("");
        if (!textSlugs.contains(current)) current = Objects.toString(entries.getFirst().get("slug"));
        return List.of("-c", "model_catalog_json=" + target.toAbsolutePath().toString().replace('\\', '/'),
            "-c", "model=" + current);
    }
}

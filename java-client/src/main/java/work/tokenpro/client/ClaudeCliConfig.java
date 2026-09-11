package work.tokenpro.client;

import java.nio.file.Path;
import java.util.*;

/** The CLI picker uses the same ordered model selection as Claude Desktop. */
final class ClaudeCliConfig {
    static final String FILE = "claude-cli-settings.json";
    private ClaudeCliConfig() {}

    static Map<String, Object> settings(List<PricedModel> models, String baseUrl, String helper) {
        List<PricedModel> ordered = ModelPickerDialog.orderedModels(models, "Claude");
        if (ordered.isEmpty()) throw new IllegalArgumentException("请至少选择一个非生图模型");
        List<ClaudeBridgeConfig.Route> routes = ClaudeBridgeConfig.routesFor(ordered);
        List<Map<String, Object>> options = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            PricedModel model = ordered.get(i);
            options.add(Map.of("model", routes.get(i).alias(), "label", model.displayName(), "description", model.displayGroupName()));
        }
        String first = routes.getFirst().alias();
        return Map.of("model", first,
            "modelPicker", Map.of("replaceBuiltInOptions", true, "options", options),
            "apiKeyHelper", helper,
            "env", Map.of("ANTHROPIC_BASE_URL", baseUrl,
                "ANTHROPIC_DEFAULT_OPUS_MODEL", first, "ANTHROPIC_DEFAULT_SONNET_MODEL", first,
                "ANTHROPIC_DEFAULT_HAIKU_MODEL", first));
    }

    static Path install(SecureStore store, List<PricedModel> models, ClaudeBridgeConfig bridge) throws Exception {
        String helper = RuntimeCommand.helperExecutable(store);
        String quoted = Platform.OS_KIND == Platform.OS.WINDOWS ? "\"" + helper + "\"" : "'" + helper.replace("'", "'\\''") + "'";
        store.write(FILE, Json.stringify(settings(models, bridge.baseUrl(), quoted)));
        return store.root().resolve(FILE).toAbsolutePath();
    }
}

package work.tokenpro.client;
import java.nio.file.*;
import java.util.*;

final class CodexCliCatalogTest {
    static int run() throws Exception {
        Path temp = Files.createTempDirectory("tokenpro-cli-catalog-");
        try {
            SecureStore store = new SecureStore(temp.resolve("store"));
            PricedModel chat = new PricedModel("gpt-text", "openai", "Text", 57);
            PricedModel image = new PricedModel("gpt-image", "openai", "openai", "Images", 65,
                "image", null, null, List.of(), false, 0d, "", " 生图 ");
            List<Map<String,Object>> selections = new ArrayList<>();
            List<Map<String,Object>> entries = new ArrayList<>();
            for (PricedModel model : List.of(chat, image)) {
                selections.add(Map.of("name", model.name(), "group_id", model.groupId(), "platform", model.platform(),
                    "group_platform", model.groupPlatform(), "group_description", model.groupDescription()));
                entries.add(Map.of("slug", CodexConfig.routedModelId(model), "display_name", model.name()));
            }
            store.write("codex-selected.json", Json.stringify(Map.of("models", selections)));
            Path catalog = temp.resolve("full.json"), config = temp.resolve("config.toml");
            String full = Json.stringify(Map.of("models", entries));
            Files.writeString(catalog, full);
            String original = "# >>> tokenpro-codex\nmodel_provider=\"openai\"\nopenai_base_url=\"https://tokenpro.work/v1\"\n"
                + "model=" + Json.stringify(CodexConfig.routedModelId(image)) + "\nmodel_catalog_json="
                + Json.stringify(catalog.toString()) + "\n# <<< tokenpro-codex\n";
            Files.writeString(config, original);
            CodexChannelState.useApiKey(store, config, "fixture-key");
            List<String> args = CodexCliCatalog.arguments(store, config);
            Path view = Path.of(args.get(1).substring("model_catalog_json=".length()));
            List<?> models = (List<?>) Json.object(Json.parse(Files.readString(view))).get("models");
            if (models.size() != 1 || !Json.object(models.getFirst()).get("slug").equals(CodexConfig.routedModelId(chat)))
                throw new AssertionError("CLI must exclude image group and retain exact text route");
            if (!args.get(3).equals("model=" + CodexConfig.routedModelId(chat))) throw new AssertionError("image default must fall back to text");
            if (!Files.readString(config).equals(original) || !Files.readString(catalog).equals(full)) throw new AssertionError("desktop data changed");
            store.write("codex-selected.json", Json.stringify(Map.of("models", List.of(selections.get(1)))));
            try { CodexCliCatalog.arguments(store, config); throw new AssertionError("image-only selection allowed"); }
            catch (IllegalStateException expected) { }
            return 4;
        } finally {
            try (var paths = Files.walk(temp)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
}

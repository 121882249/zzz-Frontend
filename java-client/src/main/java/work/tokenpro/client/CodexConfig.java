package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CodexConfig {
    private static final String START = "# >>> TokenPro managed >>>";
    private static final String END = "# <<< TokenPro managed <<<";
    private static final Pattern MANAGED_ROOT_KEY = Pattern.compile("^(model|model_provider|openai_base_url|review_model|model_catalog_json|model_reasoning_effort|model_context_window|model_auto_compact_token_limit)\\s*=.*$");
    private static final Pattern MODEL_CATALOG_ASSIGNMENT = Pattern.compile("(?m)^model_catalog_json\\s*=\\s*(['\\\"])([^'\\\"]+)\\1\\s*$");
    private static final Pattern ROOT_MODEL_ASSIGNMENT = Pattern.compile("(?m)^model\\s*=.*$");
    private static final Pattern REVIEW_MODEL_ASSIGNMENT = Pattern.compile("(?m)^review_model\\s*=.*$");
    private static final Pattern READABLE_ROUTE_MODEL = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}._:/-]{0,511}");
    private final SecureStore store;
    private final Path configPath;
    record ForeignRelayPlan(String original, String cleaned, List<String> changes) {}
    CodexConfig(SecureStore store) { this(store, Platform.codexConfig()); }
    CodexConfig(SecureStore store, Path configPath) {
        this.store = store;
        this.configPath = configPath;
    }

    static Optional<ForeignRelayPlan> foreignRelayPlan(Path configPath) throws IOException {
        if (!Files.exists(configPath)) return Optional.empty();
        String current = Files.readString(configPath);
        return CodexSwitchConfig.foreignRelayCleanup(current)
            .map(cleanup -> new ForeignRelayPlan(current, cleanup.cleaned(), cleanup.changes()));
    }

    static boolean tokenProActive(Path configPath) {
        try {
            if (!Files.isRegularFile(configPath, LinkOption.NOFOLLOW_LINKS)) return false;
            String config = Files.readString(configPath);
            Matcher catalog = MODEL_CATALOG_ASSIGNMENT.matcher(config);
            if (!catalog.find()) return false;
            Path path = Path.of(catalog.group(2));
            CodexChannelState.Detected detected = CodexChannelState.detect(configPath);
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && "openai".equals(detected.modelProvider())
                && "https://tokenpro.work/v1".equals(detected.openAiBaseUrl().replaceAll("/+$", ""))
                && detected.markerOwners().contains("tokenpro")
                && "apikey".equals(detected.authMode());
        } catch (Exception ignored) { return false; }
    }

    void apply(String baseUrl, List<PricedModel> models, String key, String accountEmail) throws Exception {
        apply(baseUrl, models, key, accountEmail, null);
    }

    void apply(String baseUrl, List<PricedModel> models, String key, String accountEmail,
               ForeignRelayPlan foreignRelayPlan) throws Exception {
        models = ModelPickerDialog.orderedModels(models, "Codex");
        String url = validateUrl(baseUrl);
        required(accountEmail, "账户邮箱");
        if (models.isEmpty()) throw new IllegalArgumentException("请至少选择一个 Codex 模型");
        List<PricedModel> chatModels = models.stream().filter(model -> !model.isImageGeneration()).toList();
        List<PricedModel> imageModels = models.stream().filter(PricedModel::isImageGeneration).toList();
        PricedModel imageModel = imageModels.isEmpty() ? null : imageModels.getFirst();
        PricedModel primaryModel = chatModels.isEmpty() ? imageModel : chatModels.getFirst();
        required(primaryModel.name(), "模型 ID");
        Path target = configPath;
        Files.createDirectories(target.getParent());
        String originalCurrent = Files.exists(target) ? Files.readString(target) : "";
        String current = originalCurrent;
        if (foreignRelayPlan != null) {
            if (!foreignRelayPlan.original().equals(originalCurrent))
                throw new IllegalStateException("Codex 配置在检测后已变化，为避免误删已取消连接，请重试");
            current = foreignRelayPlan.cleaned();
        }
        String preserved = CodexSwitchConfig.clean(current);
        if (Platform.OS_KIND == Platform.OS.WINDOWS) preserved = CodexSwitchConfig.withoutAdministrator(preserved);
        // Image models stay in their own picker group and are also written to
        // Codex's catalog so they can run directly without a selected LLM.
        Path catalog = writeModelCatalog(models);
        // Each turn selects its group-qualified slug. Native Images requests
        // correlate through the backend's authenticated turn map.
        String block = managedBlock(url, primaryModel, catalog, current);
        String candidate = CodexSwitchConfig.merge(preserved, block);
        String latest = Files.exists(target) ? Files.readString(target) : "";
        if (!originalCurrent.equals(latest))
            throw new IllegalStateException("Codex 配置在切换期间已被其他程序修改；为避免覆盖，已取消连接，请重试");
        CodexChannelState.useApiKey(store, target, key);
        if (!originalCurrent.equals(candidate)) {
            if (foreignRelayPlan == null) store.write("codex-last-switch-config.toml", originalCurrent);
            else {
                // Foreign relay removal is intentionally final: do not retain its
                // endpoint or credentials in either dedicated or generic backups.
                store.delete("codex-foreign-relay-backup.toml");
                store.delete("codex-last-switch-config.toml");
            }
            writeAtomic(target, candidate);
        }
        CodexChannelState.writeChannelCache(target, catalog);
        // Retire only our old generated catalogs after the new config is committed.
        // Failure is housekeeping, not a reason to restore a previous channel.
        try { pruneModelCatalogs(catalog); store.delete("codex-model-catalog.json"); }
        catch (IOException failure) { /* The active config already names its immutable catalog. */ }
    }

    boolean restore() throws Exception {
        Optional<String> original = store.read("codex-original.toml");
        Path target = configPath;
        String current = Files.exists(target) ? Files.readString(target) : "";
        // No selected models / no backup is a normal, repeatable no-op. If a
        // managed block survives without its backup, remove only that block.
        // Never erase unrelated settings, history, or the user's auth file.
        String baseline = original.orElseGet(() -> stripManaged(current));
        String restored = baseline;
        boolean changed = !restored.equals(current);
        if (changed) {
            Files.createDirectories(target.getParent());
            writeAtomic(target, restored);
        }
        store.delete("codex-original.toml");
        store.delete("codex-model-catalog.json");
        return changed;
    }

    /** Remove the TokenPro route without resetting the user's Windows setup or permissions. */
    void deleteForOfficial() throws IOException {
        String current = Files.exists(configPath) ? Files.readString(configPath) : "";
        String restored = CodexSwitchConfig.foreignRelayCleanup(current)
            .map(CodexSwitchConfig.ForeignRelayCleanup::cleaned).orElseGet(() -> CodexSwitchConfig.clean(current));
        if (Platform.OS_KIND == Platform.OS.WINDOWS) restored = CodexSwitchConfig.withoutAdministrator(restored);
        CodexChannelState.restoreOfficialAuth(store, configPath);
        String latest = Files.exists(configPath) ? Files.readString(configPath) : "";
        if (!current.equals(latest))
            throw new IOException("Codex 配置在切换期间已被其他程序修改；为避免覆盖，已取消切换，请重试");
        if (!current.equals(restored)) {
            Files.createDirectories(configPath.getParent());
            store.write("codex-last-switch-config.toml", current);
            writeAtomic(configPath, restored);
        }
        store.delete("codex-original.toml");
        store.delete("codex-model-catalog.json");
        store.delete("codex-selected.json");
        store.delete("codex-official-mode.txt");
        pruneModelCatalogs(null);
        CodexChannelState.markOfficialCacheStale(configPath);
    }

    /** Atomically replace the active TokenPro catalog after stale selections are pruned. */
    void refreshModelCatalog(List<PricedModel> models) throws Exception {
        models = ModelPickerDialog.orderedModels(models, "Codex");
        if (models.isEmpty() || !Files.exists(configPath)) return;
        String current = Files.readString(configPath);
        if (!CodexSwitchConfig.markerOwners(current).contains("tokenpro")
            || !MODEL_CATALOG_ASSIGNMENT.matcher(current).find()) return;
        ChannelSettingsBackup backup = new ChannelSettingsBackup(store, configPath);
        try {
            Path catalog = writeModelCatalog(models);
            PricedModel primary = models.stream().filter(model -> !model.isImageGeneration()).findFirst().orElse(models.getFirst());
            String candidate = MODEL_CATALOG_ASSIGNMENT.matcher(current)
                .replaceFirst(Matcher.quoteReplacement("model_catalog_json = " + toml(catalog.toString())));
            candidate = ROOT_MODEL_ASSIGNMENT.matcher(candidate)
                .replaceFirst(Matcher.quoteReplacement("model = " + toml(routedModelId(primary))));
            // Do not pin Codex background work (for example automatic thread
            // titles/reviews) to the model that happened to be primary when
            // TokenPro was connected. When the user changes the active model
            // later, an explicit review_model would keep billing the stale
            // model alongside the selected one.
            candidate = REVIEW_MODEL_ASSIGNMENT.matcher(candidate).replaceFirst("");
            if (!current.equals(Files.readString(configPath)))
                throw new IOException("Codex 配置在模型刷新期间已被其他程序修改；已取消刷新");
            writeAtomic(configPath, candidate);
            CodexChannelState.writeChannelCache(configPath, catalog);
            pruneModelCatalogs(catalog);
            CodexChannelState.verify(configPath, CodexChannel.tokenPro());
        } catch (Exception failure) {
            try { backup.restore(); }
            catch (Exception rollback) { failure.addSuppressed(rollback); }
            throw failure;
        }
    }

    private void pruneModelCatalogs(Path keep) throws IOException {
        Path mappings = store.root().resolve("codex-models");
        if (Files.isDirectory(mappings, LinkOption.NOFOLLOW_LINKS)) try (var files = Files.list(mappings)) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("[a-f0-9-]{36}\\.json")).toList())
                if (!path.equals(keep)) Files.deleteIfExists(path);
        }
    }

    private String managedBlock(String url, PricedModel model, Path catalog, String current) throws IOException {
        StringBuilder out = new StringBuilder();
        String routedModel = routedModelId(model);
        out.append(START).append('\n');
        out.append("model = ").append(toml(routedModel)).append('\n');
        out.append("model_provider = \"openai\"\n");
        out.append("openai_base_url = ").append(toml(providerBaseUrl(url))).append("\n\n");
        out.append("model_context_window = 372000\nmodel_auto_compact_token_limit = 372000\n");
        // Keep the exact group-qualified slug while using Codex's built-in
        // OpenAI provider. auth.json supplies TokenPro's global API key.
        Map<String, Object> catalogRoot = Json.object(Json.parse(Files.readString(catalog)));
        Map<String, Object> profile = ((List<?>) catalogRoot.get("models")).stream().map(Json::object)
            .filter(entry -> routedModel.equals(entry.get("slug"))).findFirst().orElseThrow();
        out.append(CodexPreferences.retainedLines(current, profile));
        out.append("model_catalog_json = ").append(toml(catalog.toAbsolutePath().toString())).append("\n");
        out.append(END).append('\n');
        return out.toString();
    }

    static String providerBaseUrl(String url) {
        return url;
    }

    static String routedModelId(PricedModel model) {
        String name = model.name().trim();
        if (READABLE_ROUTE_MODEL.matcher(name).matches()) return "tp-g" + model.groupId() + "-" + name;
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(name.getBytes(StandardCharsets.UTF_8));
        return "tp-g" + model.groupId() + "-" + encoded;
    }

    private Path writeModelCatalog(List<PricedModel> models) throws Exception {
        Path output = Files.createTempFile("tokenpro-codex-models-", ".json");
        Path cleanHome = Files.createTempDirectory("tokenpro-codex-home-");
        Map<String, Object> bundled;
        try {
            Path executable = Platform.codexExecutable().orElseThrow(() -> new IllegalStateException("Codex 未安装"));
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "debug", "models", "--bundled")
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("CODEX_HOME", cleanHome.toString());
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("读取 Codex 模型格式超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Codex 无法提供本机模型格式");
            bundled = Json.object(Json.parse(Files.readString(output, StandardCharsets.UTF_8)));
            if (!(bundled.get("models") instanceof List<?> list) || list.isEmpty()) throw new IOException("空模型模板");
        } catch (Exception unavailable) {
            // Local schema discovery is optional. It must not test model access or
            // prevent selected model/group pairs from being written on a fresh install.
            bundled = Map.of("models", List.of(fallbackTemplate()));
        } finally {
            Files.deleteIfExists(output);
            deleteTree(cleanHome);
        }
        Object raw = bundled.get("models");
        if (!(raw instanceof List<?> templates) || templates.isEmpty()) throw new IllegalStateException("Codex 模型格式为空");
        Map<String, Map<String, Object>> bySlug = new HashMap<>();
        for (Object item : templates) {
            Map<String, Object> template = Json.object(item);
            bySlug.put(String.valueOf(template.getOrDefault("slug", "")), template);
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        int priority = 1;
        for (PricedModel model : models) {
            Map<String, Object> exact = bySlug.get(model.name());
            Map<String, Object> closest = exact;
            if (closest == null) closest = bySlug.get("gpt-5.6-sol");
            if (closest == null) closest = bySlug.values().stream().min(Comparator.comparing(item -> String.valueOf(item.get("slug")))).orElseThrow();
            Map<String, Object> entry = deepCopy(closest);
            entry.put("slug", routedModelId(model));
            entry.put("display_name", catalogDisplayName(model));
            entry.put("description", model.groupName() + " · TokenPro");
            entry.put("visibility", "list");
            entry.put("supported_in_api", true);
            entry.put("priority", priority++);
            entry.put("availability_nux", null);
            entry.put("upgrade", null);
            // TokenPro uses Codex's built-in OpenAI API-key provider. Responses
            // Lite is a ChatGPT-only wire mode and rejects hosted tools such as
            // image_generation before the gateway can normalize image-only
            // selections into a text driver plus image tool.
            disableResponsesLite(entry);
            applyNativeCapabilities(entry, model, exact);
            entries.add(entry);
        }
        // Immutable catalogs keep the previous config internally consistent until
        // the new config's atomic rename completes, without rolling back a channel.
        Path target = store.root().resolve("codex-models").resolve(UUID.randomUUID() + ".json");
        CodexChannelState.writeAtomic(target, Json.stringify(Map.of("models", entries)));
        return target;
    }

    static Map<String,Object> fallbackTemplate() {
        return Json.object(Json.parse("""
            {"slug":"tokenpro-template","display_name":"TokenPro","description":"",
             "default_reasoning_level":"high","supported_reasoning_levels":[],"shell_type":"unified_exec",
             "visibility":"list","supported_in_api":true,"priority":1,"base_instructions":"You are a coding assistant.",
             "supports_reasoning_summaries":false,"support_verbosity":false,"default_verbosity":null,
             "apply_patch_tool_type":"freeform","web_search_tool_type":"text_and_image",
             "truncation_policy":{"mode":"tokens","limit":10000},"context_window":372000,
             "effective_context_window_percent":95,"experimental_supported_tools":[],
             "input_modalities":["text","image"],"use_responses_lite":false}
            """));
    }

    static void disableResponsesLite(Map<String, Object> entry) {
        entry.put("use_responses_lite", false);
    }

    static void applyReasoningProfile(Map<String, Object> entry, PricedModel model) {
        List<Map<String, Object>> levels = new ArrayList<>();
        Map<String, Map<String, Object>> nativeByEffort = new HashMap<>();
        if (entry.get("supported_reasoning_levels") instanceof List<?> nativeLevels) {
            for (Object raw : nativeLevels) {
                Map<String, Object> level = Json.object(raw);
                nativeByEffort.put(String.valueOf(level.getOrDefault("effort", "")), level);
            }
        }
        for (String effort : inferredReasoningEfforts(model)) {
            Map<String, Object> nativeLevel = nativeByEffort.get(effort);
            levels.add(nativeLevel == null ? reasoningLevel(effort) : new LinkedHashMap<>(nativeLevel));
        }
        entry.put("supported_reasoning_levels", levels);
        if (levels.isEmpty()) {
            entry.put("default_reasoning_level", null);
            return;
        }
        Set<String> supported = new LinkedHashSet<>();
        for (Map<String, Object> level : levels) supported.add(String.valueOf(level.get("effort")));
        String current = String.valueOf(entry.getOrDefault("default_reasoning_level", "medium"));
        entry.put("default_reasoning_level", supported.contains(current) ? current : supported.contains("medium") ? "medium" : supported.iterator().next());
    }

    /** A schema template is not evidence that another model supports Ultra/Fast. */
    static void applyNativeCapabilities(Map<String, Object> entry, PricedModel model, Map<String, Object> exact) {
        if (exact == null) {
            applyReasoningProfile(entry, model);
            entry.put("service_tiers", List.of());
            entry.remove("default_service_tier");
            return;
        }
        if (model.isImageGeneration()) {
            entry.put("supported_reasoning_levels", List.of());
            entry.put("default_reasoning_level", null);
        } else {
            Object nativeLevels = exact.get("supported_reasoning_levels");
            entry.put("supported_reasoning_levels", nativeLevels instanceof List<?> ? normalized(nativeLevels) : List.of());
            entry.put("default_reasoning_level", exact.get("default_reasoning_level"));
        }
        Object tiers = exact.get("service_tiers");
        entry.put("service_tiers", tiers instanceof List<?> ? normalized(tiers) : List.of());
        if (exact.containsKey("default_service_tier")) entry.put("default_service_tier", exact.get("default_service_tier"));
        else entry.remove("default_service_tier");
    }

    static List<String> inferredReasoningEfforts(PricedModel model) {
        String value = (model.name() + " " + model.platform()).toLowerCase(Locale.ROOT);
        if (value.contains("image") || value.contains("dall-e") || value.contains("imagen") || value.contains("flux")) return List.of();
        return List.of("low", "medium", "high", "xhigh", "max");
    }

    static String catalogDisplayName(PricedModel model) {
        String description = model.groupDescription().replaceAll("\\s+", " ").trim();
        return model.codexDisplayName() + (description.isEmpty() ? "" : "「" + description + "」");
    }

    static String catalogDisplayName(PricedModel model, boolean duplicateName) {
        return catalogDisplayName(model);
    }

    private static Map<String, Object> reasoningLevel(String effort) {
        String description = switch (effort) {
            case "low" -> "Fast responses with lighter reasoning";
            case "medium" -> "Balances speed and reasoning depth";
            case "high" -> "Greater reasoning depth for complex problems";
            case "xhigh" -> "Extra high reasoning depth";
            case "max" -> "Maximum reasoning depth";
            default -> effort;
        };
        return new LinkedHashMap<>(Map.of("effort", effort, "description", description));
    }

    private static Map<String, Object> deepCopy(Map<String, Object> value) {
        Map<String, Object> copy = new LinkedHashMap<>();
        value.forEach((key, item) -> copy.put(key, normalized(item)));
        return copy;
    }

    private static Object normalized(Object value) {
        if (value instanceof Double number && Double.isFinite(number) && number == Math.rint(number)
            && number >= Long.MIN_VALUE && number <= Long.MAX_VALUE) return number.longValue();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(String.valueOf(key), normalized(item)));
            return copy;
        }
        if (value instanceof List<?> list) return list.stream().map(CodexConfig::normalized).toList();
        return value;
    }

    static String stripManaged(String text) {
        if (text.contains("# >>> TokenPro model selection >>>") || text.contains("# >>> tokenpro-codex"))
            return CodexSwitchConfig.clean(text);
        int start = text.indexOf(START);
        if (start < 0) return text;
        int end = text.indexOf(END, start);
        if (end < 0) throw new IllegalStateException("Codex 配置中的 TokenPro 标记不完整，已停止修改");
        end += END.length();
        if (end < text.length() && text.charAt(end) == '\r') end++;
        if (end < text.length() && text.charAt(end) == '\n') end++;
        return text.substring(0, start) + text.substring(end);
    }

    static String stripRootOverrides(String text) {
        if (text.contains("\"\"\"") || text.contains("'''")) {
            throw new IllegalStateException("Codex 配置包含多行字符串，请先手动检查后再接入");
        }
        StringBuilder out = new StringBuilder();
        boolean root = true;
        for (String line : text.split("(?<=\\n)", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("[")) root = false;
            String withoutNewline = trimmed.replaceFirst("[\\r\\n]+$", "");
            if (root && MANAGED_ROOT_KEY.matcher(withoutNewline).matches()) continue;
            out.append(line);
        }
        return out.toString();
    }

    static String restoreRootOverrides(String current, String original) {
        StringBuilder rootOverrides = new StringBuilder();
        boolean root = true;
        for (String line : original.split("(?<=\\n)", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("[")) root = false;
            String withoutNewline = trimmed.replaceFirst("[\\r\\n]+$", "");
            if (root && MANAGED_ROOT_KEY.matcher(withoutNewline).matches()) rootOverrides.append(line);
        }
        String clean = stripRootOverrides(current);
        if (rootOverrides.isEmpty()) return clean;
        if (rootOverrides.charAt(rootOverrides.length() - 1) != '\n') rootOverrides.append('\n');
        return rootOverrides + clean.stripLeading();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String validateUrl(String raw) {
        String value = required(raw, "接口地址");
        java.net.URI uri = java.net.URI.create(value);
        String host = uri.getHost();
        boolean local = "http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "::1").contains(host);
        if (!("https".equals(uri.getScheme()) || local) || host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("接口地址需要 HTTPS；本机测试可使用 localhost");
        }
        return value.replaceAll("/+$", "");
    }

    private static String required(String value, String label) {
        String result = value == null ? "" : value.trim();
        if (result.isEmpty() || result.contains("\n") || result.contains("\r")) throw new IllegalArgumentException("请填写" + label);
        return result;
    }

    private static String toml(String value) {
        StringBuilder out = new StringBuilder("\"");
        value.codePoints().forEach(c -> {
            if (c == '"') out.append("\\\"");
            else if (c == '\\') out.append("\\\\");
            else if (c < 0x20 || c == 0x7f) out.append(String.format("\\u%04X", c));
            else out.appendCodePoint(c);
        });
        return out.append('"').toString();
    }

    private static void writeAtomic(Path target, String value) throws IOException {
        CodexChannelState.writeAtomic(target, value);
    }

}

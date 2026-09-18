package work.tokenpro.client;

import java.io.BufferedReader;
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
    private static final String HISTORY_PROVIDER_START = "# >>> TokenPro history provider >>>";
    private static final String HISTORY_PROVIDER_END = "# <<< TokenPro history provider <<<";
    private static final Pattern MANAGED_ROOT_KEY = Pattern.compile("^(model|model_provider|review_model|model_catalog_json|model_reasoning_effort|model_context_window|model_auto_compact_token_limit)\\s*=.*$");
    private static final Pattern HISTORY_PROVIDER_ID = Pattern.compile("\\\"model_provider\\\"\\s*:\\s*\\\"([A-Za-z0-9_-]{1,64})\\\"");
    private static final Pattern MODEL_CATALOG_ASSIGNMENT = Pattern.compile("(?m)^model_catalog_json\\s*=\\s*(['\\\"])([^'\\\"]+)\\1\\s*$");
    private static final Pattern ROOT_MODEL_ASSIGNMENT = Pattern.compile("(?m)^model\\s*=.*$");
    private static final Pattern REVIEW_MODEL_ASSIGNMENT = Pattern.compile("(?m)^review_model\\s*=.*$");
    private static final Set<String> RESERVED_PROVIDER_IDS = Set.of("openai", "ollama", "lmstudio");
    private final SecureStore store;
    private final Path configPath;
    private final boolean preserveDesktopHistoryProvider;
    record ForeignRelayPlan(String original, String cleaned, List<String> changes) {}
    CodexConfig(SecureStore store) { this(store, Platform.codexConfig(), true); }
    CodexConfig(SecureStore store, Path configPath) { this(store, configPath, false); }
    private CodexConfig(SecureStore store, Path configPath, boolean preserveDesktopHistoryProvider) {
        this.store = store;
        this.configPath = configPath;
        this.preserveDesktopHistoryProvider = preserveDesktopHistoryProvider;
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
            if (!config.contains(START) || !config.contains(END) || !catalog.find()) return false;
            if (!config.matches("(?s).*base_url\\s*=\\s*['\\\"]https://tokenpro\\.work/v1/?['\\\"].*")) return false;
            Path path = Path.of(catalog.group(2));
            CodexChannelState.Detected detected = CodexChannelState.detect(configPath);
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && "custom".equals(detected.modelProvider())
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
        String actor = required(accountEmail, "账户邮箱");
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
        String block = managedBlock(url, primaryModel, catalog, key.trim(), actor, current, Set.of("custom"));
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
        String restored = preserveDesktopHistoryProvider
            ? restoreWithHistoryCompatibility(baseline, historicalProviderIds())
            : baseline;
        boolean changed = !restored.equals(current);
        if (changed) {
            Files.createDirectories(target.getParent());
            // Desktop restore adds a compatibility provider and must be parsed
            // by the installed Codex binary. CLI/test restores are byte-for-byte
            // copies and may run on clean build hosts without Codex installed.
            if (preserveDesktopHistoryProvider) validate(restored);
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
        if (!current.contains(START) || !MODEL_CATALOG_ASSIGNMENT.matcher(current).find()) return;
        ChannelSettingsBackup backup = new ChannelSettingsBackup(store, configPath);
        try {
            Path catalog = writeModelCatalog(models);
            PricedModel primary = models.stream().filter(model -> !model.isImageGeneration()).findFirst().orElse(models.getFirst());
            String candidate = MODEL_CATALOG_ASSIGNMENT.matcher(current)
                .replaceFirst(Matcher.quoteReplacement("model_catalog_json = " + toml(catalog.toString())));
            candidate = ROOT_MODEL_ASSIGNMENT.matcher(candidate)
                .replaceFirst(Matcher.quoteReplacement("model = " + toml(routedModelId(primary))));
            candidate = REVIEW_MODEL_ASSIGNMENT.matcher(candidate)
                .replaceFirst(Matcher.quoteReplacement("review_model = " + toml(routedModelId(primary))));
            candidate = candidate.replaceFirst("(\\\"x-tokenpro-group-id\\\"\\s*=\\s*)\\\"[^\\\"]*\\\"",
                "$1\\\"" + primary.groupId() + "\\\"");
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

    static String restoreContent(String current, Optional<String> original, boolean preserveDesktopHistoryProvider) {
        String baseline = original.orElseGet(() -> stripManaged(current));
        // Upgraded desktop users may no longer have codex-original.toml because
        // an earlier restore already consumed it. They still need the `custom`
        // alias: old conversations persist that provider id in their history.
        return preserveDesktopHistoryProvider ? restoreWithHistoryCompatibility(baseline) : baseline;
    }

    static String restoreWithHistoryCompatibility(String original) {
        return restoreWithHistoryCompatibility(original, Set.of("custom"));
    }

    static String restoreWithHistoryCompatibility(String original, Collection<String> historicalProviderIds) {
        String clean = stripMarkedBlock(original, HISTORY_PROVIDER_START, HISTORY_PROVIDER_END);
        if (clean.contains(START)) clean = stripManaged(clean);

        Optional<String> originalProvider = rootAssignment(clean, "model_provider");
        if (originalProvider.isPresent() && !assignmentValue(originalProvider.get()).equals("openai")) {
            // A stale relay selection must not remain the default after the
            // user explicitly restores official mode. Old provider ids remain
            // available below only for opening their persisted conversations.
            clean = stripRootOverrides(clean);
        }
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add("custom");
        historicalProviderIds.stream().filter(CodexConfig::compatibleProviderId).sorted().forEach(aliases::add);
        String restoredBase = clean;
        List<String> missing = aliases.stream()
            .filter(id -> table(restoredBase, providerHeader(id)).isEmpty())
            .toList();

        StringBuilder out = new StringBuilder();
        boolean bom = clean.startsWith("\uFEFF");
        if (bom) clean = clean.substring(1);
        if (bom) out.append('\uFEFF');
        out.append(clean);
        if (!missing.isEmpty()) {
            if (!clean.isEmpty() && !clean.endsWith("\n")) out.append('\n');
            if (!clean.isBlank()) out.append('\n');
            out.append(HISTORY_PROVIDER_START).append('\n');
            for (String id : missing) out.append(officialHistoryProvider(id));
            out.append(HISTORY_PROVIDER_END).append('\n');
        }
        return out.toString();
    }

    private static String officialHistoryProvider(String id) {
        // Codex persists provider ids in conversations. Keep each old id
        // resolvable, but route it through the user's normal OpenAI login while
        // official mode is active. No TokenPro key or catalog remains.
        return providerHeader(id) + "\n"
            + "name = \"OpenAI\"\n"
            + "wire_api = \"responses\"\n"
            + "requires_openai_auth = true\n"
            + "supports_websockets = true\n"
            + "supports_standalone_web_search = true\n";
    }

    private Set<String> historicalProviderIds() {
        Path root = configPath.getParent();
        return root == null ? Set.of("custom") : historicalProviderIds(root.resolve("sessions"));
    }

    static Set<String> historicalProviderIds(Path sessionsRoot) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add("custom");
        if (!Files.isDirectory(sessionsRoot)) return ids;
        try (var paths = Files.walk(sessionsRoot)) {
            Iterator<Path> iterator = paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jsonl")).iterator();
            while (iterator.hasNext() && ids.size() < 64) {
                try (BufferedReader reader = Files.newBufferedReader(iterator.next(), StandardCharsets.UTF_8)) {
                    String line;
                    int metadataLines = 24;
                    while (metadataLines-- > 0 && (line = reader.readLine()) != null && ids.size() < 64) {
                        Matcher matcher = HISTORY_PROVIDER_ID.matcher(line);
                        while (matcher.find() && ids.size() < 64) {
                            String id = matcher.group(1);
                            if (compatibleProviderId(id)) ids.add(id);
                        }
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
        return ids;
    }

    private static boolean compatibleProviderId(String id) {
        return id != null && id.matches("[A-Za-z0-9_-]{1,64}") && !RESERVED_PROVIDER_IDS.contains(id);
    }

    private static String providerHeader(String id) {
        if (!compatibleProviderId(id)) throw new IllegalArgumentException("不支持的 Codex 服务商标识");
        return "[model_providers." + id + "]";
    }

    private static Optional<String> rootAssignment(String text, String key) {
        boolean root = true;
        Pattern assignment = Pattern.compile("^" + Pattern.quote(key) + "\\s*=.*$");
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("[")) root = false;
            if (root && assignment.matcher(trimmed).matches()) return Optional.of(trimmed);
        }
        return Optional.empty();
    }

    private static String assignmentValue(String assignment) {
        int equals = assignment.indexOf('=');
        if (equals < 0) return "";
        String value = assignment.substring(equals + 1).trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) return value.substring(1, value.length() - 1);
        return value;
    }

    private static Optional<String> table(String text, String header) {
        StringBuilder out = new StringBuilder();
        boolean collecting = false;
        for (String line : text.split("(?<=\\n)", -1)) {
            String trimmed = line.strip();
            if (!collecting) {
                if (!trimmed.equals(header)) continue;
                collecting = true;
            } else if (trimmed.startsWith("[") && !trimmed.startsWith(header.substring(0, header.length() - 1) + ".")) {
                break;
            } else if (trimmed.equals(END) || trimmed.equals(HISTORY_PROVIDER_END)) {
                break;
            }
            out.append(line);
        }
        return collecting ? Optional.of(out.toString().stripTrailing()) : Optional.empty();
    }

    private static String stripMarkedBlock(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        if (start < 0) return text;
        int end = text.indexOf(endMarker, start);
        if (end < 0) throw new IllegalStateException("Codex 配置中的 TokenPro 历史兼容标记不完整，已停止修改");
        end += endMarker.length();
        if (end < text.length() && text.charAt(end) == '\r') end++;
        if (end < text.length() && text.charAt(end) == '\n') end++;
        return text.substring(0, start) + text.substring(end);
    }

    void updateActor(String accountEmail) throws Exception {
        String actor = required(accountEmail, "账户邮箱");
        Path target = configPath;
        if (!Files.exists(target)) return;
        String current = Files.readString(target);
        String updated = withActor(current, actor);
        if (!updated.equals(current)) writeAtomic(target, updated);
    }

    static String withActor(String current, String actor) {
        int start = current.indexOf(START);
        int end = start < 0 ? -1 : current.indexOf(END, start);
        if (start < 0 || end < 0) return current;
        String managed = current.substring(start, end);
        managed = managed.replaceAll("(?m)^name\\s*=.*$", Matcher.quoteReplacement("name = " + toml(actor)));
        Pattern header = Pattern.compile("(\\\"x-openai-actor-authorization\\\"\\s*=\\s*)\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"");
        Matcher matcher = header.matcher(managed);
        if (matcher.find()) {
            String replacement = matcher.group(1) + toml(actor);
            managed = matcher.replaceAll(Matcher.quoteReplacement(replacement));
        }
        return current.substring(0, start) + managed + current.substring(end);
    }

    private String managedBlock(String url, PricedModel model, Path catalog, String key, String actor, String current, Collection<String> historicalProviderIds) throws IOException {
        StringBuilder out = new StringBuilder();
        String routedModel = routedModelId(model);
        out.append(START).append('\n');
        out.append("model = ").append(toml(routedModel)).append('\n');
        out.append("model_provider = \"custom\"\n");
        out.append("review_model = ").append(toml(routedModel)).append("\n\n");
        out.append("model_context_window = 372000\nmodel_auto_compact_token_limit = 372000\n");
        // Keep the TokenPro route on the Responses API without invoking Codex's
        // first-party OpenAI login path. That path replaces the supplied key and
        // loses the account's model-group routing, which especially breaks Gemini.
        Map<String, Object> catalogRoot = Json.object(Json.parse(Files.readString(catalog)));
        Map<String, Object> profile = ((List<?>) catalogRoot.get("models")).stream().map(Json::object)
            .filter(entry -> routedModel.equals(entry.get("slug"))).findFirst().orElseThrow();
        out.append(CodexPreferences.retainedLines(current, profile));
        out.append("model_catalog_json = ").append(toml(catalog.toAbsolutePath().toString())).append("\n\n");
        out.append(providerConfiguration("custom", url, key, actor, null));
        historicalProviderIds.stream().filter(id -> !"custom".equals(id)).filter(CodexConfig::compatibleProviderId).sorted()
            .forEach(id -> out.append(providerConfiguration(id, url, key, actor, null)));
        // Codex reserves built-in provider IDs; never overwrite 'openai'.
        // Official threads may retain their provider; the UI must not claim
        // they have switched just because this default config was saved.
        out.append(END).append('\n');
        return out.toString();
    }

    static String providerConfiguration(String id, String url, String key, String actor, Long groupId) {
        StringBuilder out = new StringBuilder(providerHeader(id)).append('\n');
        out.append("name = ").append(toml(actor)).append('\n');
        // Keep /v1 in the provider URL. Codex appends /responses to this value;
        // dropping /v1 sends traffic through TokenPro's legacy generic endpoint,
        // which bypasses the OpenAI image-only model normalization path.
        out.append("base_url = ").append(toml(providerBaseUrl(url))).append('\n');
        out.append("wire_api = \"responses\"\n");
        out.append("requires_openai_auth = false\n");
        out.append("experimental_bearer_token = ").append(toml(key)).append('\n');
        out.append("http_headers = { \"x-openai-actor-authorization\" = ").append(toml(actor));
        if (groupId != null) out.append(", \"x-tokenpro-group-id\" = ").append(toml(Long.toString(groupId)));
        // Native image delivery is selected by the backend from the routed
        // group's platform and description, never for this entire provider.
        out.append(" }\n");
        out.append("supports_websockets = false\n\n");
        return out.toString();
    }

    static String providerBaseUrl(String url) {
        return url;
    }

    static String routedModelId(PricedModel model) {
        String encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(model.name().getBytes(StandardCharsets.UTF_8));
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
        Map<String, Long> nameCounts = new HashMap<>();
        for (PricedModel model : models) nameCounts.merge(model.name().toLowerCase(Locale.ROOT), 1L, Long::sum);
        int priority = 1;
        for (PricedModel model : models) {
            Map<String, Object> exact = bySlug.get(model.name());
            Map<String, Object> closest = exact;
            if (closest == null) closest = bySlug.get("gpt-5.6-sol");
            if (closest == null) closest = bySlug.values().stream().min(Comparator.comparing(item -> String.valueOf(item.get("slug")))).orElseThrow();
            Map<String, Object> entry = deepCopy(closest);
            entry.put("slug", routedModelId(model));
            entry.put("display_name", catalogDisplayName(model,
                nameCounts.getOrDefault(model.name().toLowerCase(Locale.ROOT), 0L) > 1));
            entry.put("description", model.groupName() + " · TokenPro");
            entry.put("visibility", "list");
            entry.put("supported_in_api", true);
            entry.put("priority", priority++);
            entry.put("availability_nux", null);
            entry.put("upgrade", null);
            // TokenPro is a custom API-key provider. Responses Lite is a
            // ChatGPT-only wire mode and rejects hosted tools such as
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
        return model.codexDisplayName();
    }

    static String catalogDisplayName(PricedModel model, boolean duplicateName) {
        if (!duplicateName) return catalogDisplayName(model);
        String description = model.groupDescription().replaceAll("\\s+", " ").trim();
        if (description.isEmpty()) description = model.displayGroupName();
        return catalogDisplayName(model) + "「" + description + "」";
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

    private void validate(String candidate) throws Exception {
        Path executable = Platform.codexExecutable().orElseThrow(() -> new IllegalStateException("没有找到 Codex 程序，无法校验配置"));
        Path validationHome = Files.createTempDirectory("tokenpro-codex-validate-");
        Path output = Files.createTempFile("tokenpro-codex-validate-", ".json");
        try {
            Files.writeString(validationHome.resolve("config.toml"), candidate, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder(executable.toString(), "debug", "models", "--bundled")
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().put("CODEX_HOME", validationHome.toString());
            Process process = builder.start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Codex 配置校验超时，未修改原配置");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Codex 配置校验失败，未修改原配置");
        } finally {
            Files.deleteIfExists(output);
            deleteTree(validationHome);
        }
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

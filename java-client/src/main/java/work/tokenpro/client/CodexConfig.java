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
    private static final Pattern MANAGED_ROOT_KEY = Pattern.compile("^(model|model_provider|review_model|model_catalog_json|model_reasoning_effort|model_context_window|model_auto_compact_token_limit)\\s*=.*$");
    private final SecureStore store;
    CodexConfig(SecureStore store) { this.store = store; }

    void apply(String baseUrl, List<PricedModel> models, String key, String accountEmail) throws Exception {
        String url = validateUrl(baseUrl);
        String actor = required(accountEmail, "账户邮箱");
        if (models.isEmpty()) throw new IllegalArgumentException("请至少选择一个 Codex 模型");
        List<PricedModel> chatModels = models.stream().filter(model -> !model.isImageGeneration()).toList();
        List<PricedModel> imageModels = models.stream().filter(PricedModel::isImageGeneration).toList();
        if (chatModels.isEmpty()) throw new IllegalArgumentException("请至少选择 1 个 LLM Model");
        if (imageModels.size() != 1) throw new IllegalArgumentException("请选择 1 个 Image Model");
        PricedModel imageModel = imageModels.getFirst();
        PricedModel primaryModel = chatModels.getFirst();
        required(primaryModel.name(), "模型 ID");
        Path target = Platform.codexConfig();
        Files.createDirectories(target.getParent());
        String current = Files.exists(target) ? Files.readString(target) : "";
        if (store.read("codex-original.toml").isEmpty()) store.write("codex-original.toml", current);
        String clean = stripRootOverrides(stripManaged(current));
        // The Codex picker only needs the allowed LLMs. The selected image
        // model stays behind the scenes in the provider header and is paired
        // with whichever LLM is active.
        Path catalog = writeModelCatalog(chatModels);
        String block = managedBlock(url, primaryModel, imageModel, catalog, key.trim(), actor);
        writeAtomic(target, block + (clean.isBlank() ? "" : "\n" + clean.stripLeading()));
    }

    void restore() throws Exception {
        Optional<String> original = store.read("codex-original.toml");
        if (original.isEmpty()) throw new IllegalStateException("没有可恢复的 Codex 配置备份");
        Path target = Platform.codexConfig();
        Files.createDirectories(target.getParent());
        String current = Files.exists(target) ? Files.readString(target) : "";
        writeAtomic(target, restoreRootOverrides(stripManaged(current), original.get()));
        store.delete("codex-original.toml");
        store.delete("codex-model-catalog.json");
    }

    void updateActor(String accountEmail) throws Exception {
        String actor = required(accountEmail, "账户邮箱");
        Path target = Platform.codexConfig();
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
        managed = managed.replaceFirst("(?m)^name\\s*=.*$", Matcher.quoteReplacement("name = " + toml(actor)));
        Pattern header = Pattern.compile("(\\\"x-openai-actor-authorization\\\"\\s*=\\s*)\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"");
        Matcher matcher = header.matcher(managed);
        if (matcher.find()) {
            String replacement = matcher.group(1) + toml(actor);
            managed = matcher.replaceFirst(Matcher.quoteReplacement(replacement));
        }
        return current.substring(0, start) + managed + current.substring(end);
    }

    private String managedBlock(String url, PricedModel model, PricedModel imageModel, Path catalog, String key, String actor) {
        StringBuilder out = new StringBuilder();
        out.append(START).append('\n');
        out.append("model = ").append(toml(model.name())).append('\n');
        out.append("model_provider = \"custom\"\n");
        out.append("review_model = ").append(toml(model.name())).append("\n\n");
        // Keep the TokenPro route on the Responses API without invoking Codex's
        // first-party OpenAI login path. That path replaces the supplied key and
        // loses the account's model-group routing, which especially breaks Gemini.
        if (!inferredReasoningEfforts(model).isEmpty()) out.append("model_reasoning_effort = \"high\"\n");
        out.append("model_context_window = 372000\n");
        out.append("model_auto_compact_token_limit = 372000\n\n");
        out.append("model_catalog_json = ").append(toml(catalog.toAbsolutePath().toString())).append("\n\n");
        out.append("[model_providers.custom]\n");
        out.append("name = ").append(toml(actor)).append('\n');
        // Keep /v1 in the provider URL. Codex appends /responses to this value;
        // dropping /v1 sends traffic through TokenPro's legacy generic endpoint,
        // which bypasses the OpenAI image-only model normalization path.
        out.append("base_url = ").append(toml(providerBaseUrl(url))).append('\n');
        out.append("wire_api = \"responses\"\n");
        out.append("requires_openai_auth = false\n");
        out.append("experimental_bearer_token = ").append(toml(key)).append('\n');
        out.append("http_headers = { \"x-openai-actor-authorization\" = ").append(toml(actor));
        if (imageModel != null) out.append(", \"x-tokenpro-image-model\" = ").append(toml(imageModel.name()));
        out.append(" }\n");
        out.append("supports_websockets = false\n\n");
        out.append(END).append('\n');
        return out.toString();
    }

    static String providerBaseUrl(String url) {
        return url;
    }

    private Path writeModelCatalog(List<PricedModel> models) throws Exception {
        Path executable = Platform.codexExecutable().orElseThrow(() -> new IllegalStateException("没有找到 Codex 程序，无法生成兼容的模型列表"));
        Path output = Files.createTempFile("tokenpro-codex-models-", ".json");
        Map<String, Object> bundled;
        try {
            Process process = new ProcessBuilder(executable.toString(), "debug", "models", "--bundled")
                .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("读取 Codex 模型格式超时");
            }
            if (process.exitValue() != 0) throw new IllegalStateException("Codex 无法提供本机模型格式");
            bundled = Json.object(Json.parse(Files.readString(output, StandardCharsets.UTF_8)));
        } finally {
            Files.deleteIfExists(output);
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
            entry.put("slug", model.name());
            entry.put("display_name", catalogDisplayName(model));
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
            applyReasoningProfile(entry, model);
            entries.add(entry);
        }
        Path target = store.root().resolve("codex-model-catalog.json");
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), ".tokenpro-models-", ".json");
        Files.writeString(temp, Json.stringify(Map.of("models", entries)), StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Platform.privateFile(temp);
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        Platform.privateFile(target);
        return target;
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

    static List<String> inferredReasoningEfforts(PricedModel model) {
        String value = (model.name() + " " + model.platform()).toLowerCase(Locale.ROOT);
        if (value.contains("image") || value.contains("dall-e") || value.contains("imagen") || value.contains("flux")) return List.of();
        return List.of("low", "medium", "high", "xhigh", "max");
    }

    static String catalogDisplayName(PricedModel model) {
        return model.codexDisplayName();
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
        Path temp = Files.createTempFile(target.getParent(), ".tokenpro-", ".toml");
        Files.writeString(temp, value, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Platform.privateFile(temp);
        try { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        Platform.privateFile(target);
    }

}

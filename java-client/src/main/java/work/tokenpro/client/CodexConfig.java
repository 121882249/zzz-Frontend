package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class CodexConfig {
    private static final String START = "# >>> TokenPro managed >>>";
    private static final String END = "# <<< TokenPro managed <<<";
    private final SecureStore store;
    CodexConfig(SecureStore store) { this.store = store; }

    void apply(String baseUrl, List<PricedModel> models, String key) throws Exception {
        String url = validateUrl(baseUrl);
        if (models.isEmpty()) throw new IllegalArgumentException("请至少选择一个 Codex 模型");
        String modelId = required(models.getFirst().name(), "模型 ID");
        Path target = Platform.codexConfig();
        Files.createDirectories(target.getParent());
        String current = Files.exists(target) ? Files.readString(target) : "";
        if (store.read("codex-original.toml").isEmpty()) store.write("codex-original.toml", current);
        String clean = stripRootOverrides(stripManaged(current));
        Path catalog = writeModelCatalog(models);
        String block = managedBlock(url, modelId, catalog, key.trim());
        writeAtomic(target, block + (clean.isBlank() ? "" : "\n" + clean.stripLeading()));
    }

    void apply(String baseUrl, String model, String key) throws Exception {
        apply(baseUrl, List.of(new PricedModel(model, "openai", "TokenPro", 0)), key);
    }

    void restore() throws Exception {
        Optional<String> original = store.read("codex-original.toml");
        if (original.isEmpty()) throw new IllegalStateException("没有可恢复的 Codex 配置备份");
        Path target = Platform.codexConfig();
        Files.createDirectories(target.getParent());
        writeAtomic(target, original.get());
        store.delete("codex-original.toml");
        store.delete("codex-model-catalog.json");
    }

    private String managedBlock(String url, String model, Path catalog, String key) {
        StringBuilder out = new StringBuilder();
        out.append(START).append('\n');
        out.append("model = ").append(toml(model)).append('\n');
        out.append("model_provider = \"custom\"\n");
        out.append("review_model = ").append(toml(model)).append("\n\n");
        // Keep the TokenPro route on the Responses API without invoking Codex's
        // first-party OpenAI login path. That path replaces the supplied key and
        // loses the account's model-group routing, which especially breaks Gemini.
        out.append("model_reasoning_effort = \"high\"\n");
        out.append("model_context_window = 372000\n");
        out.append("model_auto_compact_token_limit = 372000\n\n");
        out.append("model_catalog_json = ").append(toml(catalog.toAbsolutePath().toString())).append("\n\n");
        out.append("[model_providers.custom]\n");
        out.append("name = \"Codex\"\n");
        out.append("base_url = ").append(toml(url.replaceFirst("/v1$", ""))).append('\n');
        out.append("wire_api = \"responses\"\n");
        out.append("requires_openai_auth = false\n");
        out.append("experimental_bearer_token = ").append(toml(key)).append('\n');
        out.append("http_headers = { \"x-openai-actor-authorization\" = \"Codex\" }\n");
        out.append("supports_websockets = false\n\n");
        out.append(END).append('\n');
        return out.toString();
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
            Map<String, Object> closest = bySlug.get(model.name());
            if (closest == null) closest = bySlug.values().stream().filter(item -> String.valueOf(item.get("slug")).startsWith("gpt-")).findFirst().orElse(bySlug.values().iterator().next());
            Map<String, Object> entry = deepCopy(closest);
            entry.put("slug", model.name());
            entry.put("display_name", model.name());
            entry.put("description", model.groupName() + " · TokenPro");
            entry.put("visibility", "list");
            entry.put("supported_in_api", true);
            entry.put("priority", priority++);
            entry.put("availability_nux", null);
            entry.put("upgrade", null);
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
        Pattern managedKey = Pattern.compile("^(model|model_provider|review_model|model_catalog_json|model_reasoning_effort|model_context_window|model_auto_compact_token_limit)\\s*=.*$");
        for (String line : text.split("(?<=\\n)", -1)) {
            String trimmed = line.stripLeading();
            if (trimmed.startsWith("[")) root = false;
            String withoutNewline = trimmed.replaceFirst("[\\r\\n]+$", "");
            if (root && managedKey.matcher(withoutNewline).matches()) continue;
            out.append(line);
        }
        return out.toString();
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

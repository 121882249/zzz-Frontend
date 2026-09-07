package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

final class CodexConfig {
    private static final String START = "# >>> TokenPro managed >>>";
    private static final String END = "# <<< TokenPro managed <<<";
    private final SecureStore store;
    CodexConfig(SecureStore store) { this.store = store; }

    void apply(String baseUrl, String model, String key) throws Exception {
        String url = validateUrl(baseUrl);
        String modelId = required(model, "模型 ID");
        String credentialId = store.createCredential(key.trim());
        Path target = Platform.codexConfig();
        Files.createDirectories(target.getParent());
        String current = Files.exists(target) ? Files.readString(target) : "";
        if (store.read("codex-original.toml").isEmpty()) store.write("codex-original.toml", current);
        String clean = stripRootOverrides(stripManaged(current));
        Helper helper = installHelper();
        String block = managedBlock(url, modelId, credentialId, helper);
        writeAtomic(target, block + (clean.isBlank() ? "" : "\n" + clean.stripLeading()));
    }

    void restore() throws Exception {
        Optional<String> original = store.read("codex-original.toml");
        if (original.isEmpty()) throw new IllegalStateException("没有可恢复的 Codex 配置备份");
        Path target = Platform.codexConfig();
        Files.createDirectories(target.getParent());
        writeAtomic(target, original.get());
        store.delete("codex-original.toml");
    }

    private String managedBlock(String url, String model, String credentialId, Helper helper) {
        StringBuilder out = new StringBuilder();
        out.append(START).append('\n');
        out.append("model = ").append(toml(model)).append('\n');
        out.append("model_provider = \"tokenpro_direct\"\n\n");
        out.append("[model_providers.tokenpro_direct]\n");
        out.append("name = \"TokenPro\"\n");
        out.append("base_url = ").append(toml(url)).append('\n');
        out.append("wire_api = \"responses\"\n");
        out.append("supports_websockets = false\n\n");
        out.append("[model_providers.tokenpro_direct.auth]\n");
        out.append("command = ").append(toml(helper.command())).append('\n');
        out.append("args = [");
        for (int i = 0; i < helper.prefixArgs().size(); i++) {
            if (i > 0) out.append(", ");
            out.append(toml(helper.prefixArgs().get(i)));
        }
        if (!helper.prefixArgs().isEmpty()) out.append(", ");
        out.append(toml(credentialId)).append("]\n");
        out.append("timeout_ms = 5000\nrefresh_interval_ms = 300000\n");
        out.append(END).append('\n');
        return out.toString();
    }

    private Helper installHelper() throws Exception {
        Path jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
        Path java = Path.of(System.getProperty("java.home"), "bin", Platform.OS_KIND == Platform.OS.WINDOWS ? "java.exe" : "java");
        if (Files.isDirectory(jar)) throw new IllegalStateException("请先运行 build 脚本并从 TokenPro.jar 启动，才能接入 Codex");
        return new Helper(java.toString(), List.of("-jar", jar.toString(), "--route-token"));
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
        Pattern managedKey = Pattern.compile("^(model|model_provider|model_catalog_json)\\s*=.*$");
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

    private record Helper(String command, List<String> prefixArgs) {}
}

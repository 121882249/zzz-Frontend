package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class ErrorAndRestoreTest {
    static int run() throws Exception {
        int passed = 0;
        String wrong = ErrorMessages.http(401, "/auth/login", "{\"code\":401,\"message\":\"invalid email or password\",\"reason\":\"INVALID_CREDENTIALS\"}");
        check(wrong.contains("邮箱或密码不正确") && wrong.contains("401"), "wrong password has useful meaning and diagnostic code"); passed++;
        check(ErrorMessages.http(401, "/auth/me", "{\"message\":\"token has expired\"}").contains("登录已过期"), "expired session is not mislabeled as wrong password"); passed++;
        check(ErrorMessages.http(401, "/v1/messages", "{\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid api key\"}}").contains("API Key"), "nested API-key error is recognized"); passed++;
        check(ErrorMessages.http(429, "/v1/messages", "{\"message\":\"concurrency limit reached\"}").contains("同时运行"), "concurrency limit explanation"); passed++;
        check(ErrorMessages.http(403, "/auth/login", "{\"reason\":\"USER_DISABLED\"}").contains("已停用"), "disabled account explanation"); passed++;
        for (int code : List.of(400,401,402,403,404,408,409,413,422,429,500,502,503,504)) {
            String result = ErrorMessages.http(code, "/any", "<html>proxy error</html>");
            check(result.contains(String.valueOf(code)) && result.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff), "HTTP " + code + " is not just a number"); passed++;
        }
        try { ApiClient.decodeResponse(200, "/auth/login", "{\"code\":401,\"message\":\"invalid email or password\"}"); throw new AssertionError("business auth error ignored"); }
        catch (ApiClient.ApiException error) { check(error.status() == 401 && error.getMessage().contains("密码"), "HTTP 200 with auth error still fails correctly"); passed++; }
        try { ApiClient.decodeResponse(200, "/test", "{\"code\":\"10005\",\"message\":\"10005\"}"); throw new AssertionError("string error code ignored"); }
        catch (ApiClient.ApiException error) { check(error.getMessage().contains("10005") && !error.getMessage().contains("密码"), "unknown business code is retained without invented meaning"); passed++; }
        check(Json.object(ApiClient.decodeResponse(200, "/test", "{\"code\":0,\"data\":{\"ok\":true}}")).get("ok").equals(true), "success response unaffected"); passed++;
        check("nested detail".equals(ApiClient.responseMessage("{\"error\":{\"message\":\"nested detail\"}}")), "nested error object is not printed as a map"); passed++;
        check(ErrorMessages.describe(new java.util.concurrent.ExecutionException(new java.net.http.HttpTimeoutException("timeout"))).contains("超时"), "async timeout is unwrapped"); passed++;
        check(ErrorMessages.describe(new IllegalStateException("17")).contains("错误代码 17"), "unknown numeric exit code has an explanation without invented mapping"); passed++;
        check(!ErrorMessages.safe("Bearer secret-access-token password=secret-password sk-fixture-secret").contains("secret"), "credentials are hidden from errors"); passed++;
        javax.swing.JScrollPane component = ErrorMessages.messageComponent(new IllegalStateException("恢复配置测试"));
        javax.swing.JTextArea text = (javax.swing.JTextArea)component.getViewport().getView();
        check(text.getForeground().getRed() > 200 && text.getBackground().getRed() < 30 && text.getLineWrap(), "error message is readable on the dark background"); passed++;
        Path root = Files.createTempDirectory("tokenpro-error-restore-");
        try {
            SecureStore store = new SecureStore(root.resolve("store"));
            Path config = root.resolve("codex/config.toml");
            CodexConfig codex = new CodexConfig(store, config);
            check(!codex.restore() && !Files.exists(config), "empty selection and absent backup is a harmless no-op"); passed++;
            Files.createDirectories(config.getParent());
            String unrelated = "model = 'my-own-model'\n[features]\napps = true\n";
            Files.writeString(config, unrelated);
            check(!codex.restore() && Files.readString(config).equals(unrelated), "no backup does not erase unrelated config"); passed++;
            Files.writeString(config, "# >>> TokenPro managed >>>\nmodel_provider = 'custom'\n# <<< TokenPro managed <<<\n" + unrelated);
            check(codex.restore() && Files.readString(config).equals(unrelated), "missing backup removes only complete TokenPro-owned block"); passed++;
            check(!codex.restore(), "repeated restore is idempotent"); passed++;
            String corrupt = "# >>> TokenPro managed >>>\nmodel_provider = 'custom'\n" + unrelated;
            Files.writeString(config, corrupt);
            try { codex.restore(); throw new AssertionError("corrupt managed block silently erased"); }
            catch (IllegalStateException expected) { check(Files.readString(config).equals(corrupt), "corrupt config is preserved for manual repair"); passed++; }
            store.write("codex-original.toml", unrelated); Files.writeString(config, "temporary-config");
            check(codex.restore() && Files.readString(config).equals(unrelated), "real backup still restores exact original"); passed++;
            SecureStore cli = store.cli("codex");
            check(!new CodexConfig(cli, cli.root().resolve("home/config.toml")).restore(), "unconfigured CLI restore is also idempotent"); passed++;
        } finally { try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);} }
        return passed;
    }
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
}

package work.tokenpro.client;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

final class ClaudeBridgeManager {
    private ClaudeBridgeManager() {}

    static boolean healthy(SecureStore store) {
        try {
            ClaudeBridgeConfig config = ClaudeBridgeConfig.load(store);
            HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/health")).timeout(Duration.ofMillis(800)).header("Authorization", "Bearer " + config.localToken()).GET().build();
            HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build().send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("tokenpro-claude-bridge-v1");
        } catch (Exception e) { return false; }
    }

    static void ensureRunning(SecureStore store) throws Exception {
        if (healthy(store)) return;
        Process bridge = new ProcessBuilder(RuntimeCommand.withArgs("--claude-bridge"))
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        bridge.getOutputStream().close();
        for (int i = 0; i < 35; i++) { Thread.sleep(100); if (healthy(store)) return; }
        throw new IllegalStateException("Claude 本地桥接未能启动，端口 23179 可能被占用");
    }
}

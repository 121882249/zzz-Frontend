package work.tokenpro.client;

import java.io.IOException;
import java.net.*;
import java.util.*;

final class OfficialConnectionCheck {
    static void check(String client) throws IOException {
        URI endpoint = URI.create(client.equals("Codex") ? "https://api.openai.com/v1/models" : "https://api.anthropic.com/v1/models");
        Proxy proxy = proxy(System.getenv());
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.toURL().openConnection(proxy);
            connection.setConnectTimeout(6000); connection.setReadTimeout(6000);
            connection.setRequestMethod("HEAD"); connection.setInstanceFollowRedirects(false);
            int status = connection.getResponseCode();
            // A 401 proves network reachability only. Official account authentication
            // is deliberately not inferred from this unauthenticated, non-billable probe.
            if (status != 401 && (status < 200 || status >= 300)) throw new IOException("官方接口暂不可用");
        } catch (IOException failure) {
            throw new IOException("无法连接官方接口，请检查网络代理后重试；尚未退出客户端或切换设置", failure);
        } finally { if (connection != null) connection.disconnect(); }
    }
    static Proxy proxy(Map<String,String> env) throws IOException {
        String value = "";
        for (String key : List.of("https_proxy", "HTTPS_PROXY", "all_proxy", "ALL_PROXY")) {
            if (!env.getOrDefault(key, "").isBlank()) { value = env.get(key); break; }
        }
        if (value.isBlank()) return Proxy.NO_PROXY;
        try {
            URI uri = URI.create(value);
            boolean socks = Set.of("socks", "socks5", "socks5h").contains(uri.getScheme());
            if ((!socks && !"http".equals(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException();
            return new Proxy(socks ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                new InetSocketAddress(uri.getHost(), uri.getPort() > 0 ? uri.getPort() : socks ? 1080 : 80));
        } catch (RuntimeException e) { throw new IOException("当前网络代理配置无法验证，请检查代理；设置未修改"); }
    }
}

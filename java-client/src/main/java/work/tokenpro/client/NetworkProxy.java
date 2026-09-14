package work.tokenpro.client;

import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.util.*;

/** Shared proxy policy for remote HTTP traffic; loopback bridge traffic always stays local. */
final class NetworkProxy {
    private NetworkProxy() {}

    static void initialize() {
        // Must run before the JDK initializes its default ProxySelector, including in helper processes.
        if (System.getProperty("java.net.useSystemProxies") == null)
            System.setProperty("java.net.useSystemProxies", "true");
    }

    static HttpClient.Builder newBuilder() {
        initialize();
        return HttpClient.newBuilder().proxy(selector(System.getenv(), System.getProperties(), ProxySelector.getDefault()));
    }

    static HttpClient.Builder localBuilder() {
        return HttpClient.newBuilder().proxy(selector(Map.of(), new Properties(), null));
    }

    static ProxySelector selector(Map<String, String> env, Properties properties, ProxySelector system) {
        return new ProxySelector() {
            @Override public List<Proxy> select(URI uri) {
                Objects.requireNonNull(uri, "uri");
                String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
                if (loopback(host) || bypass(uri, first(env, "no_proxy", "NO_PROXY"))) return List.of(Proxy.NO_PROXY);
                String override = properties.getProperty("tokenpro.proxy", "").trim();
                if (override.isEmpty()) override = first(env, "TOKENPRO_PROXY");
                if (!override.isEmpty()) return List.of(parse(override));
                String scheme = Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT);
                // Keep existing -Dhttps.proxyHost/-Dhttp.proxyHost fixes and JDK nonProxyHosts behavior.
                boolean explicitJvm = !properties.getProperty(scheme + ".proxyHost", "").isBlank();
                if (!explicitJvm) {
                    String configured = "https".equals(scheme) ? first(env, "https_proxy", "HTTPS_PROXY")
                        : "http".equals(scheme) ? first(env, "http_proxy", "HTTP_PROXY") : "";
                    if (configured.isEmpty()) configured = first(env, "all_proxy", "ALL_PROXY");
                    if (!configured.isEmpty()) return List.of(parse(configured));
                }
                List<Proxy> selected = system == null ? List.of(Proxy.NO_PROXY) : system.select(uri);
                if (selected == null || selected.isEmpty()) return List.of(Proxy.NO_PROXY);
                Proxy proxy = selected.getFirst();
                if (proxy.type() == Proxy.Type.SOCKS) throw unsupported();
                return List.of(proxy);
            }
            @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) {
                if (system != null) system.connectFailed(uri, address, failure);
            }
        };
    }

    static Proxy parse(String value) {
        try {
            URI uri = URI.create(value.contains("://") ? value : "http://" + value);
            if (!"http".equalsIgnoreCase(uri.getScheme())) throw unsupported();
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !uri.getPath().equals("/"))
                || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort() < 0 ? 80 : uri.getPort()));
        } catch (ConfigurationException error) { throw error; }
        catch (RuntimeException error) {
            // Never echo URLs: they may contain proxy passwords.
            throw new ConfigurationException("代理地址无效，请使用 http://主机:端口（不含用户名和密码）");
        }
    }

    private static ConfigurationException unsupported() {
        return new ConfigurationException("此代理类型不受支持，请使用代理软件的 HTTP 或混合代理端口，地址以 http:// 开头");
    }

    static boolean loopback(String host) {
        return host.equals("localhost") || host.endsWith(".localhost") || host.equals("::1")
            || host.equals("[::1]") || host.matches("127(?:\\.\\d{1,3}){3}");
    }

    static boolean bypass(URI uri, String exclusions) {
        String host = Objects.toString(uri.getHost(), "").toLowerCase(Locale.ROOT);
        int port = uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
        for (String entry : exclusions.split(",")) {
            String rule = entry.trim().toLowerCase(Locale.ROOT);
            if (rule.equals("*")) return true;
            if (rule.isEmpty()) continue;
            // Bracketed IPv6 and host:port entries, without DNS lookups.
            if (rule.startsWith("[") ? rule.contains("]:") : rule.indexOf(':') == rule.lastIndexOf(':') && rule.contains(":")) {
                int colon = rule.lastIndexOf(':');
                try { if (Integer.parseInt(rule.substring(colon + 1)) != port) continue; }
                catch (NumberFormatException ignored) { continue; }
                rule = rule.substring(0, colon);
            }
            if (rule.startsWith("*.")) rule = rule.substring(2);
            else if (rule.startsWith(".")) rule = rule.substring(1);
            if (!rule.isEmpty() && (host.equals(rule) || host.endsWith("." + rule))) return true;
        }
        return false;
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.getOrDefault(key, "").trim();
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    static final class ConfigurationException extends IllegalArgumentException {
        ConfigurationException(String message) { super(message); }
    }
}

package work.tokenpro.client;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

final class NetworkProxyTest {
    static int run() throws Exception {
        int passed = 0;
        URI remote = URI.create("https://tokenpro.work/health");
        Proxy systemProxy = NetworkProxy.parse("http://127.0.0.1:7892");
        ProxySelector system = ProxySelector.of((InetSocketAddress) systemProxy.address());
        check(select(Map.of(), new Properties(), system, remote).equals(systemProxy), "system proxy is used without environment variables"); passed++;
        check(select(Map.of(), new Properties(), null, remote).equals(Proxy.NO_PROXY), "no proxy configured remains direct"); passed++;
        Map<String,String> env = Map.of("HTTPS_PROXY", "http://127.0.0.1:8080", "HTTP_PROXY", "http://127.0.0.1:8081");
        check(port(select(env, new Properties(), system, remote)) == 8080, "HTTPS environment proxy overrides system route"); passed++;
        check(port(select(env, new Properties(), system, URI.create("http://example.test/"))) == 8081, "HTTP uses its own proxy"); passed++;
        check(port(select(Map.of("https_proxy", "127.0.0.1:8082", "HTTPS_PROXY", "http://127.0.0.1:8083"), new Properties(), system, remote)) == 8082, "lowercase environment variable takes precedence"); passed++;
        check(port(select(Map.of("ALL_PROXY", "http://127.0.0.1:8084"), new Properties(), null, remote)) == 8084, "HTTP ALL_PROXY fallback works"); passed++;
        Properties jvm = new Properties(); jvm.setProperty("https.proxyHost", "127.0.0.1");
        check(select(env, jvm, system, remote).equals(systemProxy), "existing JVM proxy fix takes precedence over general env"); passed++;
        jvm.setProperty("tokenpro.proxy", "http://127.0.0.1:8085");
        check(port(select(env, jvm, system, remote)) == 8085, "TokenPro-specific JVM override takes precedence"); passed++;
        check(port(select(Map.of("TOKENPRO_PROXY", "http://127.0.0.1:8086"), new Properties(), system, remote)) == 8086, "TokenPro-specific env override"); passed++;
        for (String url : List.of("http://127.0.0.1:23179/health", "http://127.0.0.2/", "http://localhost/", "http://[::1]/")) {
            check(select(env, jvm, system, URI.create(url)).equals(Proxy.NO_PROXY), "local bridge bypasses every proxy: " + url); passed++;
        }
        check(select(Map.of("HTTPS_PROXY", "http://127.0.0.1:8080", "NO_PROXY", ".tokenpro.work"), new Properties(), system, remote).equals(Proxy.NO_PROXY), "NO_PROXY exclusions honored"); passed++;
        check(NetworkProxy.bypass(remote, "tokenpro.work:443") && !NetworkProxy.bypass(remote, "tokenpro.work:444"), "NO_PROXY ports respected"); passed++;
        check(NetworkProxy.bypass(URI.create("https://api.tokenpro.work/"), "tokenpro.work")
            && !NetworkProxy.bypass(URI.create("https://nottokenpro.work/"), "tokenpro.work"), "NO_PROXY domain boundaries respected"); passed++;
        check(NetworkProxy.bypass(remote, "*") && !NetworkProxy.bypass(remote, ""), "NO_PROXY wildcard and empty value"); passed++;
        check(NetworkProxy.bypass(URI.create("https://[2001:db8::1]/"), "[2001:db8::1]:443"), "IPv6 proxy bypass with port"); passed++;
        check(port(NetworkProxy.parse("http://proxy.example")) == 80, "default HTTP proxy port"); passed++;
        for (String invalid : List.of("http://user:proxy-secret@localhost:7892", "http://localhost:0", "http://localhost:99999", "http://localhost:7892/path", "http://localhost:7892?password=proxy-secret", "http://")) {
            try { NetworkProxy.parse(invalid); throw new AssertionError("invalid proxy accepted"); }
            catch (NetworkProxy.ConfigurationException expected) { check(!expected.getMessage().contains("proxy-secret"), "proxy error never exposes credentials"); passed++; }
        }
        for (String unsupported : List.of("socks5://127.0.0.1:7892", "https://127.0.0.1:7892")) {
            try { NetworkProxy.parse(unsupported); throw new AssertionError("unsupported proxy silently used"); }
            catch (NetworkProxy.ConfigurationException expected) { check(expected.getMessage().contains("HTTP"), "unsupported proxy points to HTTP port"); passed++; }
        }
        check(ErrorMessages.describe(new IOException("wrapped", new NetworkProxy.ConfigurationException("代理地址无效"))).contains("代理地址无效"), "proxy diagnostics survive wrapping"); passed++;
        check(ErrorMessages.describe(new ExecutionException(new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake"))).contains("系统代理"), "login handshake error has useful guidance"); passed++;
        check(ErrorMessages.describe(new javax.net.ssl.SSLHandshakeException("PKIX path building failed")).contains("校验"), "certificate errors retain certificate guidance"); passed++;
        passed += proxyTransport(false);
        passed += proxyTransport(true);
        return passed;
    }

    /** An unresolvable origin must reach our proxy; HTTPS must use CONNECT rather than plaintext credentials. */
    private static int proxyTransport(boolean https) throws Exception {
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
             ExecutorService worker = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5000);
            Future<String> received = worker.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String first = reader.readLine();
                    StringBuilder headers = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) headers.append(line).append('\n');
                    if (https) check(!headers.toString().contains("fixture-access-token"), "origin credentials never appear in CONNECT headers");
                    String response = https ? "HTTP/1.1 502 Test tunnel rejected\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        : "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok";
                    socket.getOutputStream().write(response.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    return first;
                }
            });
            String proxy = "http://127.0.0.1:" + listener.getLocalPort();
            URI endpoint = URI.create((https ? "https" : "http") + "://proxy-probe.invalid/health");
            try (HttpClient client = HttpClient.newBuilder().proxy(NetworkProxy.selector(Map.of("ALL_PROXY", proxy), new Properties(), null))
                .connectTimeout(Duration.ofSeconds(3)).build()) {
                HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(4))
                    .header("Authorization", "Bearer fixture-access-token").GET().build();
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    check(!https && response.statusCode() == 200 && response.body().equals("ok"), "HTTP request uses proxy without origin DNS");
                } catch (IOException expected) { if (!https) throw expected; }
                String first = received.get(5, TimeUnit.SECONDS);
                check(first.equals(https ? "CONNECT proxy-probe.invalid:443 HTTP/1.1" : "GET http://proxy-probe.invalid/health HTTP/1.1"), "correct proxy wire request: " + first);
            }
            return 2;
        }
    }

    private static Proxy select(Map<String,String> env, Properties props, ProxySelector system, URI uri) {
        return NetworkProxy.selector(env, props, system).select(uri).getFirst();
    }
    private static int port(Proxy proxy) { return ((InetSocketAddress) proxy.address()).getPort(); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

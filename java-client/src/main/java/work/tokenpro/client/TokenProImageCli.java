package work.tokenpro.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small local CLI used by the bundled Skill; it never prints the global Key. */
final class TokenProImageCli {
    private static final Pattern TOML_STRING = Pattern.compile("(?m)^%s\\s*=\\s*([\\\"'])(.*?)\\1\\s*$");
    private TokenProImageCli() {}

    static int run(String[] args) {
        try {
            Options options = Options.parse(args);
            if (!"generate".equals(options.operation)) throw new IllegalArgumentException("用法：tokenpro-imagegen generate --prompt \"...\" --out /绝对路径/图片.png");
            generate(options, Platform.codexConfig());
            return 0;
        } catch (Exception failure) {
            System.err.println("TokenPro imagegen failed: " + failure.getMessage());
            return 1;
        }
    }

    static void generateForTest(String[] args, Path config) throws Exception {
        generate(Options.parse(args), config);
    }

    private static void generate(Options options, Path config) throws Exception {
        Path auth = config.resolveSibling("auth.json");
        Map<String, Object> authJson = Json.object(Json.parse(Files.readString(auth, StandardCharsets.UTF_8)));
        String key = String.valueOf(authJson.getOrDefault("OPENAI_API_KEY", "")).trim();
        if (key.isBlank()) throw new IllegalStateException("TokenPro 全局 Key 不存在，请先在 TokenPro 中连接 Codex");
        String baseUrl = tomlValue(config, "openai_base_url").orElse("https://tokenpro.work/v1").replaceAll("/+$", "");
        String model = options.model == null || options.model.isBlank() ? selectedImageModel(config).orElse("gpt-image-2") : options.model;
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", options.prompt);
        body.put("n", 1);
        if (options.size != null) body.put("size", options.size);
        if (options.quality != null) body.put("quality", options.quality);
        if (options.background != null) body.put("background", options.background);
        HttpClient client = NetworkProxy.newBuilder().connectTimeout(Duration.ofSeconds(30)).followRedirects(HttpClient.Redirect.NEVER).build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/images/generations"))
            .timeout(Duration.ofMinutes(5))
            .header("Authorization", "Bearer " + key)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("图片接口返回 HTTP " + response.statusCode());
        Map<String,Object> result = Json.object(Json.parse(response.body()));
        Object rawData = result.get("data");
        if (!(rawData instanceof List<?> data) || data.isEmpty()) throw new IOException("图片接口没有返回图片数据");
        Map<String,Object> first = Json.object(data.getFirst());
        String base64 = String.valueOf(first.getOrDefault("b64_json", ""));
        if (base64.isBlank()) throw new IOException("图片接口未返回 b64_json");
        Path output = Path.of(options.output).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.write(output, Base64.getDecoder().decode(base64));
        System.out.println(output);
    }

    private static Optional<String> selectedImageModel(Path config) {
        try {
            String catalog = tomlValue(config, "model_catalog_json").orElse("");
            if (catalog.isBlank()) return Optional.empty();
            Object raw = Json.object(Json.parse(Files.readString(Path.of(catalog), StandardCharsets.UTF_8))).get("models");
            if (!(raw instanceof List<?> models)) return Optional.empty();
            for (Object item : models) {
                Map<String,Object> row = Json.object(item);
                String slug = String.valueOf(row.getOrDefault("slug", ""));
                String text = (slug + " " + row.getOrDefault("display_name", "")).toLowerCase(Locale.ROOT);
                if (text.contains("image") || text.contains("dall-e")) return Optional.of(slug);
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    private static Optional<String> tomlValue(Path config, String key) throws IOException {
        if (!Files.isRegularFile(config, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Pattern pattern = Pattern.compile(String.format(TOML_STRING.pattern(), Pattern.quote(key)));
        Matcher matcher = pattern.matcher(Files.readString(config, StandardCharsets.UTF_8));
        return matcher.find() ? Optional.of(matcher.group(2)) : Optional.empty();
    }

    private record Options(String operation, String prompt, String model, String output,
                           String size, String quality, String background) {
        static Options parse(String[] args) {
            if (args.length == 0) throw new IllegalArgumentException("缺少 generate");
            String operation = args[0]; String prompt = null; String model = null; String output = null;
            String size = null, quality = null, background = null;
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--") || i + 1 >= args.length) throw new IllegalArgumentException("参数格式错误：" + arg);
                String value = args[++i];
                switch (arg) {
                    case "--prompt" -> prompt = value;
                    case "--model" -> model = value;
                    case "--out" -> output = value;
                    case "--size" -> size = value;
                    case "--quality" -> quality = value;
                    case "--background" -> background = value;
                    default -> throw new IllegalArgumentException("不支持的参数：" + arg);
                }
            }
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("缺少 --prompt");
            if (output == null || output.isBlank() || !Path.of(output).isAbsolute()) throw new IllegalArgumentException("--out 必须是绝对路径");
            return new Options(operation, prompt, model, output, size, quality, background);
        }
    }
}

package work.tokenpro.client;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;

/** Per-response adapter. Image bytes stay on this computer; no server changes. */
final class CodexImageResponse {
    private static final Pattern PLACEHOLDER = Pattern.compile("data:image/[a-zA-Z0-9.+-]+;base64,(?:\\.\\.\\.|…)(?=[)\\s\"'<>]|$)");
    private final Path directory;
    private final Set<String> results = new LinkedHashSet<>();
    private String imagePath;
    CodexImageResponse(Path directory) { this.directory = directory; }

    private void remember(Map<String, Object> item) throws Exception {
        if (!"image_generation_call".equals(item.get("type"))) return;
        String value = Objects.toString(item.get("result"), "");
        if (value.startsWith("data:image/") && value.contains(";base64,")) value = value.substring(value.indexOf(',') + 1);
        if (value.isBlank() || value.length() > 48 * 1024 * 1024) return;
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value); } catch (IllegalArgumentException e) { return; }
        String format;
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) return;
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (reader.getWidth(0) <= 0 || reader.getHeight(0) <= 0) return;
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
            } finally { reader.dispose(); }
        }
        if (!Set.of("jpeg", "jpg", "png", "gif").contains(format)) return;
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        if (!results.add(digest)) return;
        Files.createDirectories(directory);
        Path target = directory.resolve(digest + (format.equals("jpeg") || format.equals("jpg") ? ".jpg" : "." + format));
        if (!Files.exists(target)) {
            Path temp = Files.createTempFile(directory, ".image-", ".tmp");
            try {
                Files.write(temp, bytes);
                Platform.privateFile(temp);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temp); }
        }
        // Multiple image results cannot be matched to an anonymous placeholder.
        imagePath = results.size() == 1 ? "<" + target.toAbsolutePath().toString().replace('\\', '/') + ">" : null;
    }

    String rewrite(String text) {
        return imagePath == null ? text : PLACEHOLDER.matcher(text).replaceAll(Matcher.quoteReplacement(imagePath));
    }

    Map<String, Object> transform(Map<String, Object> event) throws Exception {
        if (event.get("item") instanceof Map<?, ?> raw) remember(Json.object(raw));
        Map<String, Object> response = event.get("response") instanceof Map<?, ?> raw ? Json.object(raw) : event;
        if (response.get("output") instanceof List<?> output) for (Object raw : output) if (raw instanceof Map<?, ?>) remember(Json.object(raw));
        rewriteTree(event);
        return event;
    }

    private void rewriteTree(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> map = Json.object(raw);
            String type = Objects.toString(map.get("type"), "");
            if ((type.equals("output_text") || type.equals("response.output_text.done")) && map.get("text") instanceof String text) map.put("text", rewrite(text));
            for (Object nested : map.values()) if (nested instanceof Map<?, ?> || nested instanceof List<?>) rewriteTree(nested);
        } else if (value instanceof List<?> list) for (Object nested : list) rewriteTree(nested);
    }

    void stream(InputStream input, OutputStream output) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        List<String> frame = new ArrayList<>();
        Map<String, Object> held = null;
        StringBuilder text = new StringBuilder();
        for (;;) {
            String line = readLine(reader);
            if (line == null) { if (frame.isEmpty()) break; line = ""; }
            if (!line.isEmpty()) { frame.add(line); continue; }
            String data = frame.stream().filter(s -> s.startsWith("data:")).map(s -> s.substring(5).stripLeading()).reduce((a,b) -> a+"\n"+b).orElse("");
            Map<String, Object> event = null;
            try { if (!data.isBlank() && !data.equals("[DONE]")) event = Json.object(Json.parse(data)); } catch (IllegalArgumentException ignored) { }
            String type = event == null ? "" : Objects.toString(event.get("type"), "");
            if (event == null && data.isBlank()) {
                for (String part : frame) output.write((part+"\n").getBytes(StandardCharsets.UTF_8));
                output.write('\n'); output.flush(); frame.clear(); continue;
            }
            if (imagePath != null && type.equals("response.output_text.delta") && event.get("delta") instanceof String delta) {
                if (held != null && !sameContent(held, event)) { emitText(held, text, output); text.setLength(0); }
                held = event; text.append(delta);
                // Only the small final explanation is buffered after image generation.
                if (text.length() > 1024 * 1024) { emitText(held, text, output); held = null; text.setLength(0); }
            } else {
                if (held != null) { emitText(held, text, output); held = null; text.setLength(0); }
                if (event != null) emit(transform(event), output);
                else { for (String part : frame) output.write((part+"\n").getBytes(StandardCharsets.UTF_8)); output.write('\n'); output.flush(); }
            }
            frame.clear();
        }
        if (held != null) emitText(held, text, output);
    }
    private static boolean sameContent(Map<String, Object> a, Map<String, Object> b) {
        return List.of("item_id", "output_index", "content_index").stream().allMatch(k -> Objects.equals(a.get(k), b.get(k)));
    }
    private void emitText(Map<String, Object> event, StringBuilder text, OutputStream output) throws IOException { event.put("delta", rewrite(text.toString())); emit(event, output); }
    private static void emit(Map<String, Object> event, OutputStream output) throws IOException {
        output.write(("data: " + Json.stringify(event) + "\n\n").getBytes(StandardCharsets.UTF_8)); output.flush();
    }
    private static String readLine(Reader input) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int c; (c = input.read()) != -1;) { if (c == '\n') return line.toString(); if (c != '\r') line.append((char)c); if (line.length() > 64 * 1024 * 1024) throw new IOException("图片响应过大"); }
        return line.isEmpty() ? null : line.toString();
    }
}

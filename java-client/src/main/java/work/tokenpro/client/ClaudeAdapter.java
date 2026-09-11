package work.tokenpro.client;

import java.nio.charset.StandardCharsets;
import java.util.*;

final class ClaudeAdapter {
    private ClaudeAdapter() {}

    static int estimateTokens(Map<String, Object> request) {
        Map<String, Object> relevant = new LinkedHashMap<>();
        for (String key : List.of("messages", "system", "tools")) if (request.containsKey(key)) relevant.put(key, request.get(key));
        String text = Json.stringify(relevant); int ascii = 0, unicode = 0;
        for (int i = 0; i < text.length();) { int cp = text.codePointAt(i); i += Character.charCount(cp); if (cp < 128) ascii++; else unicode++; }
        return Math.max(1, (ascii + 2) / 3 + unicode * 2 + 16);
    }

    static Map<String, Object> prepareHistory(Map<String, Object> source, ClaudeBridgeConfig.Route route) {
        Map<String, Object> result = deepMap(source); List<Object> messages = list(result.get("messages")); List<Object> kept = new ArrayList<>(); List<Object> extraSystem = new ArrayList<>();
        for (Object raw : messages) {
            Map<String, Object> message = deepMap(Json.object(raw)); String role = text(message.get("role"));
            if (!route.usesResponses() && (role.equals("system") || role.equals("developer"))) { extraSystem.addAll(blocks(message.get("content"))); continue; }
            if (message.get("content") instanceof List<?> content) {
                List<Object> filtered = new ArrayList<>();
                for (Object rawBlock : content) {
                    Map<String, Object> block = deepMap(Json.object(rawBlock)); String type = text(block.get("type"));
                    if (type.equals("thinking") || type.equals("redacted_thinking")) {
                        String field = type.equals("thinking") ? "signature" : "data"; String signature = text(block.get(field));
                        if (!signature.startsWith(prefix(route.signatureId()))) continue;
                        block.put(field, signature.substring(prefix(route.signatureId()).length()));
                    }
                    filtered.add(block);
                }
                message.put("content", filtered);
            }
            kept.add(message);
        }
        result.put("messages", kept);
        List<Object> system = new ArrayList<>(blocks(result.get("system"))); system.addAll(extraSystem);
        system.add(Map.of("type", "text", "text", "TokenPro routing metadata: selected model ID = " + route.name() + ". Claude Desktop is the application name, not evidence of the underlying model."));
        result.put("system", system); return result;
    }

    static Map<String, Object> tagNativeResponse(Map<String, Object> source, String alias) {
        Map<String, Object> result = deepMap(source);
        if (result.get("content") instanceof List<?> content) {
            List<Object> tagged = new ArrayList<>();
            for (Object raw : content) tagged.add(tagBlock(Json.object(raw), alias));
            result.put("content", tagged);
        }
        return result;
    }

    static Map<String, Object> tagBlock(Map<String, Object> source, String alias) {
        Map<String, Object> block = deepMap(source); String type = text(block.get("type"));
        String field = type.equals("redacted_thinking") ? "data" : "signature";
        if ((type.equals("thinking") || type.equals("redacted_thinking")) && !text(block.get(field)).isBlank()) block.put(field, prefix(alias) + text(block.get(field)));
        return block;
    }

    static Map<String, Object> responsesRequest(Map<String, Object> source) {
        String model = text(source.get("model")); if (model.isBlank()) throw new IllegalArgumentException("消息缺少模型");
        Map<String, Object> result = new LinkedHashMap<>(); result.put("model", model); result.put("stream", Boolean.TRUE.equals(source.get("stream"))); result.put("store", false);
        List<Object> system = blocks(source.get("system"));
        if (system.stream().anyMatch(raw -> !"text".equals(text(Json.object(raw).get("type"))))) throw new IllegalArgumentException("该模型暂不支持这种系统消息");
        result.put("instructions", system.stream().map(raw -> text(Json.object(raw).get("text"))).reduce((a,b) -> a + "\n" + b).orElse(""));
        if (source.containsKey("max_tokens")) result.put("max_output_tokens", source.get("max_tokens"));
        Map<String, Object> outputConfig = objectOrEmpty(source.get("output_config"));
        if (outputConfig.get("effort") instanceof String effort) result.put("reasoning", Map.of("effort", effort));
        if (source.get("service_tier") instanceof String tier) result.put("service_tier", tier);
        else if ("fast".equals(source.get("speed"))) result.put("service_tier", "priority");
        if (outputConfig.get("format") instanceof Map<?, ?>) {
            Map<String, Object> format = Json.object(outputConfig.get("format"));
            if (!"json_schema".equals(format.get("type")) || !(format.get("schema") instanceof Map<?, ?>)) throw new IllegalArgumentException("该模型不支持此输出格式");
            result.put("text", Map.of("format", Map.of("type", "json_schema", "name", "claude_output", "schema", format.get("schema"), "strict", false)));
        }
        List<Object> input = new ArrayList<>();
        for (Object rawMessage : list(source.get("messages"))) {
            Map<String, Object> message = Json.object(rawMessage); String role = text(message.get("role"));
            if (!Set.of("user", "assistant", "system", "developer").contains(role)) throw new IllegalArgumentException("不支持的消息角色：" + role);
            List<Object> content = new ArrayList<>();
            for (Object rawBlock : blocks(message.get("content"))) {
                Map<String, Object> block = Json.object(rawBlock); String type = text(block.get("type"));
                switch (type) {
                    case "text" -> content.add(Map.of("type", role.equals("assistant") ? "output_text" : "input_text", "text", text(block.get("text"))));
                    case "image" -> {
                        if (!role.equals("user")) throw new IllegalArgumentException("图片格式不支持");
                        Map<String, Object> image = objectOrEmpty(block.get("source")); String url;
                        if ("base64".equals(image.get("type"))) url = "data:" + text(image.get("media_type")) + ";base64," + text(image.get("data"));
                        else if ("url".equals(image.get("type"))) url = text(image.get("url")); else throw new IllegalArgumentException("图片格式不支持");
                        content.add(Map.of("type", "input_image", "image_url", url));
                    }
                    case "tool_use" -> { flush(input, role, content); input.add(Map.of("type", "function_call", "call_id", text(block.get("id")), "name", text(block.get("name")), "arguments", Json.stringify(block.getOrDefault("input", Map.of())))); }
                    case "tool_result" -> {
                        flush(input, role, content); StringBuilder output = new StringBuilder();
                        for (Object item : blocks(block.get("content"))) { Map<String, Object> value = Json.object(item); if (!"text".equals(value.get("type"))) throw new IllegalArgumentException("工具结果只支持文本"); if (!output.isEmpty()) output.append('\n'); output.append(text(value.get("text"))); }
                        input.add(Map.of("type", "function_call_output", "call_id", text(block.get("tool_use_id")), "output", (Boolean.TRUE.equals(block.get("is_error")) ? "Tool error: " : "") + output));
                    }
                    case "thinking", "redacted_thinking" -> { }
                    default -> throw new IllegalArgumentException("该模型暂不支持消息块：" + type);
                }
            }
            flush(input, role, content);
        }
        result.put("input", input);
        if (source.get("tools") instanceof List<?> tools) {
            List<Object> converted = new ArrayList<>();
            for (Object raw : tools) { Map<String, Object> tool = Json.object(raw); converted.add(Map.of("type", "function", "name", text(tool.get("name")), "description", text(tool.get("description")), "parameters", tool.getOrDefault("input_schema", Map.of()), "strict", false)); }
            result.put("tools", converted);
        }
        if (source.get("tool_choice") instanceof Map<?, ?>) {
            Map<String, Object> choice = Json.object(source.get("tool_choice")); String type = text(choice.get("type"));
            result.put("tool_choice", switch (type) { case "auto" -> "auto"; case "any" -> "required"; case "none" -> "none"; case "tool" -> Map.of("type", "function", "name", text(choice.get("name"))); default -> throw new IllegalArgumentException("工具选择方式不支持"); });
            if (Boolean.TRUE.equals(choice.get("disable_parallel_tool_use"))) result.put("parallel_tool_calls", false);
        }
        if (source.get("stop_sequences") instanceof List<?> stops && !stops.isEmpty()) throw new IllegalArgumentException("Responses 接口不支持自定义停止字符串");
        return result;
    }

    static Map<String, Object> responsesResponse(Map<String, Object> response, String model) {
        String status = text(response.get("status")); if (!status.equals("completed") && !status.equals("incomplete")) throw new IllegalArgumentException("上游没有完成请求");
        List<Object> content = new ArrayList<>(); boolean tools = false;
        for (Object raw : list(response.get("output"))) {
            Map<String, Object> item = Json.object(raw); String type = text(item.get("type"));
            if (type.equals("message")) for (Object rawBlock : list(item.get("content"))) { Map<String, Object> block = Json.object(rawBlock); if ("output_text".equals(block.get("type"))) content.add(Map.of("type", "text", "text", text(block.get("text")))); else if ("refusal".equals(block.get("type"))) content.add(Map.of("type", "text", "text", text(block.get("refusal")))); }
            else if (type.equals("function_call")) { tools = true; Object arguments = Json.parse(text(item.get("arguments"))); content.add(Map.of("type", "tool_use", "id", text(item.get("call_id")), "name", text(item.get("name")), "input", Json.object(arguments))); }
            else if (!type.equals("reasoning")) throw new IllegalArgumentException("上游返回了不支持的输出内容");
        }
        Map<String, Object> result = new LinkedHashMap<>(); result.put("id", text(response.get("id")).isBlank() ? "msg_" + UUID.randomUUID() : response.get("id")); result.put("type", "message"); result.put("role", "assistant"); result.put("model", model); result.put("content", content); result.put("stop_reason", stop(response, tools)); result.put("stop_sequence", null); result.put("usage", usage(response)); return result;
    }

    static Map<String, Object> usage(Map<String, Object> response) {
        Map<String, Object> usage = objectOrEmpty(response.get("usage")); long total = number(usage.get("input_tokens")); long cached = number(objectOrEmpty(usage.get("input_tokens_details")).get("cached_tokens"));
        return Map.of("input_tokens", Math.max(0, total - cached), "output_tokens", number(usage.get("output_tokens")), "cache_read_input_tokens", cached, "cache_creation_input_tokens", 0);
    }
    static String stop(Map<String, Object> response, boolean tools) { return "incomplete".equals(response.get("status")) ? "max_tokens" : tools ? "tool_use" : "end_turn"; }
    static String prefix(String alias) { return "tokenpro:" + alias + ":"; }

    private static void flush(List<Object> input, String role, List<Object> content) { if (!content.isEmpty()) { input.add(Map.of("role", role, "content", new ArrayList<>(content))); content.clear(); } }
    private static List<Object> blocks(Object value) { if (value == null) return new ArrayList<>(); if (value instanceof String text) return new ArrayList<>(List.of(Map.of("type", "text", "text", text))); return new ArrayList<>(list(value)); }
    @SuppressWarnings("unchecked") static List<Object> list(Object value) { return value instanceof List<?> list ? (List<Object>) list : List.of(); }
    static Map<String, Object> objectOrEmpty(Object value) { return value instanceof Map<?, ?> ? Json.object(value) : Map.of(); }
    static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
    @SuppressWarnings("unchecked") static Map<String, Object> deepMap(Map<String, Object> value) { return Json.object(Json.parse(Json.stringify(value))); }

    static final class ResponsesStream {
        private final String model; private boolean started, tools, completed; private int next; private final Map<String, Integer> open = new LinkedHashMap<>();
        ResponsesStream(String model) { this.model = model; }
        boolean completed() { return completed; }
        List<Map<String, Object>> consume(Map<String, Object> event) {
            List<Map<String, Object>> out = new ArrayList<>(); Map<String, Object> response = objectOrEmpty(event.get("response"));
            if (!started) { started = true; Map<String, Object> message = new LinkedHashMap<>(); message.put("id", text(response.get("id")).isBlank() ? "msg_" + UUID.randomUUID() : response.get("id")); message.put("type", "message"); message.put("role", "assistant"); message.put("model", model); message.put("content", List.of()); message.put("stop_reason", null); message.put("stop_sequence", null); message.put("usage", Map.of("input_tokens", 0, "output_tokens", 0)); out.add(map("type", "message_start", "message", message)); }
            String type = text(event.get("type"));
            switch (type) {
                case "response.output_text.delta", "response.refusal.delta" -> { String key = "text:" + event.getOrDefault("output_index", 0) + ":" + event.getOrDefault("content_index", 0); int index = begin(out, key, Map.of("type", "text", "text", "")); out.add(map("type", "content_block_delta", "index", index, "delta", Map.of("type", "text_delta", "text", text(event.get("delta"))))); }
                case "response.output_item.added" -> { Map<String, Object> item = objectOrEmpty(event.get("item")); if ("function_call".equals(item.get("type"))) { tools = true; begin(out, text(item.get("id")), Map.of("type", "tool_use", "id", text(item.get("call_id")), "name", text(item.get("name")), "input", Map.of())); } }
                case "response.function_call_arguments.delta" -> { Integer index = open.get(text(event.get("item_id"))); if (index == null) throw new IllegalArgumentException("流式工具参数缺少对应调用"); out.add(map("type", "content_block_delta", "index", index, "delta", Map.of("type", "input_json_delta", "partial_json", text(event.get("delta"))))); }
                case "response.completed", "response.incomplete" -> { open.values().stream().sorted().forEach(index -> out.add(map("type", "content_block_stop", "index", index))); open.clear(); Map<String, Object> delta = new LinkedHashMap<>(); delta.put("stop_reason", stop(response, tools)); delta.put("stop_sequence", null); out.add(map("type", "message_delta", "delta", delta, "usage", usage(response))); out.add(Map.of("type", "message_stop")); completed = true; }
                case "response.failed", "error" -> throw new IllegalArgumentException(text(objectOrEmpty(response.get("error")).getOrDefault("message", event.getOrDefault("message", "上游模型请求失败"))));
                default -> { }
            }
            return out;
        }
        private int begin(List<Map<String, Object>> out, String key, Map<String, Object> block) { Integer old = open.get(key); if (old != null) return old; int index = next++; open.put(key, index); out.add(map("type", "content_block_start", "index", index, "content_block", block)); return index; }
    }

    static Map<String, Object> map(Object... values) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < values.length; i += 2) result.put(String.valueOf(values[i]), values[i + 1]); return result; }
}

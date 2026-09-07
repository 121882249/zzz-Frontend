package work.tokenpro.client;

import java.util.*;

final class Json {
    private Json() {}

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.ws();
        if (!parser.end()) throw new IllegalArgumentException("JSON 末尾有多余内容");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("服务器返回的不是 JSON 对象");
        return (Map<String, Object>) map;
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) out.append("null");
        else if (value instanceof String s) quote(s, out);
        else if (value instanceof Number || value instanceof Boolean) out.append(value);
        else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                quote(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> values) {
            out.append('[');
            boolean first = true;
            for (Object item : values) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else quote(String.valueOf(value), out);
    }

    private static void quote(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int pos;
        Parser(String text) { this.text = Objects.requireNonNull(text); }
        boolean end() { return pos == text.length(); }
        void ws() { while (!end() && Character.isWhitespace(text.charAt(pos))) pos++; }
        Object value() {
            ws();
            if (end()) throw error("JSON 内容为空");
            return switch (text.charAt(pos)) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }
        Map<String, Object> object() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            pos++; ws();
            if (take('}')) return map;
            do {
                ws();
                if (end() || text.charAt(pos) != '"') throw error("对象键必须是字符串");
                String key = string();
                ws(); expect(':');
                map.put(key, value());
                ws();
                if (take('}')) return map;
                expect(',');
            } while (true);
        }
        List<Object> array() {
            ArrayList<Object> list = new ArrayList<>();
            pos++; ws();
            if (take(']')) return list;
            do {
                list.add(value()); ws();
                if (take(']')) return list;
                expect(',');
            } while (true);
        }
        String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (end()) throw error("字符串转义不完整");
                char e = text.charAt(pos++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (pos + 4 > text.length()) throw error("Unicode 转义不完整");
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw error("未知字符串转义");
                }
            }
            throw error("字符串没有结束");
        }
        Object number() {
            int start = pos;
            if (!end() && text.charAt(pos) == '-') pos++;
            while (!end() && Character.isDigit(text.charAt(pos))) pos++;
            if (!end() && text.charAt(pos) == '.') { pos++; while (!end() && Character.isDigit(text.charAt(pos))) pos++; }
            if (!end() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                pos++;
                if (!end() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) pos++;
                while (!end() && Character.isDigit(text.charAt(pos))) pos++;
            }
            if (start == pos) throw error("不是有效的 JSON 值");
            String raw = text.substring(start, pos);
            try { return raw.contains(".") || raw.contains("e") || raw.contains("E") ? Double.parseDouble(raw) : Long.parseLong(raw); }
            catch (NumberFormatException e) { throw error("数字格式错误"); }
        }
        Object literal(String raw, Object value) {
            if (!text.startsWith(raw, pos)) throw error("未知 JSON 值");
            pos += raw.length();
            return value;
        }
        boolean take(char c) { if (!end() && text.charAt(pos) == c) { pos++; return true; } return false; }
        void expect(char c) { if (!take(c)) throw error("需要字符 " + c); }
        IllegalArgumentException error(String message) { return new IllegalArgumentException(message + "（位置 " + pos + "）"); }
    }
}

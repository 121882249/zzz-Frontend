package work.tokenpro.client;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Edits route-owned TOML statements while retaining unrelated configuration verbatim. */
final class CodexSwitchConfig {
    private static final Set<String> ROUTE_KEYS = Set.of("model", "model_provider", "openai_base_url", "review_model", "model_catalog_json",
        "model_reasoning_effort", "model_context_window", "model_auto_compact_token_limit", "service_tier");
    private static final Map<String,String> MARKERS = Map.of(
        "# >>> TokenPro managed >>>", "# <<< TokenPro managed <<<",
        "# >>> TokenPro model selection >>>", "# <<< TokenPro model selection <<<",
        "# >>> TokenPro history provider >>>", "# <<< TokenPro history provider <<<");
    private static final Pattern CHANNEL_MARKER = Pattern.compile("^# (>>>|<<<) ([A-Za-z0-9_-]{1,64})-codex$");
    private static final Set<String> BUILTIN_PROVIDERS = Set.of("openai", "ollama", "lmstudio");
    private CodexSwitchConfig() {}
    record ForeignRelayCleanup(String cleaned, List<String> changes) {}

    static String clean(String current) {
        List<Statement> statements = parse(stripChannelMarkerBlocks(current));
        String profile = activeProfile(statements);
        Set<List<String>> owned = new HashSet<>();
        List<String> section = List.of();
        String closing = null;
        for (Statement statement : statements) {
            String line = statement.raw().strip();
            if (MARKERS.containsKey(line)) {
                if (closing != null) throw malformed();
                closing = MARKERS.get(line);
            } else if (MARKERS.containsValue(line)) {
                if (!line.equals(closing)) throw malformed();
                closing = null;
            } else if (statement.header()) {
                section = statement.path();
                if (closing != null && section.size() >= 2 && section.getFirst().equals("model_providers"))
                    owned.add(section.subList(0, 2));
            } else if (section.size() == 2 && section.getFirst().equals("model_providers")
                && statement.path().equals(List.of("base_url"))) {
                // Migrate older unmarked TokenPro provider tables without deleting third-party providers.
                String value = scalar(statement.raw());
                try {
                    if ("tokenpro.work".equalsIgnoreCase(java.net.URI.create(value).getHost())) owned.add(section);
                } catch (IllegalArgumentException ignored) { }
            }
        }
        if (closing != null) throw malformed();
        StringBuilder result = new StringBuilder();
        section = List.of();
        for (Statement statement : statements) {
            String line = statement.raw().strip();
            if (MARKERS.containsKey(line) || MARKERS.containsValue(line)) continue;
            if (statement.header()) section = statement.path();
            boolean ownTable = section.size() >= 2 && owned.contains(section.subList(0, 2));
            if (ownTable) continue;
            if ((section.isEmpty() || section.equals(List.of("profiles", profile)))
                && statement.path().size() == 1 && ROUTE_KEYS.contains(statement.path().getFirst())) continue;
            result.append(statement.raw());
        }
        return result.toString();
    }

    /** Build an automatic cleanup for only the active/conflicting foreign route. */
    static Optional<ForeignRelayCleanup> foreignRelayCleanup(String current) {
        List<Statement> original = parse(current);
        String profile = activeProfile(original);
        String activeProvider = assignment(original, profile, "model_provider").orElse("");
        boolean activeIsTokenPro = providerHost(original, activeProvider).map(CodexSwitchConfig::tokenProHost).orElse(false);

        String ownRouteRemoved = clean(current);
        List<Statement> statements = parse(ownRouteRemoved);
        Set<String> removeProviders = new LinkedHashSet<>();
        List<String> changes = new ArrayList<>();
        markerOwners(current).stream().filter(owner -> !"tokenpro".equals(owner))
            .forEach(owner -> changes.add("渠道标记 " + owner));

        if (!activeProvider.isBlank() && !BUILTIN_PROVIDERS.contains(activeProvider) && !activeIsTokenPro) {
            removeProviders.add(activeProvider);
            changes.add("当前路由 " + activeProvider + providerHost(statements, activeProvider).map(host -> " (" + host + ")").orElse(""));
        }
        StringBuilder result = new StringBuilder();
        List<String> section = List.of();
        boolean removeSection = false;
        boolean removedBase = false, removedInlineProviders = false;
        for (Statement statement : statements) {
            if (statement.header()) {
                section = statement.path();
                removeSection = section.size() >= 2 && section.getFirst().equals("model_providers")
                    && removeProviders.contains(section.get(1));
                if (!removeSection) result.append(statement.raw());
                continue;
            }
            if (removeSection) continue;
            boolean activeScope = section.isEmpty() || section.equals(List.of("profiles", profile));
            if (activeScope && statement.path().equals(List.of("openai_base_url"))) {
                if (!removedBase) {
                    changes.add("OpenAI 代理地址" + scalarHost(statement.raw()).map(host -> " (" + host + ")").orElse(""));
                    removedBase = true;
                }
                continue;
            }
            if (section.isEmpty() && statement.path().equals(List.of("model_providers"))) {
                if (!removedInlineProviders) { changes.add("内联服务商配置"); removedInlineProviders = true; }
                continue;
            }
            result.append(statement.raw());
        }
        String cleaned = result.toString();
        if (changes.isEmpty() || cleaned.equals(current)) return Optional.empty();
        return Optional.of(new ForeignRelayCleanup(cleaned, List.copyOf(changes)));
    }

    static Set<String> markerOwners(String current) {
        LinkedHashSet<String> owners = new LinkedHashSet<>();
        for (Statement statement : parse(current)) {
            Matcher marker = CHANNEL_MARKER.matcher(statement.raw().strip());
            if (marker.matches() && ">>>".equals(marker.group(1))) owners.add(marker.group(2).toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(owners);
    }

    private static String stripChannelMarkerBlocks(String current) {
        StringBuilder result = new StringBuilder();
        String active = null;
        for (Statement statement : parse(current)) {
            Matcher marker = CHANNEL_MARKER.matcher(statement.raw().strip());
            if (marker.matches()) {
                String owner = marker.group(2).toLowerCase(Locale.ROOT);
                if (">>>".equals(marker.group(1))) {
                    if (active != null) throw malformed();
                    active = owner;
                } else {
                    if (active == null || !active.equals(owner)) throw malformed();
                    active = null;
                }
                continue;
            }
            if (active == null) result.append(statement.raw());
        }
        if (active != null) throw malformed();
        return result.toString();
    }

    static Optional<String> rootValue(String current, String key) {
        List<Statement> statements = parse(current);
        return assignment(statements, activeProfile(statements), key);
    }

    static Optional<String> providerValue(String current, String id, String key) {
        if (id == null || id.isBlank()) return Optional.empty();
        List<String> section = List.of();
        for (Statement statement : parse(current)) {
            if (statement.header()) section = statement.path();
            else if (section.equals(List.of("model_providers", id)) && statement.path().equals(List.of(key)))
                return Optional.of(scalar(statement.raw()));
        }
        return Optional.empty();
    }

    private static Optional<String> assignment(List<Statement> statements, String profile, String key) {
        List<String> section = List.of();
        Optional<String> root = Optional.empty();
        for (Statement statement : statements) {
            if (statement.header()) section = statement.path();
            else if (statement.path().equals(List.of(key))) {
                if (section.isEmpty()) root = Optional.of(scalar(statement.raw()));
                else if (section.equals(List.of("profiles", profile))) return Optional.of(scalar(statement.raw()));
            }
        }
        return root;
    }

    private static Optional<String> providerHost(List<Statement> statements, String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        List<String> section = List.of();
        for (Statement statement : statements) {
            if (statement.header()) section = statement.path();
            else if (section.equals(List.of("model_providers", id)) && statement.path().equals(List.of("base_url")))
                return scalarHost(statement.raw());
        }
        return Optional.empty();
    }

    private static Optional<String> scalarHost(String raw) {
        try {
            String value = scalar(raw);
            String host = java.net.URI.create(value).getHost();
            return host == null || host.isBlank() ? Optional.empty() : Optional.of(host.toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) { return Optional.empty(); }
    }

    private static boolean tokenProHost(String host) {
        return host.equals("tokenpro.work") || host.endsWith(".tokenpro.work");
    }

    static String merge(String preserved, String generated) {
        String rootStart = "# >>> tokenpro-codex\n";
        String rootEnd = "# <<< tokenpro-codex\n";
        String body = generated.replace("# >>> TokenPro managed >>>\n", "").replace("# <<< TokenPro managed <<<\n", "");
        int generatedTables = firstTable(body);
        String root = body.substring(0, generatedTables);
        String tables = body.substring(generatedTables);
        int preservedTables = firstTable(preserved);
        String result = newline(preserved.substring(0, preservedTables)) + rootStart + newline(root) + rootEnd
            + newline(preserved.substring(preservedTables));
        if (!tables.isBlank()) result += "# >>> TokenPro managed >>>\n" + newline(tables) + "# <<< TokenPro managed <<<\n";
        return result;
    }

    static String withoutAdministrator(String current) {
        List<Statement> statements = parse(current);
        String profile = activeProfile(statements);
        List<String> profileSection = List.of("profiles", profile);
        List<String> profileWindows = List.of("profiles", profile, "windows");
        boolean windowsTable = statements.stream().anyMatch(s -> s.header() && s.path().equals(List.of("windows")));
        boolean dotted = false;
        List<String> section = List.of();
        for (Statement statement : statements) {
            if (statement.header()) section = statement.path();
            else if ((section.isEmpty() || section.equals(profileSection)) && statement.path().equals(List.of("windows")))
                throw new IllegalStateException("windows 使用内联配置，请先改为 [windows] 表后重试；原配置未修改");
            else if (section.isEmpty() && statement.path().equals(List.of("windows", "sandbox"))) dotted = true;
        }
        StringBuilder result = new StringBuilder();
        section = List.of();
        for (Statement statement : statements) {
            if (statement.header()) {
                section = statement.path(); result.append((section.equals(List.of("windows")) || section.equals(profileWindows)) ? newline(statement.raw()) : statement.raw());
                if (section.equals(List.of("windows")) || section.equals(profileWindows)) result.append("sandbox = \"unelevated\"\n");
            } else if ((section.equals(List.of("windows")) || section.equals(profileWindows)) && statement.path().equals(List.of("sandbox"))) {
                // Replace only the sandbox implementation choice, retaining all other Windows settings.
            } else if ((section.isEmpty() || section.equals(profileSection)) && statement.path().equals(List.of("windows", "sandbox"))) {
                result.append("windows.sandbox = \"unelevated\"\n");
            } else result.append(statement.raw());
        }
        if (!windowsTable && !dotted) return newline(result.toString()) + "[windows]\nsandbox = \"unelevated\"\n";
        return result.toString();
    }

    private static String activeProfile(List<Statement> statements) {
        for (Statement statement : statements) {
            if (statement.header()) break;
            if (statement.path().equals(List.of("profile"))) return scalar(statement.raw());
        }
        return "";
    }

    private static int firstTable(String text) {
        int offset = 0;
        for (Statement statement : parse(text)) {
            if (statement.header()) return offset;
            offset += statement.raw().length();
        }
        return text.length();
    }

    private static String scalar(String raw) {
        String value = raw.substring(raw.indexOf('=') + 1).strip();
        if (value.startsWith("'")) { int end = value.indexOf('\'', 1); return end < 0 ? "" : value.substring(1, end); }
        if (value.startsWith("\"")) {
            boolean escaped = false;
            for (int i=1; i<value.length(); i++) {
                char c = value.charAt(i);
                if (!escaped && c == '"') {
                    try { return (String)Json.parse(value.substring(0, i + 1)); } catch (RuntimeException ignored) { return ""; }
                }
                if (c == '\\' && !escaped) escaped = true; else escaped = false;
            }
        }
        return "";
    }

    private static String newline(String value) { return value.isEmpty() || value.endsWith("\n") ? value : value + "\n"; }
    private static IllegalStateException malformed() { return new IllegalStateException("Codex 配置格式或 TokenPro 标记不完整；原配置未修改"); }
    private record Statement(String raw, boolean header, List<String> path) {}

    /** Lexical boundaries only: values are kept verbatim, including multiline strings, arrays and comments. */
    private static List<Statement> parse(String text) {
        List<Statement> result = new ArrayList<>();
        int start=0, depth=0;
        String quote="";
        boolean comment=false, escape=false;
        for (int i=0; i<text.length(); i++) {
            char c=text.charAt(i);
            if (comment) { if (c!='\n') continue; comment=false; }
            else if (!quote.isEmpty()) {
                if (escape) { escape=false; continue; }
                if (quote.charAt(0)=='"' && c=='\\') { escape=true; continue; }
                if (text.startsWith(quote, i)) {
                    if (quote.length()==3) {
                        char q=quote.charAt(0); i+=2;
                        // TOML allows one or two quote characters immediately before the closing triple.
                        for (int extra=0; extra<2 && i+1<text.length() && text.charAt(i+1)==q; extra++) i++;
                    }
                    quote="";
                }
                continue;
            } else if (c=='#') { comment=true; continue; }
            else if (c=='"' || c=='\'') {
                quote=String.valueOf(c);
                if (text.startsWith(quote.repeat(3),i)) { quote=quote.repeat(3); i+=2; }
                continue;
            } else if (c=='[' || c=='{') depth++;
            else if (c==']' || c=='}') { if (--depth<0) throw malformed(); }
            if (c=='\n' && depth==0) { result.add(statement(text.substring(start,i+1))); start=i+1; }
        }
        if (!quote.isEmpty() || depth!=0) throw malformed();
        if (start<text.length()) result.add(statement(text.substring(start)));
        return result;
    }

    private static Statement statement(String raw) {
        String line=raw.strip();
        if (line.startsWith("\uFEFF")) line=line.substring(1).stripLeading();
        if (line.isEmpty() || line.startsWith("#")) return new Statement(raw,false,List.of());
        boolean header=line.startsWith("[");
        int offset=header ? line.startsWith("[[") ? 2 : 1 : 0;
        List<String> path=new ArrayList<>();
        while (offset<line.length()) {
            while (offset<line.length() && Character.isWhitespace(line.charAt(offset))) offset++;
            if (offset==line.length()) break;
            char c=line.charAt(offset);
            if (c=='=' || header && c==']') return new Statement(raw,header,List.copyOf(path));
            int begin=offset;
            if (c=='"' || c=='\'') {
                offset++;
                boolean escaped=false;
                while (offset<line.length()) {
                    char q=line.charAt(offset++);
                    if (!escaped && q==c) break;
                    if (c=='"' && q=='\\' && !escaped) escaped=true; else escaped=false;
                }
                String token=line.substring(begin,offset);
                try { path.add(c=='"' ? (String)Json.parse(token) : token.substring(1,token.length()-1)); }
                catch (RuntimeException error) { throw malformed(); }
            } else {
                while (offset<line.length() && (Character.isLetterOrDigit(line.charAt(offset)) || "_-".indexOf(line.charAt(offset))>=0)) offset++;
                if (offset==begin) throw malformed();
                path.add(line.substring(begin,offset));
            }
            while (offset<line.length() && Character.isWhitespace(line.charAt(offset))) offset++;
            if (offset<line.length() && line.charAt(offset)=='.') offset++;
            else if (offset<line.length() && (line.charAt(offset)=='=' || header && line.charAt(offset)==']'))
                return new Statement(raw,header,List.copyOf(path));
            else throw malformed();
        }
        throw malformed();
    }
}

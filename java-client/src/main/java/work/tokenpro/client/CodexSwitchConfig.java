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
        boolean[] insideManaged = managedInterior(statements);
        Set<List<String>> owned = ownedProviders(statements, insideManaged);
        StringBuilder result = new StringBuilder();
        List<String> section = List.of();
        for (Statement statement : statements) {
            Marker managed = managedMarker(statement.raw());
            String raw = statement.raw();
            if (managed != null) {
                if (!managed.inline()) continue;
                raw = stripTrailingMarker(raw);
            }
            if (statement.header()) section = statement.path();
            boolean ownTable = section.size() >= 2 && owned.contains(section.subList(0, 2));
            if (ownTable) continue;
            if ((section.isEmpty() || section.equals(List.of("profiles", profile)))
                && statement.path().size() == 1 && ROUTE_KEYS.contains(statement.path().getFirst())) continue;
            result.append(raw);
        }
        return result.toString();
    }

    /**
     * Marker problems that a later rewrite can repair without guessing through
     * broken quotes or brackets. Empty when the file has no TokenPro marker damage.
     * Unparseable TOML throws instead of being reported as marker damage.
     */
    static Optional<String> markerDamage(String current) {
        List<Statement> statements = parse(current);
        List<String> problems = new ArrayList<>();
        problems.addAll(familyDamage(statements, "channel"));
        problems.addAll(familyDamage(statements, "managed"));
        return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("；", problems));
    }

    /** Rebuild a balanced TokenPro route around the current route keys. User tables stay put. */
    static String ensureMarkerBalance(String current) {
        if (markerDamage(current).isEmpty()) return current;
        String preserved = clean(current);
        String fragment = routeFragment(current);
        if (fragment.isBlank()) return preserved;
        return merge(preserved, fragment);
    }

    /** Restore path: drop complete managed regions, and only the marker line when a pair is broken. */
    static String stripManagedRegions(String text) {
        List<Statement> statements = parse(text);
        boolean[] drop = new boolean[statements.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        Marker[] markers = managedMarkers(statements);
        for (int i = 0; i < markers.length; i++) {
            Marker marker = markers[i];
            if (marker == null) continue;
            if (marker.start()) stack.push(i);
            else if (!stack.isEmpty() && markers[stack.peek()].id().equals(marker.id())) {
                int start = stack.pop();
                drop[start] = true;
                for (int j = start + 1; j < i; j++) drop[j] = true;
                if (!marker.inline()) drop[i] = true;
            } else if (!marker.inline()) drop[i] = true;
        }
        while (!stack.isEmpty()) drop[stack.pop()] = true;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < statements.size(); i++) if (!drop[i]) result.append(withoutInlineMarker(statements.get(i).raw()));
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
            Marker marker = markerOf(statement.raw());
            if (marker != null && "channel".equals(marker.family()) && marker.start()) owners.add(marker.id());
        }
        return Set.copyOf(owners);
    }

    private static String stripChannelMarkerBlocks(String current) {
        List<Statement> statements = parse(current);
        boolean[] drop = new boolean[statements.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        Marker[] markers = channelMarkers(statements);
        for (int i = 0; i < markers.length; i++) {
            Marker marker = markers[i];
            if (marker == null) continue;
            if (marker.start()) stack.push(i);
            else if (!stack.isEmpty() && markers[stack.peek()].id().equals(marker.id())) {
                // A matched pair owns everything between its markers, including route keys.
                // A suffix closer stays with its key so a user setting Codex attached it to is not deleted.
                int start = stack.pop();
                drop[start] = true;
                for (int j = start + 1; j < i; j++) drop[j] = true;
                if (!marker.inline()) drop[i] = true;
            } else if (!marker.inline()) drop[i] = true;
        }
        // An orphan start owns only its own line. Swallowing through EOF would delete
        // plugins, MCP servers and desktop settings that Codex left outside the block
        // after it dropped the closing marker.
        while (!stack.isEmpty()) drop[stack.pop()] = true;
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < statements.size(); i++) if (!drop[i]) result.append(withoutChannelSuffix(statements.get(i).raw()));
        return result.toString();
    }

    private static String withoutInlineMarker(String raw) {
        Marker marker = markerOf(raw);
        return marker != null && marker.inline() ? stripTrailingMarker(raw) : raw;
    }

    private static String withoutChannelSuffix(String raw) {
        Marker marker = markerOf(raw);
        return marker != null && marker.inline() && "channel".equals(marker.family()) ? stripTrailingMarker(raw) : raw;
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
        String root = sealTrailing(newline(body.substring(0, generatedTables)), "# <<< tokenpro-codex");
        String tables = sealTrailing(newline(body.substring(generatedTables)), "# <<< TokenPro managed <<<");
        int preservedTables = firstTable(preserved);
        String result = newline(preserved.substring(0, preservedTables)) + rootStart + root + rootEnd
            + newline(preserved.substring(preservedTables));
        if (!tables.isBlank()) result += "# >>> TokenPro managed >>>\n" + tables + "# <<< TokenPro managed <<<\n";
        // Both channel markers are part of this one string, then written with a temp-file rename.
        // A later Codex rewrite may still drop the freestanding closing comment; the next switch repairs it.
        requireBalanced(result);
        return result;
    }

    static void requireBalanced(String config) {
        Optional<String> damage = markerDamage(config);
        if (damage.isPresent())
            throw new IllegalStateException("TokenPro 未能写回成对的 Codex 标记（" + damage.get()
                + "），原配置未修改。可从同目录 config.toml.tokenpro-backup-* 恢复，或移走 config.toml 后重新选择模型以重建路由。");
    }

    /**
     * Preserve desktop settings while removing the obsolete Computer Use
     * transport selector. Command-based MCP servers use Codex's default stdio
     * transport; some older Windows configurations retain an unsupported value
     * here and make the complete config unloadable.
     */
    static String ensureRuntimeSettings(String current) {
        List<Statement> statements = parse(current);
        StringBuilder result = new StringBuilder();
        List<String> section = List.of();
        boolean desktopFound = false;
        boolean desktopLocale = false;
        for (Statement statement : statements) {
            if (statement.header()) {
                if (section.equals(List.of("desktop")) && !desktopLocale)
                    result.append("localeOverride = \"zh-CN\"\n");
                section = statement.path();
                if (section.equals(List.of("desktop"))) desktopFound = true;
                result.append(statement.raw());
            } else if (section.equals(List.of("mcp_servers", "computer-use"))
                && statement.path().equals(List.of("transport"))) {
                // Omit only the stale selector; preserve command, args, enabled,
                // and every other user-owned MCP setting verbatim.
            } else if (section.equals(List.of("desktop"))
                && statement.path().equals(List.of("localeOverride"))) {
                result.append("localeOverride = \"zh-CN\"\n");
                desktopLocale = true;
            } else {
                result.append(statement.raw());
            }
        }
        if (section.equals(List.of("desktop")) && !desktopLocale)
            result.append("localeOverride = \"zh-CN\"\n");
        if (!desktopFound) result.append("\n[desktop]\nlocaleOverride = \"zh-CN\"\n");
        return result.toString();
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

    private record Marker(String family, String id, boolean start, boolean inline) {}

    private static Marker markerOf(String raw) {
        Marker pure = pureMarker(logical(raw));
        if (pure != null) return pure;
        int hash = trailingMarkerIndex(raw);
        if (hash < 0) return null;
        String comment = raw.substring(hash, raw.indexOf('\n', hash) < 0 ? raw.length() : raw.indexOf('\n', hash)).strip();
        Marker trailing = pureMarker(comment);
        // Only a closing marker is meaningful as a suffix. Codex keeps suffixes attached to a key
        // and drops a freestanding comment that sits between that key and the next table.
        if (trailing == null || trailing.start()) return null;
        return new Marker(trailing.family(), trailing.id(), false, true);
    }

    private static Marker pureMarker(String line) {
        Matcher channel = CHANNEL_MARKER.matcher(line);
        if (channel.matches())
            return new Marker("channel", channel.group(2).toLowerCase(Locale.ROOT), ">>>".equals(channel.group(1)), false);
        if (MARKERS.containsKey(line)) return new Marker("managed", line, true, false);
        for (var entry : MARKERS.entrySet())
            if (entry.getValue().equals(line)) return new Marker("managed", entry.getKey(), false, false);
        return null;
    }

    /** Index of a trailing `# ...` comment that is the last token of the statement, outside quotes. */
    private static int trailingMarkerIndex(String raw) {
        String quote = "";
        boolean escape = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (!quote.isEmpty()) {
                if (escape) { escape = false; continue; }
                if (quote.charAt(0) == '"' && c == '\\') { escape = true; continue; }
                if (raw.startsWith(quote, i)) {
                    if (quote.length() == 3) {
                        char q = quote.charAt(0);
                        i += 2;
                        for (int extra = 0; extra < 2 && i + 1 < raw.length() && raw.charAt(i + 1) == q; extra++) i++;
                    }
                    quote = "";
                }
                continue;
            }
            if (c == '#') {
                int end = raw.indexOf('\n', i);
                String rest = end < 0 ? "" : raw.substring(end + 1);
                return rest.isBlank() ? i : -1;
            }
            if (c == '"' || c == '\'') {
                quote = String.valueOf(c);
                if (raw.startsWith(quote.repeat(3), i)) { quote = quote.repeat(3); i += 2; }
            }
        }
        return -1;
    }

    private static String stripTrailingMarker(String raw) {
        int hash = trailingMarkerIndex(raw);
        if (hash < 0) return raw;
        int lineEnd = raw.indexOf('\n', hash);
        String head = raw.substring(0, hash).stripTrailing();
        String tail = lineEnd < 0 ? "" : raw.substring(lineEnd);
        if (head.isEmpty()) return tail.startsWith("\n") || tail.startsWith("\r") ? tail.substring(tail.indexOf('\n') + 1) : "";
        return head + (tail.isEmpty() ? "\n" : tail);
    }

    /** Keep a closing marker on the last route line so a Codex rewrite is less likely to drop it. */
    private static String sealTrailing(String body, String marker) {
        if (body.isBlank() || body.contains(marker)) return body;
        int end = body.length();
        while (end > 0 && (body.charAt(end - 1) == '\n' || body.charAt(end - 1) == '\r')) end--;
        if (end == 0) return body;
        int line = body.lastIndexOf('\n', end - 1);
        String last = body.substring(line + 1, end).strip();
        if (last.isEmpty() || last.startsWith("#")) return body;
        return body.substring(0, end) + " " + marker + body.substring(end);
    }

    private static Marker managedMarker(String raw) {
        Marker marker = markerOf(raw);
        return marker != null && "managed".equals(marker.family()) ? marker : null;
    }

    private static String logical(String raw) {
        String line = raw.strip();
        if (line.startsWith("\uFEFF")) line = line.substring(1).stripLeading();
        return line;
    }

    private static Marker[] channelMarkers(List<Statement> statements) {
        Marker[] markers = new Marker[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            Marker marker = markerOf(statements.get(i).raw());
            if (marker != null && "channel".equals(marker.family())) markers[i] = marker;
        }
        return markers;
    }

    private static Marker[] managedMarkers(List<Statement> statements) {
        Marker[] markers = new Marker[statements.size()];
        for (int i = 0; i < statements.size(); i++) {
            Marker marker = markerOf(statements.get(i).raw());
            if (marker != null && "managed".equals(marker.family())) markers[i] = marker;
        }
        return markers;
    }

    /** Interior of a matched non-TokenPro channel block. TokenPro's own route keys stay readable. */
    private static boolean[] foreignChannelInterior(List<Statement> statements) {
        boolean[] inside = new boolean[statements.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        Marker[] markers = channelMarkers(statements);
        for (int i = 0; i < markers.length; i++) {
            Marker marker = markers[i];
            if (marker == null) continue;
            if (marker.start()) stack.push(i);
            else if (!stack.isEmpty() && markers[stack.peek()].id().equals(marker.id())) {
                int start = stack.pop();
                if (!"tokenpro".equals(marker.id())) {
                    for (int j = start + 1; j < i; j++) inside[j] = true;
                }
            }
        }
        return inside;
    }

    /** Statements strictly inside a matched managed pair. Orphan markers do not claim the rest of the file. */
    private static boolean[] managedInterior(List<Statement> statements) {
        boolean[] inside = new boolean[statements.size()];
        Deque<Integer> stack = new ArrayDeque<>();
        Marker[] markers = managedMarkers(statements);
        for (int i = 0; i < markers.length; i++) {
            Marker marker = markers[i];
            if (marker == null) continue;
            if (marker.start()) stack.push(i);
            else if (!stack.isEmpty() && markers[stack.peek()].id().equals(marker.id())) {
                int start = stack.pop();
                for (int j = start + 1; j < i; j++) inside[j] = true;
            }
        }
        return inside;
    }

    private static Set<List<String>> ownedProviders(List<Statement> statements, boolean[] insideManaged) {
        Set<List<String>> owned = new HashSet<>();
        List<String> section = List.of();
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = statements.get(i);
            if (statement.header()) {
                section = statement.path();
                if (insideManaged[i] && section.size() >= 2 && section.getFirst().equals("model_providers"))
                    owned.add(List.copyOf(section.subList(0, 2)));
            } else if (section.size() == 2 && section.getFirst().equals("model_providers")
                && statement.path().equals(List.of("base_url"))) {
                // Migrate older unmarked TokenPro provider tables without deleting third-party providers.
                String value = scalar(statement.raw());
                try {
                    if ("tokenpro.work".equalsIgnoreCase(java.net.URI.create(value).getHost()))
                        owned.add(List.copyOf(section));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        return owned;
    }

    private static List<String> familyDamage(List<Statement> statements, String family) {
        List<String> problems = new ArrayList<>();
        Deque<Marker> stack = new ArrayDeque<>();
        int pairs = 0;
        boolean legacy = false;
        Marker lastClose = null;
        boolean contentSinceClose = false;
        for (Statement statement : statements) {
            Marker marker = markerOf(statement.raw());
            if (marker == null || !marker.family().equals(family)) {
                String line = logical(statement.raw());
                if (!line.isEmpty() && !line.startsWith("#")) contentSinceClose = true;
                continue;
            }
            if ("managed".equals(family) && (marker.id().contains("model selection") || marker.id().contains("history provider")))
                legacy = true;
            if (marker.start()) {
                if (!stack.isEmpty()) problems.add(markerLabel(marker) + "起始标记重复或嵌套");
                stack.push(marker);
                lastClose = null;
                contentSinceClose = false;
            } else if (stack.isEmpty()) {
                // The writer emits the closer twice: once as a key suffix Codex tends to keep,
                // and once as its own line. The second one is a seal, not a second block.
                if (lastClose != null && lastClose.id().equals(marker.id()) && !contentSinceClose) continue;
                problems.add(markerLabel(marker) + "结束标记没有对应的起始标记");
            } else if (!stack.peek().id().equals(marker.id())) {
                problems.add(markerLabel(marker) + "结束标记与起始标记不匹配");
            } else {
                stack.pop();
                pairs++;
                lastClose = marker;
                contentSinceClose = false;
            }
        }
        for (Marker open : stack) problems.add(markerLabel(open) + "起始标记没有对应的结束标记");
        if (pairs > 1) problems.add("channel".equals(family) ? "渠道标记区块重复" : "TokenPro 管理区块重复");
        if (legacy) problems.add("存在旧版 TokenPro 标记");
        return problems;
    }

    private static String markerLabel(Marker marker) {
        if ("channel".equals(marker.family())) return marker.id() + "-codex ";
        if (marker.id().contains("model selection")) return "TokenPro model selection ";
        if (marker.id().contains("history provider")) return "TokenPro history provider ";
        return "TokenPro managed ";
    }

    /** Route keys and TokenPro provider tables worth re-wrapping after an orphan marker is removed. */
    private static String routeFragment(String current) {
        List<Statement> statements = parse(current);
        boolean[] inside = managedInterior(statements);
        Set<List<String>> owned = ownedProviders(statements, inside);
        boolean[] foreign = foreignChannelInterior(statements);
        String profile = activeProfile(statements);
        Map<String, String> roots = new LinkedHashMap<>();
        StringBuilder tables = new StringBuilder();
        List<String> section = List.of();
        List<String> copying = List.of();
        for (int i = 0; i < statements.size(); i++) {
            if (foreign[i]) continue;
            Statement statement = statements.get(i);
            Marker marker = markerOf(statement.raw());
            String raw = statement.raw();
            if (marker != null) {
                if (!marker.inline()) continue;
                raw = stripTrailingMarker(raw);
            }
            if (statement.header()) {
                section = statement.path();
                boolean copy = section.size() >= 2 && owned.contains(section.subList(0, 2));
                copying = copy ? List.copyOf(section.subList(0, 2)) : List.of();
                if (copy) tables.append(raw);
                continue;
            }
            if (!copying.isEmpty() && section.size() >= 2 && section.subList(0, 2).equals(copying)) {
                tables.append(raw);
                continue;
            }
            boolean routeScope = section.isEmpty() || section.equals(List.of("profiles", profile));
            if (routeScope && statement.path().size() == 1 && ROUTE_KEYS.contains(statement.path().getFirst())) {
                roots.remove(statement.path().getFirst());
                roots.put(statement.path().getFirst(), raw);
            }
        }
        if (roots.isEmpty() && tables.isEmpty()) return "";
        StringBuilder body = new StringBuilder("# >>> TokenPro managed >>>\n");
        for (String raw : roots.values()) body.append(newline(raw));
        if (!tables.isEmpty()) body.append('\n').append(tables);
        if (!body.toString().endsWith("\n")) body.append('\n');
        body.append("# <<< TokenPro managed <<<\n");
        return body.toString();
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
    private static IllegalStateException unparseable() {
        return new IllegalStateException("Codex 配置无法解析（引号、括号或表名不完整），原配置未修改。"
            + "请检查 config.toml 的引号和括号是否成对；可从同目录的 config.toml.tokenpro-backup-* 恢复，"
            + "或把损坏的 config.toml 移走后在 TokenPro 中重新选择模型以重建路由。");
    }
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
            else if (c==']' || c=='}') { if (--depth<0) throw unparseable(); }
            if (c=='\n' && depth==0) { result.add(statement(text.substring(start,i+1))); start=i+1; }
        }
        if (!quote.isEmpty() || depth!=0) throw unparseable();
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
                catch (RuntimeException error) { throw unparseable(); }
            } else {
                while (offset<line.length() && (Character.isLetterOrDigit(line.charAt(offset)) || "_-".indexOf(line.charAt(offset))>=0)) offset++;
                if (offset==begin) throw unparseable();
                path.add(line.substring(begin,offset));
            }
            while (offset<line.length() && Character.isWhitespace(line.charAt(offset))) offset++;
            if (offset<line.length() && line.charAt(offset)=='.') offset++;
            else if (offset<line.length() && (line.charAt(offset)=='=' || header && line.charAt(offset)==']'))
                return new Statement(raw,header,List.copyOf(path));
            else throw unparseable();
        }
        throw unparseable();
    }
}

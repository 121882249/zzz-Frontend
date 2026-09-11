package work.tokenpro.client;

import java.util.*;
import java.util.regex.*;

/** Only retain supported, non-secret root preferences when rebuilding the provider config. */
final class CodexPreferences {
    private CodexPreferences() {}

    static String rootString(String config, String key) {
        Pattern value = Pattern.compile("^" + Pattern.quote(key) + "\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')\\s*(?:#.*)?$");
        for (String line : config.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("[")) break;
            Matcher match = value.matcher(trimmed);
            if (!match.matches()) continue;
            String raw = match.group(1);
            try { return raw.startsWith("'") ? raw.substring(1, raw.length() - 1) : (String) Json.parse(raw); }
            catch (Exception ignored) { return ""; }
        }
        return "";
    }

    static String retainedLines(String current, Map<String, Object> model) {
        List<String> efforts = ids(model.get("supported_reasoning_levels"), "effort");
        String chosen = rootString(current, "model_reasoning_effort");
        StringBuilder result = new StringBuilder();
        if (!efforts.isEmpty()) {
            if (!efforts.contains(chosen)) chosen = Objects.toString(model.get("default_reasoning_level"), "");
            if (!efforts.contains(chosen)) chosen = efforts.contains("high") ? "high" : efforts.getFirst();
            result.append("model_reasoning_effort = ").append(Json.stringify(chosen)).append('\n');
        }
        String tier = rootString(current, "service_tier");
        if (tier.equals("fast")) tier = "priority";
        if (tier.equals("default") || ids(model.get("service_tiers"), "id").contains(tier))
            result.append("service_tier = ").append(Json.stringify(tier)).append('\n');
        return result.toString();
    }

    private static List<String> ids(Object raw, String key) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> Objects.toString(Json.object(item).get(key), ""))
            .filter(value -> !value.isBlank()).distinct().toList();
    }
}

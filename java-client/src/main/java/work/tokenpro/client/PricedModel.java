package work.tokenpro.client;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

record PricedModel(String name, String platform, String groupName, long groupId, String billingMode,
                   Double inputPrice, Double officialOutputPrice, List<ImagePrice> imagePrices) {
    record ImagePrice(String label, double perImage) {}

    PricedModel {
        imagePrices = imagePrices == null ? List.of() : List.copyOf(imagePrices);
    }

    PricedModel(String name, String platform, String groupName, long groupId) {
        this(name, platform, groupName, groupId, "token", null, null, List.of());
    }

    PricedModel(String name, String platform, String groupName, long groupId,
                String billingMode, Double officialOutputPrice) {
        this(name, platform, groupName, groupId, billingMode, null, officialOutputPrice, List.of());
    }

    boolean tokenBilled() { return billingMode == null || billingMode.isBlank() || "token".equalsIgnoreCase(billingMode); }

    boolean usesResponses() { return "openai".equalsIgnoreCase(platform); }

    boolean isImageGeneration() {
        String value = name == null ? "" : name.trim().toLowerCase(java.util.Locale.ROOT);
        return value.startsWith("gpt-image-") || value.startsWith("dall-e-") ||
            value.contains("imagen") || value.contains("flux") ||
            value.startsWith("grok-imagine");
    }

    String displayName() {
        String value = name == null ? "" : name.trim();
        if (usesResponses() && !value.regionMatches(true, 0, "gpt", 0, 3)) value = "GPT-" + value;
        return displayCase(value);
    }

    String codexDisplayName() {
        String value = displayName();
        // Codex prettifies a leading ASCII "GPT-" by removing the vendor name.
        // A zero-width word joiner preserves the intended visual label while
        // leaving the real model slug untouched.
        return value.startsWith("GPT-") ? "GPT\u2060-" + value.substring(4) : value;
    }

    String priceLabel() {
        if (isImageGeneration() && !imagePrices.isEmpty()) {
            return imagePrices.stream()
                .map(price -> price.label() + " ¥" + money(price.perImage()) + "/IMG")
                .collect(Collectors.joining(" · "));
        }
        return inputPrice == null ? "" : "Input ¥" + money(inputPrice * 1_000_000d) + "/M";
    }

    private static String money(double value) { return String.format(Locale.US, "%.2f", value); }

    String displayGroupName() { return displayCase(groupName); }
    String displayPlatform() { return displayCase(platform); }

    static String displayCase(String source) {
        String value = source == null ? "" : source.trim();
        StringBuilder result = new StringBuilder(value.length());
        boolean wordStart = true;
        for (int index = 0; index < value.length();) {
            if (index + 3 <= value.length() && wordStart && value.regionMatches(true, index, "gpt", 0, 3)) {
                result.append("GPT");
                index += 3;
                wordStart = false;
                continue;
            }
            char current = value.charAt(index++);
            if (current == '-' && index > 1 && index < value.length()
                && Character.isDigit(value.charAt(index - 2)) && Character.isDigit(value.charAt(index))) {
                result.append('.');
                wordStart = false;
                continue;
            }
            if (wordStart && Character.isLetter(current)) current = Character.toUpperCase(current);
            result.append(current);
            wordStart = current == '-' || current == '_' || current == '/' || Character.isWhitespace(current);
        }
        return result.toString();
    }

    public String toString() { return displayName() + "  ·  " + displayGroupName() + "  [" + displayPlatform() + "]"; }
}

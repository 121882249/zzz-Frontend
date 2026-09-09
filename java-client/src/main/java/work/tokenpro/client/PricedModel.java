package work.tokenpro.client;

record PricedModel(String name, String platform, String groupName, long groupId) {
    boolean usesResponses() { return "openai".equalsIgnoreCase(platform); }

    String displayName() {
        String value = name == null ? "" : name.trim();
        if (usesResponses() && !value.regionMatches(true, 0, "gpt", 0, 3)) value = "GPT-" + value;
        return displayCase(value);
    }

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
            if (wordStart && Character.isLetter(current)) current = Character.toUpperCase(current);
            result.append(current);
            wordStart = current == '-' || current == '_' || current == '/' || Character.isWhitespace(current);
        }
        return result.toString();
    }

    public String toString() { return displayName() + "  ·  " + displayGroupName() + "  [" + displayPlatform() + "]"; }
}

package work.tokenpro.client;

record PricedModel(String name, String platform, String groupName, long groupId) {
    boolean usesResponses() { return "openai".equalsIgnoreCase(platform); }
    String displayName() {
        String value = name == null ? "" : name.trim();
        if (!usesResponses()) return value;
        if (value.regionMatches(true, 0, "gpt", 0, 3)) return "GPT" + value.substring(3);
        return "GPT-" + value;
    }
    public String toString() { return displayName() + "  ·  " + groupName + "  [" + platform + "]"; }
}

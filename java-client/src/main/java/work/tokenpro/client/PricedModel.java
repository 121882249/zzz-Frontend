package work.tokenpro.client;

record PricedModel(String name, String platform, String groupName, long groupId) {
    boolean usesResponses() { return "openai".equalsIgnoreCase(platform); }
    public String toString() { return name + "  ·  " + groupName + "  [" + platform + "]"; }
}

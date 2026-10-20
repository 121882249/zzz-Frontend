package work.tokenpro.client;

/** Declarative target state for one complete Codex channel activation. */
record CodexChannel(String id, String name, String authMode, String modelProvider,
                    String baseUrl, String markerOwner, CacheStrategy cacheStrategy) {
    enum CacheStrategy { OFFICIAL_STALE, CHANNEL }

    static CodexChannel official() {
        return new CodexChannel("official", "Codex 官方订阅", "chatgpt", "", "", "", CacheStrategy.OFFICIAL_STALE);
    }

    static CodexChannel tokenPro() {
        return new CodexChannel("tokenpro", "TokenPro", "apikey", "tokenpro",
            "https://tokenpro.work/v1", "tokenpro", CacheStrategy.CHANNEL);
    }

    boolean officialChannel() { return "official".equals(id); }
}

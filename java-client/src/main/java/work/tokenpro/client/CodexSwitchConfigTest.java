package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class CodexSwitchConfigTest {
    private static final String OFFICIAL_AUTH = "{\"auth_mode\":\"chatgpt\",\"OPENAI_API_KEY\":null,\"tokens\":{\"refresh_token\":\"fixture-refresh\"}}";
    private static final String ROUTE = "# >>> TokenPro managed >>>\nmodel = \"fixture-model\"\nmodel_provider = \"openai\"\n"
        + "openai_base_url = \"https://tokenpro.work/v1\"\n# <<< TokenPro managed <<<\n";
    private static final String LEGACY_ROUTE = "# >>> TokenPro managed >>>\nmodel = \"fixture-model\"\nmodel_provider = \"custom\"\n"
        + "[model_providers.custom]\nbase_url = \"https://tokenpro.work/v1\"\nexperimental_bearer_token = \"fixture-route-key\"\n# <<< TokenPro managed <<<\n";
    private static final String SETTINGS = "approval_policy = \"on-request\"\nsandbox_mode = \"workspace-write\"\n"
        + "instructions = \"\"\"Keep this text:\n[windows]\n# >>> TokenPro managed >>>\n\"\"\"\n"
        + "[windows]\nsandbox = \"elevated\"\nsandbox_private_desktop = true\n"
        + "[projects.\"C:\\\\Work\\\\demo\"]\ntrust_level = \"trusted\"\n"
        + "[mcp_servers.example]\ncommand = \"fixture.exe\"\nargs = [\n  \"--name\",\n  \"[windows]\"\n]\n";

    static int run() throws Exception {
        int passed = 0;
        String baseline = "model = \"old-model\"\n" + SETTINGS;
        String retained = CodexSwitchConfig.clean(baseline);
        check(retained.equals(SETTINGS), "only model route fields removed from original config"); passed++;
        String merged = CodexSwitchConfig.merge(retained, ROUTE);
        check(merged.contains("sandbox = \"elevated\"") && merged.contains("approval_policy = \"on-request\""), "merge alone preserves existing security settings"); passed++;
        check(merged.contains("instructions = \"\"\"Keep this text:\n[windows]\n# >>> TokenPro managed >>>\n\"\"\""), "TOML strings containing table names and markers preserved"); passed++;
        check(CodexSwitchConfig.clean(merged).equals(SETTINGS), "round trip removes TokenPro roots and credentials without losing user settings"); passed++;
        check(CodexSwitchConfig.merge(CodexSwitchConfig.clean(merged), ROUTE).equals(merged), "repeated model switches are idempotent"); passed++;
        String compatible = CodexSwitchConfig.withoutAdministrator(retained);
        check(compatible.contains("[windows]\nsandbox = \"unelevated\"\nsandbox_private_desktop = true"), "Windows mode changes without dropping private desktop setting"); passed++;
        check(compatible.contains("sandbox_mode = \"workspace-write\"") && compatible.contains("approval_policy = \"on-request\""), "no-admin mode does not disable sandbox or approvals"); passed++;
        check(CodexSwitchConfig.withoutAdministrator(compatible).equals(compatible), "repeated Windows setup retains same mode"); passed++;
        check(CodexSwitchConfig.withoutAdministrator("").equals("[windows]\nsandbox = \"unelevated\"\n"), "new Windows config starts without requesting elevated sandbox"); passed++;
        check(CodexSwitchConfig.withoutAdministrator("[windows]").equals("[windows]\nsandbox = \"unelevated\"\n"), "no newline at EOF handled"); passed++;
        check(CodexSwitchConfig.withoutAdministrator("windows.sandbox = 'elevated'\n[features]\na = true\n").equals("windows.sandbox = \"unelevated\"\n[features]\na = true\n"), "dotted sandbox assignment avoids duplicate table"); passed++;
        check(CodexSwitchConfig.withoutAdministrator("[\"windows\"] # comment\n'sandbox' = 'elevated'\n").equals("[\"windows\"] # comment\nsandbox = \"unelevated\"\n"), "quoted keys and comments supported"); passed++;
        check(CodexSwitchConfig.clean("\uFEFFapproval_policy = 'on-request'\nmodel='old'\n").equals("\uFEFFapproval_policy = 'on-request'\n"), "UTF-8 BOM preserved with user settings"); passed++;
        String oldWithWindowsInside = LEGACY_ROUTE.replace("# <<< TokenPro managed <<<", "[windows]\nsandbox='elevated'\n# <<< TokenPro managed <<<");
        check(CodexSwitchConfig.clean(oldWithWindowsInside).equals("[windows]\nsandbox='elevated'\n"), "user Windows settings inside legacy markers survive migration"); passed++;
        check(CodexSwitchConfig.clean("model_provider='custom'\n[model_providers.custom]\nbase_url='https://tokenpro.work/v1'\nexperimental_bearer_token='old-key'\n[windows]\nsandbox='elevated'\n").equals("[windows]\nsandbox='elevated'\n"), "unmarked legacy TokenPro route removed"); passed++;
        String foreign = "[model_providers.custom]\nbase_url='https://other.example/v1'\n";
        check(CodexSwitchConfig.clean(foreign).equals(foreign), "unrelated provider preserved during restore"); passed++;
        check(CodexSwitchConfig.merge(foreign, ROUTE).contains("https://other.example/v1"),
            "built-in OpenAI routing no longer conflicts with an inactive user custom provider"); passed++;
        String foreignRoute = "openai_base_url='http://127.0.0.1:51427/v1'\nmodel='alias'\nmodel_provider='custom'\n"
            + "approval_policy='on-request'\n[model_providers.custom]\nbase_url='https://other.example/v1'\nexperimental_bearer_token='secret'\n"
            + "[model_providers.keep]\nbase_url='https://keep.example/v1'\n[projects.demo]\ntrust_level='trusted'\n";
        CodexSwitchConfig.ForeignRelayCleanup cleanup = CodexSwitchConfig.foreignRelayCleanup(foreignRoute).orElseThrow();
        check(!cleanup.cleaned().contains("openai_base_url") && !cleanup.cleaned().contains("other.example")
            && !cleanup.cleaned().contains("experimental_bearer_token"), "confirmed cleanup removes the active foreign relay and credential"); passed++;
        check(cleanup.cleaned().contains("approval_policy='on-request'") && cleanup.cleaned().contains("keep.example")
            && cleanup.cleaned().contains("trust_level='trusted'"), "foreign cleanup preserves permissions, inactive providers and projects"); passed++;
        check(cleanup.changes().stream().anyMatch(value -> value.contains("custom")),
            "foreign cleanup reports the replaced provider without exposing credentials"); passed++;
        String namedRoutes = "model_provider='active'\n[model_providers.active]\nbase_url='https://active.example/v1'\n"
            + "[model_providers.inactive]\nbase_url='https://inactive.example/v1'\n";
        String namedClean = CodexSwitchConfig.foreignRelayCleanup(namedRoutes).orElseThrow().cleaned();
        check(!namedClean.contains("https://active.example/v1") && namedClean.contains("https://inactive.example/v1"),
            "only the active named relay is removed"); passed++;
        check(CodexSwitchConfig.foreignRelayCleanup(ROUTE).isEmpty(), "TokenPro's own route is never treated as foreign"); passed++;
        String teamo = "# >>> teamorouter-codex\nmodel='relay'\nmodel_provider='openai'\n"
            + "openai_base_url='https://api.teamorouter.cn/v1'\napproval_policy='never'\n# <<< teamorouter-codex\n"
            + "[projects.demo]\ntrust_level='trusted'\n";
        CodexSwitchConfig.ForeignRelayCleanup teamoCleanup = CodexSwitchConfig.foreignRelayCleanup(teamo).orElseThrow();
        check(teamoCleanup.cleaned().equals("[projects.demo]\ntrust_level='trusted'\n")
            && teamoCleanup.changes().contains("渠道标记 teamorouter"), "foreign owner block is removed while L0 project settings survive"); passed++;
        check(CodexSwitchConfig.markerOwners(teamo).equals(Set.of("teamorouter")), "active marker owners are detected from disk"); passed++;
        for (String invalid : List.of("# >>> TokenPro managed >>>\nmodel='x'\n", "# >>> teamorouter-codex\nmodel='x'\n",
                "note = \"\"\"unfinished\n", "args = [1,2\n")) {
            try { CodexSwitchConfig.clean(invalid); throw new AssertionError("malformed config accepted"); }
            catch (IllegalStateException expected) { passed++; }
        }
        String profile = "profile='work'\n[profiles.work]\nmodel_provider='old'\napproval_policy='on-request'\n[profiles.work.windows]\nsandbox='elevated'\n[profiles.other]\nmodel='keep-this'\n";
        String cleanProfile = CodexSwitchConfig.clean(profile);
        check(!cleanProfile.contains("model_provider='old'") && cleanProfile.contains("approval_policy='on-request'")
            && cleanProfile.contains("model='keep-this'"), "active profile route reset while retaining its permissions and inactive profile"); passed++;
        String profileMode = CodexSwitchConfig.withoutAdministrator(cleanProfile);
        check(profileMode.contains("[profiles.work.windows]\nsandbox = \"unelevated\"") && !profileMode.contains("sandbox='elevated'"), "active profile cannot override no-admin mode"); passed++;
        passed += actualSwitch();
        return passed;
    }

    private static int actualSwitch() throws Exception {
        Path root = Files.createTempDirectory("tokenpro-preserve-switch-");
        try {
            SecureStore store = new SecureStore(root.resolve("store"));
            Path configPath = root.resolve("home/config.toml");
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, SETTINGS);
            Path auth = configPath.resolveSibling("auth.json");
            Files.writeString(auth, OFFICIAL_AUTH);
            CodexConfig config = new CodexConfig(store, configPath);
            config.apply("https://tokenpro.work/v1", List.of(new PricedModel("gpt-5.4", "openai", "fixture", 1)), "fixture-route-key", "fixture@example.test");
            String applied = Files.readString(configPath);
            check(applied.contains("model_provider = \"openai\"")
                && applied.contains("openai_base_url = \"https://tokenpro.work/v1\""),
                "TokenPro openai provider preserves the API-key route");
            check(Files.exists(configPath.getParent().resolve("skills/tokenpro-imagegen/SKILL.md")),
                "TokenPro activation installs tokenpro-imagegen");
            check(Files.readString(configPath.getParent().resolve("skills/tokenpro-imagegen/SKILL.md"))
                .contains("\"" + configPath.getParent().resolve("skills/tokenpro-imagegen/scripts/tokenpro-imagegen") + "\" generate"),
                "TokenPro image Skill installs a runnable absolute CLI path");
            check(Files.readString(auth).contains("\"auth_mode\":\"apikey\""), "TokenPro activation installs API-key auth");
            check(store.read(CodexChannelState.OFFICIAL_AUTH_FILE).orElseThrow().equals(OFFICIAL_AUTH),
                "official OAuth login has an independent private copy");
            check(Files.readString(configPath.resolveSibling("models_cache.json")).contains(CodexConfig.routedModelId(new PricedModel("gpt-5.4", "openai", "fixture", 1))),
                "TokenPro activation refreshes the active model cache");
            if (Platform.OS_KIND != Platform.OS.WINDOWS)
                check(Files.getPosixFilePermissions(auth).equals(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)), "Codex auth is written with mode 0600");
            if (Platform.OS_KIND != Platform.OS.WINDOWS)
                check(Files.getPosixFilePermissions(configPath).equals(Set.of(java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE)), "provider config containing the global key is written with mode 0600");
            check(CodexConfig.tokenProActive(configPath), "fresh TokenPro route is recognized as active");
            check(applied.contains("trust_level = \"trusted\"") && applied.contains("args = [\n  \"--name\""), "actual apply preserves project and MCP settings");
            check(applied.contains("sandbox_mode = \"workspace-write\"") && applied.contains("approval_policy = \"on-request\""), "actual apply preserves permission policy");
            check(applied.contains("sandbox = \"" + (Platform.OS_KIND == Platform.OS.WINDOWS ? "unelevated" : "elevated") + "\""), "no-admin selection is Windows-only");
            check(store.read("codex-last-switch-config.toml").orElseThrow().equals(SETTINGS), "pre-switch config privately backed up");
            PricedModel refreshedModel = new PricedModel("gpt-6-refresh", "openai", "refreshed", 9);
            config.refreshModelCatalog(List.of(refreshedModel));
            String refreshed = Files.readString(configPath);
            check(refreshed.contains("model = \"" + CodexConfig.routedModelId(refreshedModel) + "\"")
                && !refreshed.contains("review_model"),
                "catalog refresh updates the active model without pinning background work to a stale model");
            try (var catalogs = Files.list(store.root().resolve("codex-models"))) {
                check(catalogs.filter(Files::isRegularFile).count() == 1, "catalog refresh retires the stale TokenPro catalog");
            }
            config.deleteForOfficial();
            String restored = Files.readString(configPath);
            check(!CodexConfig.tokenProActive(configPath), "official route is not reported as TokenPro active");
            check(!restored.contains("tokenpro.work") && !restored.contains("model_provider = \"openai\"")
                && !restored.contains("fixture-route-key"), "actual official restore removes TokenPro endpoint and credential");
            check(restored.contains("sandbox_private_desktop = true") && restored.contains("trust_level = \"trusted\""), "official restore retains Windows and project state");
            check(Files.readString(auth).equals(OFFICIAL_AUTH), "official OAuth login is restored from TokenPro's private copy");
            check(Files.readString(configPath.resolveSibling("models_cache.json")).contains("1970-01-01T00:00:00Z"),
                "official restore marks the model cache stale for Codex to refresh");
            config.deleteForOfficial();
            check(Files.readString(configPath).equals(restored), "repeated official restore leaves config stable");
            String foreignConfig = "openai_base_url='https://relay.example/v1'\nmodel_provider='custom'\n"
                + "approval_policy='on-request'\n[model_providers.custom]\nbase_url='https://relay.example/v1'\nexperimental_bearer_token='foreign-key'\n";
            Files.writeString(configPath, foreignConfig);
            Path foreignCache = configPath.getParent().resolve("models_cache.json");
            Path foreignCatalog = configPath.getParent().resolve("teamorouter-native-model-catalog.json");
            Path referencedForeignCatalog = configPath.getParent().resolve("otherrelay-catalog.json");
            Path foreignBackupCatalog = configPath.getParent().resolve("backups_state/teamorouter-fast/model-catalog.json");
            Path foreignHistoryState = configPath.getParent().resolve("backups_state/teamorouter-history-sync/state.json");
            Path legacyImageSkill = configPath.getParent().resolve("skills/teamorouter-imagegen/SKILL.md");
            Path legacyImageState = configPath.getParent().resolve("backups_state/teamorouter-imagegen/state.json");
            Path proxyConfig = configPath.getParent().resolve("teamorouter-http-proxy.json");
            Path proxyLog = configPath.getParent().resolve("teamorouter-http-proxy.log");
            Path proxyServiceLog = configPath.getParent().resolve("teamorouter-http-proxy-service.log");
            Path relayAuth = configPath.getParent().resolve("teamorouter-chatgpt-auth.json");
            Path relayConfigBackup = configPath.getParent().resolve("config.toml.teamorouter-backup-fixture");
            Path relayAuthBackup = configPath.getParent().resolve("auth.json.teamorouter-backup-fixture");
            Path genericRelayFile = configPath.getParent().resolve("otherrelay-state.json");
            Path genericRouterBackup = configPath.getParent().resolve("backups_state/otherrouter-backup/state.json");
            Files.writeString(foreignCache, "{\"models\":[{\"description\":\"glm routed through TeamoRouter\"}]}" );
            Files.writeString(foreignCatalog, "foreign catalog");
            Files.writeString(referencedForeignCatalog, "foreign catalog");
            foreignConfig += "model_catalog_json='" + referencedForeignCatalog + "'\n";
            Files.writeString(configPath, foreignConfig);
            Files.createDirectories(foreignBackupCatalog.getParent()); Files.writeString(foreignBackupCatalog, "foreign backup catalog");
            Files.createDirectories(foreignHistoryState.getParent()); Files.writeString(foreignHistoryState, "foreign history state");
            Files.createDirectories(legacyImageSkill.getParent()); Files.writeString(legacyImageSkill, "legacy image skill");
            Files.createDirectories(legacyImageState.getParent()); Files.writeString(legacyImageState, "legacy image state");
            for (Path artifact : List.of(proxyConfig, proxyLog, proxyServiceLog, relayAuth, relayConfigBackup, relayAuthBackup)) Files.writeString(artifact, "foreign state");
            Files.writeString(genericRelayFile, "foreign state");
            Files.createDirectories(genericRouterBackup.getParent()); Files.writeString(genericRouterBackup, "foreign state");
            store.write("codex-foreign-relay-backup.toml", "stale-backup");
            CodexConfig.ForeignRelayPlan plan = CodexConfig.foreignRelayPlan(configPath).orElseThrow();
            config.apply("https://tokenpro.work/v1", List.of(new PricedModel("gpt-5.4", "openai", "fixture", 1)),
                "fixture-route-key", "fixture@example.test", plan);
            String replaced = Files.readString(configPath);
            check(!replaced.contains("relay.example") && !replaced.contains("foreign-key") && replaced.contains("tokenpro.work"),
                "actual apply directly replaces a detected foreign relay");
            check(store.read("codex-foreign-relay-backup.toml").isEmpty()
                && store.read("codex-last-switch-config.toml").isEmpty(),
                "direct foreign relay removal retains no route or credential backup");
            check(Files.exists(foreignCache) && Files.exists(foreignCatalog) && Files.exists(referencedForeignCatalog) && Files.exists(foreignBackupCatalog),
                "switching does not delete another channel's cache or catalogs");
            check(Files.exists(foreignHistoryState) && List.of(proxyConfig, proxyLog, proxyServiceLog, relayAuth, relayConfigBackup, relayAuthBackup).stream().allMatch(Files::exists),
                "switching preserves another channel's proxy, auth, history state, and backups");
            check(Files.exists(genericRelayFile) && Files.exists(genericRouterBackup),
                "generic router and relay named artifacts are preserved");
            check(!Files.exists(legacyImageSkill)
                && Files.exists(legacyImageSkill.resolveSibling("SKILL.md.disabled-by-tokenpro"))
                && Files.exists(legacyImageState),
                "foreign image skill is hidden from Codex while its files and state remain recoverable");
            config.deleteForOfficial();
            String officialAfterForeign = Files.readString(configPath);
            check(!officialAfterForeign.contains("relay.example") && !officialAfterForeign.contains("tokenpro.work")
                && officialAfterForeign.contains("approval_policy='on-request'"),
                "switching official after cleanup never restores the foreign relay");
            Files.writeString(configPath, foreignConfig);
            CodexConfig.ForeignRelayPlan stale = CodexConfig.foreignRelayPlan(configPath).orElseThrow();
            String externallyChanged = foreignConfig + "# changed by another process\n";
            Files.writeString(configPath, externallyChanged);
            try {
                config.apply("https://tokenpro.work/v1", List.of(new PricedModel("gpt-5.4", "openai", "fixture", 1)),
                    "fixture-route-key", "fixture@example.test", stale);
                throw new AssertionError("stale cleanup plan accepted");
            } catch (IllegalStateException expected) { }
            check(Files.readString(configPath).equals(externallyChanged), "stale cleanup plan cannot overwrite a newer Codex config");
            return 20;
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
}

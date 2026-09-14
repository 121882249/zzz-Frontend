package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class CodexSwitchConfigTest {
    private static final String ROUTE = "# >>> TokenPro managed >>>\nmodel = \"fixture-model\"\nmodel_provider = \"custom\"\n"
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
        String oldWithWindowsInside = ROUTE.replace("# <<< TokenPro managed <<<", "[windows]\nsandbox='elevated'\n# <<< TokenPro managed <<<");
        check(CodexSwitchConfig.clean(oldWithWindowsInside).equals("[windows]\nsandbox='elevated'\n"), "user Windows settings inside legacy markers survive migration"); passed++;
        check(CodexSwitchConfig.clean("model_provider='custom'\n[model_providers.custom]\nbase_url='https://tokenpro.work/v1'\nexperimental_bearer_token='old-key'\n[windows]\nsandbox='elevated'\n").equals("[windows]\nsandbox='elevated'\n"), "unmarked legacy TokenPro route removed"); passed++;
        String foreign = "[model_providers.custom]\nbase_url='https://other.example/v1'\n";
        check(CodexSwitchConfig.clean(foreign).equals(foreign), "unrelated provider preserved during restore"); passed++;
        try { CodexSwitchConfig.merge(foreign, ROUTE); throw new AssertionError("foreign provider overwritten"); }
        catch (IllegalStateException expected) { passed++; }
        for (String invalid : List.of("# >>> TokenPro managed >>>\nmodel='x'\n", "note = \"\"\"unfinished\n", "args = [1,2\n")) {
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
            Files.writeString(auth, "fixture-official-login");
            CodexConfig config = new CodexConfig(store, configPath);
            config.apply("https://tokenpro.work/v1", List.of(new PricedModel("gpt-5.4", "openai", "fixture", 1)), "fixture-route-key", "fixture@example.test");
            String applied = Files.readString(configPath);
            check(applied.contains("trust_level = \"trusted\"") && applied.contains("args = [\n  \"--name\""), "actual apply preserves project and MCP settings");
            check(applied.contains("sandbox_mode = \"workspace-write\"") && applied.contains("approval_policy = \"on-request\""), "actual apply preserves permission policy");
            check(applied.contains("sandbox = \"" + (Platform.OS_KIND == Platform.OS.WINDOWS ? "unelevated" : "elevated") + "\""), "no-admin selection is Windows-only");
            check(store.read("codex-last-switch-config.toml").orElseThrow().equals(SETTINGS), "pre-switch config privately backed up");
            config.deleteForOfficial();
            String restored = Files.readString(configPath);
            check(!restored.contains("fixture-route-key") && !restored.contains("model_provider = \"custom\""), "actual official restore removes TokenPro authentication and route");
            check(restored.contains("sandbox_private_desktop = true") && restored.contains("trust_level = \"trusted\""), "official restore retains Windows and project state");
            check(Files.readString(auth).equals("fixture-official-login"), "official login file remains untouched");
            config.deleteForOfficial();
            check(Files.readString(configPath).equals(restored), "repeated official restore leaves config stable");
            return 8;
        } finally {
            try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
    }
    private static void check(boolean ok, String detail) { if (!ok) throw new AssertionError(detail); }
}

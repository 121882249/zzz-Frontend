package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class ReleaseRegressionTest {
    static int run() throws Exception {
        int passed=0;
        check(Main.claudeTokenRequest(new String[0], true), "implicit desktop credential helper"); passed++;
        for(String flag : List.of("--claude-bridge","--claude-cli-bridge","--codex-cli","--claude-cli","--prepare-codex-cli","--prepare-claude-cli","--self-test")) {
            check(!Main.claudeTokenRequest(new String[]{flag}, true), "explicit mode overrides helper context: "+flag); passed++;
        }
        Map<String,Object> model = Map.of("supported_reasoning_levels",
            List.of("low","medium","high","xhigh","max","ultra").stream().map(e->Map.of("effort",e)).toList(),
            "default_reasoning_level","medium","service_tiers",List.of(Map.of("id","priority"),Map.of("id","ultrafast")));
        String kept = CodexPreferences.retainedLines("model_reasoning_effort = 'ultra' # keep\nservice_tier = \"ultrafast\"\n",model);
        check(kept.contains("\"ultra\"") && kept.contains("\"ultrafast\""), "reapply retains supported effort and lightning tier"); passed++;
        check(CodexPreferences.retainedLines("",model).contains("\"medium\""), "new config uses model default"); passed++;
        check(!CodexPreferences.retainedLines("",model).contains("service_tier"), "new config never enables paid acceleration"); passed++;
        check(CodexPreferences.retainedLines("service_tier = \"fast\"",model).contains("\"priority\""), "legacy fast preference retained"); passed++;
        check(!CodexPreferences.retainedLines("service_tier = \"unknown\"",model).contains("service_tier"), "unsupported tier removed"); passed++;
        check(CodexPreferences.rootString("[provider]\nmodel_reasoning_effort = 'ultra'","model_reasoning_effort").isEmpty(), "nested preference is not a root override"); passed++;
        check(CodexPreferences.retainedLines(kept,Map.of()).isEmpty(), "image/non-reasoning model never inherits unsupported preferences"); passed++;
        List<String> events=new ArrayList<>();
        List<BridgeLifecycle.Bridge> bridges=List.of(
            new BridgeLifecycle.Bridge("desktop",()->events.add("stop desktop"),()->events.add("resume desktop")),
            new BridgeLifecycle.Bridge("cli",()->events.add("stop cli"),()->events.add("resume cli")));
        BridgeLifecycle.update(bridges,()->events.add("install"));
        check(events.equals(List.of("stop desktop","stop cli","install")), "successful update pauses all bridges before installing"); passed++;
        events.clear();
        try { BridgeLifecycle.update(bridges,()->{throw new java.io.IOException("test install failed");}); }
        catch(java.io.IOException expected) {}
        check(events.equals(List.of("stop desktop","stop cli","resume desktop","resume cli")), "failed update restores every previously active bridge"); passed++;
        events.clear();
        try { BridgeLifecycle.update(List.of(
            new BridgeLifecycle.Bridge("broken",()->{throw new java.io.IOException("stop failed");},()->{throw new java.io.IOException("resume failed");}),
            bridges.get(1)),()->events.add("install")); throw new AssertionError("stop failure swallowed"); }
        catch(java.io.IOException expected) { check(expected.getSuppressed().length==1, "recovery errors retained"); passed++; }
        check(events.equals(List.of("resume cli")), "one bridge failure never skips recovery of another"); passed++;
        String posix=Platform.posixCliCommand("/Users/test/My Tools/claude",List.of("it's safe"),Map.of("CLAUDE_CONFIG_DIR","/Users/test/CLI Home"));
        check(posix.contains("'/Users/test/My Tools/claude'") && posix.contains("'it'\\''s safe'"),
            "macOS/Linux launch resolved absolute path with shell escaping"); passed++;
        check(posix.contains("-u ANTHROPIC_API_KEY"), "inherited desktop credential is cleared in CLI"); passed++;
        String win=CliLauncher.script(Platform.OS.WINDOWS,List.of("C:/Program Files/TokenPro/TokenPro.exe","--codex-cli"));
        check(win.contains("DisableDelayedExpansion") && win.contains("%*") && win.contains("--codex-cli"), "Windows standalone entry preserves arguments"); passed++;
        String unix=CliLauncher.script(Platform.OS.MAC,List.of("/Applications/TokenPro.app/Contents/MacOS/TokenPro","--claude-cli"));
        check(unix.contains("exec '") && unix.contains("\"$@\""), "Unix standalone entry preserves arguments"); passed++;
        String nativeWin=CliLauncher.launchScript(Platform.OS.WINDOWS,List.of("C:/App/TokenPro.exe","--prepare-claude-cli"),
            "C:/CLI/claude.exe",List.of("--settings","C:/CLI Home/settings.json"),Map.of("CLAUDE_CONFIG_DIR","C:/CLI Home"));
        check(nativeWin.contains("start \"\" /b /wait") && nativeWin.contains("\"C:/CLI/claude.exe\" \"--settings\""),
            "Windows GUI bootstrap waits, then terminal directly runs native CLI"); passed++;
        check(nativeWin.contains("set \"CLAUDE_CONFIG_DIR=C:/CLI Home\""), "native CLI receives isolated environment"); passed++;
        Path root=Files.createTempDirectory("tokenpro-release-regression-");
        try {
            SecureStore store=new SecureStore(root);
            Map<String,String> codex=CliLauncher.environment(store,"codex"),claude=CliLauncher.environment(store,"claude");
            check(!codex.get("CODEX_HOME").equals(claude.get("CLAUDE_CONFIG_DIR")), "terminal entry profiles are separate"); passed++;
            check(CliLauncher.arguments(store,"claude",List.of("--version")).getLast().equals("--version"), "terminal arguments forwarded"); passed++;
            Path file=root.resolve("claude"); Files.writeString(file,"fixture");
            check(Platform.sameExecutable(file,file.toString()), "process identity compares actual files"); passed++;
        } finally { try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);} }
        return passed;
    }
    private static void check(boolean ok,String label) { if(!ok)throw new AssertionError(label); }
}

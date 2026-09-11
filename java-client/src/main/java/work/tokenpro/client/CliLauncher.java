package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

/** Explicit terminal entry points; never shadows the user's global codex/claude. */
final class CliLauncher {
    private CliLauncher() {}
    static Path install(SecureStore root, String client) throws Exception {
        validate(client);
        Path directory = root.root().resolve("bin");
        Files.createDirectories(directory);
        Path launcher = directory.resolve("tokenpro-" + client + (Platform.OS_KIND == Platform.OS.WINDOWS ? ".cmd" : ""));
        Files.writeString(launcher, launchScript(Platform.OS_KIND, RuntimeCommand.withArgs("--prepare-" + client + "-cli"),
            Platform.resolveCli(client).toString(), arguments(root,client,List.of()), environment(root,client)));
        Platform.privateFile(launcher);
        if(Platform.OS_KIND != Platform.OS.WINDOWS) launcher.toFile().setExecutable(true, true);
        return launcher.toAbsolutePath();
    }
    static String script(Platform.OS os, List<String> command) {
        if(os == Platform.OS.WINDOWS) {
            String invocation = command.stream().map(value -> "\"" + value.replace("%", "%%") + "\"")
                .collect(java.util.stream.Collectors.joining(" "));
            return "@echo off\r\nsetlocal DisableDelayedExpansion\r\n" + invocation + " %*\r\nexit /b %errorlevel%\r\n";
        }
        String invocation = command.stream().map(value -> "'" + value.replace("'", "'\\''") + "'")
            .collect(java.util.stream.Collectors.joining(" "));
        return "#!/bin/sh\nexec " + invocation + " \"$@\"\n";
    }
    static String launchScript(Platform.OS os, List<String> bootstrap, String executable,
                               List<String> arguments, Map<String,String> environment) {
        if(os == Platform.OS.WINDOWS) {
            String init=bootstrap.stream().map(CliLauncher::batchQuote).collect(java.util.stream.Collectors.joining(" "));
            StringBuilder result=new StringBuilder("@echo off\r\nsetlocal DisableDelayedExpansion\r\n");
            result.append("start \"\" /b /wait ").append(init).append("\r\nif errorlevel 1 exit /b %errorlevel%\r\n");
            if(environment.containsKey("CLAUDE_CONFIG_DIR")) {
                for(String name : List.of("ANTHROPIC_API_KEY","ANTHROPIC_AUTH_TOKEN","ANTHROPIC_BASE_URL","ANTHROPIC_MODEL",
                    "CLAUDE_CODE_USE_BEDROCK","CLAUDE_CODE_USE_VERTEX","CLAUDE_CODE_USE_FOUNDRY",
                    "ANTHROPIC_DEFAULT_OPUS_MODEL","ANTHROPIC_DEFAULT_SONNET_MODEL","ANTHROPIC_DEFAULT_HAIKU_MODEL"))
                    result.append("set \"").append(name).append("=\"\r\n");
            }
            result.append("set \"CLAUDE_HELPER_CONTEXT=\"\r\n");
            for(var entry : environment.entrySet()) result.append("set \"").append(entry.getKey()).append('=')
                .append(entry.getValue().replace("%","%%")).append("\"\r\n");
            result.append(batchQuote(executable));
            for(String argument:arguments)result.append(' ').append(batchQuote(argument));
            result.append(" %*\r\nexit /b %errorlevel%\r\n");
            return result.toString();
        }
        String init=bootstrap.stream().map(value->"'" + value.replace("'","'\\''") + "'")
            .collect(java.util.stream.Collectors.joining(" "));
        return "#!/bin/sh\n" + init + " || exit $?\nexec "
            + Platform.posixCliCommand(executable,arguments,environment) + " \"$@\"\n";
    }
    private static String batchQuote(String value) { return "\"" + value.replace("%","%%") + "\""; }
    static Map<String,String> environment(SecureStore root, String client) throws Exception {
        validate(client);
        Path home = root.cli(client).root().resolve("home").toAbsolutePath();
        Files.createDirectories(home);
        return Map.of(client.equals("codex") ? "CODEX_HOME" : "CLAUDE_CONFIG_DIR", home.toString());
    }
    static List<String> arguments(SecureStore root, String client, List<String> extra) throws Exception {
        validate(client);
        List<String> result = new ArrayList<>();
        if(client.equals("claude")) result.addAll(List.of("--settings", root.cli(client).root().resolve(ClaudeCliConfig.FILE).toAbsolutePath().toString()));
        result.addAll(extra);
        return result;
    }
    static int run(SecureStore root, String client, List<String> extra) throws Exception {
        prepare(root, client);
        return Platform.runCli(client, arguments(root, client, extra), environment(root, client));
    }
    static void prepare(SecureStore root, String client) throws Exception {
        validate(client);
        SecureStore store = root.cli(client);
        if(client.equals("codex")) {
            if(!Files.isRegularFile(store.root().resolve("home/config.toml"))) throw new IllegalStateException("请先在 TokenPro 的 Codex 命令行卡片选择模型");
            CodexImageBridge.resumeIfConfigured(store);
        } else {
            if(store.read(ClaudeCliConfig.FILE).isEmpty()) throw new IllegalStateException("请先在 TokenPro 的 Claude 命令行卡片选择模型");
            ClaudeBridgeManager.ensureRunning(store);
        }
    }
    private static void validate(String client) {
        if(!Set.of("codex","claude").contains(client)) throw new IllegalArgumentException("未知命令行工具");
    }
}

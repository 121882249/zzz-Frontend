package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

final class RuntimeCommand {
    private RuntimeCommand() {}

    static List<String> withArgs(String... args) throws Exception {
        String running = ProcessHandle.current().info().command().orElseThrow(() -> new IllegalStateException("无法确定 TokenPro 启动程序"));
        String runningName = Path.of(running).getFileName().toString().toLowerCase(Locale.ROOT);
        List<String> command = new ArrayList<>();
        if (!runningName.equals("java") && !runningName.equals("java.exe") && !runningName.equals("javaw.exe")) {
            command.add(running);
        } else {
            Path source = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath();
            if (!source.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) throw new IllegalStateException("开发模式下请从 TokenPro.jar 启动");
            command.add(Path.of(System.getProperty("java.home"), "bin", Platform.OS_KIND == Platform.OS.WINDOWS ? "java.exe" : "java").toString());
            command.add("-jar"); command.add(source.toString());
        }
        command.addAll(List.of(args));
        return command;
    }

    static String helperExecutable(SecureStore store) throws Exception {
        List<String> base = withArgs();
        String executable = base.getFirst();
        String lower = Path.of(executable).getFileName().toString().toLowerCase(Locale.ROOT);
        if (base.size() == 1 && !lower.equals("java") && !lower.equals("java.exe") && !lower.equals("javaw.exe")) return executable;
        Path helper = store.root().resolve(Platform.OS_KIND == Platform.OS.WINDOWS ? "claude-token.cmd" : "claude-token");
        String command = quote(base) + " --claude-token";
        String contents = Platform.OS_KIND == Platform.OS.WINDOWS ? "@echo off\r\n" + command + "\r\n" : "#!/bin/sh\nexec " + command + "\n";
        Files.writeString(helper, contents, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        Platform.privateFile(helper);
        if (Platform.OS_KIND != Platform.OS.WINDOWS) helper.toFile().setExecutable(true, true);
        return helper.toString();
    }

    private static String quote(List<String> command) {
        StringJoiner out = new StringJoiner(" ");
        for (String item : command) out.add(Platform.OS_KIND == Platform.OS.WINDOWS ? "\"" + item.replace("\"", "\"\"") + "\"" : "'" + item.replace("'", "'\\''") + "'");
        return out.toString();
    }
}

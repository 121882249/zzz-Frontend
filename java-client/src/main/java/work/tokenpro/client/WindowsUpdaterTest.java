package work.tokenpro.client;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

final class WindowsUpdaterTest {
    static int run() throws Exception {
        int passed = 0;
        // macOS /var is a system alias; fixture paths use its resolved location.
        Path root = Files.createTempDirectory("tokenpro-windows-update-test-").toRealPath();
        try {
            Path source = root.resolve("模拟磁盘根目录");
            image(source, "old-core");
            Files.writeString(source.resolve("无关个人文件.txt"), "leave alone");
            Path destination = root.resolve("当前用户/Programs/TokenPro");
            WindowsUpdater.migrateImage(source, destination);
            check(Files.readString(destination.resolve("app/TokenPro.jar")).equals("old-core"), "migrated core"); passed++;
            check(Files.isRegularFile(destination.resolve("runtime/release")), "runtime reused without downloads"); passed++;
            check(!Files.exists(destination.resolve("无关个人文件.txt")), "legacy drive root not recursively copied"); passed++;
            check(Files.readString(source.resolve("无关个人文件.txt")).equals("leave alone"), "source preserved"); passed++;
            WindowsUpdater.migrateImage(source, destination); passed++;
            Files.writeString(destination.resolve("app/TokenPro.jar"), "different-existing-core");
            boolean rejected = false;
            try { WindowsUpdater.migrateImage(source, destination); } catch (java.io.IOException expected) { rejected = true; }
            check(rejected && Files.readString(destination.resolve("app/TokenPro.jar")).equals("different-existing-core"), "existing different installation preserved"); passed++;
            List<String> command = WindowsUpdater.command(root.resolve("中文 ' 引号/job.json"));
            check(command.contains("-NonInteractive") && command.contains("Hidden") && !String.join(" ", command).contains("ExecutionPolicy"), "no policy changes or elevation"); passed++;
            check(command.getLast().contains("中文 '' 引号"), "job path quoted"); passed++;
            check(!WindowsUpdater.script().contains("RunAs") && !WindowsUpdater.script().contains("taskkill") && !WindowsUpdater.script().contains("timeout /T"), "no elevation, process killing or stdin-dependent waits"); passed++;
            check(WindowsUpdater.script().contains("[IO.File]::Replace") && WindowsUpdater.script().contains("baseSha256") && WindowsUpdater.script().contains("rollback-"), "atomic replacement with verification and backup"); passed++;
            check(WindowsUpdater.script().contains("'TokenPro.lnk'") && !WindowsUpdater.script().contains("TokenPro（用户版）") && WindowsUpdater.script().contains("$link.TargetPath -ne $job.previousLauncher"), "plain TokenPro shortcut name and foreign shortcut protection"); passed++;
            if (Platform.OS_KIND == Platform.OS.WINDOWS) {
                passed += execute(root.resolve("成功更新 中文 ' 路径"), false, false, false);
                passed += execute(root.resolve("校验失败保留原文件"), true, false, false);
                passed += execute(root.resolve("更新已取消"), false, true, false);
                passed += execute(root.resolve("启动失败回退"), false, false, true);
                passed += waitForParent(root.resolve("等待父进程退出"));
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return passed;
    }

    private static int execute(Path root, boolean badHash, boolean cancel, boolean launchFailure) throws Exception {
        Path install = root.resolve("TokenPro");
        image(install, "old-core");
        Path source = root.resolve("merged.jar"); Files.writeString(source, "new-core");
        Path jobs = Files.createDirectory(root.resolve("job"));
        Map<String,Object> job = WindowsUpdater.job(source, install.resolve("app/TokenPro.jar"), install.resolve("TokenPro.exe"), 0, jobs);
        job.put("launch", false); job.put("showErrors", false); job.put("createShortcuts", false);
        if (launchFailure) { Files.delete(install.resolve("TokenPro.exe")); job.put("launch", true); }
        if (badHash) job.put("sourceSha256", "0".repeat(64));
        if (cancel) Files.writeString(jobs.resolve("cancel"), "cancel");
        Path jobFile = jobs.resolve("job.json");
        Files.writeString(jobFile, Json.stringify(job), StandardCharsets.UTF_8);
        Process process = new ProcessBuilder(WindowsUpdater.command(jobFile)).redirectErrorStream(true).redirectOutput(jobs.resolve("helper.log").toFile()).start();
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            process.destroy();
            throw new AssertionError("Windows update fixture timeout");
        }
        Map<String,Object> result = Json.object(Json.parse(Files.readString(jobs.resolve("result.json"))));
        boolean failure = badHash || cancel || launchFailure;
        check(process.exitValue() == (failure ? 1 : 0), "helper exit: " + result);
        check(Objects.equals(result.get("status"), failure ? "failed" : "complete"), "actual helper receipt");
        check(Files.readString(install.resolve("app/TokenPro.jar")).equals(failure ? "old-core" : "new-core"), "actual file replacement / preservation");
        if (!failure) check(Files.readString(Path.of(result.get("backup").toString())).equals("old-core"), "actual rollback backup");
        if (launchFailure) check(Boolean.TRUE.equals(result.get("restored")), "rollback receipt");
        return failure ? 3 : 4;
    }

    private static int waitForParent(Path root) throws Exception {
        Path install = root.resolve("TokenPro"); image(install, "old-core");
        Path source = root.resolve("merged.jar"); Files.writeString(source, "new-core");
        Path jobs = Files.createDirectory(root.resolve("job"));
        String powershell = WindowsUpdater.command(jobs.resolve("job.json")).getFirst();
        Process parent = new ProcessBuilder(powershell, "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", "[Threading.Thread]::Sleep(6000)").start();
        Process helper = null;
        try {
            Map<String,Object> job = WindowsUpdater.job(source, install.resolve("app/TokenPro.jar"), install.resolve("TokenPro.exe"), parent.pid(), jobs);
            job.put("launch", false); job.put("showErrors", false); job.put("createShortcuts", false);
            Path jobFile = jobs.resolve("job.json"); Files.writeString(jobFile, Json.stringify(job), StandardCharsets.UTF_8);
            helper = new ProcessBuilder(WindowsUpdater.command(jobFile)).redirectErrorStream(true).redirectOutput(jobs.resolve("helper.log").toFile()).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(jobs.resolve("ready.json")) && helper.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
            check(Files.exists(jobs.resolve("ready.json")) && parent.isAlive(), "readiness acknowledged before parent exits");
            check(Files.readString(install.resolve("app/TokenPro.jar")).equals("old-core"), "live parent core untouched");
            check(parent.waitFor(10, TimeUnit.SECONDS) && helper.waitFor(15, TimeUnit.SECONDS) && helper.exitValue() == 0, "parent exited naturally; update completed");
            check(Files.readString(install.resolve("app/TokenPro.jar")).equals("new-core"), "replacement occurs after parent exit");
            return 4;
        } finally {
            if (parent.isAlive()) parent.destroy();
            if (helper != null && helper.isAlive()) helper.destroy();
        }
    }

    private static void image(Path directory, String core) throws Exception {
        Files.createDirectories(directory.resolve("app")); Files.createDirectories(directory.resolve("runtime"));
        Files.writeString(directory.resolve("TokenPro.exe"), "fixture-do-not-execute");
        Files.writeString(directory.resolve("app/TokenPro.jar"), core);
        Files.writeString(directory.resolve("app/TokenPro.cfg"), "fixture");
        Files.writeString(directory.resolve("runtime/release"), "fixture");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

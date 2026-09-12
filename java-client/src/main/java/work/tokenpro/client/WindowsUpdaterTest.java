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
            WindowsUpdater.validateImage(source);
            check(WindowsUpdater.canReplace(source.resolve("app/TokenPro.jar")), "writable custom location does not require elevation"); passed++;
            check(Files.readString(source.resolve("无关个人文件.txt")).equals("leave alone"), "permission probe preserves unrelated files"); passed++;
            List<String> command = WindowsUpdater.command(root.resolve("中文 ' 引号/job.json"));
            check(command.contains("-NonInteractive") && command.contains("Hidden") && !String.join(" ", command).contains("ExecutionPolicy"), "no policy changes or elevation"); passed++;
            check(command.getLast().contains("中文 '' 引号"), "job path quoted"); passed++;
            check(!WindowsUpdater.script().contains("RunAs") && !WindowsUpdater.script().contains("taskkill") && !WindowsUpdater.script().contains("timeout /T"), "no elevation, process killing or stdin-dependent waits"); passed++;
            check(WindowsUpdater.script().contains("[IO.File]::Replace") && WindowsUpdater.script().contains("baseSha256") && WindowsUpdater.script().contains("rollback-"), "atomic replacement with verification and backup"); passed++;
            check(!WindowsUpdater.script().contains("CreateShortcut") && !WindowsUpdater.script().contains("migrated"), "in-place update neither migrates nor changes shortcuts"); passed++;
            check(Arrays.stream(WindowsUpdater.class.getDeclaredMethods()).noneMatch(m -> m.getName().equals("migrateImage")), "legacy migration implementation removed"); passed++;
            check(Arrays.stream(Updater.class.getDeclaredMethods()).noneMatch(m -> m.getName().equals("installWindowsIncremental") || m.getName().equals("installWindows")), "legacy Windows batch implementations removed"); passed++;
            Map<String,Object> example = WindowsUpdater.job(source.resolve("app/TokenPro.jar"), source.resolve("app/TokenPro.jar"), source.resolve("TokenPro.exe"), 0, root);
            String broker = WindowsUpdater.elevationCommand(root.resolve("job.json"), example).getLast();
            check(broker.contains("-Verb RunAs") && !broker.contains("ExecutionPolicy") && !broker.contains("taskkill"), "UAC uses Windows consent, no security changes or process killing"); passed++;
            java.util.regex.Matcher payload = java.util.regex.Pattern.compile("'Hidden','-EncodedCommand','([^']+)'").matcher(broker);
            check(payload.find(), "embedded elevated command"); passed++;
            String helper = new String(Base64.getDecoder().decode(payload.group(1)), StandardCharsets.UTF_16LE);
            check(!helper.contains("Get-Content -LiteralPath $JobFile") && helper.contains("FromBase64String"), "elevated job snapshot cannot be retargeted by editing job.json"); passed++;
            check(broker.contains("NativeErrorCode -eq 1223") && broker.contains("TokenPro 保持打开"), "UAC cancellation explained"); passed++;
            check(broker.indexOf("Start-Process -FilePath " + WindowsUpdater.quote(example.get("launcher").toString())) > broker.indexOf("$elevated.WaitForExit()"), "original-user broker restarts only after helper finishes"); passed++;
            boolean tooLong = false;
            try { WindowsUpdater.elevationCommand(root.resolve("x".repeat(12000)).resolve("job.json"), example); } catch(java.io.IOException expected) { tooLong = true; }
            check(tooLong, "oversized elevated command rejected without execution"); passed++;
            if (Platform.OS_KIND == Platform.OS.WINDOWS) {
                passed += execute(root.resolve("成功更新 中文 ' 路径"), false, false, false);
                passed += execute(root.resolve("校验失败保留原文件"), true, false, false);
                passed += execute(root.resolve("更新已取消"), false, true, false);
                passed += execute(root.resolve("启动失败回退"), false, false, true);
                passed += waitForParent(root.resolve("等待父进程退出"));
                passed += brokerFixture(root.resolve("broker-success"), false, false);
                passed += brokerFixture(root.resolve("broker-cancel"), true, false);
                passed += brokerFixture(root.resolve("broker-corrupt"), false, true);
                passed += brokerFixture(root.resolve("broker-wrapped-cancel"), true, false, true);
            }
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        return passed;
    }

    private static int brokerFixture(Path root, boolean cancel, boolean corrupt) throws Exception {
        return brokerFixture(root, cancel, corrupt, false);
    }

    private static int brokerFixture(Path root, boolean cancel, boolean corrupt, boolean wrapped) throws Exception {
        Path install = root.resolve("User Chosen Location/TokenPro"); image(install, "old-core");
        Path source = root.resolve("merged.jar"); Files.writeString(source, "new-core");
        Path jobs = Files.createDirectory(root.resolve("job"));
        Path jobFile = jobs.resolve("job.json");
        Map<String,Object> job = WindowsUpdater.job(source, install.resolve("app/TokenPro.jar"), install.resolve("TokenPro.exe"), 0, jobs);
        job.put("launch", false);
        String broker = WindowsUpdater.elevationBrokerScript(jobFile, job);
        // Mock ONLY the UAC transport on isolated fixtures. Never display real UAC in self-test.
        if(cancel) {
            String error = wrapped ? "[InvalidOperationException]::new('由于出现以下错误，无法运行此命令: The operation was canceled by the user。')" : "[ComponentModel.Win32Exception]::new(1223)";
            broker = broker.replace("$elevated=Start-Process", "throw " + error + "\n$elevated=Start-Process");
        }
        broker = broker.replace("-Verb RunAs ", "");
        // No job file is required: the helper must use the immutable snapshot in its command.
        Files.writeString(jobFile, "invalid, potentially modified job file");
        if(corrupt) Files.writeString(source, "tampered-core");
        List<String> command = new ArrayList<>(WindowsUpdater.command(jobFile)); command.set(command.size()-1, broker);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(jobs.resolve("broker.log").toFile()).start();
        if(!process.waitFor(30, TimeUnit.SECONDS)) { process.destroy(); throw new AssertionError("Broker fixture timed out"); }
        check(Files.isRegularFile(jobs.resolve("result.json")), "Broker receipt missing: " + new String(Files.readAllBytes(jobs.resolve("broker.log")), StandardCharsets.UTF_8));
        Map<String,Object> ready = Json.object(Json.parse(Files.readString(jobs.resolve("ready.json"))));
        Map<String,Object> result = Json.object(Json.parse(Files.readString(jobs.resolve("result.json"))));
        boolean failure = cancel || corrupt;
        check(process.exitValue() == (failure ? 1 : 0), "broker exit: " + result);
        check(Boolean.valueOf(!failure).equals(ready.get("ready")), "failed authorization never tells the app to exit");
        check(Files.readString(install.resolve("app/TokenPro.jar")).equals(failure ? "old-core" : "new-core"), "broker updates exact user-selected path or preserves original");
        check(Objects.equals(result.get("status"), failure ? "failed" : "complete"), "broker result receipt");
        if(cancel) check(result.get("message").toString().contains("管理员授权未完成或已取消") && !result.containsKey("administrator"), "friendly UAC cancellation does not claim successful elevation");
        return 4;
    }

    private static int execute(Path root, boolean badHash, boolean cancel, boolean launchFailure) throws Exception {
        Path install = root.resolve("TokenPro");
        image(install, "old-core");
        Path source = root.resolve("merged.jar"); Files.writeString(source, "new-core");
        Path jobs = Files.createDirectory(root.resolve("job"));
        Map<String,Object> job = WindowsUpdater.job(source, install.resolve("app/TokenPro.jar"), install.resolve("TokenPro.exe"), 0, jobs);
        job.put("launch", false); job.put("showErrors", false);
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
        check(Files.isRegularFile(jobs.resolve("result.json")), "Helper receipt missing: " + Files.readString(jobs.resolve("helper.log")));
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
            job.put("launch", false); job.put("showErrors", false);
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

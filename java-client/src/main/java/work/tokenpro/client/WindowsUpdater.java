package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Per-user Windows delta updater. Never elevates or changes installation ACLs. */
final class WindowsUpdater {
    private WindowsUpdater() {}

    static void installDelta(Path update, String version) throws Exception {
        if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw new IOException("更新版本号无效");
        String launcher = ProcessHandle.current().info().command().orElseThrow(() -> new IOException("找不到启动程序"));
        Path root = Path.of(launcher).toAbsolutePath().normalize().getParent();
        validateImage(root);
        Path target = root.resolve("app/TokenPro.jar");
        Path merged = Updater.mergeUpdate(target, update);
        install(merged, ProcessHandle.current().pid(), target, launcher, version);
        Files.deleteIfExists(update);
    }

    static void install(Path merged, long parentPid, Path currentJar, String launcher, String version) throws Exception {
        Path executable = Path.of(launcher).toAbsolutePath().normalize();
        if (!executable.getFileName().toString().equalsIgnoreCase("TokenPro.exe"))
            throw new IOException("请从 TokenPro 应用中更新，而不是开发用 Java 命令");
        Path sourceRoot = executable.getParent();
        if (!sourceRoot.resolve("app/TokenPro.jar").equals(currentJar.toAbsolutePath().normalize()))
            throw new IOException("程序与核心文件路径不一致，已停止更新");
        validateImage(sourceRoot);
        Path destination = sourceRoot;
        boolean migrated = !canReplace(currentJar);
        if (migrated) {
            String local = System.getenv("LOCALAPPDATA");
            if (local == null || local.isBlank()) throw new IOException("找不到当前用户的应用目录");
            destination = Path.of(local, "Programs", "TokenPro").toAbsolutePath().normalize();
            migrateImage(sourceRoot, destination);
        }
        Path target = destination.resolve("app/TokenPro.jar");
        if (!canReplace(target)) throw new IOException("当前用户目录不可写，未申请管理员权限，也未退出程序");
        Path jobRoot = Files.createTempDirectory("TokenPro-update-job-").toAbsolutePath();
        Platform.privateFile(jobRoot);
        Map<String,Object> job = job(merged, target, destination.resolve("TokenPro.exe"), parentPid, jobRoot);
        job.put("version", version);
        job.put("migrated", migrated);
        job.put("previousLauncher", executable.toString());
        job.put("result", Platform.dataDirectory().resolve("windows-update-result.json").toString());
        Path jobFile = jobRoot.resolve("job.json");
        Files.writeString(jobFile, Json.stringify(job), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Platform.privateFile(jobFile);
        Process process = new ProcessBuilder(command(jobFile))
            .redirectErrorStream(true).redirectOutput(jobRoot.resolve("helper.log").toFile()).start();
        Path ready = jobRoot.resolve("ready.json");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.isRegularFile(ready)) {
            if (!process.isAlive() || System.nanoTime() >= deadline) {
                // Cancellation is observed before any replacement if startup did not acknowledge readiness.
                Files.writeString(jobRoot.resolve("cancel"), "cancel", StandardOpenOption.CREATE);
                throw new IOException("更新准备未完成，程序保持打开。请查看：" + jobRoot.resolve("helper.log"));
            }
            Thread.sleep(50);
        }
        Map<String,Object> state = Json.object(Json.parse(Files.readString(ready)));
        if (!Boolean.TRUE.equals(state.get("ready")))
            throw new IOException("更新准备失败，程序未退出：" + state.getOrDefault("message", "请检查更新日志"));
    }

    static Map<String,Object> job(Path source, Path target, Path launcher, long parentPid, Path jobRoot) throws Exception {
        Map<String,Object> job = new LinkedHashMap<>();
        job.put("source", source.toAbsolutePath().normalize().toString());
        job.put("target", target.toAbsolutePath().normalize().toString());
        job.put("launcher", launcher.toAbsolutePath().normalize().toString());
        job.put("parentPid", parentPid);
        job.put("parentStarted", ProcessHandle.of(parentPid).flatMap(p -> p.info().startInstant()).map(i -> i.toEpochMilli()).orElse(0L));
        job.put("sourceSha256", sha256(source));
        job.put("baseSha256", sha256(target));
        job.put("version", Main.VERSION);
        job.put("launch", true);
        job.put("showErrors", true);
        job.put("createShortcuts", true);
        job.put("migrated", false);
        job.put("result", jobRoot.resolve("result.json").toAbsolutePath().toString());
        return job;
    }

    static List<String> command(Path jobFile) {
        Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        // Inline, locally generated commands; no execution-policy changes or downloaded scripts.
        return List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command", "& { " + script() + " } -JobFile '" + jobFile.toAbsolutePath().toString().replace("'", "''") + "'");
    }

    static boolean canReplace(Path target) {
        if (!Files.isRegularFile(target) || !Files.isWritable(target)) return false;
        try {
            rejectLinks(target);
            Path probe = Files.createTempFile(target.getParent(), ".tokenpro-write-check-", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException e) { return false; }
    }

    static void validateImage(Path root) throws IOException {
        rejectLinks(root);
        for (String relative : List.of("TokenPro.exe", "app/TokenPro.cfg", "app/TokenPro.jar", "runtime/release")) {
            Path file = root.resolve(relative);
            rejectLinks(file);
            if (!Files.isRegularFile(file)) throw new IOException("本地程序文件不完整，缺少：" + relative);
        }
    }

    static void migrateImage(Path source, Path destination) throws Exception {
        source = source.toAbsolutePath().normalize();
        destination = destination.toAbsolutePath().normalize();
        validateImage(source);
        rejectLinks(destination);
        if (source.equals(destination)) throw new IOException("用户目录也不可写，不能原地迁移");
        if (Files.exists(destination)) {
            validateImage(destination);
            if (!sha256(source.resolve("app/TokenPro.jar")).equals(sha256(destination.resolve("app/TokenPro.jar"))))
                throw new IOException("用户目录已存在其他版本，请从该目录的 TokenPro 检查更新；未覆盖原文件");
            return;
        }
        Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), "TokenPro-migration-");
        // D:\\ can be a legacy app root. Never recurse over that root or copy unrelated user files.
        Files.copy(source.resolve("TokenPro.exe"), staging.resolve("TokenPro.exe"));
        for (String directory : List.of("app", "runtime")) {
            Path subtree = source.resolve(directory);
            Path output = staging.resolve(directory);
            Files.walkFileTree(subtree, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                    rejectLinks(dir);
                    Files.createDirectories(output.resolve(subtree.relativize(dir)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    rejectLinks(file);
                    if (!attributes.isRegularFile()) throw new IOException("迁移目录包含非普通文件");
                    Files.copy(file, output.resolve(subtree.relativize(file)));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        validateImage(staging);
        // Destination must not exist; failed staging remains available for diagnosis, never broadly deleted.
        Files.move(staging, destination);
    }

    static void rejectLinks(Path path) throws IOException {
        for (Path part = path.toAbsolutePath().normalize(); part != null; part = part.getParent()) {
            if (Files.isSymbolicLink(part)) throw new IOException("更新路径包含符号链接，已停止");
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && Platform.OS_KIND == Platform.OS.WINDOWS) {
                if (!part.toRealPath().equals(part.toRealPath(LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("更新路径包含重解析点，已停止");
            }
        }
    }

    static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = Files.newInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int n; (n = stream.read(buffer)) >= 0;) if (n > 0) digest.update(buffer, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String script() {
        return """
            param([string]$JobFile)
            $ErrorActionPreference='Stop'
            [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
            $jobRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($JobFile))
            $ready=Join-Path $jobRoot 'ready.json'
            $cancel=Join-Path $jobRoot 'cancel'
            $lock=$null; $changed=$false; $job=$null; $next=$null; $backup=$null
            function Save-Json($path,$value) {
                [IO.File]::WriteAllText($path,($value|ConvertTo-Json -Depth 6 -Compress),[Text.UTF8Encoding]::new($false))
            }
            function Hash($path) {
                $algorithm=[Security.Cryptography.SHA256]::Create()
                $stream=[IO.File]::OpenRead($path)
                try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-','').ToLowerInvariant() }
                finally { $stream.Dispose(); $algorithm.Dispose() }
            }
            function Plain-Path($path) {
                if(-not [IO.Path]::IsPathRooted($path)){throw '更新路径必须为绝对路径'}
                $itemPath=[IO.Path]::GetFullPath($path)
                while($itemPath) {
                    if(Test-Path -LiteralPath $itemPath) {
                        if((Get-Item -LiteralPath $itemPath -Force).Attributes -band [IO.FileAttributes]::ReparsePoint){throw '更新路径包含重解析点'}
                    }
                    $itemPath=[IO.Path]::GetDirectoryName($itemPath)
                }
            }
            try {
                $job=Get-Content -LiteralPath $JobFile -Raw -Encoding UTF8|ConvertFrom-Json
                foreach($path in @($job.source,$job.target,$job.launcher,$job.result)){Plain-Path $path}
                if([IO.Path]::GetFileName($job.target) -ne 'TokenPro.jar' -or [IO.Path]::GetFileName([IO.Path]::GetDirectoryName($job.target)) -ne 'app'){throw '核心文件目标不正确'}
                $appRoot=[IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($job.target))
                if($job.launcher -ne (Join-Path $appRoot 'TokenPro.exe')){throw '启动程序与更新目标不一致'}
                if((Hash $job.source) -ne $job.sourceSha256 -or (Hash $job.target) -ne $job.baseSha256){throw '更新文件校验失败'}
                $lock=[IO.File]::Open(($job.target+'.update.lock'),[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
                $suffix=[Guid]::NewGuid().ToString('N')
                $next=$job.target+'.next-'+$suffix
                $backup=$job.target+'.rollback-'+$suffix
                [IO.File]::Copy($job.source,$next,$false)
                if((Hash $next) -ne $job.sourceSha256){throw '待替换文件校验失败'}
                if(Test-Path -LiteralPath $cancel){throw '更新准备已取消'}
                Save-Json $ready @{ready=$true}
                $deadline=[DateTime]::UtcNow.AddSeconds(90)
                while([long]$job.parentPid -gt 0) {
                    $parent=Get-Process -Id ([int]$job.parentPid) -ErrorAction SilentlyContinue
                    if(-not $parent){break}
                    $started=([DateTimeOffset]$parent.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds()
                    if([long]$job.parentStarted -gt 0 -and [Math]::Abs($started-[long]$job.parentStarted) -gt 2000){break}
                    if((Test-Path -LiteralPath $cancel) -or [DateTime]::UtcNow -gt $deadline){throw '等待程序退出超时，未强制关闭任何程序'}
                    Start-Sleep -Milliseconds 150
                }
                if(Test-Path -LiteralPath $cancel){throw '更新已取消'}
                if((Hash $job.target) -ne $job.baseSha256){throw '更新期间原文件发生变化，已停止覆盖'}
                $replaceDeadline=[DateTime]::UtcNow.AddSeconds(10)
                while($true) {
                    try { [IO.File]::Replace($next,$job.target,$backup,$false); $changed=$true; break }
                    catch [IO.IOException] { if([DateTime]::UtcNow -gt $replaceDeadline){throw}; Start-Sleep -Milliseconds 200 }
                }
                if((Hash $job.target) -ne $job.sourceSha256){throw '更新后校验失败'}
                $shortcutError=$null
                if($job.migrated -and $job.createShortcuts) {
                    try {
                        $shell=New-Object -ComObject WScript.Shell
                        $desktop=[Environment]::GetFolderPath('Desktop')
                        $programs=[Environment]::GetFolderPath('Programs')
                        foreach($folder in @($desktop,$programs)) {
                            $linkPath=Join-Path $folder 'TokenPro.lnk'
                            $link=$shell.CreateShortcut($linkPath)
                            if((Test-Path -LiteralPath $linkPath) -and
                               $link.TargetPath -ne $job.previousLauncher -and $link.TargetPath -ne $job.launcher) {
                                $shortcutError='已有同名快捷方式指向其他程序，未覆盖：'+$linkPath
                                continue
                            }
                            $link.TargetPath=$job.launcher; $link.WorkingDirectory=$appRoot
                            $link.Description='TokenPro · AI 模型接入'; $link.Save()
                        }
                    } catch { $shortcutError=$_.Exception.Message }
                }
                if($job.launch){Start-Process -FilePath $job.launcher -WorkingDirectory $appRoot}
                Save-Json $job.result @{status='complete';version=$job.version;sha256=$job.sourceSha256;launcher=$job.launcher;backup=$backup;migrated=[bool]$job.migrated;shortcutWarning=$shortcutError;administrator=$false}
            } catch {
                $failure=$_.Exception.Message
                $restored=$false
                if($changed -and $backup -and (Test-Path -LiteralPath $backup)) {
                    try { [IO.File]::Replace($backup,$job.target,($job.target+'.failed-'+[Guid]::NewGuid().ToString('N')),$false); $restored=((Hash $job.target) -eq $job.baseSha256) }
                    catch { $failure+='；回退失败：'+$_.Exception.Message }
                }
                Save-Json $ready @{ready=$false;message=$failure}
                if($job) {
                    Save-Json $job.result @{status='failed';message=$failure;restored=$restored;backup=$backup;administrator=$false}
                    if($job.showErrors) {
                        Add-Type -AssemblyName System.Windows.Forms
                        [void][System.Windows.Forms.MessageBox]::Show(('增量更新未完成：'+$failure+[Environment]::NewLine+'原程序和账户配置已保留。请查看更新日志。'),'TokenPro 更新提示')
                    }
                }
                Write-Error $failure -ErrorAction Continue
                exit 1
            } finally {
                if($lock){$lock.Dispose()}
            }
            """;
    }
}

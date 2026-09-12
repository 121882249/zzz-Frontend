package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Windows delta updater. Requests UAC only for an unwritable installation; never changes ACLs. */
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
        Path target = sourceRoot.resolve("app/TokenPro.jar");
        boolean elevationRequired = !canReplace(target);
        Path jobRoot = Files.createTempDirectory("TokenPro-update-job-").toAbsolutePath();
        Platform.privateFile(jobRoot);
        Map<String,Object> job = job(merged, target, executable, parentPid, jobRoot);
        job.put("version", version);
        job.put("result", Platform.dataDirectory().resolve("windows-update-result.json").toString());
        Path jobFile = jobRoot.resolve("job.json");
        Files.writeString(jobFile, Json.stringify(job), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Platform.privateFile(jobFile);
        Process process = new ProcessBuilder(elevationRequired ? elevationCommand(jobFile, job) : command(jobFile))
            .redirectErrorStream(true).redirectOutput(jobRoot.resolve("helper.log").toFile()).start();
        Path ready = jobRoot.resolve("ready.json");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(elevationRequired ? 180 : 15);
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
        job.put("result", jobRoot.resolve("result.json").toAbsolutePath().toString());
        return job;
    }

    static List<String> command(Path jobFile) {
        Path powershell = Path.of(System.getenv().getOrDefault("SystemRoot", "C:\\Windows"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        // Inline, locally generated commands; no execution-policy changes or downloaded scripts.
        return List.of(powershell.toString(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden",
            "-Command", "& { " + script() + " } -JobFile '" + jobFile.toAbsolutePath().toString().replace("'", "''") + "'");
    }

    static String quote(String value) { return "'" + value.replace("'", "''") + "'"; }

    static String encoded(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    static List<String> elevationCommand(Path jobFile, Map<String,Object> job) throws IOException {
        String broker = elevationBrokerScript(jobFile, job);
        // Keep the broker unencoded: the embedded elevated command is already Base64 UTF-16LE.
        // Refuse overlong paths/payloads instead of silently truncating the exact update target.
        if (broker.length() > 30000) throw new IOException("更新路径过长，无法安全请求管理员授权，程序未退出");
        return List.of(command(jobFile).getFirst(), "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", broker);
    }

    static String elevationBrokerScript(Path jobFile, Map<String,Object> original) {
        Path jobRoot = jobFile.toAbsolutePath().getParent();
        Map<String,Object> elevated = new LinkedHashMap<>(original);
        // The elevated helper only replaces the already hashed core. It must not execute the
        // application, write another administrator's profile, or trust a mutable JSON job file.
        elevated.put("launch", false);
        elevated.put("showErrors", false);
        elevated.put("administrator", true);
        elevated.put("result", jobRoot.resolve("elevated-result.json").toString());
        String pinnedJob = Base64.getEncoder().encodeToString(Json.stringify(elevated).getBytes(StandardCharsets.UTF_8));
        String helper = script().replace(
            "$job=Get-Content -LiteralPath $JobFile -Raw -Encoding UTF8|ConvertFrom-Json",
            "$job=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('" + pinnedJob + "'))|ConvertFrom-Json");
        helper = "& { " + helper + " } -JobFile " + quote(jobFile.toAbsolutePath().toString());
        return """
            $ErrorActionPreference='Stop'
            [Console]::OutputEncoding=[Text.UTF8Encoding]::new($false)
            $jobRoot=__ROOT__
            $ready=Join-Path $jobRoot 'ready.json'
            $cancel=Join-Path $jobRoot 'cancel'
            $receipt=Join-Path $jobRoot 'elevated-result.json'
            function Save-State($path,$state) {
                $temp=$path+'.'+[Guid]::NewGuid().ToString('N')
                [IO.File]::WriteAllText($temp,($state|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                if(Test-Path -LiteralPath $path){[IO.File]::Replace($temp,$path,[NullString]::Value)}else{[IO.File]::Move($temp,$path)}
            }
            try {
                if(Test-Path -LiteralPath $cancel){throw '更新授权已取消'}
                $elevated=Start-Process -FilePath __POWERSHELL__ -Verb RunAs -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-NonInteractive','-WindowStyle','Hidden','-EncodedCommand','__ENCODED__')
                $elevated.WaitForExit()
                if(-not (Test-Path -LiteralPath $receipt)){throw '管理员更新未完成，原程序未被强制关闭'}
                $result=Get-Content -LiteralPath $receipt -Raw -Encoding UTF8|ConvertFrom-Json
                if($result.status -ne 'complete'){throw $result.message}
                if($elevated.ExitCode -ne 0 -or $result.sha256 -ne __SHA__){throw '管理员更新结果校验失败'}
                Save-State __RESULT__ $result
                # This broker retains the ORIGINAL user's token. Never start TokenPro elevated.
                if(__LAUNCH__){Start-Process -FilePath __LAUNCHER__ -WorkingDirectory __APPROOT__}
            } catch {
                $failure=$_.Exception.Message
                $authorizationCancelled=$false
                for($cause=$_.Exception; $null -ne $cause; $cause=$cause.InnerException) {
                    if($cause.NativeErrorCode -eq 1223 -or (($cause.HResult -band 65535) -eq 1223)){$authorizationCancelled=$true}
                }
                # Start-Process can replace Win32Exception with a message-only wrapper.
                if($failure -match 'operation was cancel[l]?ed by the user|操作已由用户取消|用户取消了操作|操作被用户取消'){$authorizationCancelled=$true}
                if($authorizationCancelled){$failure='管理员授权未完成或已取消，未更新；TokenPro 保持打开'}
                Save-State $ready @{ready=$false;message=$failure}
                Save-State __RESULT__ @{status='failed';message=$failure;administratorRequested=$true}
                Write-Error $failure -ErrorAction Continue
                exit 1
            }
            """.replace("__ROOT__", quote(jobRoot.toString()))
            .replace("__POWERSHELL__", quote(command(jobFile).getFirst()))
            .replace("__ENCODED__", encoded(helper))
            .replace("__SHA__", quote(original.get("sourceSha256").toString()))
            .replace("__RESULT__", quote(original.get("result").toString()))
            .replace("__LAUNCHER__", quote(original.get("launcher").toString()))
            .replace("__APPROOT__", quote(Path.of(original.get("launcher").toString()).getParent().toString()))
            .replace("__LAUNCH__", Boolean.TRUE.equals(original.get("launch")) ? "$true" : "$false");
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
            $lock=$null; $sourceLock=$null; $changed=$false; $job=$null; $next=$null; $backup=$null
            function Save-Json($path,$value) {
                Plain-Path $path
                $temp=$path+'.'+[Guid]::NewGuid().ToString('N')
                [IO.File]::WriteAllText($temp,($value|ConvertTo-Json -Depth 6 -Compress),[Text.UTF8Encoding]::new($false))
                if(Test-Path -LiteralPath $path){[IO.File]::Replace($temp,$path,[NullString]::Value)}else{[IO.File]::Move($temp,$path)}
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
                $sourceLock=[IO.File]::Open($job.source,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
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
                if($job.launch){Start-Process -FilePath $job.launcher -WorkingDirectory $appRoot}
                Save-Json $job.result @{status='complete';version=$job.version;sha256=$job.sourceSha256;launcher=$job.launcher;backup=$backup;administrator=[bool]$job.administrator}
            } catch {
                $failure=$_.Exception.Message
                $restored=$false
                if($changed -and $backup -and (Test-Path -LiteralPath $backup)) {
                    try { [IO.File]::Replace($backup,$job.target,($job.target+'.failed-'+[Guid]::NewGuid().ToString('N')),$false); $restored=((Hash $job.target) -eq $job.baseSha256) }
                    catch { $failure+='；回退失败：'+$_.Exception.Message }
                }
                Save-Json $ready @{ready=$false;message=$failure}
                if($job) {
                    Save-Json $job.result @{status='failed';message=$failure;restored=$restored;backup=$backup;administrator=[bool]$job.administrator}
                    if($job.showErrors) {
                        Add-Type -AssemblyName System.Windows.Forms
                        [void][System.Windows.Forms.MessageBox]::Show(('增量更新未完成：'+$failure+[Environment]::NewLine+'原程序和账户配置已保留。请查看更新日志。'),'TokenPro 更新提示')
                    }
                }
                Write-Error $failure -ErrorAction Continue
                exit 1
            } finally {
                if($lock){$lock.Dispose()}
                if($sourceLock){$sourceLock.Dispose()}
            }
            """;
    }
}

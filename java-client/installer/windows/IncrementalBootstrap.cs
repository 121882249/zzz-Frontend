using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Management;
using System.Net;
using System.Reflection;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: AssemblyTitle("TokenPro 增量更新")]
[assembly: AssemblyDescription("保留原安装位置，仅更新 TokenPro 核心")]
[assembly: AssemblyProduct("TokenPro")]
[assembly: AssemblyVersion("1.2.65.0")]

// Standalone first-upgrade entry for old installations that cannot start the new updater.
// Contains only the delta and presentation assets, not Java or a full application image.
internal static class IncrementalBootstrap {
    private const string MainEntry = "work/tokenpro/client/Main.class";
    private static readonly Version TargetVersion = new Version(1, 2, 65);
    private const long MaxUncompressed = 256L * 1024 * 1024;

    [STAThread]
    private static int Main(string[] args) {
        if(args.Length == 4 && args[0] == "--apply") {
            try { return Apply(Decode(args[1]), args[2], Decode(args[3])); }
            catch { return 30; }
        }
        if(args.Length == 2 && args[0] == "--self-test") {
            try { SelfTest(args[1]); return 0; }
            catch(Exception e) { Console.Error.WriteLine(e); return 1; }
        }
        if(args.Length != 0) return 30;
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        Application.Run(new UpdateForm());
        return 0;
    }

    private static Stream Delta() { return Assembly.GetExecutingAssembly().GetManifestResourceStream("TokenPro.delta"); }
    private static string Encode(string value) { return Convert.ToBase64String(Encoding.UTF8.GetBytes(value)); }
    private static string Decode(string value) { return Encoding.UTF8.GetString(Convert.FromBase64String(value)); }
    private static string Hash(Stream stream) { using(var hash = SHA256.Create()) return BitConverter.ToString(hash.ComputeHash(stream)).Replace("-", "").ToLowerInvariant(); }
    private static string HashFile(string path) { using(var stream = File.OpenRead(path)) return Hash(stream); }
    private static string Core(string launcher) { return Path.Combine(Path.GetDirectoryName(launcher), "app", "TokenPro.jar"); }

    // Read Main.VERSION's ConstantValue, not the manifest (which old delta updates retained).
    // JVMS 4.4, 4.5 and 4.7.2: https://docs.oracle.com/javase/specs/jvms/se21/html/jvms-4.html
    private static int U2(BinaryReader reader) { return (reader.ReadByte() << 8) | reader.ReadByte(); }
    private static uint U4(BinaryReader reader) { return ((uint)U2(reader) << 16) | (uint)U2(reader); }
    private static void Skip(BinaryReader reader, int count) { if(reader.ReadBytes(count).Length != count) throw new EndOfStreamException(); }
    private static Version CoreVersion(string path) {
        using(var archive = ZipFile.OpenRead(path)) {
            var entry = archive.GetEntry(MainEntry);
            if(entry == null || entry.Length > 1024 * 1024) throw new IOException("无法验证已安装版本");
            using(var reader = new BinaryReader(entry.Open())) {
                if(U4(reader) != 0xcafebabe) throw new IOException("主程序格式无效");
                Skip(reader, 4);
                int count = U2(reader); var pool = new object[count]; var tags = new byte[count];
                for(int i = 1; i < count; i++) {
                    byte tag = tags[i] = reader.ReadByte();
                    switch(tag) {
                        case 1: pool[i] = Encoding.UTF8.GetString(reader.ReadBytes(U2(reader))); break;
                        case 7: case 8: case 16: case 19: case 20: pool[i] = U2(reader); break;
                        case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18: Skip(reader, 4); break;
                        case 5: case 6: Skip(reader, 8); i++; break;
                        case 15: Skip(reader, 3); break;
                        default: throw new IOException("主程序常量格式不兼容");
                    }
                }
                Skip(reader, 6); Skip(reader, U2(reader) * 2);
                int fields = U2(reader);
                for(int i = 0; i < fields; i++) {
                    U2(reader); string name = pool[U2(reader)] as string, descriptor = pool[U2(reader)] as string;
                    int attributes = U2(reader);
                    for(int j = 0; j < attributes; j++) {
                        string attribute = pool[U2(reader)] as string; uint size = U4(reader);
                        if(size > 1024 * 1024) throw new IOException("主程序属性过大");
                        if(name == "VERSION" && descriptor == "Ljava/lang/String;" && attribute == "ConstantValue" && size == 2) {
                            int value = U2(reader);
                            if(tags[value] != 8) throw new IOException("主程序版本常量无效");
                            return Version.Parse((string)pool[(int)pool[value]]);
                        }
                        Skip(reader, (int)size);
                    }
                }
            }
        }
        throw new IOException("找不到已安装版本，未执行覆盖");
    }

    private static void PlainPath(string path) {
        if(!Path.IsPathRooted(path)) throw new IOException("路径必须为绝对路径");
        for(string current = Path.GetFullPath(path); !String.IsNullOrEmpty(current); current = Path.GetDirectoryName(current)) {
            if((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0)
                throw new IOException("路径包含链接，请选择实际安装位置");
        }
    }
    private static void Validate(string launcher) {
        PlainPath(launcher);
        if(!String.Equals(Path.GetFileName(launcher), "TokenPro.exe", StringComparison.OrdinalIgnoreCase)) throw new IOException("请选择已安装的 TokenPro.exe");
        string root = Path.GetDirectoryName(launcher);
        foreach(string relative in new [] { "TokenPro.exe", "app/TokenPro.jar", "app/TokenPro.cfg", "runtime/release" }) {
            string path = Path.Combine(root, relative.Replace('/', Path.DirectorySeparatorChar));
            PlainPath(path);
            if(!File.Exists(path)) throw new IOException("所选位置不是完整的 TokenPro 安装目录");
        }
    }
    private static bool Running(string launcher) {
        foreach(var process in Process.GetProcessesByName("TokenPro")) {
            using(process) {
                try {
                    if(String.Equals(Path.GetFullPath(process.MainModule.FileName), launcher, StringComparison.OrdinalIgnoreCase)) return true;
                } catch(System.ComponentModel.Win32Exception) { throw new IOException("暂时无法确认 TokenPro 是否已退出，请关闭 TokenPro 后重试"); }
                catch(InvalidOperationException) { } // Process already exited.
            }
        }
        return false;
    }
    private static bool Writable(string target) {
        string probe = Path.Combine(Path.GetDirectoryName(target), ".tokenpro-write-" + Guid.NewGuid().ToString("N"));
        try {
            if((File.GetAttributes(target) & FileAttributes.ReadOnly) != 0) return false;
            using(File.Open(target, FileMode.Open, FileAccess.Write, FileShare.ReadWrite)) { }
            using(File.Open(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None)) { }
            File.Delete(probe);
            return true;
        } catch(UnauthorizedAccessException) { return false; }
        catch(IOException) { throw new IOException("核心文件被占用或磁盘暂不可写，请退出 TokenPro 后重试"); }
    }

    private sealed class Bridge {
        internal int Pid;
        internal string Flag, Url, Token, Service;
    }
    private static List<Bridge> Bridges(string launcher) {
        var found = new List<Bridge>();
        using(var query = new ManagementObjectSearcher("SELECT ProcessId, ExecutablePath, CommandLine FROM Win32_Process WHERE Name='TokenPro.exe'"))
        using(var processes = query.Get()) foreach(ManagementObject process in processes) {
            using(process) {
                string executable = Convert.ToString(process["ExecutablePath"]);
                if(String.IsNullOrEmpty(executable)) throw new IOException("无法确认某个 TokenPro 进程的安装位置，未关闭任何程序");
                if(!String.Equals(Path.GetFullPath(executable), launcher, StringComparison.OrdinalIgnoreCase)) continue;
                string command = Convert.ToString(process["CommandLine"]).Trim();
                Match flag = Regex.Match(command, @"(?:^|\s)(--(?:claude(?:-cli)?-bridge|codex(?:-cli)?-image-bridge))\s*$");
                if(!flag.Success) throw new IOException("请先退出所选位置的 TokenPro 窗口，再重试；无需关闭 Codex 或 Claude");
                bool codex = flag.Groups[1].Value.StartsWith("--codex", StringComparison.Ordinal), cli = flag.Groups[1].Value.Contains("-cli-");
                string profile = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "TokenPro");
                if(cli) profile = Path.Combine(profile, "cli", codex ? "codex" : "claude");
                string file = Path.Combine(profile, codex ? "codex-image-bridge.json" : "claude-bridge.json");
                PlainPath(file);
                var config = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(File.ReadAllText(file));
                string token = Convert.ToString(config[codex ? "token" : "local_token"]);
                if(!Regex.IsMatch(token, @"^[A-Za-z0-9_-]{20,200}$")) throw new IOException("本机连接凭据无效，未停止连接");
                int port = codex ? (cli ? 23182 : 23180) : Convert.ToInt32(config["port"]);
                if(port <= 1024 || port > 65535) throw new IOException("本机连接端口无效");
                found.Add(new Bridge { Pid = Convert.ToInt32(process["ProcessId"]), Flag = flag.Groups[1].Value, Token = token,
                    Url = "http://127.0.0.1:" + port + (codex ? "/v1" : ""), Service = codex ? "tokenpro-codex-images-v1" : "tokenpro-claude-bridge-v1" });
            }
        }
        return found;
    }
    private static string Request(Bridge bridge, string endpoint, string method) {
        var request = (HttpWebRequest)WebRequest.Create(bridge.Url + endpoint);
        request.Proxy = null; request.AllowAutoRedirect = false; request.Timeout = 3000; request.ReadWriteTimeout = 3000; request.Method = method;
        request.Headers["Authorization"] = "Bearer " + bridge.Token;
        if(method == "POST") request.ContentLength = 0;
        using(var response = (HttpWebResponse)request.GetResponse()) using(var reader = new StreamReader(response.GetResponseStream())) {
            if(response.StatusCode != HttpStatusCode.OK) throw new IOException("本机连接没有确认暂停");
            return reader.ReadToEnd();
        }
    }
    private static void Pause(List<Bridge> bridges) {
        // Validate every service before asking any of them to stop. Never terminate a process.
        foreach(var bridge in bridges) {
            string health = Request(bridge, "/health", "GET");
            bool expected = health == bridge.Service;
            if(!expected) { try { expected = Convert.ToString(new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(health)["service"]) == bridge.Service; } catch { } }
            if(!expected) throw new IOException("端口不是预期的 TokenPro 本机连接，已停止更新");
        }
        foreach(var bridge in bridges) {
            Request(bridge, "/shutdown", "POST");
            try { using(var process = Process.GetProcessById(bridge.Pid)) if(!process.WaitForExit(6000)) throw new IOException("本机连接仍有未结束的请求，请稍后重试"); }
            catch(ArgumentException) { }
        }
    }
    private static bool Resume(string launcher, List<Bridge> bridges) {
        if(bridges.Count == 0) return true;
        if(new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator)) return false;
        bool success = true;
        foreach(var bridge in bridges) {
            try {
                try { using(var process = Process.GetProcessById(bridge.Pid)) if(!process.HasExited) continue; } catch(ArgumentException) { }
                using(var resumed = Process.Start(new ProcessStartInfo(launcher, bridge.Flag) { UseShellExecute = false, CreateNoWindow = true, WindowStyle = ProcessWindowStyle.Hidden })) { }
            } catch { success = false; }
        }
        return success;
    }
    private static bool PatchEntry(string name) {
        if(name.EndsWith("/", StringComparison.Ordinal)) return "work/tokenpro/client/".StartsWith(name, StringComparison.Ordinal);
        if(!name.StartsWith("work/tokenpro/client/", StringComparison.Ordinal) || !name.EndsWith(".class", StringComparison.Ordinal)) return false;
        string leaf = name.Substring("work/tokenpro/client/".Length);
        return leaf.IndexOf('/') < 0 && leaf.IndexOf('\\') < 0 && leaf.IndexOf("..", StringComparison.Ordinal) < 0;
    }
    private static void CopyEntry(ZipArchiveEntry from, ZipArchive output) {
        var to = output.CreateEntry(from.FullName, CompressionLevel.Optimal);
        using(var input = from.Open()) using(var target = to.Open()) input.CopyTo(target);
    }
    private static void Merge(string core, string merged, string expectedBase) {
        using(var baseStream = File.Open(core, FileMode.Open, FileAccess.Read, FileShare.Read)) {
            if(Hash(baseStream) != expectedBase) throw new IOException("更新期间原程序发生变化，已停止覆盖");
            baseStream.Position = 0;
            using(var baseline = new ZipArchive(baseStream, ZipArchiveMode.Read))
            using(var resource = Delta())
            using(var patch = new ZipArchive(resource, ZipArchiveMode.Read))
            using(var output = new ZipArchive(File.Open(merged, FileMode.CreateNew, FileAccess.Write, FileShare.None), ZipArchiveMode.Create)) {
                if(baseline.GetEntry(MainEntry) == null || patch.GetEntry(MainEntry) == null) throw new IOException("增量包或原核心文件不完整");
                var replacements = new Dictionary<string, ZipArchiveEntry>(StringComparer.Ordinal);
                long size = 0;
                foreach(var entry in patch.Entries) {
                    if(!PatchEntry(entry.FullName) || replacements.ContainsKey(entry.FullName)) throw new IOException("增量包包含不允许的内容");
                    size = checked(size + entry.Length);
                    if(size > MaxUncompressed) throw new IOException("增量包解压大小异常");
                    replacements.Add(entry.FullName, entry);
                }
                var written = new HashSet<string>(StringComparer.Ordinal);
                foreach(var entry in baseline.Entries) {
                    size = checked(size + entry.Length);
                    if(size > MaxUncompressed || !written.Add(entry.FullName)) throw new IOException("原核心文件内容异常");
                    ZipArchiveEntry replacement;
                    CopyEntry(replacements.TryGetValue(entry.FullName, out replacement) ? replacement : entry, output);
                }
                foreach(var entry in patch.Entries) if(written.Add(entry.FullName)) CopyEntry(entry, output);
            }
        }
    }

    private static int Apply(string launcher, string expectedBase, string expectedDelta) {
        string next = null, backup = null;
        bool replaced = false;
        try {
            launcher = Path.GetFullPath(launcher);
            Validate(launcher);
            if(CoreVersion(Core(launcher)) >= TargetVersion) return 2;
            if(Running(launcher)) return 10;
            using(var resource = Delta()) if(Hash(resource) != expectedDelta) return 20;
            string target = Core(launcher);
            PlainPath(target);
            using(var updateLock = File.Open(target + ".update.lock", FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None)) {
                string suffix = Guid.NewGuid().ToString("N");
                next = target + ".next-" + suffix;
                backup = target + ".rollback-" + suffix;
                Merge(target, next, expectedBase);
                string mergedHash = HashFile(next);
                if(Running(launcher) || HashFile(target) != expectedBase) return 10;
                PlainPath(target); PlainPath(next); PlainPath(backup);
                File.Replace(next, target, backup, false); replaced = true;
                if(HashFile(target) != mergedHash || CoreVersion(target) != TargetVersion) throw new IOException("更新后校验失败");
            }
            return 0;
        } catch(Exception error) {
            if(replaced && backup != null && File.Exists(backup)) {
                try { File.Replace(backup, Core(launcher), Core(launcher) + ".failed-" + Guid.NewGuid().ToString("N"), false); }
                catch { return 40; }
            }
            return error is UnauthorizedAccessException ? 5 : 30;
        } finally {
            // Only the unique staging file created by this invocation; never delete the backup.
            if(next != null && File.Exists(next)) { try { File.Delete(next); } catch { } }
        }
    }

    private sealed class UpdateForm : Form {
        private readonly TextBox path = new TextBox();
        private readonly Label status = new Label();
        private readonly Button update = new Button();
        private readonly Button browse = new Button();
        private bool busy;
        internal UpdateForm() {
            Text = "TokenPro · 增量更新"; ClientSize = new Size(700, 390); StartPosition = FormStartPosition.CenterScreen;
            Font = new Font("Microsoft YaHei UI", 10); ForeColor = Color.White; BackColor = Color.FromArgb(8, 13, 36);
            FormBorderStyle = FormBorderStyle.FixedDialog; MaximizeBox = false;
            using(var icon = Assembly.GetExecutingAssembly().GetManifestResourceStream("TokenPro.icon")) Icon = new Icon(icon);
            using(var image = Assembly.GetExecutingAssembly().GetManifestResourceStream("TokenPro.background")) using(var decoded = Image.FromStream(image)) BackgroundImage = new Bitmap(decoded);
            BackgroundImageLayout = ImageLayout.Stretch;
            var heading = new Label { Text = "保留原位置，更新模型宇宙", Font = new Font(Font.FontFamily, 22, FontStyle.Bold), AutoSize = true, Location = new Point(28, 26), BackColor = Color.Transparent };
            var explanation = new Label { Text = "仅包含增量核心，不重新下载完整程序。\n请先退出 TokenPro 窗口；更新会暂时暂停本机连接，不关闭 Codex 或 Claude。", AutoSize = false, Size = new Size(642, 65), Location = new Point(28, 92), BackColor = Color.Transparent };
            path.SetBounds(28, 173, 520, 30); path.ReadOnly = true; path.BackColor = Color.FromArgb(20, 29, 57); path.ForeColor = Color.White;
            browse.Text = "选择已有程序"; browse.SetBounds(558, 171, 112, 34);
            browse.BackColor = Color.FromArgb(35, 48, 79); browse.FlatStyle = FlatStyle.Flat;
            browse.Click += delegate { using(var dialog = new OpenFileDialog { Title = "请选择已安装的 TokenPro.exe", Filter = "TokenPro|TokenPro.exe", CheckFileExists = true }) if(dialog.ShowDialog(this) == DialogResult.OK) path.Text = dialog.FileName; };
            status.SetBounds(28, 225, 642, 67); status.BackColor = Color.Transparent;
            status.Text = "安装位置由你决定，更新不会迁移目录。\n仅在写入权限不足时请求 Windows 管理员授权。";
            update.Text = "开始增量更新"; update.SetBounds(490, 325, 180, 38); update.BackColor = Color.FromArgb(30, 110, 240); update.FlatStyle = FlatStyle.Flat;
            update.Click += async delegate {
                if(busy) return;
                busy = true; update.Enabled = browse.Enabled = false;
                List<Bridge> bridges = new List<Bridge>(); string selectedLauncher = null;
                try {
                    string launcher = Path.GetFullPath(path.Text); IncrementalBootstrap.Validate(launcher);
                    if(CoreVersion(Core(launcher)) >= TargetVersion) throw new IOException("当前版本已是此版本或更新版本，无需重复更新");
                    selectedLauncher = launcher;
                    bridges = await Task.Run(() => Bridges(launcher));
                    await Task.Run(() => Pause(bridges));
                    string original = HashFile(Core(launcher)), deltaHash;
                    using(var resource = Delta()) deltaHash = Hash(resource);
                    bool elevate = !Writable(Core(launcher));
                    status.Text = elevate ? "此位置需要管理员权限，请确认 Windows 授权；取消不会更新。" : "正在原位置合并并校验增量，请稍候…";
                    int result = await Task.Run(() => {
                        if(!elevate) return Apply(launcher, original, deltaHash);
                        var start = new ProcessStartInfo(Assembly.GetExecutingAssembly().Location,
                            "--apply " + Encode(launcher) + " " + original + " " + Encode(deltaHash)) { UseShellExecute = true, Verb = "runas", WindowStyle = ProcessWindowStyle.Hidden };
                        using(var worker = Process.Start(start)) { worker.WaitForExit(); return worker.ExitCode; }
                    });
                    if(result == 0) status.Text = "增量更新已完成，安装位置和账号配置未变。\n请关闭本窗口，再正常打开原来的 TokenPro 快捷方式。";
                    else if(result == 2) status.Text = "当前已是此版本或更新版本，未重复更新，也未降级。";
                    else if(result == 10) status.Text = "TokenPro 仍在运行或原文件已变化，未覆盖。请退出 TokenPro 后重试。";
                    else if(result == 5) status.Text = "授权后仍无法写入，未完成更新。请检查文件占用或磁盘状态。";
                    else if(result == 40) status.Text = "更新失败且回退未完成。原文件备份保留在 app 目录，请勿删除。";
                    else status.Text = "校验或文件替换失败，更新未完成；原核心或回退备份已保留。";
                } catch(System.ComponentModel.Win32Exception e) { status.Text = e.NativeErrorCode == 1223 ? "管理员授权未完成或已取消，未更新。" : "无法启动更新进程：" + e.Message; }
                catch(Exception e) { status.Text = "未完成更新：" + e.Message; }
                finally {
                    if(selectedLauncher != null && !Resume(selectedLauncher, bridges)) status.Text += "\n请正常启动 TokenPro 恢复本机连接。";
                    busy = false; update.Enabled = browse.Enabled = true;
                }
            };
            FormClosing += delegate(object sender, FormClosingEventArgs e) { if(busy) { e.Cancel = true; status.Text = "正在完成文件操作，请稍候；授权窗口可点击取消。"; } };
            Controls.AddRange(new Control[] { heading, explanation, path, browse, status, update });
        }
    }

    private static void SelfTest(string output) {
        string root = Path.GetFullPath(output);
        string workspace = Path.GetFullPath(Environment.CurrentDirectory).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
        if(!root.StartsWith(workspace, StringComparison.OrdinalIgnoreCase) || Directory.Exists(root)) throw new IOException("Test directory must be new and inside workspace");
        string app = Path.Combine(root, "Custom Location", "TokenPro");
        Directory.CreateDirectory(Path.Combine(app, "app")); Directory.CreateDirectory(Path.Combine(app, "runtime"));
        string launcher = Path.Combine(app, "TokenPro.exe"), target = Core(launcher);
        File.WriteAllText(launcher, "fixture-never-execute"); File.WriteAllText(Path.Combine(app, "app", "TokenPro.cfg"), "fixture"); File.WriteAllText(Path.Combine(app, "runtime", "release"), "fixture-runtime");
        byte[] oldMain;
        using(var resource = Delta()) using(var patch = new ZipArchive(resource, ZipArchiveMode.Read)) using(var input = patch.GetEntry(MainEntry).Open()) using(var memory = new MemoryStream()) { input.CopyTo(memory); oldMain = memory.ToArray(); }
        byte[] versionBytes = Encoding.ASCII.GetBytes("1.2.65"); int changed = 0;
        for(int i = 0; i <= oldMain.Length - versionBytes.Length; i++) {
            bool match = true; for(int j = 0; j < versionBytes.Length; j++) if(oldMain[i + j] != versionBytes[j]) match = false;
            if(match) { oldMain[i + versionBytes.Length - 1] = (byte)'4'; changed++; }
        }
        if(changed != 1) throw new Exception("Unexpected fixture version constant count");
        using(var archive = new ZipArchive(File.Create(target), ZipArchiveMode.Create)) {
            using(var writer = archive.CreateEntry(MainEntry).Open()) writer.Write(oldMain, 0, oldMain.Length);
            using(var writer = new StreamWriter(archive.CreateEntry("assets/preserve.txt").Open())) writer.Write("keep-assets");
        }
        File.WriteAllText(Path.Combine(app, "notes.txt"), "user-data");
        string before = HashFile(target), delta;
        using(var resource = Delta()) delta = Hash(resource);
        if(Apply(launcher, new string('0', 64), delta) == 0 || HashFile(target) != before) throw new Exception("Base mismatch overwrote original");
        if(Apply(launcher, before, new string('0', 64)) == 0 || HashFile(target) != before) throw new Exception("Delta mismatch overwrote original");
        if(Apply(launcher, before, delta) != 0 || HashFile(target) == before) throw new Exception("In-place delta failed");
        string updated = HashFile(target);
        if(Apply(launcher, updated, delta) != 2 || HashFile(target) != updated) throw new Exception("Same version was replaced");
        using(var archive = ZipFile.OpenRead(target)) using(var reader = new StreamReader(archive.GetEntry("assets/preserve.txt").Open())) if(reader.ReadToEnd() != "keep-assets") throw new Exception("Asset was changed");
        if(File.ReadAllText(Path.Combine(app, "notes.txt")) != "user-data" || File.ReadAllText(Path.Combine(app, "runtime", "release")) != "fixture-runtime") throw new Exception("Unrelated file changed");
        string[] backups = Directory.GetFiles(Path.Combine(app, "app"), "*.rollback-*");
        if(backups.Length != 1 || HashFile(backups[0]) != before) throw new Exception("Recovery backup missing");
        if(PatchEntry("../bad.class") || PatchEntry("work/tokenpro/client/../../bad.class") || PatchEntry("assets/changed.png")) throw new Exception("Invalid patch entry accepted");
        Console.WriteLine("TokenPro incremental bootstrap: 8 checks passed; no real installation modified.");
    }
}

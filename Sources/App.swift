import SwiftUI
import AppKit

final class NoRedirect: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}

@MainActor final class AppModel: ObservableObject {
    @Published var settings = Settings()
    @Published var notice: String?
    @Published var busy = false
    @Published var installed = false
    @Published var selectedModels: [PricedModel] = []
    @Published private(set) var activeModelID: String?
    @Published private(set) var managedCodexKey: ManagedCodexKeyState?
    @Published var claudeSelectedModels: [PricedModel] = []
    @Published private(set) var managedClaudeKey: ManagedCodexKeyState?
    @Published private(set) var claudeInstalled = false
    @Published var claudeNeedsReload = UserDefaults.standard.bool(forKey: "claudeNeedsReload")
    var selected: Route? { settings.routes.first { $0.id == settings.selectedID } }
    var activeModel: PricedModel? { selectedModels.first(where: { $0.id == activeModelID }) ?? selectedModels.first }
    var executable: String { Bundle.main.executableURL!.path }
    init() {
        do { settings = try Persistence.load() } catch { notice = "连接配置读取失败：\(error.localizedDescription)" }
        let state = ModelSelectionStore.loadState()
        selectedModels = state.models
        activeModelID = state.activeModel?.id
        managedCodexKey = state.managedKey
        installed = FileManager.default.fileExists(atPath: CodexConfig.backupURL.path)
        let claudeState = ClaudeSelectionStore.load()
        claudeSelectedModels = claudeState.models
        managedClaudeKey = claudeState.managedKey
        claudeInstalled = ClaudeDesktopConfig.isInstalled(state: claudeState)
    }
    func applyModelSelection(_ models: [PricedModel], activeModel: PricedModel, managedKey: CodexManagedKey, compactContext: Bool = CompactContext.enabled) throws {
        guard !models.isEmpty else { try clearModelSelection(); return }
        guard models.contains(where: { $0.id == activeModel.id }) else { throw RouterError("当前模型不在已选列表中。") }
        try HelperCredentialStore.writeModelToken(managedKey.key)
        try CodexConfig.installModels(models, activeModel: activeModel, executable: executable, compactContext: compactContext)
        let keyState = ManagedCodexKeyState(id: managedKey.id, groupID: managedKey.groupID)
        try ModelSelectionStore.save(ModelSelectionState(models: models, activeModelID: activeModel.id, managedKey: keyState))
        CompactContext.enabled = compactContext
        selectedModels = models
        activeModelID = activeModel.id
        managedCodexKey = keyState
        installed = true
        notice = "已将 \(models.count) 个模型加入 Codex。请重新打开一次 Codex 并审核 TokenPro Hook；以后直接在 Codex 内切换模型即可。"
    }
    func activateModel(_ model: PricedModel, keyState: ManagedCodexKeyState) throws {
        guard selectedModels.contains(where: { $0.id == model.id }) else { throw RouterError("该模型不在已选列表中。") }
        try CodexConfig.installModels(selectedModels, activeModel: model, executable: executable)
        try ModelSelectionStore.save(ModelSelectionState(models: selectedModels, activeModelID: model.id, managedKey: keyState))
        activeModelID = model.id
        managedCodexKey = keyState
        installed = true
    }
    func refreshModelConfigurationAfterCodexLaunch() async {
        guard !selectedModels.isEmpty, let active = activeModel else { return }
        var lastError: Error?
        for delay in [300_000_000, 1_500_000_000, 3_000_000_000] {
            try? await Task.sleep(nanoseconds: UInt64(delay))
            do {
                try CodexConfig.installModels(selectedModels, activeModel: active, executable: executable)
                installed = true
                lastError = nil
            } catch {
                lastError = error
            }
        }
        if let lastError {
            notice = "Codex 已启动，但 TokenPro 配置刷新失败：\(lastError.localizedDescription)"
        }
    }
    func clearModelSelection() throws {
        try CodexConfig.clearModelSelection()
        try ModelSelectionStore.clear()
        try HelperCredentialStore.removeModelToken()
        selectedModels = []
        activeModelID = nil
        managedCodexKey = nil
        installed = false
        notice = "已切回 OpenAI 官方配置。重启 Codex 后生效；如果没有有效的官方登录信息，请在 Codex 中登录。"
    }
    func applyClaudeSelection(_ models: [PricedModel], managedKey: CodexManagedKey, accountID: String) async throws {
        guard !models.isEmpty else { try clearClaudeSelection(); return }
        let previous = try? ClaudeBridgeSettings.load()
        let sameAccount = previous?.accountID == accountID
        let bridge = ClaudeBridgeSettings(accountID: accountID, port: previous?.port ?? 23179,
            token: sameAccount ? previous!.token : UUID().uuidString + UUID().uuidString,
            routes: models.map { ClaudeBridgeRoute(model: $0) }, keyID: managedKey.id, key: managedKey.key)
        let previousToken = try HelperCredentialStore.readClaudeModelToken()
        do {
            try bridge.save()
            try HelperCredentialStore.writeClaudeModelToken(bridge.token)
            try await ClaudeBridgeManager.ensureRunning(executable: executable)
            var source = ClaudeSelectionStore.load()
            source.managedKey = ManagedCodexKeyState(id: managedKey.id, groupID: managedKey.groupID)
            let next = try ClaudeDesktopConfig.install(models: models, executable: executable, state: source, baseURL: bridge.baseURL)
            try ClaudeSelectionStore.save(next)
            claudeSelectedModels = next.models
            managedClaudeKey = next.managedKey
            claudeInstalled = true
            claudeNeedsReload = true
            UserDefaults.standard.set(true, forKey: "claudeNeedsReload")
            notice = "已保存 \(models.count) 个模型。首次加载新列表需重启一次 Claude，之后直接在 Claude 内切换即可。"
        } catch {
            if let previous { try? previous.save() } else { try? ClaudeBridgeManager.remove() }
            if let previousToken { try? HelperCredentialStore.writeClaudeModelToken(previousToken) }
            else { try? HelperCredentialStore.removeClaudeModelToken() }
            throw error
        }
    }
    func clearClaudeSelection() throws {
        let next = try ClaudeDesktopConfig.restoreOfficial(state: ClaudeSelectionStore.load())
        try HelperCredentialStore.removeClaudeModelToken()
        try ClaudeBridgeManager.remove()
        try ClaudeSelectionStore.save(next)
        claudeSelectedModels = []
        managedClaudeKey = nil
        claudeInstalled = false
        notice = "已恢复 Claude 官方配置。重启 Claude 后生效；原有官网登录、聊天记录和其他配置档均已保留。"
    }
    func save(_ route: Route, key: String) throws {
        let r = try route.validated()
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.contains(where: { $0.isNewline }) else { throw RouterError("API Key 不能换行。") }
        if !trimmed.isEmpty { try LocalCredentialStore.write(trimmed, id: r.keyID) }
        guard let stored = try LocalCredentialStore.read(r.keyID), !stored.isEmpty else { throw RouterError("请输入该连接使用的 API Key。") }
        var next = settings
        if let i = next.routes.firstIndex(where: { $0.id == r.id }) { next.routes[i] = r } else { next.routes.append(r) }
        if next.selectedID == nil { next.selectedID = r.id }
        try Persistence.save(next); settings = next
    }
    func select(_ route: Route) {
        do {
            var next = settings; next.selectedID = route.id
            try Persistence.save(next); settings = next
        } catch { notice = error.localizedDescription }
    }
    func install() {
        do {
            guard let route = selected else { throw RouterError("请先选择连接。") }
            guard let token = try LocalCredentialStore.read(route.keyID), !token.isEmpty else { throw RouterError("连接令牌不可用，请编辑后重新保存。") }
            try CodexConfig.install(route: route, executable: executable)
            installed = true
            notice = "已备份并更新 Codex 直连配置。现在可以关闭 TokenPro；重新打开 Codex 后生效。"
        } catch { notice = error.localizedDescription }
    }
    func restore() {
        do { try CodexConfig.restore(); installed = false; notice = "原始配置已恢复。重新打开 Codex 后生效。" }
        catch { notice = error.localizedDescription }
    }
    func copyConfig() {
        guard let r = selected else { notice = "请先添加并选择连接。"; return }
        do {
            let text = try CodexConfig.render(original: "", route: r, executable: executable)
            NSPasteboard.general.clearContents(); NSPasteboard.general.setString(text, forType: .string)
            notice = "接入配置已复制。配置通过本机凭据助手读取令牌，不包含中转站令牌。"
        } catch { notice = error.localizedDescription }
    }
    func probe() {
        guard let r = selected else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                guard let key = try LocalCredentialStore.read(r.keyID) else { throw RouterError("令牌不存在。") }
                let config = URLSessionConfiguration.ephemeral
                config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 90
                let session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
                defer { session.invalidateAndCancel() }
                var req = URLRequest(url: r.endpoint("/responses")); req.httpMethod = "POST"
                req.setValue("Bearer " + key, forHTTPHeaderField: "Authorization")
                req.setValue("application/json", forHTTPHeaderField: "Content-Type")
                req.httpBody = try JSONSerialization.data(withJSONObject: ["model": r.model, "input": "Reply with OK only.", "stream": true, "store": false])
                let (bytes, response) = try await session.bytes(for: req)
                guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {
                    throw RouterError("接口返回 HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)。请检查地址、令牌权限和模型。")
                }
                var completed = false
                for try await line in bytes.lines {
                    if line.hasPrefix("data:"), let data = line.dropFirst(5).trimmingCharacters(in: .whitespaces).data(using: .utf8),
                       let event = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any], let type = event["type"] as? String {
                        if type == "response.completed" { completed = true }
                        if ["error", "response.failed", "response.incomplete"].contains(type) { throw RouterError("上游报告响应失败或未完成。") }
                    }
                }
                guard completed else { throw RouterError("未收到 Responses 的完成事件。该地址可能不兼容，或流式传输中断。") }
                notice = "Responses 流式测试通过。已验证认证和流式完成事件；Codex 工具调用仍需实际任务验证。"
            } catch { notice = error.localizedDescription }
        }
    }
}

struct SoftButton: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    var primary = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 17).padding(.vertical, 11)
            .foregroundStyle(primary ? Color.white : Color.primary)
            .background(primary ? Color(red: 0.40, green: 0.32, blue: 0.94).opacity(configuration.isPressed ? 0.8 : 1) : Color.black.opacity(configuration.isPressed ? 0.09 : 0.045), in: RoundedRectangle(cornerRadius: 10))
            .opacity(isEnabled ? 1 : 0.35)
            .contentShape(RoundedRectangle(cornerRadius: 10))
    }
}

struct ContentView: View {
    @EnvironmentObject var app: AppModel
    @State var page = "首页"
    @EnvironmentObject var account: NativeAccount
    @State var picker = false
    @State var claudePicker = false
    @State private var refreshingBalance = false
    @State private var preparingRecharge = false
    @State private var rechargeCredentials: RechargeCredentials?
    @State private var openingWebsite = false
    @State private var websiteCredentials: RechargeCredentials?
    @State private var openingDocs = false
    @State private var docsCredentials: RechargeCredentials?
    @State private var restartingClients: Set<String> = []
    let purple = Color(red: 0.40, green: 0.32, blue: 0.94)
    var body: some View {
        HStack(spacing: 0) {
            sidebar
            Divider()
            VStack(spacing: 0) {
                if page == "我的账户" {
                    AccountPage()
                } else {
                header
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        switch page {
                        default: home
                        }
                    }.padding(26)
                }
                }
            }.background(Color(red: 0.985, green: 0.985, blue: 0.99))
        }
        .frame(minWidth: 940, minHeight: 650)
        .preferredColorScheme(.light)
        .sheet(isPresented: $picker) { ModelPickerView(isPresented: $picker).environmentObject(app).environmentObject(account) }
        .sheet(isPresented: $claudePicker) { ClaudeModelPickerView(isPresented: $claudePicker).environmentObject(app).environmentObject(account) }
        .sheet(item: $rechargeCredentials) { credentials in RechargePage(credentials: credentials) }
        .sheet(item: $websiteCredentials) { credentials in
            RechargePage(credentials: credentials, title: "TokenPro 后台管理", initialPath: "/admin/dashboard")
        }
        .sheet(item: $docsCredentials) { credentials in
            RechargePage(credentials: credentials, title: "TokenPro 使用文档", initialPath: "/docs")
        }
        .alert("TokenPro", isPresented: Binding(get: { app.notice != nil }, set: { if !$0 { app.notice = nil } })) { Button("知道了") { app.notice = nil } } message: { Text(app.notice ?? "") }
    }
    var sidebar: some View {
        VStack(alignment: .leading, spacing: 24) {
            HStack(spacing: 9) {
                Image(systemName: "point.3.connected.trianglepath.dotted").font(.system(size: 19, weight: .bold)).foregroundStyle(purple)
                Text("TokenPro").font(.system(size: 16, weight: .bold))
            }.padding(.top, 26).padding(.horizontal, 18)
            VStack(spacing: 7) {
                nav("首页", "sparkles")
                sidebarAction(openingWebsite ? "正在打开" : "后台管理", "WebCog", disabled: openingWebsite) {
                    openBackendManagement()
                }
                sidebarAction(openingDocs ? "正在打开" : "使用文档", "WebBook", disabled: openingDocs) {
                    openDocumentation()
                }
            }
            Spacer()
            nav("我的账户", "person.crop.circle")
                .padding(.bottom, 18)
        }.frame(width: 226).background(Color(red: 0.955, green: 0.955, blue: 0.965))
    }
    func nav(_ title: String, _ icon: String) -> some View {
        Button { page = title } label: {
            Label(title, systemImage: icon).font(.system(size: 13, weight: page == title ? .semibold : .regular))
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 11)
                .background(page == title ? purple.opacity(0.10) : .clear, in: RoundedRectangle(cornerRadius: 9))
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .padding(.horizontal, 12)
    }
    @ViewBuilder func webSidebarIcon(_ resource: String) -> some View {
        if let url = Bundle.main.url(forResource: resource, withExtension: "svg"),
           let image = NSImage(contentsOf: url) {
            Image(nsImage: image).resizable().scaledToFit().frame(width: 18, height: 18)
        } else {
            Image(systemName: resource == "WebCog" ? "gearshape" : "book")
                .frame(width: 18, height: 18)
        }
    }
    func sidebarAction(_ title: String, _ iconResource: String, disabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                webSidebarIcon(iconResource)
                Text(title)
            }
                .font(.system(size: 13))
                .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 11)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
        .padding(.horizontal, 12)
        .disabled(disabled)
    }
    func openBackendManagement() {
        guard account.signedIn else { page = "我的账户"; return }
        openingWebsite = true
        Task {
            defer { openingWebsite = false }
            do { websiteCredentials = try await account.rechargeCredentials() }
            catch { app.notice = error.localizedDescription }
        }
    }
    func openDocumentation() {
        guard account.signedIn else { page = "我的账户"; return }
        openingDocs = true
        Task {
            defer { openingDocs = false }
            do { docsCredentials = try await account.rechargeCredentials() }
            catch { app.notice = error.localizedDescription }
        }
    }
    var header: some View {
        VStack(alignment: .leading, spacing: 25) {
            HStack {
                HStack(spacing: 10) {
                    Image(systemName: "point.3.connected.trianglepath.dotted").foregroundStyle(purple)
                    Text(page == "首页" ? "TokenPro" : page).font(.system(size: 23, weight: .bold))
                }
                Spacer()
                ClientUpdateButton()
                Button { page = "我的账户" } label: {
                    HStack(spacing: 8) {
                        Image(systemName: "person.crop.circle.fill").font(.system(size: 29)).foregroundStyle(purple)
                        Text(account.signedIn && !account.email.isEmpty ? account.email : "登录账户")
                            .font(.system(size: 12, weight: .semibold))
                            .foregroundStyle(Color.primary)
                            .lineLimit(1)
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 7)
                    .background(.white.opacity(0.50), in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(account.signedIn && !account.email.isEmpty ? "当前用户：\(account.email)" : "登录账户")
            }
            HStack(spacing: 12) {
                HStack(spacing: 14) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("钱包余额").font(.system(size: 12)).foregroundStyle(.secondary)
                        Text("充值比例  1￥ = 1$").font(.system(size: 10, weight: .medium)).foregroundStyle(.secondary)
                    }
                    Text(account.signedIn ? account.balance : "—").font(.system(size: 27, weight: .bold, design: .rounded))
                    Button {
                        refreshingBalance = true
                        Task {
                            defer { refreshingBalance = false }
                            do { account.user = try await account.authenticated("/auth/me") }
                            catch { app.notice = error.localizedDescription }
                        }
                    } label: {
                        Image(systemName: "arrow.clockwise")
                            .rotationEffect(.degrees(refreshingBalance ? 180 : 0))
                    }
                    .buttonStyle(.plain)
                    .disabled(!account.signedIn || refreshingBalance)
                    .accessibilityLabel("刷新余额")
                    Divider().frame(height: 32)
                    Button {
                        guard account.signedIn else { page = "我的账户"; return }
                        preparingRecharge = true
                        Task {
                            defer { preparingRecharge = false }
                            do { rechargeCredentials = try await account.rechargeCredentials() }
                            catch { app.notice = error.localizedDescription }
                        }
                    } label: {
                        Label(preparingRecharge ? "正在打开" : "充值", systemImage: "plus")
                            .font(.system(size: 13, weight: .bold))
                            .padding(.horizontal, 18)
                            .padding(.vertical, 11)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 11))
                    }
                    .buttonStyle(.plain)
                    .disabled(preparingRecharge)
                    .accessibilityLabel("充值")
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 14)
                .background(.white.opacity(0.34), in: RoundedRectangle(cornerRadius: 16))
                .overlay(RoundedRectangle(cornerRadius: 16).stroke(.white.opacity(0.92), lineWidth: 1.2))
                Spacer(minLength: 8)
            }
        }.padding(28).background(Color(red: 0.90, green: 0.93, blue: 1))
    }
    func originalClientIcon(_ resource: String) -> some View {
        Image(nsImage: NSImage(contentsOf: Bundle.main.url(forResource: resource, withExtension: "icns")!)!)
            .resizable().interpolation(.high).scaledToFit().frame(width: 52, height: 52)
            .accessibilityLabel(resource == "CodexOfficial" ? "Codex 图标" : "Claude 图标")
    }
    @ViewBuilder func installBadge(_ installed: Bool, downloadURL: String) -> some View {
        if installed {
            Text("已安装")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(Color.green)
        } else {
            Button("去下载") {
                if let url = URL(string: downloadURL) { NSWorkspace.shared.open(url) }
            }
            .buttonStyle(.plain)
            .font(.system(size: 11, weight: .semibold))
            .foregroundStyle(purple)
        }
    }
    func clientCandidates(_ name: String) -> [URL] {
        let home = FileManager.default.homeDirectoryForCurrentUser
        if name == "Codex" {
            return [
                URL(fileURLWithPath: "/Applications/ChatGPT.app"),
                URL(fileURLWithPath: "/Applications/Codex.app"),
                home.appendingPathComponent("Applications/ChatGPT.app"),
                home.appendingPathComponent("Applications/Codex.app")
            ]
        }
        return [
            URL(fileURLWithPath: "/Applications/Claude.app"),
            home.appendingPathComponent("Applications/Claude.app")
        ]
    }
    func commandLineCandidates(_ name: String) -> [URL] {
        let home = FileManager.default.homeDirectoryForCurrentUser
        if name == "Codex" {
            return [
                URL(fileURLWithPath: "/opt/homebrew/bin/codex"),
                URL(fileURLWithPath: "/usr/local/bin/codex"),
                home.appendingPathComponent(".local/bin/codex"),
                home.appendingPathComponent(".npm-global/bin/codex"),
                home.appendingPathComponent(".volta/bin/codex"),
                home.appendingPathComponent(".bun/bin/codex")
            ]
        }
        return [
            URL(fileURLWithPath: "/opt/homebrew/bin/claude"),
            URL(fileURLWithPath: "/usr/local/bin/claude"),
            home.appendingPathComponent(".local/bin/claude"),
            home.appendingPathComponent(".claude/local/claude"),
            home.appendingPathComponent(".npm-global/bin/claude")
        ]
    }
    func clientInstalled(_ name: String) -> Bool {
        clientCandidates(name).contains { FileManager.default.fileExists(atPath: $0.path) }
    }
    func commandLineInstalled(_ name: String) -> Bool {
        commandLineCandidates(name).contains { FileManager.default.isExecutableFile(atPath: $0.path) }
    }
    func openClient(_ name: String) {
        guard let url = clientCandidates(name).first(where: { FileManager.default.fileExists(atPath: $0.path) }) else {
            app.notice = "未找到 \(name) 客户端，请先安装到 Applications。"; return
        }
        let bundleID = name == "Codex" ? "com.openai.codex" : "com.anthropic.claudefordesktop"
        restartingClients.insert(name)
        Task {
            if name == "Claude" {
                do { try await ClaudeBridgeManager.ensureRunning(executable: app.executable) }
                catch { restartingClients.remove(name); app.notice = error.localizedDescription; return }
                if !app.claudeNeedsReload {
                    NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration()) { _, error in
                        Task { @MainActor in
                            restartingClients.remove(name)
                            if let error { app.notice = error.localizedDescription }
                        }
                    }
                    return
                }
            }
            let running = NSRunningApplication.runningApplications(withBundleIdentifier: bundleID)
            for process in running { _ = process.terminate() }
            for _ in 0..<25 {
                if running.allSatisfy(\.isTerminated) { break }
                try? await Task.sleep(nanoseconds: 100_000_000)
            }
            for process in running where !process.isTerminated { _ = process.forceTerminate() }
            if running.contains(where: { !$0.isTerminated }) { try? await Task.sleep(nanoseconds: 500_000_000) }
            NSWorkspace.shared.openApplication(at: url, configuration: NSWorkspace.OpenConfiguration()) { _, error in
                Task { @MainActor in
                    if let error {
                        restartingClients.remove(name)
                        app.notice = "重新启动 \(name) 失败：\(error.localizedDescription)"
                    } else if name == "Codex" {
                        await app.refreshModelConfigurationAfterCodexLaunch()
                        restartingClients.remove(name)
                    } else {
                        app.claudeNeedsReload = false
                        UserDefaults.standard.set(false, forKey: "claudeNeedsReload")
                        restartingClients.remove(name)
                    }
                }
            }
        }
    }

    @ViewBuilder func codexLaunchControl() -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            HStack(spacing: 0) {
                Button(restartingClients.contains("Codex") ? "正在重启…" : "打开应用") { openClient("Codex") }
                    .buttonStyle(.plain)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.leading, 18).padding(.trailing, 14).padding(.vertical, 12)
                    .disabled(app.selectedModels.isEmpty || restartingClients.contains("Codex"))
                Rectangle().fill(Color.white.opacity(0.18)).frame(width: 1, height: 42)
                Menu {
                    Button("选择模型") { picker = true }
                    Button("恢复官方配置", role: .destructive) {
                        do { try app.clearModelSelection() } catch { app.notice = error.localizedDescription }
                    }
                    .disabled(restartingClients.contains("Codex"))
                } label: {
                    Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white).frame(width: 42, height: 42).contentShape(Rectangle())
                }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            }
            .background(purple, in: RoundedRectangle(cornerRadius: 11))
            .opacity(app.selectedModels.isEmpty ? 0.48 : 1)
            Text(app.selectedModels.isEmpty ? "请先选择模型" : "已选 \(app.selectedModels.count) 个模型")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(app.selectedModels.isEmpty ? Color.orange : purple)
        }
    }
    @ViewBuilder func claudeLaunchControl() -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            HStack(spacing: 0) {
                Button(restartingClients.contains("Claude") ? "正在重启…" : "打开应用") { openClient("Claude") }
                    .buttonStyle(.plain)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.leading, 18).padding(.trailing, 14).padding(.vertical, 12)
                    .disabled(app.claudeSelectedModels.isEmpty || restartingClients.contains("Claude"))
                Rectangle().fill(Color.white.opacity(0.18)).frame(width: 1, height: 42)
                Menu {
                    Button("选择模型") { claudePicker = true }
                    Button("恢复官方配置", role: .destructive) {
                        do { try app.clearClaudeSelection() } catch { app.notice = error.localizedDescription }
                    }
                    .disabled(restartingClients.contains("Claude"))
                } label: {
                    Image(systemName: "chevron.down").font(.system(size: 11, weight: .bold))
                        .foregroundStyle(.white).frame(width: 42, height: 42).contentShape(Rectangle())
                }
                .menuStyle(.borderlessButton).menuIndicator(.hidden).fixedSize()
            }
            .background(purple, in: RoundedRectangle(cornerRadius: 11))
            .opacity(app.claudeSelectedModels.isEmpty ? 0.48 : 1)
            Text(app.claudeSelectedModels.isEmpty ? "请先选择模型" : "已选 \(app.claudeSelectedModels.count) 个模型")
                .font(.system(size: 11, weight: .semibold))
                .foregroundStyle(app.claudeSelectedModels.isEmpty ? Color.orange : purple)
        }
    }
    func openCommandLine(_ name: String) {
        guard commandLineCandidates(name).contains(where: {
            FileManager.default.isExecutableFile(atPath: $0.path)
        }) else {
            app.notice = "未找到 \(name) 命令行工具，请先安装后再打开。"
            return
        }
        let resource = name == "Codex" ? "OpenCodex" : "OpenClaude"
        guard let launcher = Bundle.main.url(forResource: resource, withExtension: "command") else {
            app.notice = "命令行启动器缺失，请重新安装 TokenPro。"
            return
        }
        NSWorkspace.shared.open(launcher)
    }
    var home: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("我的客户端").font(.system(size: 14, weight: .semibold))
                Spacer()
            }
            card {
                HStack(spacing: 16) {
                    originalClientIcon("CodexOfficial")
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 9) {
                            Text("Codex 客户端").font(.system(size: 19, weight: .semibold))
                            installBadge(clientInstalled("Codex"), downloadURL: "https://chatgpt.com/download/")
                        }
                        Text("桌面应用 · 独立登录").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    codexLaunchControl()
                }
            }
            card {
                HStack(spacing: 16) {
                    originalClientIcon("ClaudeOfficial")
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 9) {
                            Text("Claude 客户端").font(.system(size: 19, weight: .semibold))
                            installBadge(clientInstalled("Claude"), downloadURL: "https://claude.com/download")
                        }
                        Text("桌面应用 · 独立登录").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    claudeLaunchControl()
                }
            }
            card {
                HStack(spacing: 16) {
                    originalClientIcon("CodexOfficial")
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 9) {
                            Text("Codex 命令行").font(.system(size: 19, weight: .semibold))
                            installBadge(commandLineInstalled("Codex"), downloadURL: "https://learn.chatgpt.com/docs/codex/cli")
                        }
                        Text("命令行工具 · Codex CLI").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("打开 Codex 命令行") { openCommandLine("Codex") }.buttonStyle(SoftButton())
                }
            }
            card {
                HStack(spacing: 16) {
                    originalClientIcon("ClaudeOfficial")
                    VStack(alignment: .leading, spacing: 8) {
                        HStack(spacing: 9) {
                            Text("Claude 命令行").font(.system(size: 19, weight: .semibold))
                            installBadge(commandLineInstalled("Claude"), downloadURL: "https://docs.anthropic.com/en/docs/claude-code/getting-started")
                        }
                        Text("命令行工具 · Claude Code").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("打开 Claude 命令行") { openCommandLine("Claude") }.buttonStyle(SoftButton())
                }
            }
        }
    }
    func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 4, content: content).padding(18).frame(maxWidth: .infinity, alignment: .leading)
            .background(.white.opacity(0.9), in: RoundedRectangle(cornerRadius: 15))
            .overlay(RoundedRectangle(cornerRadius: 15).stroke(.black.opacity(0.075), lineWidth: 1))
    }
    func empty(_ title: String, detail: String, icon: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: icon).font(.system(size: 30)).foregroundStyle(purple.opacity(0.65))
            Text(title).font(.system(size: 17, weight: .semibold))
            Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }.frame(maxWidth: .infinity).padding(.vertical, 65)
    }
}

struct ModelPickerView: View {
    private struct GroupOption: Identifiable {
        let id: Int64
        let name: String
        let platform: String
        let modelCount: Int
        let rateMultiplier: Double?
    }

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var account: NativeAccount
    @Binding var isPresented: Bool
    @State private var models: [PricedModel] = []
    @State private var selection: Set<String> = []
    @State private var selectedGroupID: Int64?
    @State private var search = ""
    @State private var loading = true
    @State private var saving = false
    @State private var compactContext = CompactContext.enabled
    @State private var error: String?
    private let purple = Color(red: 0.40, green: 0.32, blue: 0.94)

    private var groupOptions: [GroupOption] {
        var result: [GroupOption] = []
        var seen = Set<Int64>()
        for model in models {
            guard let id = model.groupID, seen.insert(id).inserted else { continue }
            result.append(GroupOption(
                id: id,
                name: model.groupName,
                platform: model.platform,
                modelCount: models.filter { $0.groupID == id }.count,
                rateMultiplier: model.rateMultiplier
            ))
        }
        return result
    }

    private var displayedGroups: [GroupOption] {
        let groups = selectedGroupID.flatMap { selected in groupOptions.filter { $0.id == selected } } ?? groupOptions
        return groups.filter { !modelsForGroup($0.id).isEmpty }
    }

    private var displayedModels: [PricedModel] {
        displayedGroups.flatMap { modelsForGroup($0.id) }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("选择 Codex 模型").font(.system(size: 22, weight: .bold))
                    Text("可跨分组选择；只显示当前账户可用或订阅未到期的分组。")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Spacer()
                Button { isPresented = false } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain).font(.system(size: 13, weight: .semibold))
            }
            .padding(24)

            Divider()

            if loading {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("正在读取 TokenPro 定价模型…").font(.system(size: 12)).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if models.isEmpty, let error {
                VStack(spacing: 14) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 28)).foregroundStyle(.orange)
                    Text(error).font(.system(size: 13)).multilineTextAlignment(.center)
                    Button("重新加载") { load() }.buttonStyle(SoftButton(primary: true))
                }.padding(40).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Text("分组").font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary)
                        Picker("选择分组", selection: $selectedGroupID) {
                            Text("全部可用分组").tag(Optional<Int64>.none)
                            ForEach(groupOptions) { group in
                                Text(groupPickerTitle(group)).tag(Optional(group.id))
                            }
                        }
                        .labelsHidden()
                        .pickerStyle(.menu)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.black.opacity(0.07)))

                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("搜索 GPT、Grok、Claude、Gemini…", text: $search)
                            .textFieldStyle(.plain)
                        if !search.isEmpty {
                            Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.plain).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(Color.black.opacity(0.045), in: RoundedRectangle(cornerRadius: 10))

                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 18) {
                            ForEach(displayedGroups) { group in
                                VStack(alignment: .leading, spacing: 7) {
                                    HStack(spacing: 8) {
                                        Text(group.name).font(.system(size: 12, weight: .bold))
                                        Text(platformTitle(group.platform))
                                            .font(.system(size: 10, weight: .semibold)).foregroundStyle(purple)
                                        if let rate = group.rateMultiplier {
                                            Text(String(format: "%.2gx", rate))
                                                .font(.system(size: 10, weight: .semibold)).foregroundStyle(.secondary)
                                        }
                                    }.padding(.horizontal, 4)
                                    ForEach(modelsForGroup(group.id)) { model in modelRow(model) }
                                }
                            }
                            if displayedGroups.isEmpty {
                                Text("没有找到匹配的定价模型")
                                    .font(.system(size: 13)).foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity).padding(.top, 50)
                            }
                        }.padding(.vertical, 4)
                    }
                }.padding(.horizontal, 24).padding(.top, 16)
            }

            Divider()
            VStack(spacing: 8) {
                Toggle("精简上下文", isOn: $compactContext)
                    .font(.system(size: 12, weight: .semibold))
                    .frame(maxWidth: .infinity, alignment: .leading)
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .accessibilityLabel("注意")
                    Text("开启后会限制技能目录，并暂停外部应用连接工具，部分功能可能不可用；保留终端和文件编辑。保存并重启后，在新对话中生效。")
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .font(.system(size: 11))
                .frame(maxWidth: .infinity, alignment: .leading)
                if let error, !models.isEmpty {
                    Text(error).font(.system(size: 11)).foregroundStyle(.red).frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack {
                    Text("已选 \(selection.count) 个模型")
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(selection.isEmpty ? .secondary : purple)
                    Button("全选当前") { selectDisplayedModels() }.buttonStyle(.plain).foregroundStyle(purple)
                    Button("清空") { selection.removeAll() }.buttonStyle(.plain).foregroundStyle(purple)
                    Spacer()
                    Button("取消") { isPresented = false }.buttonStyle(SoftButton())
                    Button(saving ? "正在保存…" : (selection.isEmpty ? "恢复官方配置" : "保存到 Codex")) { save() }
                        .buttonStyle(SoftButton(primary: true)).disabled(saving || loading)
                }
            }.padding(18)
        }
        .frame(width: 680, height: 720)
        .background(Color(red: 0.985, green: 0.985, blue: 0.99))
        .task { await loadModels() }
    }

    private func modelsForGroup(_ groupID: Int64) -> [PricedModel] {
        let groupModels = models.filter { $0.groupID == groupID }
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return groupModels }
        return groupModels.filter {
            $0.name.localizedCaseInsensitiveContains(query) ||
            platformTitle($0.platform).localizedCaseInsensitiveContains(query) ||
            $0.groupName.localizedCaseInsensitiveContains(query)
        }
    }

    private func modelRow(_ model: PricedModel) -> some View {
        let chosen = selection.contains(model.id)
        return Button {
            if chosen {
                selection.remove(model.id)
            } else {
                // Codex identifies models by slug, so one slug must map to exactly one billing group.
                for duplicate in models where duplicate.name == model.name { selection.remove(duplicate.id) }
                selection.insert(model.id)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: chosen ? "checkmark.square.fill" : "square")
                    .font(.system(size: 18, weight: .medium)).foregroundStyle(chosen ? purple : Color.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.name).font(.system(size: 13, weight: .semibold)).foregroundStyle(Color.primary)
                    Text(model.groupName + rateSuffix(model))
                        .font(.system(size: 10)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(chosen ? purple.opacity(0.08) : Color.white, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(chosen ? purple.opacity(0.30) : Color.black.opacity(0.07)))
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func selectDisplayedModels() {
        for model in displayedModels {
            for duplicate in models where duplicate.name == model.name { selection.remove(duplicate.id) }
            selection.insert(model.id)
        }
    }

    private func groupPickerTitle(_ group: GroupOption) -> String {
        let rate = group.rateMultiplier.map { String(format: " · %.2gx", $0) } ?? ""
        return "\(group.name)\(rate) · \(group.modelCount) 个"
    }

    private func rateSuffix(_ model: PricedModel) -> String {
        model.rateMultiplier.map { String(format: " · %.2gx", $0) } ?? ""
    }

    private func platformTitle(_ platform: String) -> String {
        switch platform.lowercased() {
        case "openai": return "GPT / OpenAI"
        case "grok", "xai": return "Grok"
        case "anthropic", "claude": return "Claude"
        case "gemini", "google": return "Gemini"
        default: return platform.isEmpty ? "其他模型" : platform.capitalized
        }
    }

    private func load() {
        loading = true
        error = nil
        Task { await loadModels() }
    }

    @MainActor private func loadModels() async {
        loading = true
        error = nil
        do {
            models = try await account.pricedModels()
            let available = Set(models.map(\.id))
            var loaded = Set<String>()
            for saved in app.selectedModels {
                let resolved: PricedModel?
                if available.contains(saved.id) {
                    resolved = models.first(where: { $0.id == saved.id })
                } else {
                    resolved = saved.groups.lazy.compactMap { savedGroup in
                        models.first(where: { $0.name == saved.name && $0.groupName == savedGroup })
                    }.first ?? models.first(where: { $0.name == saved.name })
                }
                // Also clean up older data that contained the same model slug in two groups.
                if let resolved, !loaded.contains(resolved.name) {
                    selection.insert(resolved.id)
                    loaded.insert(resolved.name)
                }
            }
            selectedGroupID = nil
            if models.isEmpty { error = "TokenPro 定价中暂时没有可选模型。" }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    private func save() {
        saving = true
        error = nil
        Task { @MainActor in
            defer { saving = false }
            do {
                let chosen = models.filter { selection.contains($0.id) }
                if chosen.isEmpty {
                    try app.clearModelSelection()
                } else {
                    let active = chosen.first(where: { $0.id == app.activeModel?.id }) ?? chosen[0]
                    guard let groupID = active.groupID else { throw RouterError("当前模型缺少分组信息。") }
                    try account.prepareCodexHookSession()
                    let previous = app.managedCodexKey
                    let managed = try await account.codexManagedAPIKey(initialGroupID: groupID)
                    do {
                        try app.applyModelSelection(chosen, activeModel: active, managedKey: managed, compactContext: compactContext)
                    } catch {
                        if let previous, previous.id == managed.id, previous.groupID != groupID {
                            _ = try? await account.switchCodexKeyGroup(keyID: previous.id, groupID: previous.groupID)
                        }
                        throw error
                    }
                }
                isPresented = false
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

struct ConnectionEditor: View {
    @EnvironmentObject var app: AppModel
    @Environment(\.dismiss) var dismiss
    @State var route: Route
    @State var key = ""
    @State var models: [String] = []
    @State var fetching = false
    @State var error: String?
    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack { Text(app.settings.routes.contains(where: { $0.id == route.id }) ? "编辑连接" : "添加中转站连接").font(.system(size: 21, weight: .bold)); Spacer(); Button { dismiss() } label: { Image(systemName: "xmark") }.buttonStyle(.plain) }
            Text("使用中转站创建的 API Key 接入，同一站点可保存多组令牌与模型。").font(.system(size: 12)).foregroundStyle(.secondary)
            field("连接名称", placeholder: "例如：主力站 · 编程模型", text: $route.name)
            field("API 地址", placeholder: "https://你的中转站/v1", text: $route.baseURL)
            Text("填写 API 基础地址，通常以 /v1 结尾；不要填写 /responses。").font(.system(size: 11)).foregroundStyle(.secondary).padding(.top, -10)
            HStack(spacing: 15) {
                field("令牌备注", placeholder: "默认令牌", text: $route.tokenLabel)
                VStack(alignment: .leading, spacing: 7) {
                    Text("API Key").font(.system(size: 12, weight: .medium))
                    SecureField("编辑时留空保留原令牌", text: $key).textFieldStyle(.roundedBorder)
                }
            }
            HStack(alignment: .bottom) {
                field("模型 ID", placeholder: "填写站点实际提供的模型 ID", text: $route.model)
                Button(fetching ? "获取中…" : "获取模型") { fetchModels() }.buttonStyle(SoftButton()).disabled(fetching)
            }
            if !models.isEmpty {
                Picker("选择模型", selection: $route.model) {
                    Text(route.model.isEmpty ? "请选择" : route.model).tag(route.model)
                    ForEach(models.filter { $0 != route.model }, id: \.self) { Text($0).tag($0) }
                }
            }
            if let error { Text(error).font(.system(size: 12)).foregroundStyle(.red).fixedSize(horizontal: false, vertical: true) }
            HStack {
                Label("令牌仅保存在本机", systemImage: "lock").font(.system(size: 11)).foregroundStyle(.secondary)
                Spacer()
                Button("取消") { dismiss() }.buttonStyle(SoftButton())
                Button("保存连接") {
                    do { try app.save(route, key: key); dismiss() } catch { self.error = error.localizedDescription }
                }.buttonStyle(SoftButton(primary: true)).disabled(fetching)
            }.padding(.top, 8)
        }.padding(30).frame(width: 550)
    }
    func field(_ name: String, placeholder: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 7) {
            Text(name).font(.system(size: 12, weight: .medium))
            TextField(placeholder, text: text).textFieldStyle(.roundedBorder)
        }
    }
    func fetchModels() {
        fetching = true; error = nil
        Task {
            defer { fetching = false }
            do {
                var candidate = route
                if candidate.name.isEmpty { candidate.name = "连接" }
                if candidate.model.isEmpty { candidate.model = "probe" }
                let valid = try candidate.validated()
                let token = key.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? try LocalCredentialStore.read(route.keyID) : key.trimmingCharacters(in: .whitespacesAndNewlines)
                guard let token, !token.isEmpty, !token.contains(where: { $0.isNewline }) else { throw RouterError("请先填写 API Key。") }
                let config = URLSessionConfiguration.ephemeral; config.timeoutIntervalForRequest = 20
                let session = URLSession(configuration: config, delegate: NoRedirect(), delegateQueue: nil)
                defer { session.invalidateAndCancel() }
                var req = URLRequest(url: valid.endpoint("/models")); req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization")
                let (data, response) = try await session.data(for: req)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else { throw RouterError("获取模型失败（HTTP \((response as? HTTPURLResponse)?.statusCode ?? 0)），也可以手动填写模型 ID。") }
                guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any], let list = json["data"] as? [[String: Any]] else { throw RouterError("模型列表格式不兼容，请手动填写。") }
                models = Array(Set(list.compactMap { $0["id"] as? String })).sorted()
                if models.isEmpty { throw RouterError("站点返回了空模型列表，请手动填写。") }
                if route.model.isEmpty { route.model = models[0] }
            } catch { self.error = error.localizedDescription }
        }
    }
}

struct RouterApp: App {
    @StateObject var model = AppModel()
    @StateObject var account = NativeAccount()
    var body: some Scene {
        WindowGroup("TokenPro") { AppRoot().environmentObject(model).environmentObject(account) }
            .defaultSize(width: 1120, height: 760)
            .windowStyle(.hiddenTitleBar)
            .commands { CommandGroup(replacing: .newItem) {} }
    }
}

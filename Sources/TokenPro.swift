import SwiftUI
import WebKit

struct WebsiteKey: Identifiable {
    let id: Int
    let name: String
    let status: String
    let group: String
}

@MainActor final class TokenProSession: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    static let origin = "https://tokenpro.work"
    @Published var loading = false
    @Published var message: String?
    @Published var busy = false
    @Published var keys: [WebsiteKey] = []
    @Published var page = 1
    @Published var hasMore = false
    @Published var showKeys = false
    @Published var clearing = false
    let webView: WKWebView
    private var loaded = false
    private var generation = 0
    override init() {
        let configuration = WKWebViewConfiguration()
        // App-scoped persistent WebKit storage; separate from Safari and Chrome.
        configuration.websiteDataStore = .default()
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
    }
    nonisolated static func trusted(_ url: URL?) -> Bool {
        guard let url, let c = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return false }
        return c.scheme == "https" && c.host == "tokenpro.work" && (c.port == nil || c.port == 443) && c.user == nil && c.password == nil
    }
    func open(_ path: String = "/login") {
        guard !clearing else { return }
        loaded = true; message = nil
        webView.load(URLRequest(url: URL(string: Self.origin + path)!))
    }
    func openIfNeeded() { if !loaded { open() } }
    func refresh() { guard !clearing else { return }; message = nil; webView.reload() }
    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) { loading = true }
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) { loading = false; if clearing { clearStorage() } }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(error) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(error) }
    private func failed(_ error: Error) {
        loading = false
        if (error as NSError).code != NSURLErrorCancelled { message = "网站加载失败，请检查网络后点击刷新。" }
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if clearing && navigationAction.request.url?.absoluteString == "about:blank" { decisionHandler(.allow); return }
        if navigationAction.targetFrame?.isMainFrame == false { decisionHandler(.allow); return }
        guard Self.trusted(navigationAction.request.url) else {
            decisionHandler(.cancel)
            message = "外部页面未在客户端打开。请在系统浏览器中访问外部登录或支付服务。"
            return
        }
        decisionHandler(.allow)
    }
    func webView(_ webView: WKWebView, decidePolicyFor navigationResponse: WKNavigationResponse, decisionHandler: @escaping (WKNavigationResponsePolicy) -> Void) {
        if !navigationResponse.isForMainFrame || Self.trusted(navigationResponse.response.url) || (clearing && navigationResponse.response.url?.absoluteString == "about:blank") {
            decisionHandler(.allow)
        } else {
            decisionHandler(.cancel); loading = false
            message = "已停止打开非 TokenPro 的页面。"
        }
    }
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if Self.trusted(navigationAction.request.url) { webView.load(navigationAction.request) }
        else { message = "外部页面请在系统浏览器中打开。" }
        return nil
    }
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        loading = false; message = "网站进程已停止，请点击刷新。"
    }
    private func call(_ action: String, keyID: Int = 0, page: Int = 1) async throws -> [String: Any] {
        guard Self.trusted(webView.url), !clearing else { throw RouterError("请先打开 TokenPro 并登录。") }
        guard let url = Bundle.main.url(forResource: "TokenProBridge", withExtension: "js") else { throw RouterError("网站接入组件缺失，请重新安装。") }
        let script = try String(contentsOf: url, encoding: .utf8)
        let result = try await webView.callAsyncJavaScript(script, arguments: ["action": action, "keyID": keyID, "page": page], in: nil, contentWorld: .page)
        guard let dictionary = result as? [String: Any] else { throw RouterError("网站返回格式已变化，请在令牌管理页面手动复制 API Key。") }
        return dictionary
    }
    private func friendly(_ error: Error) -> String {
        let raw = String(describing: error)
        if raw.contains("LOGIN_REQUIRED") { return "请先在下方网站登录。登录过期时，请刷新网站或重新登录后再读取。" }
        if raw.contains("KEY_MASKED") { return "网站没有返回完整 API Key，请在网站令牌页面复制完整令牌，然后手动添加连接。" }
        if raw.contains("KEY_NOT_ACTIVE") { return "这个令牌当前不可用，请选择启用中的令牌。" }
        if raw.contains("WRONG_ORIGIN") { return "请回到 TokenPro 网站后再操作。" }
        if raw.contains("HTTP_403") { return "当前账户没有读取此令牌的权限。" }
        return "读取失败，请确认已登录后重试；也可以在网站令牌页面复制 Key，手动添加连接。"
    }
    func list(page requestedPage: Int = 1) {
        guard !busy, !clearing else { return }
        busy = true; message = nil
        let startedGeneration = generation
        Task {
            defer { busy = false }
            do {
                let result = try await call("list", page: requestedPage)
                guard generation == startedGeneration, !clearing else { return }
                guard let rows = result["items"] as? [[String: Any]] else { throw RouterError("FORMAT_CHANGED") }
                keys = rows.compactMap { row in
                    guard let id = row["id"] as? Int, let name = row["name"] as? String, let status = row["status"] as? String else { return nil }
                    return WebsiteKey(id: id, name: name, status: status, group: row["group"] as? String ?? "")
                }
                page = requestedPage; hasMore = result["hasMore"] as? Bool ?? false; showKeys = true
            } catch { if generation == startedGeneration { message = friendly(error) } }
        }
    }
    func importKey(_ id: Int, completion: @escaping (Route, String) -> Void) {
        guard !busy, !clearing else { return }
        busy = true; message = nil
        let startedGeneration = generation
        Task {
            defer { busy = false }
            do {
                let result = try await call("import", keyID: id)
                guard generation == startedGeneration, !clearing else { return }
                guard let key = result["key"] as? String, let name = result["name"] as? String else { throw RouterError("FORMAT_CHANGED") }
                showKeys = false
                let route = Route(name: "TokenPro · " + name, baseURL: Self.origin + "/v1", model: "", tokenLabel: name)
                completion(route, key)
            } catch { if generation == startedGeneration { message = friendly(error); showKeys = false } }
        }
    }
    func clearLogin() {
        guard !clearing else { return }
        generation += 1; clearing = true; showKeys = false; keys = []; message = nil
        webView.stopLoading()
        // Destroy the logged-in document before removing storage so its JS cannot repopulate tokens.
        webView.load(URLRequest(url: URL(string: "about:blank")!))
    }
    private func clearStorage() {
        let store = webView.configuration.websiteDataStore
        store.removeData(ofTypes: WKWebsiteDataStore.allWebsiteDataTypes(), modifiedSince: .distantPast) { [weak self] in
            DispatchQueue.main.async {
                guard let self else { return }
                self.clearing = false; self.open()
                self.message = "本机网站登录数据已清除。已保存的 API 连接不受影响；服务器上的其他会话仍保留。"
            }
        }
    }
}

struct TokenProWebView: NSViewRepresentable {
    let session: TokenProSession
    func makeNSView(context: Context) -> WKWebView { session.openIfNeeded(); return session.webView }
    func updateNSView(_ nsView: WKWebView, context: Context) {}
}

struct TokenProPage: View {
    @ObservedObject var session: TokenProSession
    let importConnection: (Route, String) -> Void
    @State private var clearPrompt = false
    @State private var pendingImport: (Route, String)?
    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                Label("tokenpro.work", systemImage: "lock.fill").font(.system(size: 12, weight: .medium))
                Spacer()
                Button("登录页") { session.open() }.buttonStyle(SoftButton())
                Button("令牌管理") { session.open("/keys") }.buttonStyle(SoftButton())
                Button { session.refresh() } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(SoftButton())
                Button("导入网站令牌") { session.list() }.buttonStyle(SoftButton(primary: true)).disabled(session.busy || session.loading)
                Menu {
                    Button("清除本机网站登录") { clearPrompt = true }
                    Button("在系统浏览器打开网站") { NSWorkspace.shared.open(URL(string: TokenProSession.origin)!) }
                } label: { Image(systemName: "ellipsis") }.menuStyle(.borderlessButton).frame(width: 24)
            }.disabled(session.clearing).padding(.horizontal, 18).padding(.vertical, 12)
            if session.loading || session.busy || session.clearing { ProgressView().progressViewStyle(.linear) }
            if let message = session.message {
                HStack(alignment: .top) {
                    Image(systemName: "info.circle")
                    Text(message).fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    Button { session.message = nil } label: { Image(systemName: "xmark") }.buttonStyle(.plain)
                }.font(.system(size: 12)).padding(12).background(Color.orange.opacity(0.09))
            }
            Divider()
            TokenProWebView(session: session)
            Divider()
            Text("这是 TokenPro 原站。登录状态和选中的 API Key 仅保存在本机。").font(.system(size: 11)).foregroundStyle(.secondary).padding(12)
        }
        .alert("清除本机网站登录？", isPresented: $clearPrompt) {
            Button("取消", role: .cancel) {}
            Button("清除") { session.clearLogin() }
        } message: { Text("将清除本应用保存的网站 Cookie 和登录数据。已导入的 API 连接仍保留。") }
        .sheet(isPresented: $session.showKeys, onDismiss: {
            if let pending = pendingImport {
                pendingImport = nil
                importConnection(pending.0, pending.1)
            }
        }) {
            VStack(alignment: .leading, spacing: 18) {
                HStack {
                    Text("选择 TokenPro 令牌").font(.system(size: 20, weight: .bold))
                    Spacer()
                    Button { session.showKeys = false; session.keys = [] } label: { Image(systemName: "xmark") }.buttonStyle(.plain).disabled(session.busy)
                }
                Text("只导入你选中的令牌，随后选择模型并保存连接。").font(.system(size: 12)).foregroundStyle(.secondary)
                if session.keys.isEmpty { Text("此账户还没有令牌，请先在网站的令牌管理中创建。").padding(.vertical, 25) }
                ScrollView {
                    VStack(spacing: 10) {
                        ForEach(session.keys) { key in
                            HStack {
                                VStack(alignment: .leading, spacing: 5) {
                                    Text(key.name).font(.system(size: 14, weight: .semibold))
                                    Text([key.group, key.status == "active" ? "已启用" : "不可用"].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.system(size: 11)).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Button("导入") { session.importKey(key.id) { route, key in pendingImport = (route, key) } }.buttonStyle(SoftButton(primary: true)).disabled(session.busy || key.status != "active")
                            }.padding(13).background(Color.black.opacity(0.035), in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }.frame(maxHeight: 360)
                HStack {
                    if session.busy { ProgressView().controlSize(.small) }
                    Spacer()
                    Button("上一页") { session.list(page: session.page - 1) }.disabled(session.page <= 1 || session.busy)
                    Text("第 \(session.page) 页").font(.system(size: 12)).foregroundStyle(.secondary)
                    Button("下一页") { session.list(page: session.page + 1) }.disabled(!session.hasMore || session.busy)
                }
            }.padding(28).frame(width: 490).interactiveDismissDisabled(session.busy)
        }
    }
}

@MainActor final class RechargeWebSession: NSObject, ObservableObject, WKNavigationDelegate, WKUIDelegate {
    @Published var loading = true
    @Published var message: String?
    @Published var currentURL = TokenProSession.origin
    let webView: WKWebView
    private let credentials: RechargeCredentials
    private let initialPath: String
    private var seeded = false

    init(credentials: RechargeCredentials, initialPath: String = "/purchase") {
        self.credentials = credentials
        self.initialPath = initialPath
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.load(URLRequest(url: URL(string: TokenProSession.origin + "/login")!))
    }

    func refresh() { webView.reload() }

    func webView(_ webView: WKWebView, didStartProvisionalNavigation navigation: WKNavigation!) {
        loading = true
        currentURL = webView.url?.absoluteString ?? currentURL
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        currentURL = webView.url?.absoluteString ?? currentURL
        guard !seeded, TokenProSession.trusted(webView.url) else {
            loading = false
            installVerifiedPaymentResultGuard()
            return
        }
        do {
            let payloadData = try JSONSerialization.data(withJSONObject: ["access": credentials.accessToken, "user": credentials.user])
            guard let payload = String(data: payloadData, encoding: .utf8) else { throw RouterError("无法准备充值会话。") }
            let script = """
            (() => {
              const payload = \(payload);
              localStorage.setItem('auth_token', payload.access);
              localStorage.setItem('auth_user', JSON.stringify(payload.user));
              localStorage.removeItem('refresh_token');
              localStorage.removeItem('token_expires_at');
            })();
            """
            seeded = true
            webView.evaluateJavaScript(script) { [weak self] _, error in
                guard let self else { return }
                if let error {
                    self.loading = false
                    self.message = "充值账户同步失败：\(error.localizedDescription)"
                    return
                }
                self.webView.load(URLRequest(url: URL(string: TokenProSession.origin + self.initialPath)!))
            }
        } catch {
            loading = false
            message = error.localizedDescription
        }
    }

    private func installVerifiedPaymentResultGuard() {
        guard TokenProSession.trusted(webView.url), webView.url?.path == "/payment/stripe" else { return }
        let script = """
        (() => {
          if (window.__tokenproVerifiedPaymentResultGuard) return;
          window.__tokenproVerifiedPaymentResultGuard = true;
          const orderID = new URLSearchParams(window.location.search).get('order_id');
          if (!orderID || !/^\\d+$/.test(orderID)) return;
          let redirected = false;
          const verify = () => {
            if (redirected) return;
            const successClaim = Array.from(document.querySelectorAll('p,h1,h2,h3'))
              .some(node => /支付成功|payment successful|payment succeeded/i.test((node.textContent || '').trim()));
            if (!successClaim) return;
            redirected = true;
            window.location.replace('/payment/result?order_id=' + encodeURIComponent(orderID));
          };
          new MutationObserver(verify).observe(document.documentElement, { subtree: true, childList: true, characterData: true });
          verify();
        })();
        """
        webView.evaluateJavaScript(script) { [weak self] _, error in
            if let error { self?.message = "支付状态保护未能加载：\(error.localizedDescription)" }
        }
    }

    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { failed(error) }
    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) { failed(error) }
    private func failed(_ error: Error) {
        loading = false
        if (error as NSError).code != NSURLErrorCancelled { message = "充值页面加载失败，请检查网络后重试。" }
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else { decisionHandler(.cancel); return }
        if ["https", "http", "about"].contains(url.scheme?.lowercased() ?? "") { decisionHandler(.allow) }
        else { NSWorkspace.shared.open(url); decisionHandler(.cancel) }
    }

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration, for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        if let url = navigationAction.request.url { webView.load(URLRequest(url: url)) }
        return nil
    }
}

struct RechargeWebView: NSViewRepresentable {
    let session: RechargeWebSession
    func makeNSView(context: Context) -> WKWebView { session.webView }
    func updateNSView(_ nsView: WKWebView, context: Context) {}
}

struct RechargePage: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var session: RechargeWebSession
    private let email: String
    private let title: String

    init(credentials: RechargeCredentials, title: String = "TokenPro 充值", initialPath: String = "/purchase") {
        email = credentials.user["email"] as? String ?? "当前 TokenPro 账户"
        self.title = title
        _session = StateObject(wrappedValue: RechargeWebSession(credentials: credentials, initialPath: initialPath))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.system(size: 16, weight: .bold))
                    Text("当前登录账户：\(email)").font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Button { session.refresh() } label: { Image(systemName: "arrow.clockwise") }.buttonStyle(SoftButton()).accessibilityLabel("刷新充值页面")
                Button("关闭") { dismiss() }.buttonStyle(SoftButton())
            }.padding(.horizontal, 18).padding(.vertical, 12)
            if session.loading { ProgressView().progressViewStyle(.linear) }
            if let message = session.message {
                HStack { Image(systemName: "exclamationmark.triangle"); Text(message); Spacer() }
                    .font(.system(size: 12)).foregroundStyle(.orange).padding(10)
            }
            Divider()
            RechargeWebView(session: session)
        }
        .frame(minWidth: 900, minHeight: 680)
    }
}

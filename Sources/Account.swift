import Foundation
import SwiftUI

enum AccountValidation {
    static func email(_ value: String, registration: Bool, settings: [String: Any]) throws -> String {
        let email = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard email.range(of: "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$", options: .regularExpression) != nil else { throw RouterError("请填写完整、有效的邮箱地址。") }
        if registration, settings["registration_email_domain_quota_enabled"] as? Bool != true,
           let suffixes = settings["registration_email_suffix_whitelist"] as? [String], !suffixes.isEmpty {
            let domain = String(email.split(separator: "@").last!).lowercased()
            let allowed = suffixes.contains { $0.lowercased().trimmingCharacters(in: CharacterSet(charactersIn: "@")) == domain }
            guard allowed else {
                if domain == "gamil.com" { throw RouterError("邮箱后缀写成了 @gamil.com；如果使用 Gmail，请改为 @gmail.com。") }
                throw RouterError("这个邮箱域名暂未开放注册，请检查邮箱拼写或使用网站支持的邮箱。")
            }
        }
        return email
    }
}

struct BackendFailure: LocalizedError {
    let status: Int
    let errorDescription: String?
    init(_ status: Int, _ message: String) { self.status = status; self.errorDescription = message }
}

final class BackendClient: @unchecked Sendable {
    let base: URL
    let session: URLSession
    init(base: URL = URL(string: "https://tokenpro.work/api/v1")!, configuration: URLSessionConfiguration = .ephemeral) {
        self.base = base
        configuration.timeoutIntervalForRequest = 30
        configuration.timeoutIntervalForResource = 45
        configuration.httpCookieStorage = nil
        session = URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
    }
    func request(_ path: String, method: String = "GET", body: [String: Any]? = nil, token: String? = nil) async throws -> [String: Any] {
        let value = try await requestValue(path, method: method, body: body, token: token)
        guard let object = value as? [String: Any] else { throw RouterError("服务器返回格式不兼容。") }
        return object
    }
    func requestArray(_ path: String, method: String = "GET", body: [String: Any]? = nil, token: String? = nil) async throws -> [[String: Any]] {
        let value = try await requestValue(path, method: method, body: body, token: token)
        guard let array = value as? [[String: Any]] else { throw RouterError("服务器返回列表格式不兼容。") }
        return array
    }
    private func requestValue(_ path: String, method: String, body: [String: Any]?, token: String?) async throws -> Any {
        var request = URLRequest(url: URL(string: base.absoluteString + path)!)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("zh-CN", forHTTPHeaderField: "Accept-Language")
        request.setValue("1", forHTTPHeaderField: "X-User-UI-Request")
        if let token { request.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
        if let body { request.httpBody = try JSONSerialization.data(withJSONObject: body) }
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw RouterError("服务器没有返回有效响应。") }
        guard (200..<300).contains(http.statusCode) else {
            let messages = [400:"提交信息有误，请检查邮箱、验证码或邀请码。",401:"邮箱或密码错误，或登录已过期。",403:"当前操作未获允许，请检查账户状态。",404:"后端接口不可用。",409:"该邮箱可能已注册，请尝试登录。",429:"操作过于频繁，请稍后重试。"]
            throw BackendFailure(http.statusCode, messages[http.statusCode] ?? "服务器暂时不可用（HTTP \(http.statusCode)）。")
        }
        guard let json = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw RouterError("服务器返回格式不兼容。") }
        if let code = json["code"] as? Int {
            guard code == 0 || code == 200 else { throw BackendFailure(code, "服务器未接受此操作，请检查输入后重试。") }
            return json["data"] ?? [:]
        }
        return json
    }
}

struct AccountTokens: Codable {
    var access: String
    var refresh: String?
    init(_ json: [String: Any]) throws {
        guard let access = json["access_token"] as? String, !access.isEmpty else { throw RouterError("登录响应缺少凭据。") }
        self.access = access; self.refresh = json["refresh_token"] as? String
    }
}

struct RechargeCredentials: Identifiable {
    let id = UUID()
    let accessToken: String
    let user: [String: Any]
}

struct CodexManagedKey {
    let id: Int64
    let groupID: Int64
    let key: String
}

@MainActor final class NativeAccount: ObservableObject {
    @Published var signedIn = false
    @Published var localMode = false
    @Published var loading = true
    @Published var restorePending = false
    @Published var retryRestoreAutomatically = false
    private var startingSession = false
    @Published var busy = false
    @Published var settingsReady = false
    @Published var settings: [String: Any] = [:]
    @Published var user: [String: Any] = [:]
    @Published var message: String?
    @Published var pending2FA: String?
    @Published var keys: [WebsiteKey] = []
    @Published var keyPage = 1
    @Published var hasMore = false
    @Published var showKeys = false
    let client: BackendClient
    private let readStored: () throws -> String?
    private let writeStored: (String) throws -> Void
    private let removeStored: () throws -> Void
    init(client: BackendClient = BackendClient(), read: @escaping () throws -> String? = { try HelperCredentialStore.readSession() }, write: @escaping (String) throws -> Void = { try HelperCredentialStore.writeSession($0) }, remove: @escaping () throws -> Void = { try HelperCredentialStore.removeSession() }) {
        self.client = client; readStored = read; writeStored = write; removeStored = remove
    }
    private var tokens: AccountTokens?
    private var generation = 0
    private var refreshTask: Task<[String: Any], Error>?
    var email: String { user["email"] as? String ?? "" }
    var balance: String {
        if let number = user["balance"] as? NSNumber { return String(format: "$%.2f", number.doubleValue) }
        return "—"
    }
    var registrationEnabled: Bool { settings["registration_enabled"] as? Bool ?? false }
    var verifyRequired: Bool { settings["email_verify_enabled"] as? Bool ?? false }
    var inviteRequired: Bool { settings["invitation_code_enabled"] as? Bool ?? false }
    var captchaRequired: Bool { ["turnstile_enabled", "tencent_captcha_enabled", "aliyun_captcha_enabled"].contains { settings[$0] as? Bool == true } }
    var agreementRequired: Bool { settings["login_agreement_enabled"] as? Bool ?? true }
    var agreements: [[String: Any]] { settings["login_agreement_documents"] as? [[String: Any]] ?? [] }
    func start(loadPublicSettings: Bool = true) async {
        guard !startingSession, !signedIn else { return }
        startingSession = true; loading = true; message = nil; retryRestoreAutomatically = false
        let run = generation
        defer { loading = false; startingSession = false }
        do {
            if let value = try readStored(), let data = value.data(using: .utf8) {
                tokens = try JSONDecoder().decode(AccountTokens.self, from: data)
                restorePending = true
                let restored = try await authenticated("/auth/me")
                guard generation == run else { return }
                user = restored; signedIn = true; restorePending = false; message = nil
            } else { restorePending = false }
        } catch {
            guard generation == run else { return }
            if let failure = error as? BackendFailure, [401, 403].contains(failure.status) {
                restorePending = false
                message = "登录凭据已失效，请重新登录。"
            } else if error is DecodingError {
                restorePending = false
                message = "本机登录信息无法读取，请重新登录。"
            } else {
                restorePending = true
                retryRestoreAutomatically = error is URLError || (error as? BackendFailure).map { $0.status >= 500 || $0.status == 429 } == true
                message = "暂时无法恢复登录，已保留登录信息。请检查网络后重试。"
            }
        }
        // Restoring an existing session does not depend on the public settings endpoint.
        if loadPublicSettings, !settingsReady { await loadSettings() }
    }
    func loadSettings() async {
        do { settings = try await client.request("/settings/public"); settingsReady = true }
        catch { settingsReady = false; if message == nil && !signedIn { message = "无法连接 TokenPro 后端，请检查网络后重试。" } }
    }
    private func persist(_ next: AccountTokens) throws {
        let value = String(data: try JSONEncoder().encode(next), encoding: .utf8)!
        try writeStored(value)
        tokens = next
    }
    func prepareCodexHookSession() throws {
        guard let tokens else { throw RouterError("TokenPro 登录状态不可用，请重新登录。") }
        let value = String(data: try JSONEncoder().encode(tokens), encoding: .utf8)!
        try HelperCredentialStore.writeSession(value)
    }
    func authenticated(_ path: String) async throws -> [String: Any] {
        try await authenticatedRequest(path)
    }
    func authenticatedRequest(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> [String: Any] {
        guard let current = tokens else { throw RouterError("请先登录账户。") }
        let run = generation
        do {
            let result = try await client.request(path, method: method, body: body, token: current.access)
            guard generation == run else { throw CancellationError() }
            return result
        }
        catch let error as BackendFailure where error.status == 401 {
            guard let refresh = tokens?.refresh else { signedIn = false; throw error }
            if refreshTask == nil { refreshTask = Task { try await self.client.request("/auth/refresh", method: "POST", body: ["refresh_token": refresh]) } }
            let task = refreshTask!
            do {
                let result = try await task.value
                guard generation == run else { throw CancellationError() }
                var next = try AccountTokens(result)
                if next.refresh == nil { next.refresh = refresh }
                try persist(next); refreshTask = nil
                let value = try await client.request(path, method: method, body: body, token: next.access)
                guard generation == run else { throw CancellationError() }
                return value
            } catch {
                refreshTask = nil
                if generation == run, let failure = error as? BackendFailure, [401, 403].contains(failure.status) { signedIn = false }
                throw error
            }
        }
    }

    func authenticatedArray(_ path: String) async throws -> [[String: Any]] {
        guard let current = tokens else { throw RouterError("请先登录账户。") }
        do { return try await client.requestArray(path, token: current.access) }
        catch let error as BackendFailure where error.status == 401 {
            _ = try await authenticated("/auth/me")
            guard let refreshed = tokens else { throw error }
            return try await client.requestArray(path, token: refreshed.access)
        }
    }

    func pricedModels() async throws -> [PricedModel] {
        let result = try await authenticated("/model-plaza")
        guard let groups = result["groups"] as? [[String: Any]] else { throw RouterError("定价模型列表格式不兼容。") }
        let availableGroups = try await authenticatedArray("/groups/available")
        let availableByID = Dictionary(uniqueKeysWithValues: availableGroups.compactMap { group -> (Int64, [String: Any])? in
            guard let id = integerID(group["id"]) else { return nil }
            return (id, group)
        })
        var models: [PricedModel] = []
        for group in groups {
            guard let groupID = integerID(group["id"]), let available = availableByID[groupID] else { continue }
            let groupName = available["name"] as? String ?? group["name"] as? String ?? "TokenPro"
            let groupPlatform = group["platform"] as? String ?? "other"
            let rateMultiplier = decimal(available["rate_multiplier"]) ?? decimal(group["rate_multiplier"])
            guard let rows = group["models"] as? [[String: Any]] else { continue }
            for row in rows {
                guard let name = row["name"] as? String, !name.isEmpty,
                      let pricing = row["pricing"], !(pricing is NSNull) else { continue }
                let platform = row["platform"] as? String ?? groupPlatform
                models.append(PricedModel(name: name, platform: platform, groups: [groupName], groupID: groupID, rateMultiplier: rateMultiplier))
            }
        }
        let platformOrder = ["openai": 0, "grok": 1, "anthropic": 2, "gemini": 3]
        return models.sorted {
            let left = platformOrder[$0.platform.lowercased()] ?? 99
            let right = platformOrder[$1.platform.lowercased()] ?? 99
            if left != right { return left < right }
            if $0.groupName != $1.groupName { return $0.groupName.localizedStandardCompare($1.groupName) == .orderedAscending }
            return $0.name.localizedStandardCompare($1.name) == .orderedAscending
        }
    }

    func codexManagedAPIKey(initialGroupID groupID: Int64) async throws -> CodexManagedKey {
        try await managedAPIKey(named: "TokenPro · Codex", clientName: "Codex", initialGroupID: groupID)
    }

    func claudeManagedAPIKey(initialGroupID groupID: Int64) async throws -> CodexManagedKey {
        try await managedAPIKey(named: "TokenPro · Claude", clientName: "Claude", initialGroupID: groupID)
    }

    private func managedAPIKey(named keyName: String, clientName: String, initialGroupID groupID: Int64) async throws -> CodexManagedKey {
        try await requireAvailableGroup(groupID)
        for page in 1...100 {
            let list = try await authenticated("/keys?page=\(page)&page_size=100")
            let items = list["items"] as? [[String: Any]] ?? []
            if let item = items.first(where: { $0["name"] as? String == keyName }) {
                guard item["status"] as? String == "active", let id = integerID(item["id"]) else {
                    throw RouterError("TokenPro 专用 \(clientName) Key 已停用，请在后台启用后重试。")
                }
                let currentGroupID = integerID(item["group_id"]) ?? integerID((item["group"] as? [String: Any])?["id"])
                if currentGroupID != groupID { _ = try await switchManagedKeyGroup(keyID: id, groupID: groupID, expectedName: keyName, clientName: clientName) }
                let detail = try await authenticated("/keys/\(id)")
                guard let key = usableAPIKey(detail["key"]) else { throw RouterError("无法读取 TokenPro 专用 \(clientName) Key，请在后台删除该 Key 后重试。") }
                return CodexManagedKey(id: id, groupID: groupID, key: key)
            }
            if items.count < 100 { break }
        }
        let created = try await authenticatedRequest("/keys", method: "POST", body: ["name": keyName, "group_id": groupID])
        guard let id = integerID(created["id"]) else { throw RouterError("TokenPro 未返回专用 Key 的编号。") }
        guard let key = usableAPIKey(created["key"]) else { throw RouterError("TokenPro 未返回可用的客户端令牌。") }
        return CodexManagedKey(id: id, groupID: groupID, key: key)
    }

    func switchCodexKeyGroup(keyID: Int64, groupID: Int64) async throws -> ManagedCodexKeyState {
        try await switchManagedKeyGroup(keyID: keyID, groupID: groupID, expectedName: "TokenPro · Codex", clientName: "Codex")
    }

    func switchClaudeKeyGroup(keyID: Int64, groupID: Int64) async throws -> ManagedCodexKeyState {
        try await switchManagedKeyGroup(keyID: keyID, groupID: groupID, expectedName: "TokenPro · Claude", clientName: "Claude")
    }

    private func switchManagedKeyGroup(keyID: Int64, groupID: Int64, expectedName: String, clientName: String) async throws -> ManagedCodexKeyState {
        try await requireAvailableGroup(groupID)
        let before = try await authenticated("/keys/\(keyID)")
        guard before["name"] as? String == expectedName else {
            throw RouterError("已停止切换：这个 Key 不是 TokenPro 创建的 \(clientName) 专用 Key。")
        }
        _ = try await authenticatedRequest("/keys/\(keyID)", method: "PUT", body: ["group_id": groupID])
        let confirmed = try await authenticated("/keys/\(keyID)")
        let confirmedGroupID = integerID(confirmed["group_id"])
            ?? integerID((confirmed["group"] as? [String: Any])?["id"])
        guard confirmedGroupID == groupID else { throw RouterError("分组切换未被服务器确认，请稍后重试。") }
        return ManagedCodexKeyState(id: keyID, groupID: groupID)
    }

    private func requireAvailableGroup(_ groupID: Int64) async throws {
        let groups = try await authenticatedArray("/groups/available")
        guard groups.contains(where: { integerID($0["id"]) == groupID }) else {
            throw RouterError("该分组当前不可用；如果是订阅分组，请检查订阅是否仍在有效期内。")
        }
    }

    private func integerID(_ value: Any?) -> Int64? {
        if let number = value as? NSNumber { return number.int64Value }
        if let value = value as? Int64 { return value }
        if let value = value as? Int { return Int64(value) }
        return nil
    }

    private func decimal(_ value: Any?) -> Double? {
        if let number = value as? NSNumber { return number.doubleValue }
        if let value = value as? String { return Double(value) }
        return nil
    }

    private func usableAPIKey(_ value: Any?) -> String? {
        guard let key = value as? String, key.count >= 8,
              !key.contains("*"), !key.contains("…"), !key.contains("..."),
              !key.contains(where: { $0.isWhitespace }) else { return nil }
        return key
    }
    func rechargeCredentials() async throws -> RechargeCredentials {
        let currentUser = try await authenticated("/auth/me")
        user = currentUser
        guard let accessToken = tokens?.access, !accessToken.isEmpty else { throw RouterError("当前登录凭据不可用，请重新登录。") }
        return RechargeCredentials(accessToken: accessToken, user: currentUser)
    }
    func authenticate(email: String, password: String, register: Bool, code: String, invitation: String, accepted: Bool) async -> Bool {
        guard !busy else { return false }; busy = true; message = nil
        defer { busy = false }
        do {
            guard settingsReady else { throw RouterError("请先加载后端配置。") }
            guard !agreementRequired || accepted else { throw RouterError("请阅读并同意用户协议。") }
            guard !captchaRequired else { throw RouterError("后端已开启人机验证，请先在网站完成此操作。") }
            let validEmail = try AccountValidation.email(email, registration: register, settings: settings)
            guard !password.isEmpty else { throw RouterError("请输入密码。") }
            var body: [String: Any] = ["email": validEmail, "password": password]
            if register {
                guard registrationEnabled else { throw RouterError("后端目前未开放注册。") }
                guard password.count >= 6 else { throw RouterError("密码至少需要 6 个字符。") }
                if verifyRequired { guard !code.isEmpty else { throw RouterError("请输入邮箱验证码。") }; body["verify_code"] = code }
                if inviteRequired { guard !invitation.isEmpty else { throw RouterError("请输入邀请码。") }; body["invitation_code"] = invitation }
            }
            let result = try await client.request(register ? "/auth/register" : "/auth/login", method: "POST", body: body)
            if result["requires_2fa"] as? Bool == true {
                guard let temp = result["temp_token"] as? String else { throw RouterError("两步验证响应异常。") }
                pending2FA = temp; return false
            }
            try finishLogin(result); return true
        } catch { message = error.localizedDescription; return false }
    }
    private func finishLogin(_ result: [String: Any]) throws {
        try persist(AccountTokens(result))
        user = result["user"] as? [String: Any] ?? [:]
        pending2FA = nil; signedIn = true; localMode = false; restorePending = false
    }
    func verify2FA(_ code: String) async -> Bool {
        guard !busy, let temp = pending2FA else { return false }
        busy = true; message = nil; defer { busy = false }
        do { let result = try await client.request("/auth/login/2fa", method: "POST", body: ["temp_token": temp, "totp_code": code]); try finishLogin(result); return true }
        catch { message = error.localizedDescription; return false }
    }
    func sendCode(email: String, accepted: Bool) async -> Int? {
        guard !busy else { return nil }; busy = true; message = nil; defer { busy = false }
        do {
            guard settingsReady, registrationEnabled, verifyRequired, !captchaRequired else { throw RouterError("当前后端不支持直接发送注册验证码。") }
            guard !agreementRequired || accepted else { throw RouterError("请先阅读并同意用户协议。") }
            let validEmail = try AccountValidation.email(email, registration: true, settings: settings)
            let result = try await client.request("/auth/send-verify-code", method: "POST", body: ["email": validEmail])
            message = "验证码已发送，请查看邮箱。"
            return max(1, min(result["countdown"] as? Int ?? 60, 600))
        } catch { message = error.localizedDescription; return nil }
    }
    func listKeys(page: Int = 1) {
        guard !busy else { return }; busy = true; message = nil
        Task {
            defer { busy = false }
            do {
                let result = try await authenticated("/keys?page=\(page)&page_size=100")
                guard let items = result["items"] as? [[String: Any]] else { throw RouterError("令牌列表格式不兼容。") }
                keys = items.compactMap { item in
                    guard let id = item["id"] as? Int, let name = item["name"] as? String else { return nil }
                    return WebsiteKey(id: id, name: name, status: item["status"] as? String ?? "", group: (item["group"] as? [String: Any])?["name"] as? String ?? "")
                }
                keyPage = page; hasMore = (result["total"] as? Int ?? 0) > page * 100; showKeys = true
            } catch { message = error.localizedDescription }
        }
    }
    func importKey(_ id: Int, completion: @escaping (Route, String) -> Void) {
        guard !busy else { return }; busy = true; message = nil
        Task {
            defer { busy = false }
            do {
                let result = try await authenticated("/keys/\(id)")
                guard result["id"] as? Int == id, result["status"] as? String == "active", let key = result["key"] as? String,
                      key.count >= 8, !key.contains("*"), !key.contains("…"), !key.contains("..."), !key.contains(where: { $0.isWhitespace }) else { throw RouterError("这个令牌不可用或已被隐藏，请在网站查看完整 Key。") }
                let name = result["name"] as? String ?? "默认令牌"
                completion(Route(name: "TokenPro · " + name, baseURL: "https://tokenpro.work/v1", model: "", tokenLabel: name), key)
                showKeys = false
            } catch { message = error.localizedDescription; showKeys = false }
        }
    }
    func logout() async {
        guard !busy else { return }; busy = true; defer { busy = false }
        let previous = tokens
        do { try removeStored() }
        catch { message = "无法清除本机登录凭据，请稍后重试。"; return }
        try? HelperCredentialStore.removeModelToken()
        generation += 1; refreshTask?.cancel(); refreshTask = nil
        tokens = nil; user = [:]; keys = []; pending2FA = nil; signedIn = false; restorePending = false; localMode = false; message = nil
        if let refresh = previous?.refresh { _ = try? await client.request("/auth/logout", method: "POST", body: ["refresh_token": refresh]) }
    }
}

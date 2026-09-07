import Foundation

final class AccountStub: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, [String: Any]))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, json) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: try JSONSerialization.data(withJSONObject: json)); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

@MainActor func runAccountTests() async {
    var count = 0
    func check(_ value: Bool, _ title: String) throws { guard value else { throw RouterError("FAIL: " + title) }; count += 1 }
    var stored: String?
    var calls: [(String, String, [String: Any], String?)] = []
    var twoFactor = false
    var expire = false
    var nextFailure = 0
    var pathFailures: [String: Int] = [:]
    var managedKeyExists = false
    var managedGroupID: Int64 = 16
    var claudeKeyExists = false
    var claudeGroupID: Int64 = 62
    let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [AccountStub.self]
    let client = BackendClient(base: URL(string: "https://fixture.invalid/api/v1")!, configuration: config)
    let account = NativeAccount(client: client, read: { stored }, write: { stored = $0 }, remove: { stored = nil })
    AccountStub.handler = { req in
        var data = req.httpBody ?? Data()
        if let stream = req.httpBodyStream { stream.open(); defer { stream.close() }; var buffer = [UInt8](repeating: 0, count: 1024); while stream.hasBytesAvailable { let n = stream.read(&buffer, maxLength: buffer.count); if n <= 0 { break }; data.append(contentsOf: buffer.prefix(n)) } }
        let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        let path = req.url!.path
        calls.append((path, req.httpMethod!, body, req.value(forHTTPHeaderField: "Authorization")))
        if let status = pathFailures.removeValue(forKey: path) { return (status, [:]) }
        if nextFailure != 0 { let status = nextFailure; nextFailure = 0; return (status, ["message": "fixture failure"]) }
        var result: [String: Any] = [:]
        switch path {
        case "/api/v1/settings/public": result = ["registration_enabled": true, "email_verify_enabled": true, "login_agreement_enabled": true, "invitation_code_enabled": true]
        case "/api/v1/auth/login":
            if twoFactor { result = ["requires_2fa": true, "temp_token": "temporary-2fa"] }
            else { result = ["access_token": "fixture-access", "refresh_token": "fixture-refresh", "user": ["email": "fixture@example.com"]] }
        case "/api/v1/auth/register", "/api/v1/auth/login/2fa": result = ["access_token": "fixture-access", "refresh_token": "fixture-refresh", "user": ["email": "fixture@example.com"]]
        case "/api/v1/auth/send-verify-code": result = ["countdown": 45]
        case "/api/v1/auth/me":
            if expire && req.value(forHTTPHeaderField: "Authorization") == "Bearer fixture-access" { return (401, [:]) }
            result = ["email": "fixture@example.com", "balance": 12.5]
        case "/api/v1/auth/refresh": result = ["access_token": "fixture-new-access", "refresh_token": "fixture-new-refresh"]
        case "/api/v1/auth/logout": result = [:]
        case "/api/v1/groups/available":
            return (200, ["code": 0, "data": [
                ["id": 16, "name": "GPT", "platform": "openai", "rate_multiplier": 0.28],
                ["id": 62, "name": "Grok", "platform": "grok", "rate_multiplier": 0.25]
            ]])
        case "/api/v1/model-plaza":
            result = ["groups": [
                ["id": 16, "name": "GPT", "platform": "openai", "models": [["name": "gpt-test", "platform": "openai", "pricing": ["input": 1]]]],
                ["id": 62, "name": "Grok", "platform": "grok", "models": [["name": "grok-test", "platform": "grok", "pricing": ["input": 1]]]],
                ["id": 99, "name": "Expired", "platform": "openai", "models": [["name": "expired-test", "pricing": ["input": 1]]]]
            ]]
        case "/api/v1/keys":
            if req.httpMethod == "POST" {
                if body["name"] as? String == "TokenPro · Claude" {
                    claudeKeyExists = true
                    claudeGroupID = (body["group_id"] as? NSNumber)?.int64Value ?? claudeGroupID
                    result = ["id": 78, "name": "TokenPro · Claude", "status": "active", "group_id": claudeGroupID, "key": "sk-fixture-claude-key"]
                } else {
                    managedKeyExists = true
                    managedGroupID = (body["group_id"] as? NSNumber)?.int64Value ?? managedGroupID
                    result = ["id": 77, "name": "TokenPro · Codex", "status": "active", "group_id": managedGroupID, "key": "sk-fixture-codex-key"]
                }
            } else {
                var items: [[String: Any]] = []
                if managedKeyExists { items.append(["id": 77, "name": "TokenPro · Codex", "status": "active", "group_id": managedGroupID]) }
                if claudeKeyExists { items.append(["id": 78, "name": "TokenPro · Claude", "status": "active", "group_id": claudeGroupID]) }
                result = ["items": items]
            }
        case "/api/v1/keys/77":
            if req.httpMethod == "PUT" { managedGroupID = (body["group_id"] as? NSNumber)?.int64Value ?? managedGroupID }
            result = ["id": 77, "name": "TokenPro · Codex", "status": "active", "group_id": managedGroupID, "key": "sk-fixture-codex-key"]
        case "/api/v1/keys/43":
            result = ["id": 43, "name": "用户自己的 Key", "status": "active", "group_id": 16, "key": "sk-user-fixture-key"]
        case "/api/v1/keys/78":
            if req.httpMethod == "PUT" { claudeGroupID = (body["group_id"] as? NSNumber)?.int64Value ?? claudeGroupID }
            result = ["id": 78, "name": "TokenPro · Claude", "status": "active", "group_id": claudeGroupID, "key": "sk-fixture-claude-key"]
        default: throw RouterError("Unexpected fixture endpoint")
        }
        return (200, ["code": 0, "data": result])
    }
    do {
        await account.start()
        try check(account.settingsReady && !account.signedIn, "public settings without login")
        let before = calls.count
        let denied = await account.authenticate(email: "fixture@example.com", password: "fixture-password", register: false, code: "", invitation: "", accepted: false)
        try check(!denied && calls.count == before, "agreement gate makes no auth request")
        let sent = await account.sendCode(email: "fixture@example.com", accepted: true)
        try check(sent == 45 && calls.last?.0 == "/api/v1/auth/send-verify-code", "verification endpoint/countdown")
        let registered = await account.authenticate(email: "fixture@example.com", password: "fixture-password", register: true, code: "123456", invitation: "invite", accepted: true)
        try check(registered && account.signedIn, "registration success")
        try check(calls.last?.2["verify_code"] as? String == "123456" && calls.last?.2["invitation_code"] as? String == "invite", "registration wire fields")
        try check(stored != nil && !stored!.contains("fixture-password"), "password not persisted")
        await account.logout()
        try check(!account.signedIn && stored == nil && calls.last?.0 == "/api/v1/auth/logout", "logout clears session and calls revocation")
        twoFactor = true
        let first = await account.authenticate(email: "fixture@example.com", password: "fixture-password", register: false, code: "", invitation: "", accepted: true)
        try check(!first && account.pending2FA != nil && !account.signedIn, "2FA does not prematurely sign in")
        let second = await account.verify2FA("123456")
        try check(second && calls.last?.2["totp_code"] as? String == "123456" && calls.last?.2["temp_token"] as? String == "temporary-2fa", "2FA contract")
        expire = true
        let me = try await account.authenticated("/auth/me")
        try check(me["email"] as? String == "fixture@example.com", "expired access token retry")
        try check(calls.contains { $0.0 == "/api/v1/auth/refresh" && $0.2["refresh_token"] as? String == "fixture-refresh" }, "refresh contract")
        try check(calls.last?.3 == "Bearer fixture-new-access" && stored!.contains("fixture-new-refresh"), "rotated tokens persisted")
        let priced = try await account.pricedModels()
        try check(priced.map(\.name) == ["gpt-test", "grok-test"], "only currently available groups become selectable")
        try check(priced.first?.groupID == 16 && priced.first?.rateMultiplier == 0.28, "group id and rate carried into models")
        let managed = try await account.codexManagedAPIKey(initialGroupID: 16)
        try check(managed.id == 77 && managed.groupID == 16 && managed.key == "sk-fixture-codex-key", "dedicated Codex key created")
        try check(calls.contains { $0.0 == "/api/v1/keys" && $0.1 == "POST" && $0.2["name"] as? String == "TokenPro · Codex" }, "dedicated key exact name")
        let switched = try await account.switchCodexKeyGroup(keyID: 77, groupID: 62)
        try check(switched == ManagedCodexKeyState(id: 77, groupID: 62), "managed key group switched and confirmed")
        try check(calls.contains { $0.0 == "/api/v1/keys/77" && $0.1 == "PUT" && ($0.2["group_id"] as? NSNumber)?.int64Value == 62 }, "group switch wire contract")
        let postCount = calls.filter { $0.0 == "/api/v1/keys" && $0.1 == "POST" }.count
        let reused = try await account.codexManagedAPIKey(initialGroupID: 16)
        try check(reused.id == 77 && managedGroupID == 16, "existing dedicated key reused and rebound")
        try check(calls.filter { $0.0 == "/api/v1/keys" && $0.1 == "POST" }.count == postCount, "no duplicate dedicated key")
        let claudeKey = try await account.claudeManagedAPIKey(initialGroupID: 62)
        try check(claudeKey.id == 78 && claudeKey.groupID == 62 && claudeKey.key == "sk-fixture-claude-key", "dedicated Claude key created separately")
        try check(calls.contains { $0.0 == "/api/v1/keys" && $0.1 == "POST" && $0.2["name"] as? String == "TokenPro · Claude" }, "dedicated Claude key exact name")
        let switchCalls = calls.filter { $0.1 == "PUT" }.count
        do { _ = try await account.switchCodexKeyGroup(keyID: 77, groupID: 99); throw RouterError("unexpected unavailable group") }
        catch { try check(error.localizedDescription.contains("不可用"), "expired or unavailable group rejected") }
        try check(calls.filter { $0.1 == "PUT" }.count == switchCalls, "unavailable group never mutates key")
        do { _ = try await account.switchCodexKeyGroup(keyID: 43, groupID: 62); throw RouterError("unexpected foreign key switch") }
        catch { try check(error.localizedDescription.contains("不是 TokenPro"), "user key protected from mutation") }
        nextFailure = 429
        do { _ = try await client.request("/auth/me"); throw RouterError("expected failure") }
        catch let failure as BackendFailure { try check(failure.status == 429, "HTTP rate limit propagated") }
        account.settings["turnstile_enabled"] = true
        let beforeCaptcha = calls.count
        _ = await account.authenticate(email: "fixture@example.com", password: "fixture-password", register: false, code: "", invitation: "", accepted: true)
        try check(calls.count == beforeCaptcha, "captcha requirements fail closed")
        let policy: [String: Any] = ["registration_email_suffix_whitelist": ["@gmail.com", "@qq.com"]]
        try check(try AccountValidation.email("a@gmail.com", registration: true, settings: policy) == "a@gmail.com", "allowed registration domain")
        do { _ = try AccountValidation.email("a@gamil.com", registration: true, settings: policy); throw RouterError("unexpected success") }
        catch { try check(error.localizedDescription.contains("@gmail.com"), "actionable Gmail typo error") }
        do { _ = try AccountValidation.email("a@", registration: false, settings: [:]); throw RouterError("unexpected success") }
        catch { try check(error.localizedDescription.contains("邮箱"), "malformed email rejected") }
        try check(try AccountValidation.email("a@custom.example", registration: false, settings: policy) == "a@custom.example", "login unaffected by registration whitelist")
        func freshAccount() -> NativeAccount {
            NativeAccount(client: client, read: { stored }, write: { stored = $0 }, remove: { stored = nil })
        }
        let restarted = freshAccount()
        let restartCallIndex = calls.count
        await restarted.start()
        try check(restarted.signedIn && restarted.email == "fixture@example.com", "restart restores saved login")
        try check(!calls.dropFirst(restartCallIndex).contains { $0.0.hasSuffix("/login") }, "restart never resubmits password")
        stored = "{\"access\":\"fixture-access\",\"refresh\":\"fixture-refresh\"}"
        let expiredRestart = freshAccount()
        await expiredRestart.start()
        try check(expiredRestart.signedIn && stored!.contains("fixture-new-refresh"), "restart refreshes expired saved access")
        pathFailures["/api/v1/auth/me"] = 503
        let offlineRestart = freshAccount()
        let preserved = stored
        await offlineRestart.start()
        try check(!offlineRestart.signedIn && offlineRestart.restorePending && offlineRestart.retryRestoreAutomatically && stored == preserved, "temporary outage retains restore state and credentials")
        await offlineRestart.start()
        try check(offlineRestart.signedIn && !offlineRestart.restorePending, "network recovery retries without password")
        stored = "{\"access\":\"fixture-access\",\"refresh\":\"fixture-refresh\"}"
        pathFailures["/api/v1/auth/refresh"] = 503
        let refreshOutage = freshAccount()
        await refreshOutage.start()
        try check(refreshOutage.restorePending && stored != nil, "temporary refresh outage retains saved session")
        await refreshOutage.start()
        try check(refreshOutage.signedIn, "refresh retry recovers automatically")
        stored = "{\"access\":\"fixture-access\",\"refresh\":\"fixture-refresh\"}"
        pathFailures["/api/v1/auth/refresh"] = 401
        let revoked = freshAccount()
        await revoked.start()
        try check(!revoked.signedIn && !revoked.restorePending, "revoked refresh requires real login")
        await offlineRestart.logout()
        let afterLogout = freshAccount()
        await afterLogout.start()
        try check(!afterLogout.signedIn && !afterLogout.restorePending, "explicit logout prevents future automatic login")
        print("PASS: \(count) native account tests (offline, in-memory credentials)")
    } catch { print(error.localizedDescription); exit(1) }
}

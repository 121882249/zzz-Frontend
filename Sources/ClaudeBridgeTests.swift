import Foundation

func runClaudeBridgeTests() async {
    var checks = 0
    func check(_ value: Bool, _ label: String) throws {
        guard value else { throw RouterError("Bridge test failed: " + label) }
        checks += 1
    }
    let dir = FileManager.default.temporaryDirectory.appendingPathComponent("tokenpro-bridge-test-" + UUID().uuidString)
    do {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        let model = PricedModel(name: "gpt-test", platform: "openai", groups: ["test"], groupID: 42)
        let config = ClaudeBridgeSettings(accountID: "test", port: UInt16.random(in: 32000...49000), token: UUID().uuidString,
            routes: [ClaudeBridgeRoute(model: model)], keyID: 42, key: "test-only-never-sent")
        let path = dir.appendingPathComponent("bridge.json")
        try config.save(to: path)
        let server = try ClaudeBridgeServer(config: config, configURL: path)
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel(); server.listener.cancel(); server.timer?.cancel(); try? FileManager.default.removeItem(at: dir) }
        func request(_ path: String, token: String? = config.token, body: [String: Any]? = nil, origin: String? = nil) async throws -> (Int, [String: Any]) {
            var req = URLRequest(url: URL(string: config.baseURL + path)!)
            req.timeoutInterval = 5
            if let token { req.setValue("Bearer " + token, forHTTPHeaderField: "Authorization") }
            if let origin { req.setValue(origin, forHTTPHeaderField: "Origin") }
            if let body { req.httpMethod = "POST"; req.httpBody = try JSONSerialization.data(withJSONObject: body) }
            let (data, response) = try await session.data(for: req)
            return ((response as? HTTPURLResponse)?.statusCode ?? 0, try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:])
        }
        for _ in 0..<30 { if await ClaudeBridgeManager.healthy(config) { break }; try await Task.sleep(nanoseconds: 50_000_000) }
        try check(await ClaudeBridgeManager.healthy(config), "authenticated local health")
        try check(try await request("/health", token: nil).0 == 401, "unauthenticated request rejected")
        try check(try await request("/health", token: "wrong").0 == 401, "wrong local token rejected")
        try check(try await request("/health", origin: "https://example.com").0 == 403, "browser origin rejected")
        let models = try await request("/v1/models")
        let first = (models.1["data"] as? [[String: Any]])?.first
        try check(first?["id"] as? String == ClaudeBridgeRoute.alias(model), "only selected alias exposed")
        try check(first?["display_name"] as? String == "gpt-test", "actual model label exposed")
        try check(!String(decoding: try JSONSerialization.data(withJSONObject: models.1), as: UTF8.self).contains(config.key), "upstream key never exposed")
        try check(try await request("/v1/messages", body: ["model": "unselected", "messages": []]).0 == 404, "unselected model rejected before account API")
        try check(try await request("/arbitrary-proxy").0 == 404, "arbitrary endpoints rejected")
        let estimate = try await request("/v1/messages/count_tokens", body: ["model": ClaudeBridgeRoute.alias(model), "messages": [["role": "user", "content": "你好 Hello"]]])
        try check(estimate.0 == 200 && estimate.1["tokenpro_estimated"] as? Bool == true && (estimate.1["input_tokens"] as? Int ?? 0) > 0, "local token estimate is explicitly marked, without inference billing")
        let second = ClaudeBridgeRoute(model: PricedModel(name: "grok-test", platform: "grok", groups: ["other"], groupID: 43))
        let signed = ClaudeHistory.tagResponse(["content": [["type": "thinking", "thinking": "test", "signature": "original-signature"]]], alias: config.routes[0].alias)
        let history: [String: Any] = ["messages": [["role": "assistant", "content": signed["content"]!]]]
        let same = ClaudeHistory.prepare(history, route: config.routes[0])["messages"] as! [[String: Any]]
        try check((same[0]["content"] as! [[String: Any]])[0]["signature"] as? String == "original-signature", "same model signature restored exactly")
        let switched = ClaudeHistory.prepare(history, route: second)["messages"] as! [[String: Any]]
        try check((switched[0]["content"] as! [[String: Any]]).isEmpty, "foreign model signature not forwarded")
        let developer = try ClaudeResponsesAdapter.request(["model": "gpt-test", "messages": [["role": "developer", "content": "Instruction"], ["role": "user", "content": "Hello"]]])
        try check((developer["input"] as? [[String: Any]])?.first?["role"] as? String == "developer", "developer message role preserved")
        print("PASS: \(checks) Claude bridge checks (local only)")
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8)); exit(1)
    }
}

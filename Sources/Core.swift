import Foundation
import CryptoKit

struct RouterError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

struct Route: Codable, Identifiable, Equatable {
    var id = UUID()
    var name: String
    var baseURL: String
    var model: String
    var tokenLabel: String
    var alias: String { "cr-" + id.uuidString.lowercased() }
    var keyID: String { "upstream-" + id.uuidString }
    static var blank: Route { Route(name: "", baseURL: "", model: "", tokenLabel: "默认令牌") }
    func validated() throws -> Route {
        var r = self
        r.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
        r.model = model.trimmingCharacters(in: .whitespacesAndNewlines)
        r.baseURL = baseURL.trimmingCharacters(in: .whitespacesAndNewlines)
        while r.baseURL.hasSuffix("/") { r.baseURL.removeLast() }
        guard !r.name.isEmpty, !r.model.isEmpty else { throw RouterError("请填写连接名称和模型 ID。") }
        guard let u = URLComponents(string: r.baseURL), let host = u.host, !host.isEmpty,
              u.user == nil, u.password == nil, u.query == nil, u.fragment == nil,
              u.scheme == "https" || (u.scheme == "http" && ["127.0.0.1", "localhost", "::1"].contains(host)) else {
            throw RouterError("接口地址需要 HTTPS；本机测试可用 http://127.0.0.1。地址不能包含密码、查询参数或片段。")
        }
        guard !r.model.contains(where: { $0.isNewline }) else { throw RouterError("模型 ID 不能换行。") }
        return r
    }
    func endpoint(_ suffix: String) -> URL { URL(string: baseURL + suffix)! }
}

struct Settings: Codable {
    var routes: [Route] = []
    var selectedID: UUID?
}

struct PricedModel: Codable, Identifiable, Hashable {
    var name: String
    var platform: String
    var groups: [String]
    var groupID: Int64?
    var rateMultiplier: Double?
    var id: String { "\(groupID ?? 0)::\(name)" }
    var groupName: String { groups.first ?? "TokenPro" }
    var isClaudeModel: Bool {
        ["anthropic", "claude"].contains(platform.lowercased()) || name.lowercased().contains("claude")
    }

    init(name: String, platform: String, groups: [String], groupID: Int64? = nil, rateMultiplier: Double? = nil) {
        self.name = name
        self.platform = platform
        self.groups = groups
        self.groupID = groupID
        self.rateMultiplier = rateMultiplier
    }
}

struct ManagedCodexKeyState: Codable, Equatable {
    var id: Int64
    var groupID: Int64
}

struct ModelSelectionState: Codable {
    var models: [PricedModel]
    var activeModelID: String?
    var managedKey: ManagedCodexKeyState?

    init(models: [PricedModel] = [], activeModelID: String? = nil, managedKey: ManagedCodexKeyState? = nil) {
        self.models = models
        self.activeModelID = activeModelID
        self.managedKey = managedKey
    }

    var activeModel: PricedModel? {
        models.first(where: { $0.id == activeModelID }) ?? models.first
    }
}

struct ModelSelectionStore {
    static var url: URL { Persistence.directory.appendingPathComponent("selected-models.json") }
    static func loadState() -> ModelSelectionState {
        guard let data = try? Data(contentsOf: url) else { return ModelSelectionState() }
        if let state = try? JSONDecoder().decode(ModelSelectionState.self, from: data) { return state }
        // Migrate the v0.4.1 array-only format without losing the user's model choices.
        if let models = try? JSONDecoder().decode([PricedModel].self, from: data) {
            return ModelSelectionState(models: models, activeModelID: models.first?.id)
        }
        return ModelSelectionState()
    }
    static func load() -> [PricedModel] {
        loadState().models
    }
    static func save(_ state: ModelSelectionState) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONEncoder().encode(state).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    static func clear() throws {
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}

struct ClaudeSelectionState: Codable {
    var models: [PricedModel]
    var managedKey: ManagedCodexKeyState?
    var tokenProProfileID: String?
    var officialProfileID: String?

    init(models: [PricedModel] = [], managedKey: ManagedCodexKeyState? = nil, tokenProProfileID: String? = nil, officialProfileID: String? = nil) {
        self.models = models
        self.managedKey = managedKey
        self.tokenProProfileID = tokenProProfileID
        self.officialProfileID = officialProfileID
    }
}

struct ClaudeSelectionStore {
    static var url: URL { Persistence.directory.appendingPathComponent("claude-selected-models.json") }
    static func load() -> ClaudeSelectionState {
        guard let data = try? Data(contentsOf: url),
              let state = try? JSONDecoder().decode(ClaudeSelectionState.self, from: data) else { return ClaudeSelectionState() }
        return state
    }
    static func save(_ state: ClaudeSelectionState) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        try encoder.encode(state).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
}

struct LocalCredentialStore {
    static var directory: URL { Persistence.directory.appendingPathComponent("credentials", isDirectory: true) }
    static func read(_ id: String) throws -> String? {
        let url = try credentialURL(id)
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try String(contentsOf: url, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    static func write(_ value: String, id: String) throws {
        let url = try credentialURL(id)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: directory.path)
        try Data(value.utf8).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    static func remove(_ id: String) throws {
        let url = try credentialURL(id)
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
    static func routeKeyID(_ raw: String) throws -> String {
        let prefix = "upstream-"
        guard raw.hasPrefix(prefix), UUID(uuidString: String(raw.dropFirst(prefix.count))) != nil else {
            throw RouterError("无效的连接令牌标识。")
        }
        return raw
    }
    private static func credentialURL(_ id: String) throws -> URL {
        directory.appendingPathComponent(try routeKeyID(id), isDirectory: false)
    }
}

struct Persistence {
    static var directory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("TokenPro")
    }
    static func save(_ settings: Settings) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let encoder = JSONEncoder(); encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        let url = directory.appendingPathComponent("connections.json")
        try encoder.encode(settings).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    static func load() throws -> Settings {
        let url = directory.appendingPathComponent("connections.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return Settings() }
        return try JSONDecoder().decode(Settings.self, from: Data(contentsOf: url))
    }
}

struct HelperCredentialStore {
    static var sessionURL: URL { Persistence.directory.appendingPathComponent("account-session.json") }
    static var modelTokenURL: URL { Persistence.directory.appendingPathComponent("codex-model-token") }
    static var claudeModelTokenURL: URL { Persistence.directory.appendingPathComponent("claude-model-token") }

    static func readSession() throws -> String? { try read(sessionURL) }
    static func writeSession(_ value: String) throws { try write(value, to: sessionURL) }
    static func removeSession() throws { try remove(sessionURL) }
    static func readModelToken() throws -> String? { try read(modelTokenURL) }
    static func writeModelToken(_ value: String) throws { try write(value, to: modelTokenURL) }
    static func removeModelToken() throws { try remove(modelTokenURL) }
    static func readClaudeModelToken() throws -> String? { try read(claudeModelTokenURL) }
    static func writeClaudeModelToken(_ value: String) throws { try write(value, to: claudeModelTokenURL) }
    static func removeClaudeModelToken() throws { try remove(claudeModelTokenURL) }

    private static func read(_ url: URL) throws -> String? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        return try String(contentsOf: url, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
    }
    private static func write(_ value: String, to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.deletingLastPathComponent().path)
        try Data(value.utf8).write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }
    private static func remove(_ url: URL) throws {
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
    }
}

struct ClaudeDesktopConfig {
    static var defaultLibraryURL: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/Claude-3p/configLibrary", isDirectory: true)
    }

    private static func validID(_ value: String?) -> String? {
        guard let value, UUID(uuidString: value) != nil else { return nil }
        return value.lowercased()
    }

    private static func readMeta(at library: URL) throws -> [String: Any] {
        let url = library.appendingPathComponent("_meta.json")
        guard FileManager.default.fileExists(atPath: url.path) else { return ["appliedId": "", "entries": []] }
        guard let value = try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
              value["entries"] is [[String: Any]], value["appliedId"] is String else {
            throw RouterError("Claude 桌面配置目录格式不兼容，未进行修改。")
        }
        return value
    }

    private static func writeJSON(_ value: Any, to url: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        try data.write(to: url, options: .atomic)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    private static func ensureProfile(id preferred: String?, name: String, contents: [String: Any], meta: inout [String: Any], library: URL) throws -> String {
        var entries = meta["entries"] as? [[String: Any]] ?? []
        let id = validID(preferred) ?? UUID().uuidString.lowercased()
        if let index = entries.firstIndex(where: { $0["id"] as? String == id }) {
            entries[index]["name"] = name
        } else {
            entries.append(["id": id, "name": name])
        }
        meta["entries"] = entries
        try writeJSON(contents, to: library.appendingPathComponent(id + ".json"))
        return id
    }

    static func configuration(models: [PricedModel], executable: String, baseURL: String = "http://127.0.0.1:23179") throws -> [String: Any] {
        guard !models.isEmpty, models.allSatisfy({ $0.groupID != nil }) else { throw RouterError("请选择有效分组内的模型。") }
        guard Set(models.map(\.id)).count == models.count else { throw RouterError("模型重复。") }
        guard executable.hasPrefix("/"), FileManager.default.isExecutableFile(atPath: executable) else {
            throw RouterError("TokenPro 凭据助手不可执行，请重新安装客户端。")
        }
        let modelEntries: [[String: Any]] = models.map { model in
            ["name": ClaudeBridgeRoute.alias(model), "labelOverride": model.name]
        }
        return [
            "deploymentDisplayName": "TokenPro",
            "endUserAttribution": false,
            "inferenceProvider": "gateway",
            "inferenceGatewayBaseUrl": baseURL,
            "inferenceGatewayAuthScheme": "bearer",
            "inferenceCredentialKind": "helper-script",
            "inferenceCredentialHelper": executable,
            "inferenceCredentialHelperTtlSec": 30,
            "inferenceCredentialHelperTimeoutSec": 10,
            "inferenceCredentialHelperSilentRefreshEnabled": true,
            "modelDiscoveryEnabled": false,
            "inferenceModels": modelEntries
        ]
    }

    static func install(models: [PricedModel], executable: String, state: ClaudeSelectionState, library: URL = defaultLibraryURL, baseURL: String = "http://127.0.0.1:23179") throws -> ClaudeSelectionState {
        let config = try configuration(models: models, executable: executable, baseURL: baseURL)
        let fm = FileManager.default
        try fm.createDirectory(at: library, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: library.path)
        var meta = try readMeta(at: library)
        let officialID = try ensureProfile(id: state.officialProfileID, name: "Claude 官方配置", contents: [:], meta: &meta, library: library)
        let tokenProID = try ensureProfile(id: state.tokenProProfileID, name: "TokenPro", contents: config, meta: &meta, library: library)
        meta["appliedId"] = tokenProID
        meta.removeValue(forKey: "hybridPointer")
        try writeJSON(meta, to: library.appendingPathComponent("_meta.json"))
        return ClaudeSelectionState(models: models, managedKey: state.managedKey, tokenProProfileID: tokenProID, officialProfileID: officialID)
    }

    static func restoreOfficial(state: ClaudeSelectionState, library: URL = defaultLibraryURL) throws -> ClaudeSelectionState {
        let fm = FileManager.default
        try fm.createDirectory(at: library, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: library.path)
        var meta = try readMeta(at: library)
        let officialID = try ensureProfile(id: state.officialProfileID, name: "Claude 官方配置", contents: [:], meta: &meta, library: library)
        meta["appliedId"] = officialID
        meta.removeValue(forKey: "hybridPointer")
        try writeJSON(meta, to: library.appendingPathComponent("_meta.json"))
        return ClaudeSelectionState(models: [], managedKey: nil, tokenProProfileID: state.tokenProProfileID, officialProfileID: officialID)
    }

    static func isInstalled(state: ClaudeSelectionState, library: URL = defaultLibraryURL) -> Bool {
        guard !state.models.isEmpty, let id = validID(state.tokenProProfileID),
              let meta = try? readMeta(at: library) else { return false }
        return meta["appliedId"] as? String == id
    }
}

struct ConfigBackup: Codable {
    var existed: Bool
    var original: Data
    var installed: Data
    var path: String
}

struct CodexConfig {
    static let provider = "tokenpro_direct"
    static let modelTokenKeyID = "tokenpro-codex-model-token"
    static func quote(_ s: String) -> String {
        var out = "\""
        for c in s.unicodeScalars {
            switch c.value {
            case 34: out += "\\\""
            case 92: out += "\\\\"
            case 0...31, 127: out += String(format: "\\u%04X", c.value)
            default: out.unicodeScalars.append(c)
            }
        }
        return out + "\""
    }
    static func render(original: String, route: Route, executable: String) throws -> String {
        // Fail closed for uncommon TOML forms rather than risk changing an unrelated section.
        guard !original.contains("\"\"\""), !original.contains("'''"),
              !original.contains(provider) else { throw RouterError("配置含有复杂多行字符串或同名服务商。请使用“复制配置”手动合并，或先恢复上次接入。") }
        var root = true
        var lines: [String] = []
        for line in original.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("[") { root = false }
            if root {
                if t.range(of: "^(model|model_provider|model_catalog_json)\\s*=", options: .regularExpression) != nil { continue }
                if t.range(of: "^[\"'](model|model_provider|model_catalog_json)[\"']\\s*=", options: .regularExpression) != nil || t.hasPrefix("model_providers") {
                    throw RouterError("配置使用了特殊 TOML 写法，请复制配置手动合并。")
                }
            }
            lines.append(line)
        }
        return "model = \(quote(route.model))\nmodel_provider = \(quote(provider))\n" + lines.joined(separator: "\n") + "\n\n" + providerBlock(route: route, executable: executable)
    }
    static func providerBlock(route: Route, executable: String) -> String {
        """
        [model_providers.\(provider)]
        name = "TokenPro"
        base_url = \(quote(route.baseURL))
        wire_api = "responses"
        supports_websockets = false

        [model_providers.\(provider).auth]
        command = \(quote(executable))
        args = ["--route-token", \(quote(route.keyID))]
        timeout_ms = 5000
        refresh_interval_ms = 300000
        """
    }
    static var configURL: URL {
        if let home = ProcessInfo.processInfo.environment["CODEX_HOME"], !home.isEmpty {
            return URL(fileURLWithPath: home).appendingPathComponent("config.toml")
        }
        return FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent(".codex/config.toml")
    }
    static var backupURL: URL { Persistence.directory.appendingPathComponent("codex-backup.json") }
    static var catalogURL: URL { Persistence.directory.appendingPathComponent("codex-models.json") }
    static var hooksURL: URL { configURL.deletingLastPathComponent().appendingPathComponent("hooks.json") }

    static func stripManagedConfiguration(_ text: String) -> String {
        var table: String?
        var skippingProvider = false
        var lines: [String] = []
        for line in CompactContext.restore(text).components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("["), trimmed.hasSuffix("]") {
                let name = String(trimmed.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
                table = name
                skippingProvider = name == "model_providers.\(provider)" || name.hasPrefix("model_providers.\(provider).")
                if skippingProvider { continue }
                lines.append(line)
                continue
            }
            if skippingProvider { continue }
            if table == nil, trimmed.range(of: "^(model|model_provider|model_catalog_json)\\s*=", options: .regularExpression) != nil { continue }
            if table == "features", trimmed.range(of: "^hooks\\s*=", options: .regularExpression) != nil { continue }
            lines.append(line)
        }
        while lines.last?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true { lines.removeLast() }
        return lines.joined(separator: "\n")
    }

    static func settingFeatureHook(_ line: String, in text: String) -> String {
        var lines = text.components(separatedBy: "\n")
        if let index = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "[features]" }) {
            lines.insert(line, at: index + 1)
        } else {
            if lines.last?.isEmpty == false { lines.append("") }
            lines.append("[features]")
            lines.append(line)
        }
        return lines.joined(separator: "\n")
    }

    static func originalManagedSettings(_ text: String) -> (root: [String], featureHook: String?) {
        var table: String?
        var root: [String] = []
        var featureHook: String?
        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("["), trimmed.hasSuffix("]") {
                table = String(trimmed.dropFirst().dropLast()).trimmingCharacters(in: .whitespaces)
                continue
            }
            if table == nil, trimmed.range(of: "^(model|model_provider|model_catalog_json)\\s*=", options: .regularExpression) != nil { root.append(line) }
            if table == "features", trimmed.range(of: "^hooks\\s*=", options: .regularExpression) != nil { featureHook = line }
        }
        return (root, featureHook)
    }

    static func restoringManagedConfiguration(current: String, original: String) -> String {
        var restored = stripManagedConfiguration(current)
        let originalSettings = originalManagedSettings(original)
        if let featureHook = originalSettings.featureHook { restored = settingFeatureHook(featureHook, in: restored) }
        if !originalSettings.root.isEmpty { restored = originalSettings.root.joined(separator: "\n") + "\n" + restored }
        return restored
    }

    static func shellQuote(_ value: String) -> String {
        "'" + value.replacingOccurrences(of: "'", with: "'\"'\"'") + "'"
    }

    static func modelHookCommand(executable: String) -> String {
        shellQuote(executable) + " --tokenpro-sync-hook"
    }

    static func canonicalJSONObject(_ value: Any) -> Any {
        if let dict = value as? [String: Any] {
            var sorted: [String: Any] = [:]
            for key in dict.keys.sorted() { sorted[key] = canonicalJSONObject(dict[key] as Any) }
            return sorted
        }
        if let array = value as? [Any] { return array.map(canonicalJSONObject) }
        return value
    }

    static func modelHookTrustedHash(executable: String) throws -> String {
        let identity: [String: Any] = [
            "event_name": "user_prompt_submit",
            "hooks": [[
                "async": false,
                "command": modelHookCommand(executable: executable),
                "statusMessage": "正在同步 TokenPro 模型分组",
                "timeout": 20,
                "type": "command"
            ]]
        ]
        let data = try JSONSerialization.data(withJSONObject: canonicalJSONObject(identity), options: [.sortedKeys, .withoutEscapingSlashes])
        let digest = SHA256.hash(data: data)
        return "sha256:" + digest.map { String(format: "%02x", $0) }.joined()
    }

    static func modelHookTrustKey(config: URL) -> String {
        config.deletingLastPathComponent().appendingPathComponent("hooks.json").path + ":user_prompt_submit:0:0"
    }

    static func stripModelHookTrustState(_ text: String, key: String) -> String {
        let targetDouble = "[hooks.state.\(quote(key))]"
        let targetSingle = "[hooks.state.'\(key.replacingOccurrences(of: "'", with: "\\'"))']"
        var skipping = false
        var lines: [String] = []
        for line in text.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("["), trimmed.hasSuffix("]") {
                skipping = trimmed == targetDouble || trimmed == targetSingle
                if skipping { continue }
            }
            if !skipping { lines.append(line) }
        }
        while lines.last?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true { lines.removeLast() }
        return lines.joined(separator: "\n")
    }

    static func modelHookTrustStateBlock(executable: String, config: URL) throws -> String {
        let key = modelHookTrustKey(config: config)
        return """
        [hooks.state.\(quote(key))]
        enabled = true
        trusted_hash = \(quote(try modelHookTrustedHash(executable: executable)))
        """
    }

    static func installModelHook(executable: String, target: URL = hooksURL) throws {
        let fm = FileManager.default
        var root: [String: Any] = [:]
        if fm.fileExists(atPath: target.path) {
            guard let parsed = try JSONSerialization.jsonObject(with: Data(contentsOf: target)) as? [String: Any] else {
                throw RouterError("Codex hooks.json 格式不兼容，无法安全加入 TokenPro。")
            }
            root = parsed
        }
        var hooks = root["hooks"] as? [String: Any] ?? [:]
        var groups = hooks["UserPromptSubmit"] as? [[String: Any]] ?? []
        groups.removeAll(where: isTokenProHookGroup)
        groups.append([
            "hooks": [[
                "type": "command",
                "command": modelHookCommand(executable: executable),
                "timeout": 20,
                "statusMessage": "正在同步 TokenPro 模型分组"
            ]]
        ])
        hooks["UserPromptSubmit"] = groups
        root["hooks"] = hooks
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys]).write(to: target, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
    }

    static func removeModelHook(target: URL = hooksURL) throws {
        let fm = FileManager.default
        guard fm.fileExists(atPath: target.path) else { return }
        if let updated = try modelHooksWithoutTokenPro(Data(contentsOf: target)) {
            try updated.write(to: target, options: .atomic)
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
        } else { try fm.removeItem(at: target) }
    }

    static func modelHooksWithoutTokenPro(_ data: Data) throws -> Data? {
        guard var root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["hooks"] == nil || root["hooks"] is [String: Any] else {
            throw RouterError("Codex hooks.json 格式不兼容，无法安全移除 TokenPro。")
        }
        var hooks = root["hooks"] as? [String: Any] ?? [:]
        guard hooks["UserPromptSubmit"] == nil || hooks["UserPromptSubmit"] is [[String: Any]] else {
            throw RouterError("Codex UserPromptSubmit 格式不兼容，无法安全移除 TokenPro。")
        }
        let groups = try (hooks["UserPromptSubmit"] as? [[String: Any]] ?? []).compactMap { group -> [String: Any]? in
            guard let handlers = group["hooks"] as? [[String: Any]] else { throw RouterError("Codex Hook 处理器格式不兼容。") }
            let remaining = handlers.filter { ($0["command"] as? String)?.contains("--tokenpro-sync-hook") != true }
            if remaining.isEmpty { return nil }
            var updated = group
            updated["hooks"] = remaining
            return updated
        }
        if groups.isEmpty { hooks.removeValue(forKey: "UserPromptSubmit") } else { hooks["UserPromptSubmit"] = groups }
        if hooks.isEmpty { root.removeValue(forKey: "hooks") } else { root["hooks"] = hooks }
        if root.isEmpty { return nil }
        return try JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])
    }

    private static func isTokenProHookGroup(_ group: [String: Any]) -> Bool {
        guard let handlers = group["hooks"] as? [[String: Any]] else { return false }
        return handlers.contains { ($0["command"] as? String)?.contains("--tokenpro-sync-hook") == true }
    }

    static func renderModelSelection(original: String, models: [PricedModel], activeModel: PricedModel? = nil, executable: String, catalog: URL = catalogURL) throws -> String {
        guard !models.isEmpty else { throw RouterError("请至少选择一个模型。") }
        guard models.allSatisfy({ $0.groupID != nil }) else { throw RouterError("模型缺少 TokenPro 分组信息，请重新加载。") }
        guard Set(models.map(\.name)).count == models.count else { throw RouterError("同一模型只能选择一个计费分组。") }
        let active = activeModel.flatMap { candidate in models.first(where: { $0.id == candidate.id }) } ?? models.first!
        guard !original.contains("\"\"\""), !original.contains("'''"), !original.contains(provider) else {
            throw RouterError("Codex 配置含有复杂多行字符串或同名服务商，请先恢复上次配置。")
        }
        var root = true
        var lines: [String] = []
        for line in original.components(separatedBy: "\n") {
            let t = line.trimmingCharacters(in: .whitespaces)
            if t.hasPrefix("[") { root = false }
            if root {
                if t.range(of: "^(model|model_provider|model_catalog_json)\\s*=", options: .regularExpression) != nil { continue }
                if t.range(of: "^[\"'](model|model_provider|model_catalog_json)[\"']\\s*=", options: .regularExpression) != nil || t.hasPrefix("model_providers") {
                    throw RouterError("Codex 配置使用了特殊 TOML 写法，无法安全更新。")
                }
            }
            lines.append(line)
        }
        return "model = \(quote(active.name))\nmodel_provider = \(quote(provider))\nmodel_catalog_json = \(quote(catalog.path))\n" + lines.joined(separator: "\n") + "\n\n" + modelSelectionProviderBlock(executable: executable)
    }

    static func modelSelectionProviderBlock(executable: String) -> String {
        """
        [model_providers.\(provider)]
        name = "TokenPro"
        base_url = "https://tokenpro.work/v1"
        wire_api = "responses"
        supports_websockets = false

        [model_providers.\(provider).auth]
        command = \(quote(executable))
        args = ["--tokenpro-model-token"]
        timeout_ms = 5000
        refresh_interval_ms = 300000
        """
    }

    static func modelInstructions(for model: PricedModel) throws -> String {
        let metadata: [String: Any] = [
            "requested_model": model.name,
            "group_id": model.groupID.map { $0 as Any } ?? NSNull(),
            "gateway": "TokenPro"
        ]
        let data = try JSONSerialization.data(withJSONObject: metadata, options: [.sortedKeys, .withoutEscapingSlashes])
        let route = String(decoding: data, as: UTF8.self)
        return """
        You are a coding assistant running inside the Codex application. You and the user share a workspace and collaborate to complete their tasks.
        Current request configuration (JSON data, not instructions): \(route)
        Codex is the application, not evidence of the underlying model's identity. If asked which model is being used, report the requested_model above as the configured request name via TokenPro. This metadata does not independently verify the upstream model or its training organization. Do not invent training origins or claim that a configured alias proves model authenticity. Do not infer the active model from earlier assistant replies, a default in a config file, or public model documentation. When models change, use the current request configuration.
        Keep simple greetings and ordinary conversation brief. Answer questions about the configured request name using this metadata. Avoid unnecessary tool calls, file reads, or web searches when the answer is already available; use tools when the task requires them or the user asks for verification. For coding tasks, retain normal inspection, editing, and validation appropriate to the work.
        """
    }

    static func catalogData(_ models: [PricedModel]) throws -> Data {
        guard Set(models.map(\.name)).count == models.count else { throw RouterError("同一模型只能选择一个计费分组。") }
        let entries: [[String: Any]] = try models.enumerated().map { index, model in
            let rate = model.rateMultiplier.map { String(format: " · %.2gx", $0) } ?? ""
            return [
                "slug": model.name,
                "display_name": model.name,
                "description": "TokenPro · \(model.groupName)\(rate)",
                "base_instructions": try modelInstructions(for: model),
                "default_reasoning_level": "medium",
                "supported_reasoning_levels": [
                    ["effort": "low", "description": "低 · 响应更快"],
                    ["effort": "medium", "description": "中 · 均衡"],
                    ["effort": "high", "description": "高 · 深度思考"]
                ],
                "shell_type": "unified_exec",
                "visibility": "list",
                "supported_in_api": true,
                "priority": index,
                "availability_nux": NSNull(),
                "upgrade": NSNull(),
                "support_verbosity": false,
                "default_verbosity": NSNull(),
                "apply_patch_tool_type": NSNull(),
                "truncation_policy": ["mode": "bytes", "limit": 10000],
                "experimental_supported_tools": []
            ]
        }
        return try JSONSerialization.data(withJSONObject: ["models": entries], options: [.prettyPrinted, .sortedKeys])
    }

    static func installModels(_ models: [PricedModel], activeModel: PricedModel? = nil, executable: String, target: URL = configURL, backup: URL = backupURL, catalog: URL = catalogURL, compactContext: Bool = CompactContext.enabled) throws {
        let fm = FileManager.default
        let existed = fm.fileExists(atPath: target.path)
        let current = existed ? try Data(contentsOf: target) : Data()
        var original = current
        var originalExisted = existed
        if fm.fileExists(atPath: backup.path) {
            let previous = try JSONDecoder().decode(ConfigBackup.self, from: Data(contentsOf: backup))
            guard previous.path == target.path else { throw RouterError("Codex 配置备份路径不一致，已停止写入。") }
            if previous.installed == current {
                original = previous.original
            } else {
                guard let currentText = String(data: current, encoding: .utf8),
                      let previousOriginal = String(data: previous.original, encoding: .utf8) else { throw RouterError("Codex 配置不是 UTF-8 文本。") }
                original = Data(restoringManagedConfiguration(current: currentText, original: previousOriginal).utf8)
            }
            originalExisted = previous.existed
        }
        guard let currentText = String(data: current, encoding: .utf8) else { throw RouterError("Codex 配置不是 UTF-8 文本。") }
        let cleaned = stripManagedConfiguration(currentText)
        let hookEnabled = settingFeatureHook("hooks = true", in: cleaned)
        let trustKey = modelHookTrustKey(config: target)
        var updatedText = try renderModelSelection(original: hookEnabled, models: models, activeModel: activeModel, executable: executable, catalog: catalog)
        updatedText = stripModelHookTrustState(updatedText, key: trustKey)
        updatedText += "\n\n" + (try modelHookTrustStateBlock(executable: executable, config: target)) + "\n"
        if compactContext { updatedText = try CompactContext.apply(updatedText) }
        let updated = Data(updatedText.utf8)
        try fm.createDirectory(at: backup.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try catalogData(models).write(to: catalog, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: catalog.path)
        let record = ConfigBackup(existed: originalExisted, original: original, installed: updated, path: target.path)
        try JSONEncoder().encode(record).write(to: backup, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: backup.path)
        try updated.write(to: target, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
        try installModelHook(executable: executable, target: target.deletingLastPathComponent().appendingPathComponent("hooks.json"))
    }

    static func officialConfiguration(from text: String, config: URL = configURL) throws -> String {
        guard !text.contains("\"\"\""), !text.contains("'''") else {
            throw RouterError("配置含有复杂多行字符串，无法安全恢复官方配置；原文件尚未修改。")
        }
        let cleaned = stripModelHookTrustState(CompactContext.restore(text), key: modelHookTrustKey(config: config))
        let rootKeys = "model|review_model|model_provider|model_catalog_json|model_instructions_file|model_context_window|model_auto_compact_token_limit|openai_base_url|chatgpt_base_url|profile|model_providers"
        var atRoot = true
        var skippingProvider = false
        var lines: [String] = []
        for line in cleaned.components(separatedBy: "\n") {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("[") {
                guard let end = trimmed.lastIndex(of: "]") else { throw RouterError("配置表头格式不兼容，原文件尚未修改。") }
                let table = String(trimmed[...end]).trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
                    .replacingOccurrences(of: "\"", with: "").replacingOccurrences(of: "'", with: "")
                    .replacingOccurrences(of: " ", with: "")
                atRoot = false
                skippingProvider = table == "model_providers" || table.hasPrefix("model_providers.")
            }
            if skippingProvider { continue }
            if atRoot, trimmed.range(of: "^[\"']?(\(rootKeys))[\"']?\\s*(?:=|\\.)", options: .regularExpression) != nil { continue }
            lines.append(line)
        }
        while lines.first?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true { lines.removeFirst() }
        while lines.last?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true { lines.removeLast() }
        return "model_provider = \"openai\"\n" + lines.joined(separator: "\n") + "\n"
    }

    static func restoreOfficial(target: URL = configURL, backup: URL = backupURL, catalog: URL = catalogURL) throws {
        let fm = FileManager.default
        let current = fm.fileExists(atPath: target.path) ? try Data(contentsOf: target) : Data()
        guard let text = String(data: current, encoding: .utf8) else { throw RouterError("Codex 配置不是 UTF-8 文本。") }
        // Render and validate before writing. Never restore a previous third-party
        // provider just because it happened to be active before TokenPro.
        let updated = Data(try officialConfiguration(from: text, config: target).utf8)
        let hooks = target.deletingLastPathComponent().appendingPathComponent("hooks.json")
        let previousHooks = fm.fileExists(atPath: hooks.path) ? try Data(contentsOf: hooks) : nil
        if let previousHooks { _ = try modelHooksWithoutTokenPro(previousHooks) }
        let recovery = backup.deletingLastPathComponent().appendingPathComponent("before-official-" + UUID().uuidString, isDirectory: true)
        try fm.createDirectory(at: recovery, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        func preserve(_ data: Data, name: String) throws {
            let destination = recovery.appendingPathComponent(name)
            try data.write(to: destination, options: .atomic)
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: destination.path)
        }
        try preserve(current, name: "config.toml")
        if let previousHooks { try preserve(previousHooks, name: "hooks.json") }
        if fm.fileExists(atPath: backup.path) { try preserve(Data(contentsOf: backup), name: "previous-connection-backup.json") }
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try updated.write(to: target, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
        try removeModelHook(target: hooks)
        if fm.fileExists(atPath: catalog.path) { try fm.removeItem(at: catalog) }
        if fm.fileExists(atPath: backup.path) { try fm.removeItem(at: backup) }
    }

    static func clearModelSelection() throws {
        try restoreOfficial()
    }
    static func install(route: Route, executable: String, target: URL = configURL, backup: URL = backupURL) throws {
        let fm = FileManager.default
        let existed = fm.fileExists(atPath: target.path)
        let current = existed ? try Data(contentsOf: target) : Data()
        var original = current
        var originalExisted = existed
        if fm.fileExists(atPath: backup.path) {
            let previous = try JSONDecoder().decode(ConfigBackup.self, from: Data(contentsOf: backup))
            guard previous.path == target.path else { throw RouterError("Codex 配置备份路径不一致，已停止写入。") }
            if previous.installed == current {
                original = previous.original
            } else {
                guard let currentText = String(data: current, encoding: .utf8),
                      let previousOriginal = String(data: previous.original, encoding: .utf8) else { throw RouterError("Codex 配置不是 UTF-8 文本。") }
                original = Data(restoringManagedConfiguration(current: currentText, original: previousOriginal).utf8)
            }
            originalExisted = previous.existed
        }
        guard let currentText = String(data: current, encoding: .utf8) else { throw RouterError("Codex 配置不是 UTF-8 文本。") }
        let updated = Data(try render(original: stripManagedConfiguration(currentText), route: route, executable: executable).utf8)
        try fm.createDirectory(at: backup.deletingLastPathComponent(), withIntermediateDirectories: true)
        try fm.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        let record = ConfigBackup(existed: originalExisted, original: original, installed: updated, path: target.path)
        // Save recovery data before writing the live config.
        try JSONEncoder().encode(record).write(to: backup, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: backup.path)
        try updated.write(to: target, options: .atomic)
        try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
    }
    static func restore(backup: URL = backupURL) throws {
        let fm = FileManager.default
        let record = try JSONDecoder().decode(ConfigBackup.self, from: Data(contentsOf: backup))
        let target = URL(fileURLWithPath: record.path)
        let current = (try? Data(contentsOf: target)) ?? Data()
        if current == record.installed {
            if record.existed {
                try record.original.write(to: target, options: .atomic)
                try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
            } else if fm.fileExists(atPath: target.path) {
                try fm.removeItem(at: target)
            }
            try fm.removeItem(at: backup)
            return
        }
        guard let currentText = String(data: current, encoding: .utf8),
              let originalText = String(data: record.original, encoding: .utf8) else { throw RouterError("Codex 配置不是 UTF-8 文本。") }
        let restored = restoringManagedConfiguration(current: currentText, original: originalText)
        if record.existed || !restored.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            try Data(restored.utf8).write(to: target, options: .atomic)
            try fm.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
        } else if fm.fileExists(atPath: target.path) { try fm.removeItem(at: target) }
        try fm.removeItem(at: backup)
    }
}

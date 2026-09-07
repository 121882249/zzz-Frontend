import Foundation
import Network
import CryptoKit
import Darwin

// Secrets stay in a private file; Claude receives only a loopback credential.
struct ClaudeBridgeRoute: Codable {
    var model: PricedModel
    var alias: String { Self.alias(model) }
    static func alias(_ model: PricedModel) -> String {
        "claude-tokenpro-" + SHA256.hash(data: Data(model.id.utf8)).prefix(12).map { String(format: "%02x", $0) }.joined()
    }
    var usesResponses: Bool { model.platform.lowercased() == "openai" }
}
struct ClaudeBridgeSettings: Codable {
    var accountID: String
    var port: UInt16
    var token: String
    var routes: [ClaudeBridgeRoute]
    var keyID: Int64
    var key: String
    var baseURL: String { "http://127.0.0.1:\(port)" }
    static var url: URL { Persistence.directory.appendingPathComponent("claude-bridge.json") }
    static func load(from url: URL = url) throws -> Self {
        let result = try JSONDecoder().decode(Self.self, from: Data(contentsOf: url))
        guard result.port > 1024, !result.token.isEmpty, !result.routes.isEmpty else { throw RouterError("请先选择 Claude 桌面模型。") }
        return result
    }
    func save(to url: URL = Self.url) throws {
        let fm = FileManager.default
        try fm.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
        try fm.setAttributes([.posixPermissions: 0o700], ofItemAtPath: url.deletingLastPathComponent().path)
        // Create with restrictive permissions before writing, including during atomic replacement.
        let temporary = url.deletingLastPathComponent().appendingPathComponent(UUID().uuidString)
        guard fm.createFile(atPath: temporary.path, contents: nil, attributes: [.posixPermissions: 0o600]) else { throw RouterError("无法保存本地连接。") }
        defer { try? fm.removeItem(at: temporary) }
        try JSONEncoder().encode(self).write(to: temporary)
        guard Darwin.rename(temporary.path, url.path) == 0 else { throw RouterError("无法保存本地连接。") }
    }
}

struct ClaudeBridgeManager {
    static func healthy(_ config: ClaudeBridgeSettings) async -> Bool {
        var req = URLRequest(url: URL(string: config.baseURL + "/health")!)
        req.timeoutInterval = 0.7
        req.setValue("Bearer " + config.token, forHTTPHeaderField: "Authorization")
        let session = URLSession(configuration: .ephemeral, delegate: NoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        guard let (data, response) = try? await session.data(for: req), (response as? HTTPURLResponse)?.statusCode == 200,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return false }
        return object["service"] as? String == "tokenpro-claude-bridge-v1"
    }
    static func ensureRunning(executable: String) async throws {
        let config = try ClaudeBridgeSettings.load()
        if await healthy(config) { return }
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = ["--claude-bridge"]
        var environment = ProcessInfo.processInfo.environment
        environment.removeValue(forKey: "CLAUDE_HELPER_CONTEXT")
        process.environment = environment
        process.standardInput = FileHandle.nullDevice
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        for _ in 0..<35 {
            try await Task.sleep(nanoseconds: 100_000_000)
            if await healthy(config) { return }
        }
        throw RouterError("Claude 本地连接服务未能启动，可能是端口被占用。请重新选择模型后重试。")
    }
    static func remove() throws {
        if FileManager.default.fileExists(atPath: ClaudeBridgeSettings.url.path) { try FileManager.default.removeItem(at: ClaudeBridgeSettings.url) }
    }
}

// A strict one-request HTTP/1.1 connection. Bounded input, no CORS, no proxying arbitrary URLs.
final class ClaudeBridgeConnection: @unchecked Sendable {
    let connection: NWConnection
    let configURL: URL
    var worker: Task<Void, Never>?
    private var buffer = Data()
    private var header: (method: String, path: String, headers: [String: String], length: Int)?
    private var timer: DispatchWorkItem?
    private let queue = DispatchQueue(label: "tokenpro.claude.connection")
    init(_ connection: NWConnection, configURL: URL) { self.connection = connection; self.configURL = configURL }
    func start() {
        connection.stateUpdateHandler = { [weak self] state in
            if case .failed = state { self?.worker?.cancel(); self?.timer?.cancel() }
            if case .cancelled = state { self?.worker?.cancel(); self?.timer?.cancel() }
        }
        connection.start(queue: queue)
        let timeout = DispatchWorkItem { [weak self] in self?.connection.cancel() }
        timer = timeout; queue.asyncAfter(deadline: .now() + 30, execute: timeout)
        receive()
    }
    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [self] data, _, done, error in
            guard error == nil else { connection.cancel(); return }
            if let data { buffer.append(data) }
            do {
                if header == nil {
                    if let range = buffer.range(of: Data("\r\n\r\n".utf8)) {
                        guard range.lowerBound <= 16384, let text = String(data: buffer[..<range.lowerBound], encoding: .utf8) else { throw RouterError("Invalid HTTP headers") }
                        let lines = text.components(separatedBy: "\r\n")
                        let first = lines[0].split(separator: " ")
                        guard first.count == 3, first[2] == "HTTP/1.1" else { throw RouterError("Invalid HTTP request") }
                        var fields: [String: String] = [:]
                        for line in lines.dropFirst() {
                            guard let colon = line.firstIndex(of: ":") else { throw RouterError("Invalid HTTP header") }
                            let name = String(line[..<colon]).lowercased()
                            guard fields[name] == nil else { throw RouterError("Duplicate HTTP header") }
                            fields[name] = String(line[line.index(after: colon)...]).trimmingCharacters(in: .whitespaces)
                        }
                        guard fields["transfer-encoding"] == nil, let length = Int(fields["content-length"] ?? "0"), (0...33_554_432).contains(length) else { throw RouterError("Unsupported request encoding or size") }
                        header = (String(first[0]), String(first[1]).components(separatedBy: "?")[0], fields, length)
                        buffer.removeSubrange(..<range.upperBound)
                    } else if buffer.count > 16384 { throw RouterError("HTTP headers too large") }
                }
                if let header, buffer.count >= header.length {
                    guard buffer.count == header.length else { throw RouterError("Pipelining is not supported") }
                    timer?.cancel()
                    let body = buffer; buffer = Data()
                    worker = Task { [self] in await self.handle(header.method, path: header.path, headers: header.headers, body: body) }
                    connection.receive(minimumIncompleteLength: 1, maximumLength: 1) { [weak self] _, _, ended, failure in
                        if ended || failure != nil { self?.worker?.cancel() }
                    }
                    return
                }
                if done { connection.cancel() } else { receive() }
            } catch {
                worker = Task { [self] in try? await self.replyError(400, "invalid_request_error", error.localizedDescription); self.connection.cancel() }
            }
        }
    }
    func send(_ data: Data) async throws {
        try Task.checkCancellation()
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if let error { cont.resume(throwing: error) } else { cont.resume() }
            })
        }
    }
    func reply(_ status: Int, data: Data, type: String = "application/json") async throws {
        let header = "HTTP/1.1 \(status) \(status == 200 ? "OK" : "Error")\r\nContent-Type: \(type)\r\nContent-Length: \(data.count)\r\nConnection: close\r\nCache-Control: no-store\r\n\r\n"
        try await send(Data(header.utf8) + data)
    }
    func replyJSON(_ object: Any, status: Int = 200) async throws { try await reply(status, data: JSONSerialization.data(withJSONObject: object)) }
    func replyError(_ status: Int, _ type: String, _ message: String) async throws {
        try await replyJSON(["type": "error", "error": ["type": type, "message": message]], status: status)
    }
    func beginStream() async throws {
        try await send(Data("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".utf8))
    }
    func chunk(_ data: Data) async throws {
        guard !data.isEmpty else { return }
        try await send(Data("\(String(data.count, radix: 16))\r\n".utf8) + data + Data("\r\n".utf8))
    }
    func event(_ object: [String: Any]) async throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes])
        try await chunk(Data("event: \(object["type"] as? String ?? "error")\ndata: ".utf8) + data + Data("\n\n".utf8))
    }
    private func handle(_ method: String, path: String, headers: [String: String], body: Data) async {
        var requestedModel = ""
        var upstreamSecret = ""
        var streaming = false
        var locked = false
        var keyLock: ClaudeKeyLock?
        let sessionConfig = URLSessionConfiguration.ephemeral
        // Do not let one stalled upstream request hold the single-Key gate for
        // minutes. The defer below releases both locks when this deadline fires,
        // so a different model can be selected immediately afterwards.
        sessionConfig.timeoutIntervalForRequest = 45
        sessionConfig.timeoutIntervalForResource = 90
        sessionConfig.waitsForConnectivity = false
        sessionConfig.httpCookieStorage = nil
        let session = URLSession(configuration: sessionConfig, delegate: NoRedirect(), delegateQueue: nil)
        defer { keyLock?.unlock(); session.invalidateAndCancel(); connection.cancel(); worker = nil; if locked { Task { await ClaudeRequestGate.shared.release() } } }
        do {
            let config = try ClaudeBridgeSettings.load(from: configURL)
            guard headers["authorization"] == "Bearer " + config.token || headers["x-api-key"] == config.token else {
                try await replyError(401, "authentication_error", "本地连接凭据已失效，请从 TokenPro 打开 Claude。"); return
            }
            guard headers["origin"] == nil else { try await replyError(403, "permission_error", "Browser requests are not allowed"); return }
            if method == "GET", path == "/health" { try await replyJSON(["service": "tokenpro-claude-bridge-v1"]); return }
            if method == "GET", path == "/v1/models" {
                try await replyJSON(["data": config.routes.map { ["id": $0.alias, "type": "model", "display_name": $0.model.name, "created_at": "2026-01-01T00:00:00Z"] }, "has_more": false]); return
            }
            guard method == "POST", ["/v1/messages", "/v1/messages/count_tokens"].contains(path) else { try await replyError(404, "not_found_error", "Unsupported endpoint"); return }
            guard var input = try JSONSerialization.jsonObject(with: body) as? [String: Any], let alias = input["model"] as? String else { throw RouterError("请求缺少模型。") }
            // Resolve the requested model independently; hold the one-Key gate until the response ends.
            guard let route = config.routes.first(where: { $0.alias == alias }) else { try await replyError(404, "not_found_error", "该模型未在 TokenPro 中选择，请更新模型列表。"); return }
            if path.hasSuffix("count_tokens") {
                try await replyJSON(["input_tokens": ClaudeTokenEstimate.count(input), "tokenpro_estimated": true]); return
            }
            requestedModel = route.model.name
            upstreamSecret = config.key
            ClaudeBridgeDiagnostics.record("request", model: requestedModel, detail: "\(path) stream=\(input["stream"] as? Bool ?? false)")
            await ClaudeRequestGate.shared.acquire(priority: input["stream"] as? Bool == true ? 1 : 0)
            locked = true
            keyLock = try await ClaudeKeyLock.acquire()
            try Task.checkCancellation()
            // Reload after waiting: a removed selection or changed account must not use stale credentials.
            let latest = try ClaudeBridgeSettings.load(from: configURL)
            guard latest.accountID == config.accountID, latest.keyID == config.keyID,
                  latest.routes.contains(where: { $0.alias == alias }) else { throw RouterError("模型配置已变化，请重试。") }
            try await ClaudeRequestGate.selectGroup(config: latest, route: route)
            let roleSummary = (input["messages"] as? [[String: Any]] ?? []).map { String(describing: $0["role"] ?? "missing").prefix(24) }.joined(separator: ",")
            ClaudeBridgeDiagnostics.record("roles", model: requestedModel, detail: roleSummary)
            input = ClaudeHistory.prepare(input, route: route)
            input["model"] = route.model.name
            let payload = route.usesResponses ? try ClaudeResponsesAdapter.request(input) : input
            var request = URLRequest(url: URL(string: "https://tokenpro.work" + (route.usesResponses ? "/v1/responses" : path))!)
            request.httpMethod = "POST"
            request.setValue("Bearer " + config.key, forHTTPHeaderField: "Authorization")
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue(headers["anthropic-version"] ?? "2023-06-01", forHTTPHeaderField: "anthropic-version")
            if !route.usesResponses, let beta = headers["anthropic-beta"] { request.setValue(beta, forHTTPHeaderField: "anthropic-beta") }
            request.httpBody = try JSONSerialization.data(withJSONObject: payload)
            if input["stream"] as? Bool != true {
                let (data, response) = try await session.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 502
                if status != 200 { try await upstreamError(data, status: status, route: route, key: config.key); return }
                if route.usesResponses {
                    guard let result = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw RouterError("上游返回无效响应。") }
                    try await replyJSON(try ClaudeResponsesAdapter.response(result, model: route.model.name))
                } else if let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    try await replyJSON(ClaudeHistory.tagResponse(object, alias: route.alias))
                } else { throw RouterError("上游返回格式不兼容。") }
                return
            }
            let (bytes, response) = try await session.bytes(for: request)
            let status = (response as? HTTPURLResponse)?.statusCode ?? 502
            guard status == 200 else {
                var data = Data()
                for try await byte in bytes { if data.count < 65536 { data.append(byte) } else { break } }
                try await upstreamError(data, status: status, route: route, key: config.key); return
            }
            guard (response as? HTTPURLResponse)?.value(forHTTPHeaderField: "Content-Type")?.contains("text/event-stream") == true else { throw RouterError("上游未返回流式响应。") }
            try await beginStream(); streaming = true
            let adapter = ClaudeResponsesStream(model: route.model.name)
            var eventData: [String] = []
            var nativeCompleted = false
            var taggedIndices = Set<Int>()
            var lineBytes = Data()
            for try await byte in bytes {
                lineBytes.append(byte)
                guard lineBytes.count <= 4_194_304 else { throw RouterError("上游流式事件过大。") }
                guard byte == 10 else { continue }
                var line = String(decoding: lineBytes.dropLast(), as: UTF8.self)
                if line.hasSuffix("\r") { line.removeLast() }
                lineBytes.removeAll(keepingCapacity: true)
                try Task.checkCancellation()
                if route.usesResponses {
                    if line.isEmpty {
                        let text = eventData.joined(separator: "\n"); eventData.removeAll(keepingCapacity: true)
                        if text == "[DONE]" || text.isEmpty { continue }
                        guard let data = text.data(using: .utf8), let object = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw RouterError("无效的流式事件。") }
                        for event in try adapter.consume(object) { try await self.event(event) }
                    } else if line.hasPrefix("data:") { eventData.append(String(line.dropFirst(5)).trimmingCharacters(in: .whitespaces)) }
                } else {
                    if line.hasPrefix("data:"), let data = String(line.dropFirst(5)).data(using: .utf8), var object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                        let index = object["index"] as? Int ?? 0
                        if object["type"] as? String == "message_stop" { nativeCompleted = true }
                        if var delta = object["delta"] as? [String: Any], delta["type"] as? String == "signature_delta", let signature = delta["signature"] as? String, taggedIndices.insert(index).inserted {
                            delta["signature"] = ClaudeHistory.prefix(route.alias) + signature; object["delta"] = delta
                        }
                        if let block = object["content_block"] as? [String: Any] {
                            object["content_block"] = ClaudeHistory.tagBlock(block, alias: route.alias)
                            if let signature = block["signature"] as? String, !signature.isEmpty { taggedIndices.insert(index) }
                        }
                        let encoded = try JSONSerialization.data(withJSONObject: object, options: [.withoutEscapingSlashes])
                        try await chunk(Data("data: ".utf8) + encoded + Data("\n".utf8))
                    } else { try await chunk(Data((line + "\n").utf8)) }
                }
            }
            guard route.usesResponses ? adapter.completed : nativeCompleted else { throw RouterError("上游连接提前结束，回答未完成。") }
            try await send(Data("0\r\n\r\n".utf8))
            ClaudeBridgeDiagnostics.record("completed", model: requestedModel)
        } catch {
            var message = error is CancellationError ? "请求已取消。" : error.localizedDescription
            if !upstreamSecret.isEmpty { message = message.replacingOccurrences(of: upstreamSecret, with: "[redacted]") }
            ClaudeBridgeDiagnostics.record("error", model: requestedModel, detail: message)
            if streaming {
                try? await event(["type": "error", "error": ["type": "api_error", "message": message]])
                try? await send(Data("0\r\n\r\n".utf8))
            } else {
                let status = (error as? BackendFailure)?.status ?? (error is RouterError ? 400 : 502)
                try? await replyError(status, "api_error", message)
            }
        }
    }
    private func upstreamError(_ data: Data, status: Int, route: ClaudeBridgeRoute, key: String) async throws {
        let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
        let detail = (object?["error"] as? [String: Any])?["message"] as? String ?? "上游返回 HTTP \(status)"
        let safe = String(detail.prefix(1500)).replacingOccurrences(of: key, with: "[redacted]")
        ClaudeBridgeDiagnostics.record("upstream_error", model: route.model.name, detail: "HTTP \(status): " + safe)
        try await replyError(status, status == 401 ? "authentication_error" : "api_error", "\(route.model.name)（\(route.model.groupName)）：\(safe)")
    }
}

final class ClaudeBridgeServer {
    let listener: NWListener
    var timer: DispatchSourceTimer?
    init(config: ClaudeBridgeSettings, configURL: URL = ClaudeBridgeSettings.url) throws {
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: config.port)!)
        listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { connection in ClaudeBridgeConnection(connection, configURL: configURL).start() }
        listener.stateUpdateHandler = { if case .failed = $0 { exit(1) } }
        listener.start(queue: DispatchQueue(label: "tokenpro.claude.listener"))
        timer = DispatchSource.makeTimerSource(queue: .global())
        timer?.schedule(deadline: .now() + 2, repeating: 2)
        timer?.setEventHandler {
            guard let current = try? ClaudeBridgeSettings.load(from: configURL), current.port == config.port else { exit(0) }
        }
        timer?.resume()
    }
}

// This mutex spans upstream streaming, not just the group-update API call.
actor ClaudeRequestGate {
    static let shared = ClaudeRequestGate()
    private var busy = false
    private var waiters: [(Int, CheckedContinuation<Void, Never>)] = []
    func acquire(priority: Int = 1) async {
        if !busy { busy = true; return }
        await withCheckedContinuation { waiters.append((priority, $0)) }
    }
    func release() {
        if waiters.isEmpty { busy = false } else {
            let priority = waiters.map { $0.0 }.max()!
            let index = waiters.firstIndex { $0.0 == priority }!
            waiters.remove(at: index).1.resume()
        }
    }
    @MainActor static func selectGroup(config: ClaudeBridgeSettings, route: ClaudeBridgeRoute) async throws {
        let account = NativeAccount()
        await account.start(loadPublicSettings: false)
        guard account.signedIn, String(describing: account.user["id"] ?? "") == config.accountID else {
            throw RouterError("TokenPro 账户已变化或登录过期，请重新登录并选择模型。")
        }
        guard let groupID = route.model.groupID else { throw RouterError("模型没有有效分组。") }
        let detail = try await account.authenticated("/keys/\(config.keyID)")
        guard detail["name"] as? String == "TokenPro · Claude", detail["status"] as? String == "active" else {
            throw RouterError("Claude 专用 Key 不可用，请在 TokenPro 中重新选择模型。")
        }
        if (detail["group_id"] as? NSNumber)?.int64Value != groupID {
            _ = try await account.switchClaudeKeyGroup(keyID: config.keyID, groupID: groupID)
        }
    }
}

final class ClaudeKeyLock {
    private var fd: Int32
    private init(_ fd: Int32) { self.fd = fd }
    static func acquire() async throws -> ClaudeKeyLock {
        try FileManager.default.createDirectory(at: Persistence.directory, withIntermediateDirectories: true)
        let fd = Darwin.open(Persistence.directory.appendingPathComponent("claude-key.lock").path, O_CREAT | O_RDWR | O_CLOEXEC, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw RouterError("无法锁定 Claude 连接。") }
        do {
            while flock(fd, LOCK_EX | LOCK_NB) != 0 {
                guard errno == EWOULDBLOCK else { throw RouterError("无法锁定 Claude 连接。") }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            try Task.checkCancellation()
            return ClaudeKeyLock(fd)
        } catch { Darwin.close(fd); throw error }
    }
    func unlock() {
        if fd >= 0 { flock(fd, LOCK_UN); Darwin.close(fd); fd = -1 }
    }
    deinit { unlock() }
}

// Only diagnostic metadata is retained; never write messages, tool arguments, or credentials.
enum ClaudeBridgeDiagnostics {
    private static let lock = NSLock()
    static func record(_ phase: String, model: String = "", detail: String = "") {
        lock.lock(); defer { lock.unlock() }
        let url = Persistence.directory.appendingPathComponent("claude-bridge-diagnostics.jsonl")
        let object = ["time": ISO8601DateFormatter().string(from: Date()), "phase": phase, "model": model, "detail": detail]
        guard let data = try? JSONSerialization.data(withJSONObject: object) else { return }
        if !FileManager.default.fileExists(atPath: url.path) { _ = FileManager.default.createFile(atPath: url.path, contents: nil, attributes: [.posixPermissions: 0o600]) }
        guard let handle = try? FileHandle(forWritingTo: url) else { return }
        defer { try? handle.close() }
        if let size = try? handle.seekToEnd(), size > 65536 { try? handle.truncate(atOffset: 0); try? handle.seek(toOffset: 0) }
        try? handle.write(contentsOf: data + Data("\n".utf8))
    }
}

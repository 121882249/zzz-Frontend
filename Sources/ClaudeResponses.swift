import Foundation

// Messages <-> Responses conversion is used only for OpenAI groups. Other groups keep Messages intact.
enum ClaudeResponsesAdapter {
    static func blocks(_ value: Any?) throws -> [[String: Any]] {
        if let text = value as? String { return [["type": "text", "text": text]] }
        if let array = value as? [[String: Any]] { return array }
        if value == nil { return [] }
        throw RouterError("不支持的消息内容格式。")
    }
    static func request(_ source: [String: Any]) throws -> [String: Any] {
        guard let model = source["model"] as? String, let messages = source["messages"] as? [[String: Any]] else { throw RouterError("消息缺少模型或内容。") }
        var result: [String: Any] = ["model": model, "stream": source["stream"] as? Bool ?? false, "store": false]
        let system = try blocks(source["system"])
        guard system.allSatisfy({ $0["type"] as? String == "text" }) else { throw RouterError("该模型暂不支持这种系统消息。") }
        result["instructions"] = system.compactMap { $0["text"] as? String }.joined(separator: "\n")
        if let max = source["max_tokens"] { result["max_output_tokens"] = max }
        if let effort = (source["output_config"] as? [String: Any])?["effort"] as? String {
            result["reasoning"] = ["effort": effort == "max" ? "xhigh" : effort]
        }
        if let format = (source["output_config"] as? [String: Any])?["format"] as? [String: Any] {
            guard format["type"] as? String == "json_schema", let schema = format["schema"] as? [String: Any] else { throw RouterError("该模型不支持此输出格式。") }
            result["text"] = ["format": ["type": "json_schema", "name": "claude_output", "schema": schema, "strict": false]]
        }
        var input: [[String: Any]] = []
        for message in messages {
            guard let role = message["role"] as? String, ["user", "assistant", "system", "developer"].contains(role) else { throw RouterError("不支持的消息角色：\(String(describing: message["role"] ?? "missing").prefix(24))。") }
            var content: [[String: Any]] = []
            func flush() {
                if !content.isEmpty { input.append(["role": role, "content": content]); content.removeAll() }
            }
            for block in try blocks(message["content"]) {
                switch block["type"] as? String {
                case "text":
                    content.append(["type": role == "assistant" ? "output_text" : "input_text", "text": block["text"] as? String ?? ""])
                case "image":
                    guard role == "user", let image = block["source"] as? [String: Any] else { throw RouterError("图片格式不支持。") }
                    let url: String
                    if image["type"] as? String == "base64", let data = image["data"] as? String, let mime = image["media_type"] as? String { url = "data:\(mime);base64,\(data)" }
                    else if image["type"] as? String == "url", let value = image["url"] as? String { url = value }
                    else { throw RouterError("图片格式不支持。") }
                    content.append(["type": "input_image", "image_url": url])
                case "tool_use":
                    guard role == "assistant", let id = block["id"] as? String, let name = block["name"] as? String else { throw RouterError("工具调用缺少编号或名称。") }
                    flush()
                    let args = try JSONSerialization.data(withJSONObject: block["input"] ?? [:], options: [.sortedKeys])
                    input.append(["type": "function_call", "call_id": id, "name": name, "arguments": String(decoding: args, as: UTF8.self)])
                case "tool_result":
                    guard let id = block["tool_use_id"] as? String else { throw RouterError("工具结果缺少编号。") }
                    flush()
                    let output = try blocks(block["content"])
                    guard output.allSatisfy({ $0["type"] as? String == "text" }) else { throw RouterError("当前 GPT 适配暂不支持含图片的工具结果，请改用文本结果。") }
                    let text = output.compactMap { $0["text"] as? String }.joined(separator: "\n")
                    input.append(["type": "function_call_output", "call_id": id, "output": (block["is_error"] as? Bool == true ? "Tool error: " : "") + text])
                case "thinking", "redacted_thinking":
                    // Vendor-specific reasoning signatures cannot be replayed to a different vendor.
                    continue
                default: throw RouterError("该模型暂不支持消息块：\(block["type"] as? String ?? "unknown")。")
                }
            }
            flush()
        }
        result["input"] = input
        if let tools = source["tools"] as? [[String: Any]] {
            result["tools"] = try tools.map { tool -> [String: Any] in
                guard let name = tool["name"] as? String, let schema = tool["input_schema"] as? [String: Any], tool["type"] == nil || tool["type"] as? String == "custom" else { throw RouterError("该模型暂不支持这个内置工具。") }
                return ["type": "function", "name": name, "description": tool["description"] as? String ?? "", "parameters": schema, "strict": false]
            }
        }
        if let choice = source["tool_choice"] as? [String: Any] {
            switch choice["type"] as? String {
            case "auto": result["tool_choice"] = "auto"
            case "any": result["tool_choice"] = "required"
            case "none": result["tool_choice"] = "none"
            case "tool": result["tool_choice"] = ["type": "function", "name": choice["name"] as? String ?? ""]
            default: throw RouterError("工具选择方式不支持。")
            }
            if choice["disable_parallel_tool_use"] as? Bool == true { result["parallel_tool_calls"] = false }
        }
        if let sequences = source["stop_sequences"] as? [String], !sequences.isEmpty { throw RouterError("Responses 接口不支持自定义停止字符串。") }
        return result
    }
    static func usage(_ response: [String: Any]) -> [String: Any] {
        let usage = response["usage"] as? [String: Any] ?? [:]
        let total = usage["input_tokens"] as? Int ?? 0
        let cached = (usage["input_tokens_details"] as? [String: Any])?["cached_tokens"] as? Int ?? 0
        return ["input_tokens": max(0, total - cached), "output_tokens": usage["output_tokens"] as? Int ?? 0, "cache_read_input_tokens": cached, "cache_creation_input_tokens": 0]
    }
    static func stop(_ response: [String: Any], tools: Bool) -> String {
        if response["status"] as? String == "incomplete" { return "max_tokens" }
        return tools ? "tool_use" : "end_turn"
    }
    static func response(_ response: [String: Any], model: String) throws -> [String: Any] {
        guard ["completed", "incomplete"].contains(response["status"] as? String ?? "") else { throw RouterError("上游没有完成请求。") }
        var content: [[String: Any]] = []
        var hasTools = false
        for item in response["output"] as? [[String: Any]] ?? [] {
            switch item["type"] as? String {
            case "message":
                for block in item["content"] as? [[String: Any]] ?? [] {
                    if block["type"] as? String == "output_text" { content.append(["type": "text", "text": block["text"] as? String ?? ""]) }
                    else if block["type"] as? String == "refusal" { content.append(["type": "text", "text": block["refusal"] as? String ?? ""]) }
                }
            case "function_call":
                guard let id = item["call_id"] as? String, let name = item["name"] as? String,
                      let args = (item["arguments"] as? String)?.data(using: .utf8), let object = try JSONSerialization.jsonObject(with: args) as? [String: Any] else { throw RouterError("上游工具参数无效。") }
                hasTools = true; content.append(["type": "tool_use", "id": id, "name": name, "input": object])
            case "reasoning": continue
            default: throw RouterError("上游返回了不支持的输出内容。")
            }
        }
        return ["id": response["id"] as? String ?? "msg_" + UUID().uuidString, "type": "message", "role": "assistant", "model": model, "content": content, "stop_reason": stop(response, tools: hasTools), "stop_sequence": NSNull(), "usage": usage(response)]
    }
}

final class ClaudeResponsesStream {
    let model: String
    private var started = false
    private var nextIndex = 0
    private var open: [String: Int] = [:]
    private var toolIDs = Set<String>()
    private var hasTools = false
    private(set) var completed = false
    init(model: String) { self.model = model }
    func consume(_ event: [String: Any]) throws -> [[String: Any]] {
        let type = event["type"] as? String ?? ""
        var output: [[String: Any]] = []
        let response = event["response"] as? [String: Any] ?? [:]
        if !started {
            started = true
            output.append(["type": "message_start", "message": ["id": response["id"] as? String ?? "msg_" + UUID().uuidString, "type": "message", "role": "assistant", "model": model, "content": [], "stop_reason": NSNull(), "stop_sequence": NSNull(), "usage": ["input_tokens": 0, "output_tokens": 0]]])
        }
        func begin(_ key: String, _ block: [String: Any]) -> Int {
            if let index = open[key] { return index }
            let index = nextIndex; nextIndex += 1; open[key] = index
            output.append(["type": "content_block_start", "index": index, "content_block": block])
            return index
        }
        switch type {
        case "response.output_text.delta", "response.refusal.delta":
            let key = "text:\(event["output_index"] as? Int ?? 0):\(event["content_index"] as? Int ?? 0)"
            let index = begin(key, ["type": "text", "text": ""])
            output.append(["type": "content_block_delta", "index": index, "delta": ["type": "text_delta", "text": event["delta"] as? String ?? ""]])
        case "response.output_item.added":
            if let item = event["item"] as? [String: Any], item["type"] as? String == "function_call" {
                guard let id = item["id"] as? String, let call = item["call_id"] as? String, let name = item["name"] as? String else { throw RouterError("流式工具调用缺少标识。") }
                hasTools = true; toolIDs.insert(id)
                _ = begin(id, ["type": "tool_use", "id": call, "name": name, "input": [:]])
            }
        case "response.function_call_arguments.delta":
            guard let id = event["item_id"] as? String, let index = open[id] else { throw RouterError("流式工具参数缺少对应调用。") }
            output.append(["type": "content_block_delta", "index": index, "delta": ["type": "input_json_delta", "partial_json": event["delta"] as? String ?? ""]])
        case "response.completed", "response.incomplete":
            for index in open.values.sorted() { output.append(["type": "content_block_stop", "index": index]) }
            open.removeAll()
            output.append(["type": "message_delta", "delta": ["stop_reason": ClaudeResponsesAdapter.stop(response, tools: hasTools), "stop_sequence": NSNull()], "usage": ClaudeResponsesAdapter.usage(response)])
            output.append(["type": "message_stop"]); completed = true
        case "response.failed", "error":
            throw RouterError((response["error"] as? [String: Any])?["message"] as? String ?? event["message"] as? String ?? "上游模型请求失败。")
        default: break
        }
        return output
    }
}

// Signatures are opaque to the desktop. Tag them locally so a vendor never receives another vendor's signature.
enum ClaudeHistory {
    static func prefix(_ alias: String) -> String { "tokenpro:" + alias + ":" }
    static func prepare(_ source: [String: Any], route: ClaudeBridgeRoute) -> [String: Any] {
        var result = source
        var extraSystem: [[String: Any]] = []
        if let messages = source["messages"] as? [[String: Any]] {
            let chatMessages = messages.filter { message in
                if !route.usesResponses, ["system", "developer"].contains(message["role"] as? String ?? "") {
                    if let text = message["content"] as? String { extraSystem.append(["type": "text", "text": text]) }
                    else if let blocks = message["content"] as? [[String: Any]] { extraSystem.append(contentsOf: blocks) }
                    return false
                }
                return true
            }
            result["messages"] = chatMessages.map { message -> [String: Any] in
                var message = message
                if let blocks = message["content"] as? [[String: Any]] {
                    message["content"] = blocks.compactMap { block -> [String: Any]? in
                        let type = block["type"] as? String
                        guard type == "thinking" || type == "redacted_thinking" else { return block }
                        let field = type == "thinking" ? "signature" : "data"
                        guard let signature = block[field] as? String, signature.hasPrefix(prefix(route.alias)) else { return nil }
                        var block = block
                        block[field] = String(signature.dropFirst(prefix(route.alias).count))
                        return block
                    }
                }
                return message
            }
        }
        let info = "TokenPro routing metadata: selected model ID = \(route.model.name). Claude Desktop is the application name, not evidence of the underlying model. Do not infer model identity from earlier replies. The upstream provider's internal model mapping cannot be independently verified."
        let existingSystem = (source["system"] as? String).map { [["type": "text", "text": $0]] } ?? (source["system"] as? [[String: Any]] ?? [])
        result["system"] = existingSystem + extraSystem + [["type": "text", "text": info]]
        return result
    }
    static func tagBlock(_ original: [String: Any], alias: String) -> [String: Any] {
        var block = original
        let type = block["type"] as? String
        let field = type == "redacted_thinking" ? "data" : "signature"
        if ["thinking", "redacted_thinking"].contains(type ?? ""), let value = block[field] as? String, !value.isEmpty { block[field] = prefix(alias) + value }
        return block
    }
    static func tagResponse(_ response: [String: Any], alias: String) -> [String: Any] {
        var response = response
        if let blocks = response["content"] as? [[String: Any]] { response["content"] = blocks.map { tagBlock($0, alias: alias) } }
        return response
    }
}

// A conservative local preview, never substituted into inference usage or billing fields.
// Desktop falls back to paid one-token generations if count_tokens is unavailable.
enum ClaudeTokenEstimate {
    static func count(_ request: [String: Any]) -> Int {
        var relevant: [String: Any] = [:]
        for key in ["messages", "system", "tools"] { if let value = request[key] { relevant[key] = value } }
        guard let data = try? JSONSerialization.data(withJSONObject: relevant), let text = String(data: data, encoding: .utf8) else { return 1 }
        var ascii = 0, unicode = 0
        for scalar in text.unicodeScalars { if scalar.value < 128 { ascii += 1 } else { unicode += 1 } }
        return max(1, (ascii + 2) / 3 + unicode * 2 + 16)
    }
}

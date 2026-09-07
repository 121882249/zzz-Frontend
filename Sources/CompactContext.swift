import Foundation

enum CompactContext {
    static var enabled: Bool {
        get { UserDefaults.standard.object(forKey: "tokenproCompactContext") as? Bool ?? false }
        set { UserDefaults.standard.set(newValue, forKey: "tokenproCompactContext") }
    }

    private static let marker = "# tokenpro-context-restore "
    private struct Override: Codable {
        let original: String?
        let installed: String
    }

    // Keep recovery data alongside each owned scalar so later config edits can be
    // preserved without restoring an entire, potentially stale configuration.
    static func restore(_ text: String) -> String {
        let lines = text.components(separatedBy: "\n")
        var result: [String] = []
        var index = 0
        while index < lines.count {
            let line = lines[index]
            if line.hasPrefix(marker),
               let data = Data(base64Encoded: String(line.dropFirst(marker.count))),
               let record = try? JSONDecoder().decode(Override.self, from: data),
               index + 1 < lines.count {
                if lines[index + 1] == record.installed {
                    if let original = record.original { result.append(original) }
                    index += 2
                } else {
                    // An external edit to this scalar takes precedence.
                    index += 1
                }
                continue
            }
            result.append(line)
            index += 1
        }
        return result.joined(separator: "\n")
    }

    static func apply(_ text: String) throws -> String {
        var result = restore(text)
        result = try setting(table: "features", key: "apps", value: "false", in: result)
        result = try setting(table: "skills", key: "max_context_tokens", value: "1200", in: result, keepLowerBudget: true)
        return result
    }

    private static func setting(table: String, key: String, value: String, in text: String, keepLowerBudget: Bool = false) throws -> String {
        var lines = text.components(separatedBy: "\n")
        let header = "[\(table)]"
        // Existing simple and quoted table/key forms are supported. Dotted root
        // scalars are handled without introducing conflicting TOML tables.
        let scalar = "(?:\(key)|\"\(key)\"|'\(key)')"
        let dotted = "^\(table)\\.\(scalar)\\s*="
        let local = "^\(scalar)\\s*="
        var inTable = false
        var atRoot = true
        var tableIndex: Int?
        var valueIndex: Int?
        var dottedValue = false
        for (index, line) in lines.enumerated() {
            let trimmed = line.trimmingCharacters(in: .whitespaces)
            if trimmed.hasPrefix("[") {
                atRoot = false
                let bare = trimmed.components(separatedBy: "#")[0].trimmingCharacters(in: .whitespaces)
                inTable = [header, "[\"\(table)\"]", "['\(table)']"].contains(bare)
                if inTable { tableIndex = index }
            } else if (inTable && trimmed.range(of: local, options: .regularExpression) != nil) ||
                        (atRoot && trimmed.range(of: dotted, options: .regularExpression) != nil) {
                valueIndex = index
                dottedValue = atRoot
            } else if atRoot, trimmed.range(of: "^\(table)\\s*=", options: .regularExpression) != nil {
                throw RouterError("精简上下文暂不支持 \(table) 内联配置；请关闭精简选项后保存。")
            }
        }
        let original = valueIndex.map { lines[$0] }
        if keepLowerBudget, let original,
           let rhs = original.split(separator: "=", maxSplits: 1).last,
           let budget = Int(rhs.split(separator: "#", maxSplits: 1)[0].trimmingCharacters(in: .whitespaces)),
           budget > 0, budget <= 1200 { return text }
        let installed = "\(dottedValue ? table + "." : "")\(key) = \(value)"
        if original?.trimmingCharacters(in: .whitespaces) == installed { return text }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let record = try encoder.encode(Override(original: original, installed: installed))
        let replacement = [marker + record.base64EncodedString(), installed]
        if let valueIndex {
            lines.replaceSubrange(valueIndex...valueIndex, with: replacement)
        } else if let tableIndex {
            lines.insert(contentsOf: replacement, at: tableIndex + 1)
        } else {
            lines.append(contentsOf: ["", header] + replacement)
        }
        return lines.joined(separator: "\n")
    }
}

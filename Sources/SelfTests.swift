import Foundation

func runSelfTests() {
    do {
        var checks = 0
        func check(_ ok: Bool, _ label: String) throws { guard ok else { throw RouterError("FAIL: " + label) }; checks += 1 }
        func rejects(_ label: String, _ f: () throws -> Void) throws {
            var threw = false
            do { try f() } catch { threw = true }
            try check(threw, label)
        }
        try check(TokenProSession.trusted(URL(string: "https://tokenpro.work/login")), "website HTTPS origin")
        try check(!TokenProSession.trusted(URL(string: "http://tokenpro.work/login")), "reject website downgrade")
        try check(!TokenProSession.trusted(URL(string: "https://tokenpro.work.evil.example/login")), "reject lookalike origin")
        try check(!TokenProSession.trusted(URL(string: "https://user:password@tokenpro.work/login")), "reject credentials in website URL")
        try check(!TokenProSession.trusted(URL(string: "https://tokenpro.work:444/login")), "reject alternative website port")
        let release = ClientRelease(version: "0.10.0", downloadURL: URL(string: "https://tokenpro.work/client-updates/Codex-Router-latest-macOS-arm64.dmg")!, notes: "test")
        _ = try release.validated()
        try check(release.newer(than: "0.9.9"), "numeric update ordering")
        try check(!release.newer(than: "0.10.0"), "same release")
        try check(!release.newer(than: "1.0.0"), "no downgrade")
        try check(ClientRelease.components("0.4.bad") == nil, "invalid update version")
        try rejects("untrusted update origin") { _ = try ClientRelease(version: "1.0.0", downloadURL: URL(string: "https://evil.example/client-updates/app.dmg")!, notes: "").validated() }
        try rejects("update HTTP downgrade") { _ = try ClientRelease(version: "1.0.0", downloadURL: URL(string: "http://tokenpro.work/client-updates/app.dmg")!, notes: "").validated() }
        let route = Route(name: "Test", baseURL: "https://example.com/v1", model: "test-model", tokenLabel: "test")
        try check(try route.validated() == route, "valid URL")
        var bad = route; bad.baseURL = "http://remote.example.com/v1"
        try rejects("remote plain HTTP") { _ = try bad.validated() }
        bad.baseURL = "https://user:pass@example.com/v1"
        try rejects("URL credentials") { _ = try bad.validated() }
        bad.baseURL = "https://example.com/v1?secret=x"
        try rejects("URL query") { _ = try bad.validated() }
        var local = route; local.baseURL = "http://127.0.0.1:8000/v1/"
        try check(try local.validated().baseURL == "http://127.0.0.1:8000/v1", "trailing slash")
        let original = "# user config\nmodel = \"old\"\nmodel_provider = \"original\"\n[projects.\"/tmp\"]\ntrust_level = \"trusted\"\nmodel = \"section-value\"\n"
        let rendered = try CodexConfig.render(original: original, route: route, executable: "/Applications/TokenPro.app/Contents/MacOS/TokenPro")
        try check(rendered.contains("model = \"section-value\""), "preserve section key")
        try check(!rendered.contains("model = \"old\""), "replace root key")
        try check(rendered.contains("[model_providers.tokenpro_direct.auth]"), "auth helper")
        try check(rendered.contains("base_url = \"https://example.com/v1\""), "direct base URL")
        try check(rendered.contains("args = [\"--route-token\", \"\(route.keyID)\"]"), "route token helper")
        try check(!rendered.contains("127.0.0.1") && !rendered.contains("/route/"), "no local proxy")
        try check(try LocalCredentialStore.routeKeyID(route.keyID) == route.keyID, "valid route token id")
        try rejects("invalid route token id") { _ = try LocalCredentialStore.routeKeyID("tokenpro-native-session") }
        try check(CodexConfig.quote("a\"b\\c\n") == "\"a\\\"b\\\\c\\u000A\"", "TOML escaping")
        try rejects("complex TOML") { _ = try CodexConfig.render(original: "x = '''many\nlines'''", route: route, executable: "test") }
        let priced = [
            PricedModel(name: "gpt-test", platform: "openai", groups: ["GPT"], groupID: 16, rateMultiplier: 0.28),
            PricedModel(name: "gpt-test-fast", platform: "openai", groups: ["GPT"], groupID: 16, rateMultiplier: 0.28)
        ]
        let catalog = try JSONSerialization.jsonObject(with: CodexConfig.catalogData(priced)) as? [String: Any]
        let catalogModels = catalog?["models"] as? [[String: Any]]
        try check(catalogModels?.compactMap { $0["slug"] as? String } == ["gpt-test", "gpt-test-fast"], "selected model catalog")
        try check(catalogModels?.allSatisfy { $0["visibility"] as? String == "list" } == true, "catalog models visible")
        let efforts = catalogModels?.first?["supported_reasoning_levels"] as? [[String: Any]]
        try check(efforts?.compactMap { $0["effort"] as? String } == ["low", "medium", "high"], "reasoning effort levels")
        try check(catalogModels?.first?["default_reasoning_level"] as? String == "medium", "default reasoning effort")
        try check((catalogModels?.first?["description"] as? String)?.contains("0.28x") == true, "group rate in catalog")
        let selectedConfig = try CodexConfig.renderModelSelection(original: original, models: priced, activeModel: priced[1], executable: "/Applications/TokenPro.app/Contents/MacOS/TokenPro", catalog: URL(fileURLWithPath: "/tmp/tokenpro-models.json"))
        try check(selectedConfig.contains("model_catalog_json = \"/tmp/tokenpro-models.json\""), "catalog path")
        try check(selectedConfig.contains("model = \"gpt-test-fast\""), "explicit active model default")
        try check(selectedConfig.contains("args = [\"--tokenpro-model-token\"]"), "model token helper")
        try rejects("empty model selection") { _ = try CodexConfig.renderModelSelection(original: original, models: [], executable: "/test") }
        let mixedGroups = [priced[0], PricedModel(name: "gemini-test", platform: "gemini", groups: ["Gemini"], groupID: 59)]
        let mixedCatalog = try JSONSerialization.jsonObject(with: CodexConfig.catalogData(mixedGroups)) as? [String: Any]
        let mixedEntries = mixedCatalog?["models"] as? [[String: Any]] ?? []
        for (entry, model) in zip(mixedEntries, mixedGroups) {
            let instructions = entry["base_instructions"] as? String ?? ""
            let metadataLine = instructions.components(separatedBy: "\n").first { $0.hasPrefix("Current request configuration") } ?? ""
            let metadataText = metadataLine.components(separatedBy: ": ").dropFirst().joined(separator: ": ")
            let metadata = try JSONSerialization.jsonObject(with: Data(metadataText.utf8)) as? [String: Any]
            try check(metadata?["requested_model"] as? String == model.name && metadata?["group_id"] as? Int64 == model.groupID, "each model receives its own route metadata")
        }
        let mixedConfig = try CodexConfig.renderModelSelection(original: original, models: mixedGroups, activeModel: mixedGroups[1], executable: "/test")
        try check(mixedConfig.contains("model = \"gemini-test\""), "mixed groups with active model")
        let duplicateSlug = [priced[0], PricedModel(name: "gpt-test", platform: "openai", groups: ["Other GPT"], groupID: 41)]
        try rejects("duplicate model slug across groups") { _ = try CodexConfig.renderModelSelection(original: original, models: duplicateSlug, executable: "/test") }
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let claudeModels = [
            PricedModel(name: "claude-sonnet-test", platform: "anthropic", groups: ["Claude Group"], groupID: 71, rateMultiplier: 0.5),
            PricedModel(name: "claude-opus-test", platform: "claude", groups: ["Claude Group"], groupID: 71, rateMultiplier: 0.5)
        ]
        let claudeConfig = try ClaudeDesktopConfig.configuration(models: claudeModels, executable: "/bin/cat")
        try check(claudeConfig["inferenceProvider"] as? String == "gateway" && claudeConfig["modelDiscoveryEnabled"] as? Bool == false, "Claude Desktop gateway configuration")
        try check((claudeConfig["inferenceModels"] as? [[String: Any]])?.compactMap { $0["name"] as? String } == claudeModels.map(ClaudeBridgeRoute.alias), "Claude Desktop route aliases")
        try check(claudeConfig["inferenceGatewayApiKey"] == nil && claudeConfig["inferenceCredentialKind"] as? String == "helper-script", "Claude Desktop token stays outside configuration")
        let crossVendor = try ClaudeDesktopConfig.configuration(models: [claudeModels[0], priced[0]], executable: "/bin/cat")
        try check((crossVendor["inferenceModels"] as? [[String: Any]])?.compactMap { $0["labelOverride"] as? String } == [claudeModels[0].name, priced[0].name], "cross-vendor names stay accurate in picker")
        try check(crossVendor["inferenceGatewayBaseUrl"] as? String == "http://127.0.0.1:23179", "local bridge endpoint")
        let toolBody: [String: Any] = ["model": "gpt-test", "system": "system", "max_tokens": 256, "messages": [
            ["role": "user", "content": "Calculate"],
            ["role": "assistant", "content": [["type": "tool_use", "id": "call_1", "name": "sum", "input": ["a": 1]]]],
            ["role": "user", "content": [["type": "tool_result", "tool_use_id": "call_1", "content": "2"]]]
        ], "tools": [["name": "sum", "input_schema": ["type": "object", "properties": [:]]]]]
        let converted = try ClaudeResponsesAdapter.request(toolBody)
        let convertedInput = converted["input"] as! [[String: Any]]
        try check(convertedInput[1]["call_id"] as? String == "call_1" && convertedInput[2]["call_id"] as? String == "call_1", "tool IDs preserved both directions")
        try check(convertedInput[2]["output"] as? String == "2", "tool result forwarded")
        let stream = ClaudeResponsesStream(model: "gpt-test")
        _ = try stream.consume(["type": "response.created", "response": ["id": "r1"]])
        let started = try stream.consume(["type": "response.output_item.added", "item": ["type": "function_call", "id": "item1", "call_id": "call_1", "name": "sum"]])
        try check((started.first?["content_block"] as? [String: Any])?["id"] as? String == "call_1", "stream tool ID mapped")
        let delta = try stream.consume(["type": "response.function_call_arguments.delta", "item_id": "item1", "delta": "{}"])
        try check((delta.first?["delta"] as? [String: Any])?["partial_json"] as? String == "{}", "stream arguments preserved")
        let finished = try stream.consume(["type": "response.completed", "response": ["status": "completed", "usage": ["input_tokens": 100, "input_tokens_details": ["cached_tokens": 60], "output_tokens": 8]]])
        try check(stream.completed && finished.last?["type"] as? String == "message_stop", "stream terminates")
        let usage = finished[finished.count - 2]["usage"] as! [String: Any]
        try check(usage["input_tokens"] as? Int == 40 && usage["cache_read_input_tokens"] as? Int == 60, "cached input is not double counted")
        let secretConfig = ClaudeBridgeSettings(accountID: "test", port: 23179, token: "loopback-token", routes: [ClaudeBridgeRoute(model: priced[0])], keyID: 1, key: "private-key")
        let secretURL = dir.appendingPathComponent("bridge.json")
        try secretConfig.save(to: secretURL)
        try check((try FileManager.default.attributesOfItem(atPath: secretURL.path)[.posixPermissions] as? NSNumber)?.intValue == 0o600, "bridge secrets file owner only")
        let claudeLibrary = dir.appendingPathComponent("Claude-3p/configLibrary")
        let installedClaude = try ClaudeDesktopConfig.install(models: claudeModels, executable: "/bin/cat", state: ClaudeSelectionState(), library: claudeLibrary)
        try check(ClaudeDesktopConfig.isInstalled(state: installedClaude, library: claudeLibrary), "Claude Desktop TokenPro profile applied")
        let restoredClaude = try ClaudeDesktopConfig.restoreOfficial(state: installedClaude, library: claudeLibrary)
        try check(!ClaudeDesktopConfig.isInstalled(state: restoredClaude, library: claudeLibrary) && restoredClaude.models.isEmpty, "Claude Desktop official profile restored")
        let claudeMeta = try JSONSerialization.jsonObject(with: Data(contentsOf: claudeLibrary.appendingPathComponent("_meta.json"))) as? [String: Any]
        try check((claudeMeta?["entries"] as? [[String: Any]])?.count == 2 && claudeMeta?["appliedId"] as? String == restoredClaude.officialProfileID, "Claude Desktop profiles preserved separately")
        let target = dir.appendingPathComponent("config.toml"), backup = dir.appendingPathComponent("backup.json")
        try Data(original.utf8).write(to: target)
        try CodexConfig.install(route: route, executable: "/test", target: target, backup: backup)
        var secondRoute = route; secondRoute.model = "second-model"
        try CodexConfig.install(route: secondRoute, executable: "/test", target: target, backup: backup)
        try check(try String(contentsOf: target, encoding: .utf8).contains("model = \"second-model\""), "repeat install")
        try CodexConfig.restore(backup: backup)
        try check(try Data(contentsOf: target) == Data(original.utf8), "exact restoration")
        try CodexConfig.install(route: route, executable: "/test", target: target, backup: backup)
        try Data("changed".utf8).write(to: target)
        try CodexConfig.restore(backup: backup)
        let mergedRestore = try String(contentsOf: target, encoding: .utf8)
        try check(mergedRestore.contains("changed") && mergedRestore.contains("model = \"old\""), "restore owned keys while preserving external changes")
        try check(!mergedRestore.contains(CodexConfig.provider), "remove managed provider after external changes")
        try FileManager.default.removeItem(at: target)
        try CodexConfig.install(route: route, executable: "/test", target: target, backup: backup)
        try CodexConfig.restore(backup: backup)
        try check(!FileManager.default.fileExists(atPath: target.path), "restore nonexistent config")

        let fullContext = "[features]\napps = true\nhooks = true\n[skills]\nmax_context_tokens = 8000\n[plugins.external]\nenabled = true\n"
        let reducedContext = try CompactContext.apply(fullContext)
        try check(reducedContext.contains("apps = false") && reducedContext.contains("max_context_tokens = 1200"), "compact context bounds skills and disables apps")
        try check(CompactContext.restore(reducedContext) == fullContext, "compact context restores original scalars exactly")
        try check(try CompactContext.apply(reducedContext) == reducedContext, "compact context repeated apply is stable")
        let externalEdit = reducedContext.replacingOccurrences(of: "max_context_tokens = 1200", with: "max_context_tokens = 900") + "\n[projects.example]\ntrust_level = \"trusted\"\n"
        let restoredEdit = CompactContext.restore(externalEdit)
        try check(restoredEdit.contains("max_context_tokens = 900") && restoredEdit.contains("[projects.example]") && restoredEdit.contains("apps = true"), "compact restore preserves user edits")
        let smallBudget = "skills.max_context_tokens = 600\nfeatures.apps = true\n"
        let reducedSmall = try CompactContext.apply(smallBudget)
        try check(reducedSmall.contains("skills.max_context_tokens = 600") && reducedSmall.contains("features.apps = false"), "compact respects smaller budgets and dotted settings")
        try check(CompactContext.restore(reducedSmall) == smallBudget, "dotted settings restored")
        try rejects("inline skills table") { _ = try CompactContext.apply("skills = { max_context_tokens = 8000 }") }

        let leanTarget = dir.appendingPathComponent("lean-config.toml")
        let leanBackup = dir.appendingPathComponent("lean-backup.json")
        let leanCatalog = dir.appendingPathComponent("lean-catalog.json")
        try Data(fullContext.utf8).write(to: leanTarget)
        try CodexConfig.installModels(mixedGroups, executable: "/test", target: leanTarget, backup: leanBackup, catalog: leanCatalog, compactContext: true)
        try CodexConfig.installModels(mixedGroups, executable: "/test", target: leanTarget, backup: leanBackup, catalog: leanCatalog, compactContext: false)
        let leanDisabled = try String(contentsOf: leanTarget, encoding: .utf8)
        try check(leanDisabled.contains("apps = true") && leanDisabled.contains("max_context_tokens = 8000"), "disabling compact mode restores previous preferences")
        try CodexConfig.installModels(mixedGroups, executable: "/test", target: leanTarget, backup: leanBackup, catalog: leanCatalog, compactContext: true)
        let leanEdited = try String(contentsOf: leanTarget, encoding: .utf8) + "\n[projects.later]\ntrust_level = \"trusted\"\n"
        try Data(leanEdited.utf8).write(to: leanTarget)
        try CodexConfig.restore(backup: leanBackup)
        let leanRestored = try String(contentsOf: leanTarget, encoding: .utf8)
        try check(leanRestored.contains("apps = true") && leanRestored.contains("max_context_tokens = 8000") && leanRestored.contains("[projects.later]"), "official restore recovers context settings and preserves unrelated edits")

        let modelTarget = dir.appendingPathComponent("model-config.toml")
        let modelBackup = dir.appendingPathComponent("model-backup.json")
        let modelCatalog = dir.appendingPathComponent("model-catalog.json")
        let hooks = dir.appendingPathComponent("hooks.json")
        try Data(original.utf8).write(to: modelTarget)
        try CodexConfig.installModels(mixedGroups, activeModel: mixedGroups[0], executable: "/Applications/TokenPro.app/Contents/MacOS/TokenPro", target: modelTarget, backup: modelBackup, catalog: modelCatalog)
        var installedText = try String(contentsOf: modelTarget, encoding: .utf8)
        try check(installedText.contains("hooks = true") && installedText.contains(CodexConfig.provider), "hook feature and provider installed")
        let hookJSON = try JSONSerialization.jsonObject(with: Data(contentsOf: hooks)) as? [String: Any]
        let hookGroups = (hookJSON?["hooks"] as? [String: Any])?["UserPromptSubmit"] as? [[String: Any]]
        try check(hookGroups?.count == 1, "model group hook installed")
        installedText += "\n[plugins.external]\nenabled = true\n"
        try Data(installedText.utf8).write(to: modelTarget)
        try CodexConfig.installModels(mixedGroups, activeModel: mixedGroups[1], executable: "/Applications/TokenPro.app/Contents/MacOS/TokenPro", target: modelTarget, backup: modelBackup, catalog: modelCatalog)
        let mergedInstall = try String(contentsOf: modelTarget, encoding: .utf8)
        try check(mergedInstall.contains("[plugins.external]") && mergedInstall.components(separatedBy: "[model_providers.\(CodexConfig.provider)]").count == 2, "repeat install preserves external config without duplicate provider")
        try CodexConfig.restore(backup: modelBackup)
        let modelRestored = try String(contentsOf: modelTarget, encoding: .utf8)
        try check(modelRestored.contains("[plugins.external]") && !modelRestored.contains(CodexConfig.provider), "model restore preserves later config")
        try CodexConfig.removeModelHook(target: hooks)
        try check(!FileManager.default.fileExists(atPath: hooks.path), "model hook removed cleanly")
        let thirdParty = """
        model = "third-party-model"
        model_provider = "another-relay"
        model_catalog_json = "/custom/catalog.json"
        openai_base_url = "https://relay.invalid/v1"
        chatgpt_base_url = "https://relay.invalid"
        profile = "relay-profile"
        [model_providers.another-relay]
        base_url = "https://relay.invalid/v1"
        [projects.example]
        trust_level = "trusted"
        [plugins.example]
        enabled = true
        """
        let official = try CodexConfig.officialConfiguration(from: thirdParty)
        try check(official.hasPrefix("model_provider = \"openai\""), "official recovery selects built-in OpenAI provider")
        try check(!official.contains("relay.invalid") && !official.contains("third-party-model") && !official.contains("profile ="), "official recovery removes endpoint model and active profile overrides")
        try check(official.contains("[projects.example]") && official.contains("[plugins.example]"), "official recovery preserves project and plugin settings")
        let quoted = try CodexConfig.officialConfiguration(from: "\"model_provider\" = \"relay\"\n'openai_base_url' = \"https://relay.invalid\"\n[\"model_providers\".\"relay\"] # custom\nbase_url = \"https://relay.invalid\"\n[plugins.keep]\nenabled = true")
        try check(!quoted.contains("relay.invalid") && quoted.contains("[plugins.keep]"), "official recovery handles quoted TOML routing keys")
        let officialTarget = dir.appendingPathComponent("official/config.toml")
        let officialBackup = dir.appendingPathComponent("official-backup.json")
        let officialCatalog = dir.appendingPathComponent("official-catalog.json")
        let auth = officialTarget.deletingLastPathComponent().appendingPathComponent("auth.json")
        let history = officialTarget.deletingLastPathComponent().appendingPathComponent("history.jsonl")
        try FileManager.default.createDirectory(at: officialTarget.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(thirdParty.utf8).write(to: officialTarget)
        try Data("login-fixture".utf8).write(to: auth)
        try Data("history-fixture".utf8).write(to: history)
        try CodexConfig.installModels(mixedGroups, executable: "/test", target: officialTarget, backup: officialBackup, catalog: officialCatalog, compactContext: true)
        try CodexConfig.restoreOfficial(target: officialTarget, backup: officialBackup, catalog: officialCatalog)
        let officialRestored = try String(contentsOf: officialTarget, encoding: .utf8)
        try check(officialRestored.contains("model_provider = \"openai\"") && !officialRestored.contains("another-relay") && !officialRestored.contains("tokenpro_direct"), "official recovery does not return to previous relay")
        try check(try String(contentsOf: auth, encoding: .utf8) == "login-fixture" && String(contentsOf: history, encoding: .utf8) == "history-fixture", "official recovery leaves login and history untouched")
        try check(!FileManager.default.fileExists(atPath: officialBackup.path) && !FileManager.default.fileExists(atPath: officialCatalog.path), "official recovery removes active TokenPro recovery record and catalog")
        let mixedHookData = Data("{\"hooks\":{\"UserPromptSubmit\":[{\"hooks\":[{\"command\":\"/test --tokenpro-sync-hook\"},{\"command\":\"echo keep\"}]}]}}".utf8)
        let preservedHook = try CodexConfig.modelHooksWithoutTokenPro(mixedHookData)
        try check(preservedHook.map { String(decoding: $0, as: UTF8.self).contains("echo keep") } == true, "official recovery preserves unrelated handlers in mixed hook groups")
        try rejects("malformed hook list") { _ = try CodexConfig.modelHooksWithoutTokenPro(Data("{\"hooks\":{\"UserPromptSubmit\":\"invalid\"}}".utf8)) }
        try rejects("complex official config") { _ = try CodexConfig.officialConfiguration(from: "notes = '''multiline'''\n") }
        let missingTarget = dir.appendingPathComponent("fresh-home/.codex/config.toml")
        let missingBackup = dir.appendingPathComponent("fresh-state/backup.json")
        let missingCatalog = dir.appendingPathComponent("fresh-state/catalog.json")
        try CodexConfig.restoreOfficial(target: missingTarget, backup: missingBackup, catalog: missingCatalog)
        try check(try String(contentsOf: missingTarget, encoding: .utf8).contains("model_provider = \"openai\""), "missing config and directory are created with official provider")
        try check(!FileManager.default.fileExists(atPath: missingTarget.deletingLastPathComponent().appendingPathComponent("auth.json").path), "missing official credentials are never fabricated")
        let malformedHooks = missingTarget.deletingLastPathComponent().appendingPathComponent("hooks.json")
        let unchangedConfig = try Data(contentsOf: missingTarget)
        try Data("{\"hooks\":{\"UserPromptSubmit\":\"invalid\"}}".utf8).write(to: malformedHooks)
        try rejects("malformed hooks leave live config unchanged") { try CodexConfig.restoreOfficial(target: missingTarget, backup: missingBackup, catalog: missingCatalog) }
        try check(try Data(contentsOf: missingTarget) == unchangedConfig, "restore validates hook data before changing config")
        print("PASS: \(checks) core checks")
    } catch { print(error.localizedDescription); exit(1) }
}

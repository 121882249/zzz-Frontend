import Foundation
import AppKit
import Darwin

func withTokenProHookLock<T>(_ body: () async throws -> T) async throws -> T {
    try FileManager.default.createDirectory(at: Persistence.directory, withIntermediateDirectories: true)
    let path = Persistence.directory.appendingPathComponent("model-hook.lock").path
    let descriptor = Darwin.open(path, O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
    guard descriptor >= 0 else { throw RouterError("无法建立模型切换锁。") }
    defer { Darwin.lockf(descriptor, F_ULOCK, 0); Darwin.close(descriptor) }
    guard Darwin.lockf(descriptor, F_LOCK, 0) == 0 else { throw RouterError("无法锁定模型切换状态。") }
    return try await body()
}

if CommandLine.arguments.contains("--claude-bridge") {
    do {
        let configURL: URL
        if let i = CommandLine.arguments.firstIndex(of: "--config"), CommandLine.arguments.count > i + 1 {
            configURL = URL(fileURLWithPath: CommandLine.arguments[i + 1])
        } else { configURL = ClaudeBridgeSettings.url }
        let config = try ClaudeBridgeSettings.load(from: configURL)
        let fd = Darwin.open(configURL.path + ".lock", O_CREAT | O_RDWR, S_IRUSR | S_IWUSR)
        guard fd >= 0, flock(fd, LOCK_EX | LOCK_NB) == 0 else { exit(0) }
        let server = try ClaudeBridgeServer(config: config, configURL: configURL)
        withExtendedLifetime(server) { dispatchMain() }
    } catch { FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8)); exit(1) }
} else if ProcessInfo.processInfo.environment["CLAUDE_HELPER_CONTEXT"] != nil {
    Task {
      do {
        try await ClaudeBridgeManager.ensureRunning(executable: Bundle.main.executableURL!.path)
        guard let token = try HelperCredentialStore.readClaudeModelToken(), !token.isEmpty else { throw RouterError("TokenPro Claude 模型令牌不存在，请重新选择模型。") }
        print(token)
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8)); exit(1)
      }
      exit(0)
    }
    dispatchMain()
} else if CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--tokenpro-model-token" {
    do {
        guard let token = try HelperCredentialStore.readModelToken(), !token.isEmpty else { throw RouterError("TokenPro 模型令牌不存在，请重新选择模型。") }
        print(token)
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8)); exit(1)
    }
} else if let i = CommandLine.arguments.firstIndex(of: "--route-token"), CommandLine.arguments.count > i + 1 {
    do {
        let keyID = try LocalCredentialStore.routeKeyID(CommandLine.arguments[i + 1])
        guard let token = try LocalCredentialStore.read(keyID), !token.isEmpty else { throw RouterError("连接令牌不存在，请在 TokenPro 中重新保存该连接。") }
        print(token)
    } catch {
        FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8)); exit(1)
    }
} else if CommandLine.arguments.contains("--claude-bridge-tests") {
    Task { await runClaudeBridgeTests(); exit(0) }
    dispatchMain()
} else if CommandLine.arguments.contains("--account-tests") {
    Task { await runAccountTests(); exit(0) }
    dispatchMain()
} else if CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--tokenpro-sync-hook" {
    Task { @MainActor in
        do {
            try await withTokenProHookLock {
                let data = FileHandle.standardInput.readDataToEndOfFile()
                guard data.count <= 1_048_576,
                      let input = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      input["hook_event_name"] as? String == "UserPromptSubmit",
                      let modelName = input["model"] as? String else {
                    throw RouterError("Codex Hook 输入格式不兼容。")
                }
                var state = ModelSelectionStore.loadState()
                guard let model = state.models.first(where: { $0.name == modelName }), let groupID = model.groupID else {
                    throw RouterError("模型 \(modelName) 尚未在 TokenPro 中选择。")
                }
                let account = NativeAccount(
                    read: { try HelperCredentialStore.readSession() },
                    write: { try HelperCredentialStore.writeSession($0) },
                    remove: { try HelperCredentialStore.removeSession() }
                )
                await account.start(loadPublicSettings: false)
                guard account.signedIn else { throw RouterError(account.message ?? "TokenPro 登录状态不可用，请打开 TokenPro 重新登录。") }

                let storedToken = try HelperCredentialStore.readModelToken()
                if let keyState = state.managedKey, keyState.groupID == groupID, storedToken?.isEmpty == false {
                    state.activeModelID = model.id
                } else if let keyState = state.managedKey, storedToken?.isEmpty == false {
                    do {
                        state.managedKey = try await account.switchCodexKeyGroup(keyID: keyState.id, groupID: groupID)
                    } catch let failure as BackendFailure where failure.status == 404 {
                        let managed = try await account.codexManagedAPIKey(initialGroupID: groupID)
                        try HelperCredentialStore.writeModelToken(managed.key)
                        state.managedKey = ManagedCodexKeyState(id: managed.id, groupID: groupID)
                    }
                    state.activeModelID = model.id
                } else {
                    let managed = try await account.codexManagedAPIKey(initialGroupID: groupID)
                    try HelperCredentialStore.writeModelToken(managed.key)
                    state.managedKey = ManagedCodexKeyState(id: managed.id, groupID: groupID)
                    state.activeModelID = model.id
                }
                try ModelSelectionStore.save(state)
            }
            exit(0)
        } catch {
            FileHandle.standardError.write(Data(("TokenPro 无法同步模型分组：" + error.localizedDescription + "\n").utf8))
            exit(2)
        }
    }
    dispatchMain()
} else if CommandLine.arguments.count == 2, CommandLine.arguments[1] == "--sync-model-key" {
    Task { @MainActor in
        let account = NativeAccount(
            read: { try HelperCredentialStore.readSession() },
            write: { try HelperCredentialStore.writeSession($0) },
            remove: { try HelperCredentialStore.removeSession() }
        )
        await account.start()
        do {
            guard account.signedIn else { throw RouterError(account.message ?? "TokenPro 登录状态不可用。") }
            var state = ModelSelectionStore.loadState()
            guard let active = state.activeModel, let groupID = active.groupID else {
                throw RouterError("请先在 TokenPro 中选择模型。")
            }
            let managed = try await account.codexManagedAPIKey(initialGroupID: groupID)
            try HelperCredentialStore.writeModelToken(managed.key)
            try CodexConfig.installModels(state.models, activeModel: active, executable: Bundle.main.executableURL!.path)
            state.managedKey = ManagedCodexKeyState(id: managed.id, groupID: groupID)
            state.activeModelID = active.id
            try ModelSelectionStore.save(state)
            print("TokenPro 专用 Key、分组与模型已同步。")
            exit(0)
        } catch {
            FileHandle.standardError.write(Data((error.localizedDescription + "\n").utf8))
            exit(1)
        }
    }
    dispatchMain()
} else if CommandLine.arguments.contains("--self-test") {
    runSelfTests()
} else {
    NSApplication.shared.setActivationPolicy(.regular)
    RouterApp.main()
}

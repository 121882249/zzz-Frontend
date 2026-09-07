import SwiftUI
import AppKit

struct ClientRelease: Decodable {
    let version: String
    let downloadURL: URL
    let notes: String
    static func components(_ version: String) -> [Int]? {
        let parts = version.split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count == 3 else { return nil }
        var result: [Int] = []
        for part in parts {
            guard !part.isEmpty, part.allSatisfy({ $0.isASCII && $0.isNumber }), let value = Int(part) else { return nil }
            result.append(value)
        }
        return result
    }
    func validated() throws -> ClientRelease {
        guard Self.components(version) != nil,
              downloadURL.scheme == "https", downloadURL.host == "tokenpro.work",
              downloadURL.port == nil || downloadURL.port == 443,
              downloadURL.user == nil, downloadURL.password == nil,
              downloadURL.path.hasPrefix("/client-updates/"), downloadURL.path.hasSuffix(".dmg"),
              downloadURL.query == nil, downloadURL.fragment == nil else {
            throw RouterError("更新信息格式不正确，请稍后重试。")
        }
        return self
    }
    func newer(than version: String) -> Bool {
        guard let remote = Self.components(self.version), let local = Self.components(version) else { return false }
        return local.lexicographicallyPrecedes(remote)
    }
}

@MainActor final class ClientUpdates: ObservableObject {
    @Published var checking = false
    @Published var release: ClientRelease?
    @Published var showDetails = false
    @Published var message = ""
    private var checked = false
    let current = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.4.2"
    var available: Bool { release?.newer(than: current) == true }
    var checkedSuccessfully: Bool { release != nil }
    var label: String {
        if checking { return "检查中…" }
        if checkedSuccessfully && !available { return "最新版本" }
        return "检查更新"
    }
    var labelColor: Color {
        if checking { return .secondary }
        if checkedSuccessfully && !available { return .green }
        return Color(red: 0.78, green: 0.55, blue: 0.02)
    }
    func check(manual: Bool) async {
        guard !checking, manual || !checked else { return }
        checked = true; checking = true
        defer { checking = false; if manual { showDetails = true } }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 12
        configuration.httpShouldSetCookies = false
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        let session = URLSession(configuration: configuration, delegate: NoRedirect(), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        do {
            #if arch(x86_64)
            let manifest = "macos-intel.json"
            #else
            let manifest = "macos.json"
            #endif
            let (data, response) = try await session.data(from: URL(string: "https://tokenpro.work/client-updates/" + manifest)!)
            guard let http = response as? HTTPURLResponse, http.statusCode == 200,
                  http.mimeType == "application/json", data.count <= 65_536 else {
                throw RouterError("更新服务暂不可用，请稍后重试。")
            }
            let info = try JSONDecoder().decode(ClientRelease.self, from: data).validated()
            release = info
            message = available ? "发现新版本 \(info.version)" : "当前已是最新版本。"
        } catch {
            message = "暂时无法获取更新信息。请检查网络，或等待更新服务上线。"
        }
    }
}

struct ClientUpdateButton: View {
    @StateObject private var updates = ClientUpdates()
    var body: some View {
        Button {
            if updates.available { updates.showDetails = true }
            else { Task { await updates.check(manual: true) } }
        } label: {
            HStack(spacing: 7) {
                Label(updates.label, systemImage: "arrow.triangle.2.circlepath")
                    .foregroundStyle(updates.labelColor)
                if updates.available {
                    Image(systemName: "exclamationmark.circle.fill")
                        .foregroundStyle(Color.red).accessibilityLabel("有新版本可更新")
                }
            }
        }.buttonStyle(SoftButton()).disabled(updates.checking)
        .task { await updates.check(manual: false) }
        .sheet(isPresented: $updates.showDetails) {
            VStack(alignment: .leading, spacing: 18) {
                HStack { Text("客户端更新").font(.title2.bold()); Spacer(); Button("关闭") { updates.showDetails = false }.buttonStyle(SoftButton()) }
                Text("当前版本 \(updates.current)").foregroundStyle(.secondary)
                Text(updates.message).font(.headline)
                if updates.available, let release = updates.release {
                    ScrollView { Text(release.notes).frame(maxWidth: .infinity, alignment: .leading).textSelection(.enabled) }.frame(maxHeight: 180)
                    Text("下载后，将应用替换到原来的安装位置即可。账户和连接配置会保留。").font(.system(size: 12)).foregroundStyle(.secondary)
                    Button("下载更新") { NSWorkspace.shared.open(release.downloadURL) }.buttonStyle(SoftButton(primary: true))
                } else {
                    Button("重试") { Task { await updates.check(manual: true) } }.buttonStyle(SoftButton()).disabled(updates.checking)
                }
            }.padding(26).frame(width: 460)
        }
    }
}

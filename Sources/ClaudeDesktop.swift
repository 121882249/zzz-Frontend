import SwiftUI

struct ClaudeModelPickerView: View {
    private struct GroupOption: Identifiable {
        let id: Int64
        let name: String
        let modelCount: Int
        let rateMultiplier: Double?
    }

    @EnvironmentObject private var app: AppModel
    @EnvironmentObject private var account: NativeAccount
    @Binding var isPresented: Bool
    @State private var models: [PricedModel] = []
    @State private var selection: Set<String> = []
    @State private var selectedGroupID: Int64?
    @State private var search = ""
    @State private var loading = true
    @State private var saving = false
    @State private var error: String?
    @State private var selectionNote: String?
    private let purple = Color(red: 0.40, green: 0.32, blue: 0.94)

    private var groupOptions: [GroupOption] {
        var result: [GroupOption] = []
        var seen = Set<Int64>()
        for model in models {
            guard let id = model.groupID, seen.insert(id).inserted else { continue }
            result.append(GroupOption(id: id, name: model.groupName,
                                      modelCount: models.filter { $0.groupID == id }.count,
                                      rateMultiplier: model.rateMultiplier))
        }
        return result
    }

    private var displayedModels: [PricedModel] {
        let groupFiltered = selectedGroupID.map { id in models.filter { $0.groupID == id } } ?? models
        let query = search.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !query.isEmpty else { return groupFiltered }
        return groupFiltered.filter {
            $0.name.localizedCaseInsensitiveContains(query) || $0.groupName.localizedCaseInsensitiveContains(query)
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 5) {
                    Text("选择 Claude 桌面模型").font(.system(size: 22, weight: .bold))
                    Text("勾选后直接在 Claude 桌面版内切换，无需退出应用。")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Spacer()
                Button { isPresented = false } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain).font(.system(size: 13, weight: .semibold))
            }.padding(24)

            Divider()

            if loading {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("正在读取可用模型…").font(.system(size: 12)).foregroundStyle(.secondary)
                }.frame(maxWidth: .infinity, maxHeight: .infinity)
            } else if models.isEmpty {
                VStack(spacing: 14) {
                    Image(systemName: "exclamationmark.triangle").font(.system(size: 28)).foregroundStyle(.orange)
                    Text(error ?? "当前没有可用模型。")
                        .font(.system(size: 13)).multilineTextAlignment(.center)
                    Button("重新加载") { load() }.buttonStyle(SoftButton(primary: true))
                }.padding(40).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(spacing: 12) {
                    HStack(spacing: 12) {
                        Text("分组").font(.system(size: 12, weight: .semibold)).foregroundStyle(.secondary)
                        Picker("选择分组", selection: $selectedGroupID) {
                            Text("全部有效分组").tag(Optional<Int64>.none)
                            ForEach(groupOptions) { group in
                                Text(groupTitle(group)).tag(Optional(group.id))
                            }
                        }
                        .labelsHidden().pickerStyle(.menu).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(Color.white, in: RoundedRectangle(cornerRadius: 10))
                    .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color.black.opacity(0.07)))

                    HStack(spacing: 10) {
                        Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                        TextField("搜索 GPT、Grok、Claude、Gemini…", text: $search).textFieldStyle(.plain)
                        if !search.isEmpty {
                            Button { search = "" } label: { Image(systemName: "xmark.circle.fill") }
                                .buttonStyle(.plain).foregroundStyle(.secondary)
                        }
                    }
                    .padding(.horizontal, 12).padding(.vertical, 10)
                    .background(Color.black.opacity(0.045), in: RoundedRectangle(cornerRadius: 10))

                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 8) {
                            ForEach(displayedModels) { model in modelRow(model) }
                            if displayedModels.isEmpty {
                                Text("没有找到匹配的模型")
                                    .font(.system(size: 13)).foregroundStyle(.secondary)
                                    .frame(maxWidth: .infinity).padding(.top, 50)
                            }
                        }.padding(.vertical, 4)
                    }
                }.padding(.horizontal, 24).padding(.top, 16)
            }

            Divider()
            VStack(spacing: 8) {
                HStack(alignment: .top, spacing: 6) {
                    Image(systemName: "info.circle.fill").foregroundStyle(purple)
                    Text("共用一把 Key，随模型自动切换分组。不同分组请求依次处理；TokenPro 会自动维护本地连接。")
                        .foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }.font(.system(size: 11)).frame(maxWidth: .infinity, alignment: .leading)
                if let selectionNote {
                    Text(selectionNote).font(.system(size: 11)).foregroundStyle(.orange)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if let error, !models.isEmpty {
                    Text(error).font(.system(size: 11)).foregroundStyle(.red)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                HStack {
                    Text("已选 \(selection.count) 个模型")
                        .font(.system(size: 12, weight: .semibold)).foregroundStyle(selection.isEmpty ? .secondary : purple)
                    Button("全选当前列表") { selectCurrentGroup() }.buttonStyle(.plain).foregroundStyle(purple)
                    Button("清空") { selection.removeAll(); selectionNote = nil }.buttonStyle(.plain).foregroundStyle(purple)
                    Spacer()
                    Button("取消") { isPresented = false }.buttonStyle(SoftButton())
                    Button(saving ? "正在保存…" : (selection.isEmpty ? "恢复官方配置" : "保存到 Claude")) { save() }
                        .buttonStyle(SoftButton(primary: true)).disabled(saving || loading)
                }
            }.padding(18)
        }
        .frame(width: 650, height: 690)
        .background(Color(red: 0.985, green: 0.985, blue: 0.99))
        .task { await loadModels() }
    }

    private func modelRow(_ model: PricedModel) -> some View {
        let chosen = selection.contains(model.id)
        return Button {
            if chosen {
                selection.remove(model.id)
            } else {
                for duplicate in models where duplicate.name == model.name { selection.remove(duplicate.id) }
                selection.insert(model.id)
            }
        } label: {
            HStack(spacing: 12) {
                Image(systemName: chosen ? "checkmark.square.fill" : "square")
                    .font(.system(size: 18, weight: .medium)).foregroundStyle(chosen ? purple : Color.secondary)
                VStack(alignment: .leading, spacing: 3) {
                    Text(model.name).font(.system(size: 13, weight: .semibold)).foregroundStyle(Color.primary)
                    Text(model.groupName + rateSuffix(model)).font(.system(size: 10)).foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .background(chosen ? purple.opacity(0.08) : Color.white, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(chosen ? purple.opacity(0.30) : Color.black.opacity(0.07)))
            .contentShape(Rectangle())
        }.buttonStyle(.plain)
    }

    private func groupTitle(_ group: GroupOption) -> String {
        let rate = group.rateMultiplier.map { String(format: " · %.2gx", $0) } ?? ""
        return "\(group.name)\(rate) · \(group.modelCount) 个"
    }

    private func rateSuffix(_ model: PricedModel) -> String {
        model.rateMultiplier.map { String(format: " · %.2gx", $0) } ?? ""
    }

    private func selectCurrentGroup() {
        for model in displayedModels {
            for duplicate in models where duplicate.name == model.name { selection.remove(duplicate.id) }
            selection.insert(model.id)
        }
        selectionNote = nil
    }

    private func load() {
        loading = true
        error = nil
        Task { await loadModels() }
    }

    @MainActor private func loadModels() async {
        loading = true
        error = nil
        do {
            models = try await account.pricedModels().filter { !$0.name.lowercased().contains("image") }
            let available = Set(models.map(\.id))
            let saved = app.claudeSelectedModels.filter { available.contains($0.id) }
            selection = Set(saved.map(\.id))
            selectedGroupID = nil
            if models.isEmpty { error = "当前有效分组中没有可用的对话模型。" }
        } catch {
            models = []
            self.error = error.localizedDescription
        }
        loading = false
    }

    private func save() {
        saving = true
        error = nil
        Task { @MainActor in
            defer { saving = false }
            do {
                let chosen = models.filter { selection.contains($0.id) }
                if chosen.isEmpty {
                    try app.clearClaudeSelection()
                } else {
                    guard let groupID = chosen.first?.groupID else { throw RouterError("请选择有效分组。") }
                    await ClaudeRequestGate.shared.acquire()
                    do {
                        let keyLock = try await ClaudeKeyLock.acquire()
                        defer { keyLock.unlock() }
                        let managed = try await account.claudeManagedAPIKey(initialGroupID: groupID)
                        try await app.applyClaudeSelection(chosen, managedKey: managed, accountID: String(describing: account.user["id"] ?? ""))
                        await ClaudeRequestGate.shared.release()
                    } catch {
                        await ClaudeRequestGate.shared.release()
                        throw error
                    }
                }
                isPresented = false
            } catch {
                self.error = error.localizedDescription
            }
        }
    }
}

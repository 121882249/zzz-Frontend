import SwiftUI
import AppKit

private enum LoginPalette {
    // Same RGB values as the application icon.
    static let blue = Color(red: 0.40, green: 0.32, blue: 0.94)
    static let ink = Color(red: 0.15, green: 0.20, blue: 0.43)
}

private struct LoginButton: ButtonStyle {
    @Environment(\.isEnabled) private var enabled
    var primary = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label.font(.system(size: 13, weight: .semibold))
            .padding(.horizontal, 17).padding(.vertical, 11)
            .foregroundStyle(primary ? Color.white : LoginPalette.blue)
            .background(primary ? LoginPalette.blue : LoginPalette.blue.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.35)
    }
}

struct LoginView: View {
    @EnvironmentObject var account: NativeAccount
    @State private var registering = false
    @State private var email = ""
    @State private var password = ""
    @State private var verification = ""
    @State private var invitation = ""
    @State private var totp = ""
    @State private var accepted = false
    @State private var showAgreement = false
    @State private var revealPassword = false
    @State private var codeUntil = Date.distantPast
    @State private var now = Date()
    @State private var heroCardsFloating = false
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()
    var countdown: Int { max(0, Int(ceil(codeUntil.timeIntervalSince(now)))) }
    var body: some View {
        GeometryReader { geometry in
            HStack(spacing: 0) {
                promotionalPanel.frame(width: geometry.size.width * 0.445)
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        HStack {
                            Text(account.pending2FA != nil ? "两步验证" : "登录 / 注册").font(.system(size: 29, weight: .bold))
                            Spacer()
                            Label("简体中文", systemImage: "globe").font(.system(size: 12)).foregroundStyle(.secondary)
                        }.padding(.bottom, 7)
                        if account.pending2FA == nil {
                            HStack(spacing: 22) {
                                modeButton("登录", selected: !registering) { registering = false }
                                modeButton("注册账户", selected: registering) { registering = true }
                            }
                            input("邮箱", icon: "envelope", placeholder: "请输入邮箱", text: $email)
                            VStack(alignment: .leading, spacing: 8) {
                                HStack {
                                    Text("密码").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                                    Spacer()
                                    if !registering { Button("忘记密码？") { NSWorkspace.shared.open(URL(string: "https://tokenpro.work/forgot-password")!) }.buttonStyle(.plain).font(.system(size: 11)).foregroundStyle(.secondary) }
                                }
                                HStack(spacing: 12) {
                                    Image(systemName: "lock").foregroundStyle(.secondary).frame(width: 18)
                                    Group { if revealPassword { TextField("请输入密码", text: $password) } else { SecureField(registering ? "设置密码（至少 6 位）" : "请输入密码", text: $password) } }.textFieldStyle(.plain)
                                    Button { revealPassword.toggle() } label: { Image(systemName: revealPassword ? "eye.slash" : "eye") }.buttonStyle(.plain).foregroundStyle(.secondary).accessibilityLabel(revealPassword ? "隐藏密码" : "显示密码")
                                }.padding(.horizontal, 15).frame(height: 48).background(.white, in: RoundedRectangle(cornerRadius: 11)).overlay(RoundedRectangle(cornerRadius: 11).stroke(LoginPalette.blue.opacity(0.18)))
                            }
                            if registering && account.verifyRequired {
                                VStack(alignment: .leading, spacing: 8) {
                                    Text("邮箱验证码").font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
                                    HStack(spacing: 10) {
                                        Image(systemName: "ellipsis.rectangle").foregroundStyle(.secondary)
                                        TextField("请输入验证码", text: $verification).textFieldStyle(.plain)
                                        Button(countdown > 0 ? "\(countdown) 秒" : "获取验证码") {
                                            Task { if let seconds = await account.sendCode(email: email, accepted: accepted) { codeUntil = Date().addingTimeInterval(Double(seconds)); now = Date() } }
                                        }.buttonStyle(LoginButton()).disabled(countdown > 0 || account.busy || email.isEmpty || (account.agreementRequired && !accepted))
                                    }.padding(.leading, 15).padding(.trailing, 5).frame(height: 48).background(.white, in: RoundedRectangle(cornerRadius: 11)).overlay(RoundedRectangle(cornerRadius: 11).stroke(LoginPalette.blue.opacity(0.18)))
                                }
                            }
                            if registering && account.inviteRequired { input("邀请码", icon: "gift", placeholder: "请输入邀请码", text: $invitation) }
                            if account.agreementRequired {
                                HStack(spacing: 4) {
                                    Toggle("我已阅读并同意", isOn: $accepted).toggleStyle(.checkbox).fixedSize()
                                    Button("用户协议") { showAgreement = true }.buttonStyle(.plain).foregroundStyle(LoginPalette.blue).underline()
                                }.font(.system(size: 11)).foregroundStyle(.secondary)
                            }
                        } else {
                            Text("请输入身份验证器中的动态验证码。").font(.system(size: 13)).foregroundStyle(.secondary)
                            input("动态验证码", icon: "lock.shield", placeholder: "请输入 6 位验证码", text: $totp)
                        }
                        if let message = account.message { Text(message).font(.system(size: 12)).foregroundStyle(.orange).fixedSize(horizontal: false, vertical: true) }
                        if account.loading { ProgressView("正在连接 TokenPro…").controlSize(.small) }
                        else if !account.settingsReady {
                            Button("重新连接后端") { Task { await account.loadSettings() } }.buttonStyle(LoginButton())
                        }
                        Button {
                            Task {
                                let success: Bool
                                if account.pending2FA != nil { success = await account.verify2FA(totp) }
                                else { success = await account.authenticate(email: email, password: password, register: registering, code: verification, invitation: invitation, accepted: accepted) }
                                if success || account.pending2FA != nil { password = "" }
                            }
                        } label: {
                            HStack { Spacer(); if account.busy { ProgressView().controlSize(.small).tint(.white) }; Text(account.pending2FA != nil ? "验证并登录" : registering ? "注册并登录" : "登录").font(.system(size: 16, weight: .semibold)); Spacer() }.frame(height: 34)
                        }.buttonStyle(LoginButton(primary: true)).disabled(account.busy || !account.settingsReady || (account.pending2FA == nil && (email.isEmpty || password.isEmpty || (account.agreementRequired && !accepted) || (registering && !account.registrationEnabled))))
                        if account.pending2FA != nil { Button("返回登录") { account.pending2FA = nil; totp = "" }.buttonStyle(.plain).disabled(account.busy) }
                        Text("由 TokenPro 提供账户与模型服务").font(.system(size: 11)).foregroundStyle(.tertiary).frame(maxWidth: .infinity).padding(.top, 6)
                    }.frame(maxWidth: 440).padding(.horizontal, 45).padding(.vertical, 72).frame(maxWidth: .infinity)
                }.frame(maxWidth: .infinity)
            }.padding(12).background(Color(red: 0.975, green: 0.98, blue: 1))
        }.frame(minWidth: 1000, minHeight: 720).preferredColorScheme(.light).tint(LoginPalette.blue)
        .onReceive(timer) { now = $0 }
        .onChange(of: registering) { _, _ in account.message = nil; password = ""; verification = "" }
        .sheet(isPresented: $showAgreement) {
            VStack(alignment: .leading, spacing: 18) {
                Text("用户协议").font(.title2.bold())
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        ForEach(Array(account.agreements.enumerated()), id: \.offset) { _, doc in
                            Text(doc["title"] as? String ?? "协议").font(.headline)
                            AgreementText(markdown: doc["content_md"] as? String ?? "")
                        }
                    }
                }
                HStack { Button("暂不同意") { accepted = false; showAgreement = false }.buttonStyle(LoginButton()); Spacer(); Button("同意并继续") { accepted = true; showAgreement = false }.buttonStyle(LoginButton(primary: true)).disabled(account.agreements.isEmpty) }
            }.padding(25).frame(width: 650, height: 540)
        }
    }
    private var promotionalPanel: some View {
        GeometryReader { panel in
            ZStack(alignment: .topLeading) {
                LinearGradient(colors: [Color(red: 0.91, green: 0.95, blue: 1), Color(red: 0.78, green: 0.84, blue: 1), LoginPalette.blue.opacity(0.75)], startPoint: .topLeading, endPoint: .bottomTrailing)
                VStack(alignment: .leading, spacing: 18) {
                    Text("TokenPro").font(.system(size: 19, weight: .bold)).tracking(1.2).foregroundStyle(LoginPalette.blue)
                    Text("一个入口，畅用全球顶尖 AI").foregroundStyle(LoginPalette.ink).font(.system(size: 28, weight: .bold)).lineSpacing(6).fixedSize(horizontal: false, vertical: true)
                    Text("自由选择模型，让灵感即刻开始。").font(.system(size: 13)).foregroundStyle(LoginPalette.ink.opacity(0.65))
                    Spacer()
                    VStack(spacing: 22) {
                        HStack {
                            badge("统一账户", "person.crop.circle")
                                .offset(y: heroCardsFloating ? -5 : 3)
                                .animation(.easeInOut(duration: 3.0).repeatForever(autoreverses: true), value: heroCardsFloating)
                            Spacer()
                            badge("API 密钥", "key")
                                .offset(y: heroCardsFloating ? 6 : -2)
                                .animation(.easeInOut(duration: 2.8).repeatForever(autoreverses: true).delay(0.2), value: heroCardsFloating)
                        }
                        HStack {
                            Spacer()
                            Image(systemName: "point.3.connected.trianglepath.dotted")
                                .font(.system(size: 51, weight: .semibold))
                                .foregroundStyle(.white)
                                .frame(width: 102, height: 102)
                                .background(LoginPalette.blue, in: RoundedRectangle(cornerRadius: 28))
                                .offset(y: heroCardsFloating ? -7 : 3)
                                .animation(.easeInOut(duration: 3.3).repeatForever(autoreverses: true).delay(0.1), value: heroCardsFloating)
                            Spacer()
                        }
                        ZStack {
                            badge("Codex", "terminal.fill")
                                .offset(x: -112, y: heroCardsFloating ? 9 : 1)
                                .animation(.easeInOut(duration: 2.7).repeatForever(autoreverses: true), value: heroCardsFloating)
                            badge("Claude", "sun.max")
                                .offset(x: 0, y: heroCardsFloating ? -8 : 2)
                                .animation(.easeInOut(duration: 3.1).repeatForever(autoreverses: true).delay(0.15), value: heroCardsFloating)
                            badge("Gemini", "sparkles")
                                .offset(x: 110, y: heroCardsFloating ? 5 : -3)
                                .animation(.easeInOut(duration: 2.9).repeatForever(autoreverses: true).delay(0.3), value: heroCardsFloating)
                        }
                        .frame(maxWidth: .infinity)
                        .frame(height: 72)
                        .onAppear { heroCardsFloating = true }
                        HStack {
                            Spacer()
                            badge("自由切换模型", "arrow.triangle.branch")
                                .offset(y: heroCardsFloating ? 6 : -3)
                                .animation(.easeInOut(duration: 3.2).repeatForever(autoreverses: true).delay(0.25), value: heroCardsFloating)
                        }
                    }.padding(.bottom, 56)
                }.padding(30).padding(.top, 28)
            }.frame(width: panel.size.width, height: panel.size.height).clipShape(RoundedRectangle(cornerRadius: 22))
        }
    }
    private func badge(_ title: String, _ symbol: String) -> some View {
        Label(title, systemImage: symbol).foregroundStyle(LoginPalette.ink).font(.system(size: 13, weight: .medium)).padding(.horizontal, 17).padding(.vertical, 11).background(.white.opacity(0.88), in: Capsule())
    }
    private func modeButton(_ title: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) { Text(title).font(.system(size: 13, weight: selected ? .semibold : .regular)).padding(.bottom, 8).foregroundStyle(selected ? LoginPalette.blue : .secondary).overlay(alignment: .bottom) { if selected { Rectangle().fill(LoginPalette.blue).frame(height: 2) } } }.buttonStyle(.plain).disabled(account.busy)
    }
    private func input(_ title: String, icon: String, placeholder: String, text: Binding<String>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
            HStack(spacing: 12) { Image(systemName: icon).foregroundStyle(.secondary).frame(width: 18); TextField(placeholder, text: text).textFieldStyle(.plain) }
                .padding(.horizontal, 15).frame(height: 48).background(.white, in: RoundedRectangle(cornerRadius: 11)).overlay(RoundedRectangle(cornerRadius: 11).stroke(LoginPalette.blue.opacity(0.18)))
        }
    }
}

struct AccountPage: View {
    @EnvironmentObject var account: NativeAccount
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            if account.signedIn {
                HStack {
                    Image(systemName: "person.crop.circle.fill").font(.system(size: 44)).foregroundStyle(.indigo)
                    VStack(alignment: .leading, spacing: 6) { Text(account.email).font(.headline); Text("TokenPro 云端账户").font(.system(size: 12)).foregroundStyle(.secondary) }
                    Spacer()
                    Button("退出登录") { Task { await account.logout() } }.buttonStyle(SoftButton()).disabled(account.busy)
                }
                Divider()
                Text("账户余额").font(.system(size: 12)).foregroundStyle(.secondary)
                Text(account.balance).font(.system(size: 32, weight: .bold, design: .rounded))
                Button("刷新账户") { Task { do { account.user = try await account.authenticated("/auth/me") } catch { account.message = error.localizedDescription } } }.buttonStyle(.plain).disabled(account.busy)
            } else {
                Text("登录 TokenPro").font(.title2.bold())
                Text("登录后可读取云端账户与 API 令牌。").foregroundStyle(.secondary)
                Button("登录 / 注册") { account.localMode = false }.buttonStyle(SoftButton(primary: true))
            }
            if account.busy { ProgressView().controlSize(.small) }
            if let message = account.message { Text(message).font(.system(size: 12)).foregroundStyle(.orange) }
            Spacer()
        }.padding(32)
    }
}

struct AppRoot: View {
    @EnvironmentObject var account: NativeAccount
    var body: some View {
        Group {
            if account.signedIn || account.localMode { ContentView() }
            else if account.loading || account.restorePending {
                VStack(spacing: 20) {
                    Image(systemName: "point.3.connected.trianglepath.dotted").font(.system(size: 48)).foregroundStyle(LoginPalette.blue)
                    Text(account.loading ? "正在恢复登录…" : "等待恢复登录").font(.title2.bold())
                    if account.loading { ProgressView().controlSize(.small) }
                    else {
                        Text(account.message ?? "登录信息已保留，重试即可继续。").foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 430)
                        Button("重试自动登录") { Task { await account.start() } }.buttonStyle(LoginButton(primary: true))
                        Button("仅管理本地连接") { account.localMode = true }.buttonStyle(.plain)
                    }
                }.frame(maxWidth: .infinity, maxHeight: .infinity).frame(minWidth: 1000, minHeight: 720)
                    .background(Color(red: 0.975, green: 0.98, blue: 1)).preferredColorScheme(.light)
            }
            else { LoginView() }
        }.task { if account.loading { await account.start() } }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            if account.restorePending && !account.loading { Task { await account.start() } }
        }
        .onReceive(Timer.publish(every: 10, on: .main, in: .common).autoconnect()) { _ in
            if account.restorePending && account.retryRestoreAutomatically && !account.loading && !account.localMode { Task { await account.start() } }
        }
    }
}

struct AgreementText: View {
    let markdown: String
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            ForEach(Array(markdown.components(separatedBy: "\n").enumerated()), id: \.offset) { _, line in
                if line.hasPrefix("# ") { Text(String(line.dropFirst(2))).font(.title3.bold()).padding(.top, 8) }
                else if line.hasPrefix("## ") { Text(String(line.dropFirst(3))).font(.headline).padding(.top, 8) }
                else if !line.isEmpty { Text(LocalizedStringKey(line)).font(.system(size: 13)).textSelection(.enabled).fixedSize(horizontal: false, vertical: true) }
            }
        }.frame(maxWidth: .infinity, alignment: .leading)
    }
}

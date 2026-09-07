# TokenPro Java 跨平台客户端

同一套 Java 21/Swing 源码支持 Windows、macOS 和 Linux。安装包必须在目标系统和目标芯片上构建；`jpackage` 会把 Java 运行时一起放进安装包，最终用户无需单独安装 Java。

## 当前功能

- TokenPro 邮箱密码登录和登录状态恢复。
- 读取并导入账户中的 API Key。
- 配置 TokenPro 接口地址和模型 ID。
- 安全地保存 API Key，通过 Codex command-backed authentication 提供凭据。
- 备份、接入和恢复 Codex 配置。
- 打开 TokenPro 官网、充值页、Codex 和 Claude。
- 配置目录自动适配 `%APPDATA%`、macOS Application Support 和 Linux XDG。

当前版本暂不写 Claude 的第三方配置，因为 Windows 与 macOS 的 Claude 配置目录和版本格式需要分别实机验证。客户端可以直接打开已经安装的 Claude。

## 构建

安装 JDK 21 后：

```bash
cd java-client
./build.sh
java -jar build/TokenPro.jar
```

Windows PowerShell：

```powershell
cd java-client
./build.ps1
& "$env:JAVA_HOME/bin/java.exe" -jar build/TokenPro.jar
```

## 原生安装包

- macOS Intel：在 Intel Mac 上运行 `./package.sh`。
- macOS Apple 芯片：在 Apple Silicon Mac 上运行 `./package.sh`。
- Windows x64/ARM64：在相应芯片的 Windows 机器上运行 `./package.ps1`。
- Linux：在 Linux 构建机运行 `./package.sh`。

GitHub Actions 会分别构建 Windows x64、macOS Intel、macOS Apple 芯片和 Linux x64 产物。Windows ARM64 可以在 ARM64 Windows 自托管 Runner 上使用相同脚本构建。

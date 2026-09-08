# TokenPro 跨平台桌面客户端

安装包可从仓库的 [GitHub Releases](https://github.com/121882249/TokenPro-Frontend/releases) 页面下载。

TokenPro 现在只有一套 Java 21/Swing 客户端源码，支持 Windows、macOS 和 Linux。macOS 的 Intel 与 Apple 芯片版本、Windows x64 版本和 Linux x64 版本由 GitHub Actions 分别在对应系统构建。

## 功能

- TokenPro 邮箱密码登录与本机会话恢复。
- 读取和导入账户中的 API Key。
- 设置接口地址与模型 ID。
- 将连接应用到 Codex，并恢复接入前的 Codex 配置。
- 通过纯 Java 本地桥接把 TokenPro 模型接入 Claude Desktop，支持模型分组自动切换、工具调用和流式响应。
- API Key 保存在当前系统用户的 TokenPro 私有配置目录，不写入 `config.toml`。
- 在 TokenPro 内置浏览器中打开后台管理、使用文档和充值页，并可启动 Codex 和 Claude。

## 构建

需要 JDK 21。macOS 或 Linux：

```bash
./build.sh
./release.sh
```

Windows PowerShell：

```powershell
./build.ps1
./release.ps1
```

详细的平台目录与打包说明见 [`java-client/README.md`](java-client/README.md)。

## 源码

Java 源码位于 `java-client/src/main/java/work/tokenpro/client/`。入口类是 `work.tokenpro.client.Main`。

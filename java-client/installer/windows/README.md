# Windows 中文安装包

沿用 `Router.ico`、`TokenProCosmosIcon.png`，以 `LoginCosmos-v2.png` 为风格参考，
使用内置 image_gen 生成匹配安装窗口比例的横向星空背景 `cosmos-installer-v1.png`。
提示词与生成说明见 `background-prompt.md`。将 jpackage app-image 打包为中文、
深色星空主题的当前用户安装包，不改变应用原有运行时素材。

## 构建

需要 JDK 21、已安装的 Windows SDK（`mt.exe`）和 Inno Setup 6.7.1+；可用 `INNO_ISCC` 指定 `ISCC.exe`。
运行 `java-client/package.ps1`。脚本不会自动安装构建工具。
Windows CI 也必须预先提供符合版本要求的编译器；工具缺失或版本过旧会明确报错，
不会退回旧的英文安装包。

GitHub Windows Server 2025 构建镜像已预装 6.7.1；该版本支持本模板所需的
深色主题和背景图片，不需要在构建中另外安装编译器。6.7.3 也兼容本模板。

官方来源：[下载](https://jrsoftware.org/isdl.php)、
[许可证](https://jrsoftware.org/files/is/license.txt)、
[权限](https://jrsoftware.org/ishelp/topic_setup_privilegesrequired.htm)、
[背景图片](https://jrsoftware.org/ishelp/topic_setup_wizardbackimagefile.htm)。
保留 Inno Setup 自身版权信息，不冒充自研安装引擎。

## 权限与数据

- 默认当前用户安装，可自选位置；也可选择“为所有用户安装”，由 Windows 请求 UAC 授权。
- 重装记住用户选择的位置；快捷方式名称与说明均为 `TokenPro`，安装向导仍为中文。
- 增量更新保留原安装目录。有写入权限时不提权；写入权限不足时仅让更新辅助进程申请 UAC，取消后程序保持打开，不强制迁移。
- 管理员辅助进程不启动 TokenPro、不改账号配置；普通权限的原用户进程负责重新打开应用。
- 不改变系统目录权限，不关闭 UAC 或 SmartScreen。
- 不删除账户数据、模型配置或聊天记录，不自动卸载旧的机器级安装。
- Windows 系统提示的语言和未签名警告不受安装主题控制。

## 旧版首次升级

v1.2.64 及以前的更新器在写入 Program Files 时可能失败。
新版代码不能改变尚未成功应用更新的旧更新器；旧受限安装首次应用新代码仍需单独验证引导方案。
不得以迁移到指定目录作为升级前提，也不能宣称新增量包能自动修复尚未升级的旧更新器。

发布前必须完成真实安装界面、非管理员安装/卸载、UAC 同意/取消、旧版原地引导、后续增量替换和
快捷方式验证；当前源码变更不代表这些验收已经通过。

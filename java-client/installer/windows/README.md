# Windows 中文安装包

沿用 `Router.ico`、`LoginCosmos-v2.png`、`TokenProCosmosIcon.png`，
将 jpackage app-image 打包为中文、深色星空主题的当前用户安装包。

## 构建

需要 JDK 21 和 Inno Setup 6.7.1+；可用 `INNO_ISCC` 指定 `ISCC.exe`。
运行 `java-client/package.ps1`。脚本不会自动安装构建工具。
Windows CI 也必须预先提供符合版本要求的编译器；工具缺失或版本过旧会明确报错，
不会退回英文、管理员安装包。

GitHub Windows Server 2025 构建镜像已预装 6.7.1；该版本支持本模板所需的
深色主题和背景图片，不需要在构建中另外安装编译器。6.7.3 也兼容本模板。

官方来源：[下载](https://jrsoftware.org/isdl.php)、
[许可证](https://jrsoftware.org/files/is/license.txt)、
[权限](https://jrsoftware.org/ishelp/topic_setup_privilegesrequired.htm)、
[背景图片](https://jrsoftware.org/ishelp/topic_setup_wizardbackimagefile.htm)。
保留 Inno Setup 自身版权信息，不冒充自研安装引擎。

## 权限与数据

- 默认 `%LOCALAPPDATA%\Programs\TokenPro`，不申请管理员权限。
- 不改变系统目录权限，不关闭 UAC 或 SmartScreen。
- 不删除账户数据、模型配置或聊天记录，不自动卸载旧的机器级安装。
- Windows 系统提示的语言和未签名警告不受安装主题控制。

## 旧版首次迁移

v1.2.64 及以前的更新器在写入 Program Files 时可能失败。
新版代码不能改变尚未成功应用更新的旧更新器；首次升级需在用户可写目录复用
本地 app-image，再应用已校验的增量包。不能宣称存在新增量包就能自动修复旧安装。

发布前必须完成真实安装界面、非管理员安装/卸载、旧版迁移、后续增量替换和
快捷方式验证；当前源码变更不代表这些验收已经通过。

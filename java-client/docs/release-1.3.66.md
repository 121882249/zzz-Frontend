# v1.3.66

- 修复 TokenPro ImageGen Skill 在 macOS 上绕过应用原生启动器、错误依赖 `/usr/bin/java` 的问题。
- 优先使用 `TokenPro.app/Contents/MacOS/TokenPro --tokenpro-imagegen`，终端用户无需单独安装 Java。
- 兼容 macOS jpackage 的嵌套 runtime 路径，并保留开发环境 Java fallback。

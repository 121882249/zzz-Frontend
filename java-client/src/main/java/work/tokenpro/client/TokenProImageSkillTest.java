package work.tokenpro.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

final class TokenProImageSkillTest {
    private TokenProImageSkillTest() {}

    static int run() throws Exception {
        int passed = 0;
        Path root = Files.createTempDirectory("tokenpro-image-skill-runtime-");
        try {
            Path macJar = root.resolve("TokenPro.app/Contents/app/TokenPro.jar");
            Path macJava = root.resolve("TokenPro.app/Contents/runtime/bin/java");
            executable(macJar, macJava);
            Optional<Path> mac = TokenProImageSkill.findJavaExecutable(macJar, root.resolve("missing-java"), "", "", Platform.OS.MAC);
            check(mac.orElseThrow().equals(macJava.toAbsolutePath().normalize()), "macOS uses the bundled Contents/runtime Java"); passed++;

            Path nestedMacJar = root.resolve("NestedTokenPro.app/Contents/app/TokenPro.jar");
            Path nestedMacJava = root.resolve("NestedTokenPro.app/Contents/runtime/Contents/Home/bin/java");
            executable(nestedMacJar, nestedMacJava);
            Optional<Path> nestedMac = TokenProImageSkill.findJavaExecutable(nestedMacJar, root.resolve("missing-java"), "", "", Platform.OS.MAC);
            check(nestedMac.orElseThrow().equals(nestedMacJava.toAbsolutePath().normalize()), "macOS accepts the nested jpackage runtime layout"); passed++;

            Path macLauncher = root.resolve("TokenPro.app/Contents/MacOS/TokenPro");
            executable(macLauncher);
            if (Platform.OS_KIND == Platform.OS.MAC) {
                Optional<Path> launcher = TokenProImageSkill.nativeLauncher(macJar);
                check(launcher.orElseThrow().equals(macLauncher.toAbsolutePath().normalize()), "macOS prefers the native jpackage launcher"); passed++;
            }

            Path windowsJar = root.resolve("Windows/TokenPro/app/TokenPro.jar");
            Path windowsJava = root.resolve("Windows/TokenPro/runtime/bin/java.exe");
            executable(windowsJar, windowsJava);
            Optional<Path> windows = TokenProImageSkill.findJavaExecutable(windowsJar, root.resolve("missing-java"), "", "", Platform.OS.WINDOWS);
            check(windows.orElseThrow().equals(windowsJava.toAbsolutePath().normalize()), "Windows uses the bundled runtime Java"); passed++;

            Path linuxJar = root.resolve("Linux/TokenPro/lib/app/TokenPro.jar");
            Path linuxJava = root.resolve("Linux/TokenPro/lib/runtime/bin/java");
            executable(linuxJar, linuxJava);
            Optional<Path> linux = TokenProImageSkill.findJavaExecutable(linuxJar, root.resolve("missing-java"), "", "", Platform.OS.LINUX);
            check(linux.orElseThrow().equals(linuxJava.toAbsolutePath().normalize()), "Linux uses the bundled lib/runtime Java"); passed++;

            Path javaHome = root.resolve("java-home");
            Path fallback = javaHome.resolve("bin/java");
            executable(fallback);
            Optional<Path> system = TokenProImageSkill.findJavaExecutable(root.resolve("development/TokenPro.jar"), javaHome, "", "", Platform.OS.MAC);
            check(system.orElseThrow().equals(fallback.toAbsolutePath().normalize()), "development mode falls back to java.home"); passed++;
        } finally {
            try (var files = Files.walk(root)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
        return passed;
    }

    private static void executable(Path... paths) throws Exception {
        for (Path path : paths) {
            Files.createDirectories(path.getParent());
            Files.writeString(path, "runtime");
            path.toFile().setExecutable(true, true);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}

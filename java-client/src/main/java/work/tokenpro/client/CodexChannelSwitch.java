package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class CodexChannelSwitch {
    static int run(SecureStore store, Path config, String provider, List<String> models,
                   ClientReconnect.Action stop, ClientReconnect.Action write,
                   ClientReconnect.Action start, ClientReconnect.Action restartPrevious) throws Exception {
        stop.run();
        ChannelSettingsBackup backup = null;
        List<CodexHistorySettings.Setting> previous = List.of();
        boolean historyTouched = false;
        boolean applied = false;
        try {
            backup = new ChannelSettingsBackup(store, config);
            Path home = config.toAbsolutePath().getParent();
            Files.createDirectories(home);
            if (Files.isDirectory(home.resolve("sessions"))) previous = CodexHistorySettings.capture(home);
            backup.history(previous);
            for (String mode : List.of("openai", "custom")) {
                List<CodexHistorySettings.Setting> group = previous.stream().filter(s -> mode.equals(s.provider())).toList();
                if (!group.isEmpty()) store.write("channel-" + mode + "-threads.json", CodexHistorySettings.encode(group));
            }
            List<CodexHistorySettings.Setting> preferences = CodexHistorySettings.decode(store.read("channel-" + provider + "-threads.json").orElse("[]"));
            write.run();
            if (!previous.isEmpty()) {
                List<CodexHistorySettings.Setting> targets = CodexHistorySettings.targets(home, previous, provider, models, preferences);
                historyTouched = true;
                CodexHistorySettings.apply(home, targets);
            }
            applied = true;
            start.run();
            return previous.size();
        } catch (Exception failure) {
            if (applied) throw new ManualStartRequiredException("Codex", failure);
            try {
                stop.run();
                if (backup != null) backup.restore();
                if (historyTouched) CodexHistorySettings.apply(config.toAbsolutePath().getParent(), previous);
                restartPrevious.run();
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
                throw new IOException("切换失败，自动恢复未完成；设置备份位于 " + (backup == null ? "未建立" : backup.location()), failure);
            }
            throw new IOException("切换未完成：" + failure.getMessage() + "；已恢复切换前的设置并重新打开原渠道", failure);
        }
    }
}

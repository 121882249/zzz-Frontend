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
        boolean applied = false;
        int skipped = 0;
        Exception historyWarning = null;
        try {
            backup = new ChannelSettingsBackup(store, config);
            Path home = config.toAbsolutePath().getParent();
            Files.createDirectories(home);
            if (Files.isDirectory(home.resolve("sessions"))) {
                try {
                    CodexHistorySettings.Capture captured = CodexHistorySettings.captureAvailable(home);
                    previous = captured.settings();
                    skipped += captured.skipped();
                } catch (Exception unavailable) {
                    // Existing conversations are optional compatibility data. A changed or
                    // damaged app-server history must not prevent the selected channel from
                    // becoming active for new conversations.
                    historyWarning = unavailable;
                }
            }
            backup.history(previous);
            for (String mode : List.of("openai", "custom")) {
                List<CodexHistorySettings.Setting> group = previous.stream().filter(s -> mode.equals(s.provider())).toList();
                if (!group.isEmpty()) store.write("channel-" + mode + "-threads.json", CodexHistorySettings.encode(group));
            }
            List<CodexHistorySettings.Setting> preferences = CodexHistorySettings.decode(store.read("channel-" + provider + "-threads.json").orElse("[]"));
            write.run();
            applied = true;
            if (!previous.isEmpty()) {
                try {
                    List<CodexHistorySettings.Setting> targets = CodexHistorySettings.targets(home, previous, provider, models, preferences);
                    CodexHistorySettings.Migration migration = CodexHistorySettings.applyAvailable(home, targets);
                    skipped += migration.skipped();
                } catch (Exception unavailable) {
                    historyWarning = unavailable;
                }
            }
            start.run();
            if (skipped > 0 || historyWarning != null)
                throw new ChannelSwitchCompletedWarningException(skipped, historyWarning);
            return previous.size();
        } catch (Exception failure) {
            if (failure instanceof ChannelSwitchCompletedWarningException) throw failure;
            if (applied) throw new ManualStartRequiredException("Codex", failure);
            try {
                stop.run();
                if (backup != null) backup.restore();
                restartPrevious.run();
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
                throw new IOException("切换失败，自动恢复未完成；设置备份位于 " + (backup == null ? "未建立" : backup.location()), failure);
            }
            throw new IOException("切换未完成：" + failure.getMessage() + "；已恢复切换前的设置并重新打开原渠道", failure);
        }
    }
}

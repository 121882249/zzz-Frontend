package work.tokenpro.client;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class CodexChannelSwitch {
    static int run(SecureStore store, Path config, CodexChannel target, List<String> models,
                   ClientReconnect.Action stop, ClientReconnect.Action write,
                   ClientReconnect.Action start) throws Exception {
        CodexChannelState.captureOfficialAuth(store, config);
        ChannelSettingsBackup backup = new ChannelSettingsBackup(store, config);
        boolean stopped = false;
        boolean writeStarted = false;
        try {
            stop.run();
            stopped = true;
            writeStarted = true;
            write.run();
            CodexChannelState.verify(config, target);
            start.run();
            CodexChannelState.verify(config, target);
        } catch (Exception failure) {
            if (!writeStarted) throw new IOException("Codex 客户端未能安全停止，渠道配置未修改", failure);
            try {
                if (stopped) stop.run();
                backup.restore();
                if (stopped) start.run();
            } catch (Exception rollback) {
                failure.addSuppressed(rollback);
                throw new IOException("Codex 渠道切换失败，自动恢复未完成；设置备份位于 " + backup.location(), failure);
            }
            throw new IOException("Codex 渠道切换失败：" + Objects.toString(failure.getMessage(), failure.getClass().getSimpleName())
                + "；已恢复原配置并重新打开原渠道", failure);
        }
        return models.size();
    }
}

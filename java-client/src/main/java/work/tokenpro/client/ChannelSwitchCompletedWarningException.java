package work.tokenpro.client;

import java.io.IOException;

/** The selected channel is active, but one or more stale conversations could not be migrated. */
final class ChannelSwitchCompletedWarningException extends IOException {
    ChannelSwitchCompletedWarningException(int skipped, Throwable cause) {
        super(message(skipped, cause), cause);
    }

    private static String message(int skipped, Throwable cause) {
        String affected = skipped > 0 ? "有 " + skipped + " 个旧对话" : "旧对话";
        String reason = cause == null ? "" : "（" + ErrorMessages.describe(cause) + "）";
        return "渠道配置已生效，但" + affected + "无法同步渠道设置" + reason
            + "；不会回滚新渠道，可新建对话继续使用";
    }
}

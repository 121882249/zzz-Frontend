package work.tokenpro.client;

import java.io.IOException;

/** The channel was committed successfully; only automatic client startup failed. */
final class ManualStartRequiredException extends IOException {
    ManualStartRequiredException(String client, Throwable cause) {
        super("渠道配置已完成，但自动启动 " + client + " 失败；请手动启动客户端。新渠道配置已保留，不会回滚", cause);
    }
}

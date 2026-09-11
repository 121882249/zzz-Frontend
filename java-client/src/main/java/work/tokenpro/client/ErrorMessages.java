package work.tokenpro.client;

import java.awt.*;
import java.util.*;
import java.util.concurrent.*;
import java.net.*;
import java.net.http.HttpTimeoutException;
import javax.swing.*;

final class ErrorMessages {
    private ErrorMessages() {}
    static String http(int status, String path, String body) {
        Map<String,Object> json;
        try { json = Json.object(Json.parse(body)); } catch (Exception ignored) { json = Map.of(); }
        Map<String,Object> nested = json.get("error") instanceof Map<?,?> raw ? Json.object(raw) : Map.of();
        String message = first(json.get("message"), nested.get("message"), json.get("msg"), json.get("error"));
        String code = first(json.get("reason"), nested.get("code"), nested.get("type"), json.get("code"));
        String lower = (code + " " + message).toLowerCase(Locale.ROOT);
        String meaning;
        if (lower.contains("invalid_credentials") || lower.contains("invalid email or password") || lower.contains("incorrect password"))
            meaning = "邮箱或密码不正确，请检查后重试";
        else if (lower.contains("api key") && (lower.contains("invalid") || lower.contains("expired") || lower.contains("disabled")))
            meaning = "API Key 无效、过期或已停用，请重新连接或检查密钥状态";
        else if (lower.contains("token_expired") || lower.contains("token has expired") || lower.contains("token expired"))
            meaning = "登录已过期，请重新登录";
        else if (lower.contains("user_disabled") || lower.contains("account_disabled") || lower.contains("account is disabled") || lower.contains("user is disabled"))
            meaning = "账户已停用，请联系管理员";
        else if (lower.contains("insufficient") && (lower.contains("balance") || lower.contains("credit")))
            meaning = "账户余额不足，请充值或检查订阅额度";
        else if (lower.contains("quota") && (lower.contains("exceed") || lower.contains("exhaust")))
            meaning = "可用额度已用完，请检查账户或订阅额度";
        else if (lower.contains("concurren")) meaning = "同时运行的请求已达上限，请等待当前请求完成";
        else if (lower.contains("rate_limit") || lower.contains("rate limit") || lower.contains("too many requests"))
            meaning = "请求过于频繁，请稍后再试";
        else if (lower.contains("invalid_email") || lower.contains("invalid email")) meaning = "邮箱格式不正确，请检查邮箱地址";
        else if (lower.contains("email_not_verified") || lower.contains("email is not verified")) meaning = "邮箱尚未验证，请先完成邮箱验证";
        else if (message.codePoints().anyMatch(c -> c >= 0x4e00 && c <= 0x9fff)) meaning = safe(message);
        else meaning = switch (status) {
            case 400, 422 -> "请求参数不正确，请检查输入内容";
            case 401 -> "/auth/login".equals(path) ? "邮箱或密码不正确，请检查后重试" : "登录状态或连接凭据已失效，请重新登录或重新连接";
            case 402 -> "账户余额或可用额度不足，请检查余额和订阅";
            case 403 -> "没有权限执行此操作，请检查账户状态及模型权限";
            case 404 -> "请求的模型、接口或资源不存在，请刷新列表后重试";
            case 408, 504 -> "服务响应超时，请检查网络并稍后重试";
            case 409 -> "当前状态发生冲突，请刷新后重试";
            case 413 -> "请求内容过大，请减少输入或附件大小";
            case 429 -> "请求频率或并发数已达上限，请稍后再试";
            case 500 -> "服务器内部异常，请稍后重试";
            case 502, 503 -> "服务暂时不可用，请稍后重试";
            default -> "服务未能完成操作，请稍后重试或联系支持";
        };
        String details = status >= 400 ? "HTTP " + status : "";
        if (!code.isBlank() && !java.util.List.of("0", "200", String.valueOf(status)).contains(code) && code.matches("[A-Za-z0-9_.-]{1,64}"))
            details += (details.isEmpty() ? "" : " / ") + "服务代码 " + code;
        return meaning + (details.isEmpty() ? "" : "（" + details + "）");
    }

    static String describe(Throwable error) {
        while ((error instanceof ExecutionException || error instanceof CompletionException || error instanceof java.lang.reflect.InvocationTargetException)
            && error.getCause() != null) error = error.getCause();
        if (error instanceof ApiClient.ApiException) return safe(error.getMessage());
        if (error instanceof HttpTimeoutException || error instanceof SocketTimeoutException) return "网络请求超时，请检查网络后重试";
        if (error instanceof UnknownHostException) return "无法解析服务器地址，请检查网络或 DNS 设置";
        if (error instanceof ConnectException) return "无法连接服务，请检查网络或对应的本地连接是否启动";
        if (error instanceof javax.net.ssl.SSLException) return "安全连接校验失败，请检查系统时间或网络代理；不要关闭证书校验";
        if (error instanceof java.nio.file.AccessDeniedException) return "无法读写配置文件，请检查权限或文件是否被其他程序占用";
        if (error instanceof CancellationException || error instanceof InterruptedException) return "操作已取消，可稍后重试";
        String message = safe(error == null ? "" : error.getMessage());
        if (message.matches("[45]\\d{2}")) return http(Integer.parseInt(message), "", "");
        if (message.matches("-?\\d+")) return "程序未能完成操作（错误代码 " + message + "），请重试或联系支持";
        java.util.regex.Matcher status = java.util.regex.Pattern.compile("\\bHTTP\\s+(\\d{3})\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
        if (status.find()) return http(Integer.parseInt(status.group(1)), "", "");
        return message.isBlank() ? "操作未完成，请重试或联系支持" : message;
    }

    static String safe(String value) {
        if (value == null) return "";
        String text = value.replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+", "Bearer [已隐藏]")
            .replaceAll("\\bsk-[A-Za-z0-9_-]{6,}", "[密钥已隐藏]")
            .replaceAll("(?i)((?:access_token|refresh_token|password|api_key)\\s*[:=]\\s*)[^\\s,;]+", "$1[已隐藏]")
            .replaceAll("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f]", "").trim();
        return text.length() > 700 ? text.substring(0, 700) + "…" : text;
    }

    static void show(Component owner, Throwable error) {
        JButton ok = new JButton("确定");
        ok.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        ok.setForeground(Color.WHITE); ok.setBackground(new Color(65,105,235)); ok.setOpaque(true);
        ok.setBorder(BorderFactory.createEmptyBorder(8, 24, 8, 24));
        JOptionPane pane = new JOptionPane(messageComponent(error), JOptionPane.ERROR_MESSAGE,
            JOptionPane.DEFAULT_OPTION, null, new Object[]{ok}, ok);
        pane.setBackground(new Color(11,20,47));
        JDialog dialog = pane.createDialog(owner, "TokenPro");
        ok.addActionListener(e -> dialog.dispose());
        dialog.getRootPane().setDefaultButton(ok);
        dialog.setVisible(true); dialog.dispose();
    }

    static JScrollPane messageComponent(Throwable error) {
        JTextArea message = new JTextArea(describe(error));
        message.setEditable(false); message.setLineWrap(true); message.setWrapStyleWord(true);
        message.setFont(new Font("Dialog", Font.PLAIN, 14));
        message.setForeground(new Color(242,245,255)); message.setBackground(new Color(11,20,47));
        message.setBorder(BorderFactory.createEmptyBorder(12,12,12,12));
        message.setColumns(34); message.setRows(Math.min(9, Math.max(3, message.getText().length() / 30 + 1)));
        JScrollPane scroll = new JScrollPane(message); scroll.setBorder(BorderFactory.createEmptyBorder());
        return scroll;
    }

    private static String first(Object... values) {
        for (Object value : values) if ((value instanceof String || value instanceof Number) && !String.valueOf(value).isBlank()) {
            if (value instanceof Number number && Double.isFinite(number.doubleValue()) && number.doubleValue() == Math.rint(number.doubleValue())) return Long.toString(number.longValue());
            return String.valueOf(value);
        }
        return "";
    }
}

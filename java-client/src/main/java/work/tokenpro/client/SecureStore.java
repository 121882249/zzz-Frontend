package work.tokenpro.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

final class SecureStore {
    private final Path root = Platform.dataDirectory();

    SecureStore() throws IOException { Files.createDirectories(root); }

    Optional<String> read(String name) throws IOException {
        Path path = safe(name);
        return Files.exists(path) ? Optional.of(Files.readString(path)) : Optional.empty();
    }

    void write(String name, String value) throws IOException {
        Path path = safe(name);
        Path temporary = Files.createTempFile(root, ".tokenpro-", ".tmp");
        Files.writeString(temporary, value, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
        Platform.privateFile(temporary);
        try { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        Platform.privateFile(path);
    }

    void delete(String name) throws IOException { Files.deleteIfExists(safe(name)); }

    String createCredential(String key) throws IOException {
        if (key.length() < 8 || key.chars().anyMatch(Character::isWhitespace) || key.contains("*") || key.contains("…")) {
            throw new IllegalArgumentException("API Key 格式不正确");
        }
        String id = "upstream-" + UUID.randomUUID();
        Path credentials = root.resolve("credentials");
        Files.createDirectories(credentials);
        Path path = credentials.resolve(id);
        Files.writeString(path, key, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Platform.privateFile(path);
        return id;
    }

    String credential(String id) throws IOException {
        if (!id.matches("upstream-[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("凭据编号无效");
        Path path = root.resolve("credentials").resolve(id).normalize();
        if (!path.startsWith(root.resolve("credentials"))) throw new IllegalArgumentException("凭据路径无效");
        return Files.readString(path).trim();
    }

    Path root() { return root; }

    private Path safe(String name) {
        if (!name.matches("[a-z0-9.-]+")) throw new IllegalArgumentException("文件名无效");
        return root.resolve(name);
    }
}

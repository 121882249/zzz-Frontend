package work.tokenpro.client;

import java.nio.file.*;
import java.util.*;

/** Verifies the installed Codex persists a legacy provider override without sending a turn. */
public final class CodexConversationRepairIntegrationTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("tokenpro-conversation-repair-");
        try {
            String id = UUID.randomUUID().toString();
            Path session = root.resolve("sessions/2026/09/19/rollout-2026-09-19T01-00-00-" + id + ".jsonl");
            Files.createDirectories(session.getParent());
            Files.writeString(root.resolve("config.toml"), "model=\"gpt-5.6-sol\"\nmodel_provider=\"openai\"\n");
            String initial = Json.stringify(Map.of(
                "timestamp", "2026-09-19T01:00:00Z", "type", "session_meta", "payload", Map.of(
                    "id", id, "session_id", id, "timestamp", "2026-09-19T01:00:00Z", "cwd", root.toString(),
                    "originator", "Codex Desktop", "cli_version", "0.155.0-alpha.9", "source", "vscode",
                    "thread_source", "user", "model_provider", "custom"))) + "\n"
                + Json.stringify(Map.of("timestamp", "2026-09-19T01:00:01Z", "type", "event_msg", "payload", Map.of(
                    "type", "user_message", "message", "Preserve fixture conversation", "images", List.of()))) + "\n";
            Files.writeString(session, initial);

            CodexConversationRepair.Result result = CodexConversationRepair.repair(root, "openai", "gpt-5.6-sol");
            require(result.discovered() == 1 && result.repaired() == 1 && result.failed() == 0,
                "legacy provider was not repaired: " + result);
            try (CodexAppServerRpc rpc = new CodexAppServerRpc(root)) {
                Map<String,Object> resumed = rpc.call("thread/resume", Map.of("threadId", id, "excludeTurns", true));
                require("openai".equals(resumed.get("modelProvider")), "provider did not persist as openai");
                require("gpt-5.6-sol".equals(resumed.get("model")), "repair changed the requested target model");
                rpc.call("thread/unsubscribe", Map.of("threadId", id));
            }
            Files.writeString(root.resolve("config.toml"), """
                model="gpt-5.5"
                model_provider="custom"
                [model_providers.custom]
                name="CCSwitch fixture"
                base_url="http://127.0.0.1:1/v1"
                wire_api="responses"
                requires_openai_auth=false
                experimental_bearer_token="fixture-key"
                """);
            CodexConversationRepair.Result custom = CodexConversationRepair.repair(root, "custom", "gpt-5.5");
            require(custom.repaired() == 1 && custom.failed() == 0, "reverse custom repair failed: " + custom);
            try (CodexAppServerRpc rpc = new CodexAppServerRpc(root)) {
                Map<String,Object> resumed = rpc.call("thread/resume", Map.of("threadId", id, "excludeTurns", true));
                require("custom".equals(resumed.get("modelProvider")), "provider did not persist as custom");
                require("gpt-5.5".equals(resumed.get("model")), "custom repair changed the target model");
                rpc.call("thread/unsubscribe", Map.of("threadId", id));
            }
            require(Files.readString(session).startsWith(initial), "repair rewrote original conversation records");
            System.out.println("Installed Codex persisted both openai and custom repair targets without sending a user turn.");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}

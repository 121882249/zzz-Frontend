# Channel switching verification

Implementation uses the installed Codex `app-server` protocol. `thread/resume` provider overrides alone proved transient. Calling `thread/settings/update` after the explicit resume persists the thread-owned settings. The implementation cold-resumes through a second server and checks the effective provider and model before reporting success.

Only resumable interactive Codex threads with an effective `openai` or `custom` provider are migrated. Other providers are left alone. A stale/deleted thread or an app-server version that cannot migrate old conversations no longer blocks the selected channel: usable threads are handled independently, the new configuration remains active, and the user receives a partial-migration warning with the supported RPC failure detail. There is no direct rollout/SQLite editing fallback.

Codex switching is a transaction over an explicit private snapshot: `config.toml`, `auth.json`, `models_cache.json`, TokenPro selection state, and immutable TokenPro model catalogs. Conversation text and session databases are never copied. Before activating an API-key channel, a structurally valid ChatGPT OAuth login is copied to TokenPro's independent credential store. Returning to official mode restores that copy and marks the model cache stale for Codex to refresh.

The active route uses a paired `# >>> tokenpro-codex` marker for L2 keys and a separately owned provider block. All complete `<owner>-codex` activation blocks are removed during a switch, while L0 desktop, plugin, marketplace, project, MCP, feature, approval, and sandbox settings remain byte-for-byte unless the documented Windows compatibility setting applies. Foreign channel applications, services, backups, catalogs, and skills are never deleted.

After writing, TokenPro reads the disk again and jointly verifies provider, endpoint, marker owner, and `auth_mode`. A write, validation, or automatic launch failure restores the full snapshot and attempts to reopen the previous channel. Old-conversation migration remains best-effort work only after the new disk state and client launch have succeeded. If rollback fails, the private backup location is reported instead of claiming success.

Official connectivity preflight makes an unauthenticated HEAD request before exiting. A 401 proves reachability, not successful official login. The success message does not claim authenticated network access. Proxy/network failures after launch do not trigger background channel changes. HTTP and SOCKS environment proxies are recognized; unsupported proxy formats stop preflight rather than being silently bypassed.

Validation on 2026-09-18:

- 538 headless checks, including pre-mutation stop failure handling, partial-write and launch-failure rollback, post-start route-overwrite detection, OAuth preservation and missing-OAuth refusal, API-key activation, disk-only channel detection, model-cache refresh/staling, owner-marker replacement, foreign artifact preservation, concurrent config-write rejection, private-file permissions, partial-history warning behavior, proxy parsing, managed CLI identification, and Windows updater readiness/error-dialog behavior.
- Swing account-card regression and two-entry channel menu screenshot.
- Installed Codex 0.154.0-alpha.6.2: synthetic thread migrated openai → custom → openai with fresh-process verification in both directions.
- An additional synthetic turn after custom migration reached a localhost mock Responses endpoint with the expected Bearer token. No paid upstream model was called.
- Original synthetic conversation records remained byte-for-byte intact; Codex appended its own settings events through supported APIs.

The integration harness creates its own temporary profile. It never opens the user's live Codex home. Run with JDK 21:

```sh
./java-client/build.sh
javac --add-modules jdk.httpserver -cp java-client/build/classes -d java-client/build/ui-tests java-client/src/test/java/work/tokenpro/client/CodexHistorySettingsIntegrationTest.java
java --add-modules jdk.httpserver -cp java-client/build/classes:java-client/build/ui-tests work.tokenpro.client.CodexHistorySettingsIntegrationTest
```

Windows/Linux packaging is checked by release CI; the live app-server integration was executed on macOS. Actual user-session switching was not exercised to avoid interrupting ongoing work.

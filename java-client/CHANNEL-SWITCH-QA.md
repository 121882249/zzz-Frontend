# Channel switching verification

Implementation uses the installed Codex `app-server` protocol. `thread/resume` provider overrides alone proved transient. Calling `thread/settings/update` after the explicit resume persists the thread-owned settings. The implementation cold-resumes through a second server and checks the effective provider and model before reporting success.

Only resumable interactive Codex threads with an effective `openai` or `custom` provider are migrated. Other providers are left alone. A stale/deleted thread or an app-server version that cannot migrate old conversations no longer blocks the selected channel: usable threads are handled independently, the new configuration remains active, and the user receives a partial-migration warning with the supported RPC failure detail. There is no direct rollout/SQLite editing fallback.

Settings backups use an explicit file allowlist and private files. They include channel configuration and available thread ID/provider/model associations, never conversation text or session databases. A failed configuration restores the pre-switch settings and attempts to reopen the previous channel. Old-conversation migration is best-effort compatibility work after the new configuration is committed and cannot roll it back. An automatic launch failure likewise keeps the new channel and asks the user to start the client manually. Desktop startup confirmation waits at most eight seconds, while CLI launch keeps its separate twenty-second allowance. If rollback fails, the backup location is reported instead of claiming success.

Official connectivity preflight makes an unauthenticated HEAD request before exiting. A 401 proves reachability, not successful official login. The success message does not claim authenticated network access. Proxy/network failures after launch do not trigger background channel changes. HTTP and SOCKS environment proxies are recognized; unsupported proxy formats stop preflight rather than being silently bypassed.

Validation on 2026-09-13:

- 424 headless checks, including partial-write rollback, partial-history warning behavior, preservation of new message content, explicit settings-only backup, proxy parsing, managed CLI identification, and Windows updater readiness/error-dialog behavior.
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

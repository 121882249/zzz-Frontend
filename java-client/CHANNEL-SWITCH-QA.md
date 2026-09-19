# Codex channel switching verification

TokenPro now follows Codex's lightweight built-in channel model. A switch owns only:

- `config.toml` route keys inside the paired `tokenpro-codex` marker;
- `auth.json` (`apikey` for TokenPro, saved ChatGPT OAuth state for official mode);
- `models_cache.json` and one immutable TokenPro model catalog;
- TokenPro's selected-model state.

Normal TokenPro channel switching does not list, resume, migrate, rewrite, or back up Codex conversations. The old automatic history migration was removed. This avoids touching conversation working directories under macOS Documents/Desktop and avoids carrying a legacy provider identity between channels.

The Codex desktop channel menu also exposes a separate, explicit **修复历史对话** action. It is never invoked by a normal channel switch. The dialog offers `CCSwitch` and `OpenAI`: the selected target must already be the active disk configuration, so its provider, endpoint, credentials, and model stay consistent. It stops Codex, backs up each rollout, changes only the `session_meta.model_provider` field, uses Codex's app-server settings API to persist the current route model, temporarily unarchives archived threads and restores their archive state, verifies the result with a fresh app-server session, and restarts Codex automatically. It does not submit a turn, change conversation content, or directly edit SQLite. The result separates user-visible conversations from Codex internal guardian tasks.

The TokenPro activation block uses Codex's built-in provider:

```toml
# >>> tokenpro-codex
model = "tp-g<group-id>-<base64url-model>"
model_provider = "openai"
openai_base_url = "https://tokenpro.work/v1"
model_catalog_json = "<immutable TokenPro catalog>"
# <<< tokenpro-codex
```

There is no persistent `[model_providers.custom]` table and no `experimental_bearer_token` in `config.toml`. TokenPro's global key is stored in `auth.json`; the group-qualified model slug supplies the backend group ID.

Switching from an older release removes marked or unmarked `custom -> tokenpro.work` provider tables. Switching from a foreign channel removes only that channel's active config block and route credentials; its application, skills, catalogs, caches, and backups remain untouched.

After writing, TokenPro reads the disk again and verifies provider, root endpoint, marker owner, authentication mode, and model cache. A write, validation, or launch failure restores the explicit settings snapshot and reopens the previous channel. Conversation files and databases are never part of that snapshot.

Validation on 2026-09-18:

- 519 headless checks passed.
- Installed Codex `0.155.0-alpha.9` was run with an isolated temporary `CODEX_HOME` against a loopback fixture.
- The fixture received the exact `tp-g41-...` model slug and `Authorization: Bearer <global key>` through `model_provider = "openai"`.
- No real upstream model was called and no user Codex profile or conversation was opened.

Additional validation on 2026-09-19:

- 523 headless checks passed.
- The installed Codex migrated an isolated synthetic conversation in both directions (`custom -> openai` and `openai -> custom`), persisted each selected model, sent no user turn, and retained the original conversation records byte-for-byte.

Run with JDK 21:

```sh
./java-client/build.sh
javac --release 21 --add-modules jdk.httpserver \
  -cp java-client/build/classes -d java-client/build/ui-tests \
  java-client/src/test/java/work/tokenpro/client/CodexOpenAiProviderIntegrationTest.java
java --add-modules jdk.httpserver \
  -cp java-client/build/classes:java-client/build/ui-tests \
  work.tokenpro.client.CodexOpenAiProviderIntegrationTest
```

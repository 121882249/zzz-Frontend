# Codex channel switching verification

TokenPro now follows Codex's lightweight built-in channel model. A switch owns only:

- `config.toml` route keys inside the paired `tokenpro-codex` marker;
- `auth.json` (`apikey` for TokenPro, saved ChatGPT OAuth state for official mode);
- `models_cache.json` and one immutable TokenPro model catalog;
- TokenPro's selected-model state.

TokenPro does not list, resume, migrate, rewrite, or back up Codex conversations. The old history-repair implementation and its app-server migration tests were removed. This avoids touching conversation working directories under macOS Documents/Desktop and avoids carrying a legacy provider identity between channels.

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

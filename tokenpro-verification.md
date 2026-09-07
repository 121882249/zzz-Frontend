# v0.2.0 TokenPro integration verification

- Public live site checked: https://tokenpro.work/login renders email/password login, forgot-password and registration links in the in-app browser.
- Live public frontend assets confirm localStorage auth_token, /api/v1 API root, GET /keys pagination and GET /keys/{id}.
- Swift build: passed for Apple Silicon/macOS 14+.
- 20 core tests: passed, including 5 origin-policy checks.
- 19 proxy tests: passed against offline fixtures.
- 12 JavaScript bridge tests: passed. Covers origin, auth absence/401, metadata-only list, pagination, GET-only requests, redirect rejection, selected key import, inactive/masked/mismatched keys, invalid inputs and malformed responses.
- No live user credentials or website API keys were read during development.
- The existing user's Codex configuration was not modified.
- Native v0.2 website view and real login/import are not verified: CUA reported the Mac locked and automatic unlock failed. User was asked to unlock. This is a validation limitation, not a claim that login has succeeded.

Native UI verification status: pending unlock.
Previous design-qa.md records only the v0.1 baseline and does not establish v0.2 UI verification.

# v0.3 Native login QA

Source: user screenshot `/var/folders/1s/tl_s3grd4p50bs85w46x1h9h0000gn/T/codex-clipboard-f96c712b-428b-40bd-abdf-a4a0f9c48101.png` (2192 × 1454 physical pixels; conversation display 1930 × 1280).
Implementation: native `build-v0.3/Codex Router.app`, CUA screenshots 1120 × 760 logical pixels. Different density; compared two-column proportions and corresponding content, not absolute pixel equality. Captures are inline CUA outputs.

Observed: large left warm textured promotional panel; right native email/password form; login/register tabs; black primary action with disabled state; dynamic registration email-code field; backend-loaded agreement sheet. No web wrapper or task/chat button in the main flow.

Intentional adaptations: TokenPro identity and functional badges instead of Teamo assets. Password-based login and email verification for registration reflect the actual backend. Google/mobile and optional invitation fields are absent when disabled by backend configuration. Native system font and SF Symbols are used.

Interaction checks: login/register switching, cloud settings load, agreement viewing. User filled the form and corrected a misspelled Gmail domain; live code-send succeeded. The assistant did not accept the agreement or send the real registration request.

Fixes: added actionable whitelist/domain error; aligned login and registration vertical padding in final build; rendered agreement heading and inline markdown instead of raw markup. Regression tests cover domain validation. Final packaging uses the updated build while preserving the user's in-progress registration in the original preview window.

Limit: final minor padding/markdown revision is not yet recaptured because user is actively completing registration. Main native layout was visually checked before these small adjustments. This is not a pixel-identical Teamo clone.

## v0.3.1 blue palette
Replaced warm raster background with native blue gradient, matching the app icon RGB (0.40, 0.32, 0.94). Login-specific buttons, selected tabs, links and borders share the palette. CUA screenshots of an isolated NSHostingView preview verified both login and registration tabs. Corrected background layout overflow; final screenshots show no clipping or overlap. Preview used empty in-memory credentials and no network requests; backend-conditional registration fields were not enabled in this fixture. Release build passed 20 existing core checks. Authentication and proxy logic are unchanged.

## v0.3.2 labels
Replaced “Codex 客户端” with “Codex”, and added Claude and Gemini beside it using native symbols and the existing capsule style. This is a presentation-only change.

# 主页面本地设计预览

Source: /Users/Tiger/.codex/generated_images/01a08a76-445e-77b3-914b-3bc3c4a40406/exec-73d43e71-0f50-4916-a18d-16c011e331b9.png

Implementation: /Users/Tiger/Documents/Codex/2026-09-10/tokenpro-followup/preview-home.png

Comparison: /Users/Tiger/Documents/Codex/2026-09-10/tokenpro-followup/preview-compare.png

State: isolated demo account, four installed applications, four selected models, two subscriptions. No live account writes or requests. Actual Swing rendering, 1280×820 and 1080×820 logical pixels, 1× image density; CSS size is not applicable. Source is 1487×1058; comparison crops the application region and scales source proportionally to 1002px width. Implementation is unscaled. Full screen and focused application region were opened and inspected.

## Findings and iteration history

- Initial layout wrapped and clipped connection buttons. Fixed by explicit action-column layout and fixed 96×42 connection buttons.
- Initial preview omitted subscriptions. Added isolated subscription data, retaining original top double-card positions and dimensions.
- At 1080px the original page minimum width caused horizontal scrolling and offscreen controls. Added viewport width tracking; the model ticker hides in narrow layouts. Reduced wallet interior gaps to keep its actions visible without changing card placement.
- Final default and narrow captures show all four action buttons and status labels. Connection-state bounds assertion passes. Final Java build reports 380 checks passed.

## Fidelity surfaces

- Typography: existing native application font retained. App name 18px, state 11px, support count 10px. Smaller density than the concept is intentional to retain the existing navigation and account area.
- Spacing: brand information, divider, flexible state column, configuration, fixed connection button are aligned across four cards. Normal/loading dimensions remain identical.
- Colors: original cosmic palette retained; cyan Codex and orange Claude edge highlights added per user correction; mint TokenPro state and gradient connection action retained.
- Assets: original bundled client images used, including the separately requested Codex CLI image. Existing settings asset and a monochrome link symbol are used for actions.
- Copy: 检测应用; 渠道配置; 连接 / 连接中…; 当前：TokenPro·已选 4 款模型. All visible without clipping in inspected states.

## Scope and remaining validation

This is a local design preview, not a release or a claim of Windows/Linux native QA. Real subscription switching, live reconnect, and cross-platform native fonts still need release validation. User visual acceptance remains pending. Smaller font scale than the standalone concept is an intentional adaptation, not a pixel-identical reproduction.

final result: passed

## 2026-09-13 wallet/subscription continuation

- Preserved the existing uncommitted application-card work and three v5 PNG files.
- Kept the header card sizing and placement rules. Wallet and membership icons, persistent gray-blue inactive subscription card and gold active card are present. Both cards have refresh controls; 充值 and 订阅 open the existing purchase URL and share its cooldown.
- Fixed subscription selection to update the name, remaining balance and expiry together; retained selection across refreshes when the same package remains available. Removed obsolete purchase buttons from the cooldown registry when rebuilding the card.
- Failed subscription reads retain the last displayed result and report failure instead of displaying an unsubscribed state. Rebuilt subscription refresh controls inherit the current refresh-disabled state.
- Validation: ./java-client/build.sh passed (388 self-test checks); git diff --check passed. Isolated Swing UI assertions passed for switching packages, fixed card bounds, shared purchase cooldown, inactive card visibility, 4/10/100/999 model counts, and fixed connection/loading bounds.
- Inspected actual Swing renders at 1280×820 and 1080×820. No overlap or clipped action buttons in the checked states. Preview data is synthetic; live purchase/authentication and subscription API operations were not exercised.
- Preview directory: /Users/Tiger/Documents/Codex/2026-09-13/tokenpro-ui-continuation/outputs (preview-home.png, preview-switched.png, preview-unsubscribed-narrow.png, plus loading/count and other size captures).

### Follow-up: refresh completion and long package names

- Manual refresh now waits for the subscription response before restoring both refresh controls or reporting full success. Partial failure preserves previous subscription data and states that only the wallet refreshed. Logout restores refresh controls even if a previous request is pending.
- The package dropdown arrow is now a separate icon, so it remains visible when a long name is ellipsized at 1080px. The full package description is available in the tooltip.
- Saved the desktop regression harness at java-client/src/test/java/work/tokenpro/client/AccountCardsUiTest.java. It uses synthetic data and a temporary store, without opening purchase pages or sending account requests. It is separate from headless self-tests.
- Rebuilt successfully: 388 self-tests passed. Desktop harness passed package switching, card bounds, long names, refresh retry controls, purchase-gate registry cleanup, model counts and loading bounds. Refreshed screenshots in the same output directory; inspected preview-long-subscription.png with the persistent arrow.

Repeat desktop UI checks after building (JDK 21 on PATH, run from java-client; macOS/Linux classpath syntax):

```sh
javac -cp build/classes -d build/ui-tests src/test/java/work/tokenpro/client/AccountCardsUiTest.java
java -cp build/ui-tests:build/classes work.tokenpro.client.AccountCardsUiTest build/ui-previews
```

The UI harness tests local control state; live API latency/failure and native Windows/Linux rendering remain untested.

### Wallet action alignment and lighter refresh controls

- Moved wallet actions into a fixed east column with the same right inset as subscription actions. 充值 stays right-aligned at both 1280px and 1080px without changing outer card bounds.
- Replaced the boxed refresh controls with small borderless line icons tinted mint/gold/gray-blue for the respective card. Hover, pressed and keyboard focus show a subtle background; disabled state fades the icon. Accessible names and refresh behavior remain available.
- Build passed with 388 self-tests; existing desktop UI regression passed. Inspected refreshed default and narrow inactive screenshots for spacing, balance readability and action alignment.

### Header version and account polish

- Version and account controls now use subtle translucent fills and thin borders, equal 34px heights, regular 12px type and consistent icon spacing. The latest-version state uses a mint check-circle icon instead of the prominent green action treatment.
- Header control widths are fixed; long account labels are ellipsized with the full account label in the tooltip. Hover and keyboard focus brighten the interactive account/update control. Existing account navigation, update actions and progress rendering are retained.
- Build: 388 checks passed. Existing desktop UI harness passed; inspected default and 1080px inactive-state screenshots. The preview harness now applies the real latest-version state helper, including the disabled state and correct icon.

### Login homepage update control

- Login homepage now reuses the dashboard header control and version icon factory. Removed the separate legacy update-button painter to keep both pages consistent.
- Latest, checking, actionable retry and numeric progress states still use the existing update callbacks. Shortened failure text to 更新失败 · 重试 so the retry action remains visible at the shared fixed width.
- Build passed (388 checks); isolated desktop preview/regression passed. Captured login latest, 45% progress and narrow retry states in preview-login.png, preview-login-updating.png and preview-login-retry.png.

### My account page

- Replaced the loose identity/balance layout with three consistent translucent sections: account identity with the existing planet avatar, a mint wallet balance card, and a separate login-management row with a subdued logout action.
- Added 返回首页 navigation. The account refresh icon shares the existing refresh request and is disabled/restored along with the header refresh controls; account identity and balance still use live-bound labels.
- Build passed (388 checks); existing UI regression and local return-home navigation assertion passed. Inspected 1280×820 and 1080×820 account captures with synthetic email/balance (preview-account.png and preview-account-narrow.png). No live logout or account API action was performed.

### Account layout correction: one row

- Replaced the three stacked sections with one 120px horizontal card: avatar/email on the left, wallet balance and refresh after a subtle divider, logout at the right.
- Removed redundant section captions; retained the page heading, return-home action and existing account handlers.
- Build passed (388 checks), local UI/navigation regression passed, and inspected 1080px preview confirms all controls remain on one row. Updated preview-account.png and preview-account-narrow.png.

### Account content on a single line

- Following clarification, removed the second account caption line and placed 钱包余额 beside the amount. Avatar, email, wallet label, amount, refresh and logout are vertically centered in a single 78px row.
- Build (388 checks), UI/navigation regression and 1080px visual inspection passed; account previews updated.

- Removed the account-page 返回首页 button as requested. Sidebar 首页 remains the navigation entry. Rebuilt successfully (388 checks) and regenerated account previews; sidebar return navigation regression passed.

### Sidebar polish

- Unified all four sidebar entries on the same navigation control, with consistent padding, regular-weight labels and hover/keyboard-focus feedback.
- Replaced the solid purple selection block with a translucent surface, thin outline and short mint-to-blue edge marker. Darkened the sidebar gradient and softened its separator.
- Added subtle external-link arrows for backend management and documentation while retaining the existing guarded browser actions.
- Build passed (388 checks); existing UI/navigation regression passed. Inspected the updated 1080px account preview with selected sidebar state; screenshots regenerated.

### Homepage membership avatar

- Reused the existing planet avatar in the homepage account entry. Active subscription data adds a small gold crown; an empty subscription list removes it. The icon has identical 28×28 bounds in both states, preserving text and button alignment.
- Subscription refresh and logout use the existing showSubscriptions path, keeping the crown synchronized with the displayed subscription state. Failed refreshes retain the last known subscription/avatar state.
- Build passed (388 checks); existing desktop regression passed. Rendered and inspected focused 2× Swing previews preview-member-avatar.png and preview-regular-avatar.png; full homepage previews also updated with synthetic subscription data.

### Membership avatar refinement

- Subscriber avatar now has a fine champagne-gold gradient ring. The small crown sits slightly off-center at a 14-degree tilt, with muted metallic shading and a fine highlight instead of a bright jewel dot.
- Subscriber account text and subtle button tint use warm champagne tones. Non-subscriber avatar and account colors retain their ordinary state; dimensions remain identical.
- Build (388 checks), desktop regression and focused subscriber preview inspection passed. Updated member/non-member and full homepage preview artifacts.

## v1.2.80 release validation

- Synchronized the existing v1.2.79 release commit without dropping local changes; advanced all five runtime/build/package version declarations to 1.2.80.
- Release build passed 388 self-tests. Desktop AccountCardsUiTest passed with version 1.2.80. Incremental archive contains only supported class entries; runtime assets are unchanged from v1.2.79.
- Deployment uses the existing tagged GitHub Actions workflow for macOS arm64/x64, Windows x64, Linux x64, class-only incremental package and tokenpro.work download metadata.

### Follow-up: dynamic username and independent purchase cooldowns

- Homepage account entry now measures its text and avatar for its preferred width; long usernames/emails no longer inherit a fixed 120px width. The site title uses the remaining header space. Checked numeric names and two email lengths at 1280px and 1080px.
- Recharge and subscription have separate three-second cooldown gates, both starting after a successful browser open. Both still open the existing purchase URL. Admin/docs retain their independent fifteen-second gates. Failures remain immediately retryable.
- Added deterministic clock regression for the 2999/3000ms boundary and staggered independent expiry; updated desktop assertions to require separate purchase gates.

## v1.2.81 release validation

- Version declarations advanced to 1.2.81. Build passed 392 self-tests; desktop layout and independent cooldown regressions passed.
- Confirmed v1.2.65 lacks CodexCommandLine.png, causing upgraded installations to fall back to the desktop logo. ApplicationArtwork embeds the existing artwork into class data, compatible with the current class-only updater. ApplicationIcon draws clean desktop/Claude backgrounds at display scale, removing the old baked-in grain; Codex CLI retains its existing purple terminal artwork.
- Loaded icons directly from TokenPro-update.jar in an isolated classloader with no assets directory and successfully rendered all four at 1×/2×/3×. Inspected 2× output. Original runtime asset files remain unchanged, so old-client delta compatibility is retained.

## v1.2.82 final scope and validation

- v1.2.81 was not published: the Intel artifact upload failed with GitHub DNS ENOTFOUND, and the release job was skipped.
- User correction: restore both desktop client icons exactly to their existing resource path. Only command-line icons change: Codex CLI uses the existing embedded purple terminal artwork; Claude CLI uses the supplied orange pixel-character silhouette, rendered directly without screenshot text/background.
- Keep the approved dynamic account width and independent 3-second recharge/subscription cooldown changes.
- Build passed 392 checks; desktop UI regression passed; class-only update archive rendered both CLI icons without any asset resources at 1×/2×/3×. Inspected 3× icon preview. Original Resources remain unchanged.

## v1.2.83 approved icon treatment

- Both CLI icons now use matching rounded-square bounds and corner radius. Codex keeps its purple terminal illustration within the tile; Claude uses a warm gray-purple tile and the supplied orange pixel character at the final reduced scale (60% of icon width). No gold outline remains.
- Desktop client icons remain unchanged. Includes previously approved dynamic username width and independent three-second purchase controls.
- Release build: 392 checks passed. Desktop UI regression and class-only CLI icon rendering at 1×/2×/3× passed. Source assets remain unchanged for incremental compatibility.

### Balance typography and currency display

- Wallet and subscription amounts now use ￥. Subscription amount reuses the wallet amount font (26px bold) and remains gold; the package picker and expiry occupy the smaller heading line. Dropdown/tooltip subscription amounts use the same currency symbol.
- Preserved card bounds and centered subscription actions vertically. Package selection still updates the correct amount and expiry.
- Build passed 392 checks; existing UI regression passed with updated currency assertions. Inspected default and narrow previews in outputs/balance-design. Amount values are unchanged; this is a display change, not a currency conversion.

- Layout correction: retained the original package-name-over-expiry arrangement. Extracted ￥ amount into its own adjacent column, vertically centered at the same 26px bold size as wallet balance. Default and narrow renders inspected; 392 checks and UI regression passed.

### Final balance alignment and dollar display

- Latest user direction supersedes the ￥ proposal: wallet/account and subscription balances all display $. Numeric values remain unchanged.
- Both header cards now share identical outer insets, four-pixel layout gaps, two-row caption grids (three-pixel gap), 12px bold first lines and 10px regular second lines. Amounts use the same 26px bold font and sit right-aligned before the refresh and purchase actions.
- Build passed 392 checks; UI regression passed with dollar-display assertions; default screenshot inspected. Updated balance-design previews.

### Hold-to-view amount explanation

- Replaced the always-visible recharge ratio with 金额说明 and a small eye icon. Holding the control reveals “$ 为平台额度标记，$1 额度对应人民币 1 元”; releasing, disarming, hiding or losing focus dismisses it without changing card layout. Keyboard press behavior uses the same button model.
- Naming alternatives offered: 金额说明 (selected default), 额度说明, 计价说明.
- Removed the persistent focus outline from 金额说明 so releasing the hold returns it to the quiet text-only state. Official states on both desktop and command-line cards now identify the provider consistently as 当前：OpenAI 官方配置 or 当前：Anthropic 官方配置.
- Build passed 392 checks and desktop UI regression. A standalone real-window check confirmed hidden-by-default, held reveal, release hide and pointer-disarm hide. Updated balance-design previews; not yet released.

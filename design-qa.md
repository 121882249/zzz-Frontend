# v0.4.0 Home design QA

Source: /var/folders/1s/tl_s3grd4p50bs85w46x1h9h0000gn/T/codex-clipboard-9a8d9434-6c28-4e26-9587-a1a448f9272c.png (2090 × 686 pixels, cropped reference).
Implementation: native Home Preview NSHostingView, 1120 × 760 logical content size, screenshot displayed inline in CUA calls “对比参考图与主页布局” and “复核蓝色主按钮和两张客户端卡片”. No standalone screenshot file was saved.
State: home, signed out, no connections. The source is signed in and cropped; comparison is of corresponding layout regions and controls, not pixel equality or account data. Source and implementation were emitted together in each comparison call.

## Findings and iteration history
- Initial P2: primary actions retained black despite the established blue brand. Fixed shared primary button color to the exact icon RGB (0.40, 0.32, 0.94). Final combined comparison confirms blue primary actions.
- Initial P3: duplicate stopped-proxy text. Replaced secondary line with purpose of proxy; verified final capture.
- No remaining actionable P0/P1/P2 issues in the requested homepage layout.

## Required surfaces
- Typography: native system type consistent with existing app, hierarchy of brand, client names, model badge and statuses; no wrapping/clipping seen.
- Layout: left navigation, account header, stacked Claude/Codex cards, trailing actions and lower controls reproduce reference structure. All primary controls visible at 1120 × 760. Full-view text is readable so separate focused crops not needed.
- Colors: intentional continuation of user's blue theme and existing light mode rather than the reference's orange/dark palette. No fake promotions or installation counts.
- Assets: existing TokenPro SF Symbol identity and existing library-based terminal/sun icons; native symbols are crisp. No third-party brand asset copied or synthetic promotional imagery used.
- Copy: actual state labels. Claude explicitly says relay not configured; no claim it is integrated. No task creation control.

## Interaction evidence
CUA verified Claude explanation opens and dismisses, Codex empty connection selector opens and dismisses. Balance refresh and install are disabled in signed-out/no-route state. No production configuration or credentials modified. Native release compiles and passes 20 existing checks. App-launch callbacks are implemented, but launching user clients and authenticated balance refresh were not exercised in the isolated preview.

## Accepted scope / follow-up
This release implements the homepage. Claude desktop relay remains unimplemented and is explicitly disclosed in UI/README. Login view and proxy protocol remain unchanged. Smaller-window and populated-account visual states not recaptured in this pass.

final result: passed

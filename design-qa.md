# TokenPro 模型名称徽章 Design QA

- Source visual truth: `/var/folders/1s/tl_s3grd4p50bs85w46x1h9h0000gn/T/codex-clipboard-222a12cb-b0f7-486c-b020-e25dc193ac49.png`
- Implementation screenshot: `/tmp/tokenpro-model-names-preview.png`
- Focused comparison: `/tmp/tokenpro-model-names-comparison.png`
- Viewport/state: TokenPro macOS Swing 客户端，2560 × 1640 px，首页，已登录状态
- Source pixels: 2117 × 743 px; source focus crop 1000 × 260 px normalized to 900 × 234 px
- Implementation pixels: 2560 × 1640 px; implementation focus crop 900 × 180 px
- Density normalization: component-focused comparison; the generated concept is an enlarged presentation mock, so the implementation is judged at the native desktop header scale rather than by absolute mock pixel size

## Findings

No actionable P0/P1/P2 findings remain.

- Fonts and typography: GPT, Claude, Gemini, and Grok are all readable at native UI scale, use one consistent bold UI face, and remain visually subordinate to the page title.
- Spacing and layout rhythm: all four cards share one baseline; names precede their corresponding logos; rounded overlap is consistent; the complete cluster and “更多模型” remain within the header without clipping.
- Colors and visual tokens: cyan, orange, blue, and purple treatments preserve the selected concept and existing TokenPro palette; transparency retains the starfield context.
- Image quality and asset fidelity: existing production brand assets are reused; logos remain sharp at 18 px and are not replaced by drawn approximations.
- Copy and content: model names are exact and paired correctly; the existing “更多模型” affordance is retained so the interface does not imply support is limited to four vendors.

## Full-view comparison evidence

The implementation preserves the source hierarchy—four named brand cards in a right-aligned horizontal overlapping stack—while adapting the oversized concept presentation to the actual 34 px TokenPro header slot. No card collides with the application rows or right window boundary.

## Focused comparison evidence

The combined comparison confirms the same name-before-logo ordering, rounded contour overlap, brand-specific borders/fills, and horizontal rhythm. The compact implementation intentionally uses smaller radii, type, and padding to match the production header density.

## Comparison history

- Initial implementation: no P0/P1/P2 issue found in the first source/implementation combined comparison.
- No visual fixes were required after the comparison.

## Follow-up polish

- P3: If a future larger header is introduced, card padding could be increased by 1–2 px to move even closer to the presentation mock.

## Premium model ticker follow-up

- Added a compact “精选” ticker immediately before the provider cards.
- It selects the two highest-priced available LLMs from each of GPT, Claude, Gemini, and Grok, removes duplicates, and excludes image-generation models.
- The ticker loops smoothly from right to left with clipped edges and does not move or resize the four provider cards.
- Verified in the packaged macOS application at the 1280 × 820 production window size.

## Implementation checklist

- [x] Exact model names before logos
- [x] Existing brand assets reused
- [x] Rounded overlap without straight clipping seams
- [x] Starfield-visible translucent surfaces
- [x] “更多模型” retained
- [x] Premium models scroll from right to left
- [x] Java build and 86 self-tests pass

final result: passed

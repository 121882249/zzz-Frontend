# TokenPro v1.1.5 Design QA

## Evidence

- Alignment issue reference: `/var/folders/1s/tl_s3grd4p50bs85w46x1h9h0000gn/T/codex-clipboard-e5540a75-9eb0-4c08-88f2-bccc56270393.png`
- Final implementation screenshot: `design-preview/tokenpro-login-v1.1.5.jpg`
- Focused comparison: `design-preview/tokenpro-login-v1.1.5-comparison.jpg`
- State: signed-out desktop login screen, cosmos theme.
- Application viewport: 1280 x 820 logical pixels; CUA capture 1198 x 768 pixels.
- Focused comparison: issue reference and final card crop normalized to matching 720 x 760 panels.

## Findings

- No P0/P1/P2 findings remain.
- Fonts and typography: the hero title is a single line and retains clear hierarchy.
- Spacing and layout rhythm: every card child now uses the same left alignment basis. Card padding is 24 px horizontally and 20 px vertically, with equal visible left and right margins.
- Inputs: email and password fields are 40 px high with true 12 px rounded corners and consistent 14 px text inset.
- Colors and visual tokens: the navy glass card and violet-blue action remain consistent with the cosmos background.
- Image quality: supplied icon artwork and model imagery remain unchanged.
- Copy and content: login labels and security text remain unchanged.

## Comparison History

- Earlier P2: mixed BoxLayout alignment values pushed the form toward the right edge. Fixed by assigning one left alignment basis to the status row, title, subtitle, labels, input wrappers, action, divider, and footer.
- Earlier P2: fields were too tall and square. Fixed with 40 px rounded input containers.
- Earlier P2: excess card whitespace. Fixed by reducing the card to 400 x 404 and tightening its padding and gaps.
- Post-fix evidence: the focused comparison shows equal left and right boundaries, rounded inputs, and a smaller card.

## Implementation Checklist

- [x] Single-line hero title.
- [x] Equal card side padding.
- [x] Short rounded input fields.
- [x] Reduced card whitespace.
- [x] 15 Java self-tests pass.
- [x] Packaged app installed and visually inspected.

final result: passed

---
name: tokenpro-imagegen
description: Generate or edit raster images through TokenPro when the TokenPro channel is connected. Use this for requests to draw, create, generate, render, illustrate, redesign, redraw, transform, or edit an image. The skill uses the TokenPro-provided local CLI and preserves the selected global-key route.
---

# TokenPro Image Generation

When TokenPro is connected, use the bundled local command below for image
generation. This is intentionally separate from Codex's built-in `image_gen`
tool: Codex stays on the built-in `openai` provider and the command calls the
TokenPro `/v1/images/generations` endpoint with the existing global Key and
the selected image-group model route.

## Generate

```bash
"{{TOKENPRO_IMAGEGEN_SCRIPT}}" generate \
  --prompt "<complete image prompt>" \
  --out "/absolute/path/to/output.png"
```

Optional arguments are `--model`, `--size`, `--quality`, and `--background`.
If `--model` is omitted, the command uses the first selected image model from
the TokenPro Codex catalog and otherwise falls back to `gpt-image-2`.

Always use an absolute output path, preserve the user's requested subject and
style, and return the generated file exactly once using an absolute Markdown
image path. Do not call `view_image` for the same file and do not send a second
Markdown image link; this avoids showing the same image twice. Do not print,
copy, or request the global Key. If the command is unavailable, ask the user to
reconnect TokenPro so the application can reinstall this Skill.

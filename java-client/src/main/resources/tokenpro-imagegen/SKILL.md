---
name: tokenpro-imagegen
description: Generate or edit raster images through TokenPro when the TokenPro channel is connected. Use this for requests to draw, create, generate, render, illustrate, redesign, redraw, transform, or edit an image. The skill uses the TokenPro-provided local CLI and preserves the selected global-key route.
---

# TokenPro Image Generation

When TokenPro is connected, this Skill is the only image-generation path. Invoke
the bundled local command below directly for every image request. Do not call
Codex's built-in `image_gen`/`image_generation` tool, do not call a hosted image
generator, and do not fall back to another image interface. The command calls
TokenPro's `/v1/images/generations` endpoint with the existing global Key and
the currently selected TokenPro route as the image driver. If the active Codex
model is a dedicated GPT image-group route, that route is preserved and used
directly.

## Generate

```bash
"{{TOKENPRO_IMAGEGEN_SCRIPT}}" generate \
  --prompt "<complete image prompt>" \
  --out "/absolute/path/to/output.png"
```

Optional arguments are `--model`, `--size`, `--quality`, and `--background`.
If `--model` is omitted, the command uses the currently active TokenPro route;
selecting a dedicated GPT image model in Codex therefore uses that image group.

Always use an absolute output path, preserve the user's requested subject and
style, and return the generated file exactly once using an absolute Markdown
image path. Do not call `view_image` for the same file and do not send a second
Markdown image link; this avoids showing the same image twice. Do not print,
copy, or request the global Key. If the command is unavailable, ask the user to
reconnect TokenPro so the application can reinstall this Skill.

package com.localchatbot.core.storage

/**
 * Almacén del archivo de documentación de tools (`tools.md`), que vive junto a los
 * skills (`~/.localchatbot/tools.md` en desktop). Es la guía que el modelo consulta
 * on-demand vía la tool `read_tool_docs` cuando duda cómo usar una herramienta o una
 * tool le falla repetidamente.
 *
 * Contenido "default nuestro": si el archivo no existe, [read] lo siembra con
 * [DEFAULT_TOOLS_MD]. El usuario puede editarlo (es un .md plano), pero el flujo
 * esperado es que lo mantengamos nosotros.
 *
 * Solo desktop tiene impl real; en móvil [isAvailable] es false y [read] devuelve null.
 */
expect class ToolDocsStore {
    val isAvailable: Boolean

    /**
     * Devuelve el contenido de `tools.md`. Si el archivo no existe lo crea con el
     * contenido por defecto y devuelve ese. Null si la plataforma no lo soporta o
     * hay un error de I/O.
     */
    fun read(): String?
}

expect fun createToolDocsStore(): ToolDocsStore

/**
 * Documentación por defecto. Se centra en lo que los modelos suelen IGNORAR de cada
 * tool (no en repetir la `description`): atajos, modos alternativos y cómo recuperarse
 * de errores comunes. Mantener en inglés — coincide con el idioma de las definiciones
 * de tools que ve el modelo.
 */
/**
 * Pasos de recuperación de `edit_file`, inyectados DIRECTAMENTE en el resultado de
 * error de la tool (campo `recovery`) cuando una edición falla. Así el modelo siempre
 * ve cómo recuperarse en el siguiente turno, sin depender de que decida llamar
 * `read_tool_docs`. Versión condensada de la sección edit_file de [DEFAULT_TOOLS_MD].
 */
const val EDIT_FILE_RECOVERY: String =
    "edit_file failed. Before retrying: (1) re-read the file with read_file and copy the " +
        "exact text WITHOUT the 'N: ' line-number prefix; (2) small indentation/space " +
        "differences are tolerated automatically, so don't obsess over a perfect copy; " +
        "(3) if the exact match still fails, switch to MODE B — pass start_line + end_line " +
        "(+ new_string) using the line numbers from read_file or from the 'Región más " +
        "parecida' hint above; (4) 'appears N times' → widen old_string to make it unique " +
        "or pass replace_all=true. Do NOT rewrite the whole file with create_file. Call " +
        "read_tool_docs for the full guide if still stuck."

const val DEFAULT_TOOLS_MD: String = """# Tool Guide

Read this when you're unsure how to use a tool, or when a tool keeps failing. It
documents the non-obvious behaviour and the recovery paths — the things easy to miss
from the short tool descriptions.

## edit_file

Editing is more forgiving than it looks. Before giving up on a failed edit:

- **Two modes, pick one.** Mode A = `old_string` + `new_string` (exact text). Mode B =
  `start_line` + `end_line` + `new_string` (replace a line range). Never pass both.
- **Mode A has a whitespace-tolerant fallback.** If the exact text isn't found, a match
  ignoring leading/trailing spaces and indentation is attempted automatically and the
  result reports `mode: "string_replace_flexible"`. So small indentation differences
  do NOT need a perfect copy.
- **Strip the `N: ` prefix.** `read_file` prefixes every line with its number
  (`42: code`). That prefix is NOT part of the file — remove it before using the text
  as `old_string`.
- **On failure you get a hint.** A failed Mode A edit returns the closest matching
  region with real line numbers ("Región más parecida (líneas X-Y)"). Use those line
  numbers with Mode B instead of fighting the exact match.
- **"appears N times" →** widen `old_string` with surrounding lines to make it unique,
  OR pass `replace_all=true`, OR switch to Mode B with the exact line range.
- **Big files:** prefer Mode B. Exact string matching gets fragile; line numbers from
  `read_file` are precise.

Rule of thumb: exact match fails twice → switch to Mode B (`start_line`/`end_line`).
Don't rewrite the whole file with `create_file`.

## read_file

- Paginated by lines: `offset` (1-based) + `limit` (default 2000). A block outside the
  window is NOT unreachable — read another window with a higher `offset`.
- The payload reports `totalLines` and `truncated`; if `truncated` is true there are
  more lines after your window.

## search_files

- Recursive content search under the workspace (native grep). Prefer it over
  `run_command` with grep/find — it's faster and works in Plan mode.
- `pattern` is a regex by default; if it doesn't compile the search falls back to a
  literal match automatically (check `mode` in the response). Pass `literal=true` to
  force a literal match (useful for code with `(`, `[`, `.`).
- Case-insensitive by default; `case_sensitive=true` to match exactly.
- Narrow with `file_glob` (e.g. `*.kt`) and `path` (subdirectory). `max_results`
  defaults to 100.
- Hits come back as `path:line: text` relative to the workspace — follow up with
  `read_file` using that line as `offset` to see the surrounding code.
- Skips binary files, files > 1MB and .git/build/node_modules-style directories.

## save_image

- Saves the LAST generated image (from `generate_image` / `render_diagram`) to disk.
- Call it right after generating, when the user wants to keep the image — don't ask
  them to download it manually.
- `path` is relative to the workspace; a `.png` extension is added if missing.
- There must be a freshly generated image available; if not, generate one first.

## run_command

- Foreground (default): blocks until exit or `timeoutSeconds`, returns full stdout /
  stderr / exitCode.
- Long-running (servers, watchers, `npm run dev`, etc.): pass `background=true`. You get
  back a PID and the initial output; the process keeps running. Stop it later with
  `run_command` running `kill <pid>`.
- Destructive commands (rm -rf, etc.) always pop a confirmation dialog, even in YOLO.

## generate_image vs render_diagram

- Diagram / flowchart / sequence / class / ER / mind map → `render_diagram` with Mermaid
  syntax. Do NOT use `generate_image` for these.
- Artistic / photorealistic image → `generate_image` with a detailed English SDXL prompt.

## ask_user

- The ONLY way to actually pause your turn and wait for the user. A plain-text question
  does not pause anything — the loop keeps running and nobody answers.
- Set `recommended` on your best option so YOLO mode can auto-pick and keep going.

## manage_todos

- For multi-step work, add all steps in ONE call (`operation=add`, `texts=[...]`).
- Mark each `operation=complete` the moment it finishes — not batched at the end.
- Don't end your turn with tasks still pending: complete them, or `operation=clear`.
"""

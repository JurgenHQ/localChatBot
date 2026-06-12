# Plan: soporte MCP (Model Context Protocol)

## Factibilidad

Muy factible. La arquitectura existente ya tiene todo lo necesario:

| Aspecto | Veredicto |
|---|---|
| Tool abstraction | Fit perfecto. MCP `tools/call` → `Tool.execute`; `inputSchema` → `ToolDefinition.parameters` |
| Tools dinámicas | Precedente: los scriptTools de skills se mergean en send-time (`UseCases.kt`, junto a `toolRegistry.availableDefinitions()`) |
| Transporte stdio | Desktop only (spawn de proceso, como `run_command`). La mayoría de servers MCP son stdio |
| Transporte Streamable HTTP | Todas las plataformas vía Ktor (engines ya configurados por target) |
| SDK | Existe `io.modelcontextprotocol:kotlin-sdk` (oficial, KMP). Alternativa: JSON-RPC 2.0 a mano (`initialize` → `tools/list` → `tools/call`) |
| Confirmación humana | `ToolConfirmationController` ya existe → tools MCP con `requiresConfirmation = true` por defecto |
| Persistencia config | Patrón `SkillFileStore` (expect/actual) reusable |
| UI | Patrón `SkillsScreen` / `SkillsViewModel` reusable |

Riesgo bajo. Esfuerzo moderado. MVP desktop ≈ 3-4 días.

## Fase 1 — Domain + config

- `McpServerConfig`: `id`, `name`, `transport` (`Stdio(command, args, env)` / `Http(url, headers)`), `enabled`.
- Persistir vía `McpServerStore` (expect/actual por plataforma, copia del patrón `SkillFileStore`).
- `InstalledMcpServer` en `AppPreferences` para el estado enabled/disabled.

## Fase 2 — Cliente MCP (data layer)

- `McpClient`: handshake `initialize`, `tools/list`, `tools/call`. Protocolo JSON-RPC 2.0.
- Transporte expect/actual:
  - `StdioTransport` — `desktopMain`, `ProcessBuilder` (mismo enfoque que el shell agent).
  - `HttpTransport` — `commonMain`, Ktor Streamable HTTP.
- Registrar transacciones HTTP en `NetworkInspector` para debug.
- **Decisión SDK:** hand-rolled recomendado — el protocolo es chico, evita dependencia pesada y da control total sobre los engines Ktor existentes. Evaluar `kotlin-sdk` oficial si el hand-rolled crece.

## Fase 3 — Adapter a Tool

- `McpTool : Tool`:
  - `name = "mcp_<serverId>_<toolName>"` (sanitizado; evita colisiones, mismo patrón que `sk_*`).
  - `definition` construida desde el `inputSchema` del server.
  - `execute` → `tools/call`; output pasa por `truncateToolOutput` (cap 8k chars, ya existe).
  - `requiresConfirmation = true` → pasa por `ToolConfirmationController`. YOLO mode aplica; evaluar `force = true` para tools de escritura.
  - `activityLabel = "MCP: <tool>…"`.

## Fase 4 — Integración en el tool-calling loop

- `McpToolProvider` en `AppContainer`: conecta los servers enabled, cachea las tools descubiertas.
- `SendMessageUseCase`: merge de `mcpTools.map { it.definition }` junto a los scriptTools (la vía dinámica existente). `ToolRegistry` queda estático.
- Lifecycle:
  - Conexión lazy al primer send.
  - Reconexión con backoff.
  - Kill de procesos stdio al cerrar la app (shutdown hook en `main.kt` desktop).

## Fase 5 — UI

- `McpServersScreen` + `McpServersViewModel` (clonar patrón Skills):
  - Add/edit server (formulario stdio vs HTTP).
  - Toggle enabled, `StatusDot` de estado de conexión.
  - Lista de tools descubiertas por server.
  - Botón "test connection".
- Entrada desde `SettingsScreen` (como `onOpenSkills`).

## Fase 6 — Hardening

- Timeout por call; los errores vuelven como tool result legible (nunca rompen el loop).
- Cap de tools por server — muchos servers exponen 20+ tools e inflan el contexto. Si crece: índice corto + carga on-demand estilo `use_skill`.
- Android/iOS: solo HTTP; servers stdio reportan `isAvailable() = false` fuera de desktop.

## Orden MVP

Fases 1-4 con solo stdio en desktop = valor inmediato (abre servers de GitHub, Slack, DBs, etc.). HTTP + UI después.

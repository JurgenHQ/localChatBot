# Roadmap — mejoras candidatas

Ideas evaluadas contra el código real (julio 2026), no genéricas. Lo que ya existe y por
tanto **no** está en esta lista: ejecución de tools en paralelo (`UseCases.kt`, ya usa
`async`/`await`), diff-preview de `edit_file`/`multi_edit` (existe, dentro del diálogo de
confirmación), métricas de tokens por mensaje (`TokenMetrics`).

Estado: ninguna implementada salvo donde se indique.

---

## 1. Techo de escalabilidad

### 1.1 Partir `ChatRepository.sessions` — ✅ **HECHO**

`sessions: Flow<List<ChatSession>>` sustituido por `sessionSummaries` (metadatos, sin
mensajes) + `sessionWithMessages(sessionId)` + `messageImageDataUrl(messageId)`. Detalle en
`CLAUDE.md`, sección *Persistence*.

**Lo que salió de aquí — ✅ HECHO:**

- **Migraciones de esquema reales.** `migrateOrCreate` (`desktopMain/core/storage/db/`)
  versiona la base con `PRAGMA user_version` y llama a `Schema.migrate()`. Android e iOS
  nunca estuvieron rotos (sus drivers delegan en `onCreate`/`onUpgrade` del motor).
  - Las bases de desktop existentes están en `user_version = 0` aunque su contenido *ya* es
    el esquema v1, así que se **adoptan** estampando la versión: un pragma, cero filas
    tocadas. Migrar "desde 0" les reaplicaría migraciones que ya tienen.
  - `databases/1.db` es el snapshot commiteado de v1; `verifyMigrations` aplica los `.sqm`
    sobre él y rompe el build si el resultado no coincide con los `.sq`. O sea que ahora el
    mecanismo está verificado en cada build, no solo escrito.
  - Antes de cada migración se guarda `localchatbot.db.v<origen>.bak` con `VACUUM INTO` (no
    una copia de archivo: en WAL parte de lo confirmado vive en el `-wal`).
  - Validado end-to-end sobre una base v0 con datos: `user_version` 0→2, columna añadida,
    índice backfilleado, `integrity_check` ok, contenido idéntico, y un segundo arranque que
    no vuelve a migrar. Detalle en `CLAUDE.md`, sección *Persistence*.
- **Primera migración real: `1.sqm`** añade `message.model` (nullable, sin default → en
  SQLite es solo metadatos, no reescribe ninguna fila). Desbloquea el desglose por modelo de
  4.3, que hasta ahora aproximaba atribuyendo todo al modelo actual de la sesión.
- **`LegacySettingsChatRepository` ya no implementa `ChatRepository`**: se redujo a `load()`,
  que es lo único que usa la migración one-shot. Se fueron ~330 líneas de escritura que nadie
  llamaba y que había que ampliar cada vez que cambiaba el contrato.

### 1.2 Tests de las funciones puras críticas

No cobertura por cobertura: ~6 funciones donde un bug es silencioso y caro.
`buildMessagesForApi` (si el recorte de contexto se pasa, el modelo falla en producción sin
explicación), `looksLikeQuestionToUser`, el matching de `edit_file`, `DangerousCommands`, la
idempotencia de `ChatHistoryMigration`. Todas en commonMain, sin UI ni red. `UseCases.kt`
tiene ~1.400 líneas de lógica crítica sin red de seguridad.

---

## 2. Capacidades de agente que faltan

### 2.1 `fetch_url` / `read_web_page` — ✅ HECHO

Hoy el agente puede **buscar** (Tavily) pero no **leer** una página: le pasás un link a una
doc y no puede abrirlo. Ktor + extractor de texto de HTML, ~150 líneas. Mejor relación
valor/esfuerzo de la lista.

### 2.2 Tools de git de primera clase — ✅ HECHO

`git_diff`, `git_status`, `git_commit`, `git_log`. Hoy todo pasa por `run_command`: salida
sin estructura, sin confirmación específica, sin poder renderizar el diff en el chat.

### 2.3 Extender los checkpoints a `run_command` — ✅ HECHO

Resuelto con `git stash create` antes del primer comando opaco del turno (`run_command`,
`mcp_*`, `sk_*`), y `git checkout <ref> -- .` al revertir.

**Límites, ya reflejados en el diálogo de revert:** solo funciona si el workspace es un repo
git, y solo sobre archivos que git ya sigue — lo que un comando cree sin añadir al índice no
se borra. Sin git no hay forma barata de saber qué cambió, y copiar el árbol entero antes de
cada comando no es viable (node_modules, build/).

### 2.4 Sub-agentes (`spawn_agent`) — ✅ HECHO

Tool que abre una sesión hija con contexto propio (arranca **limpia**, no hereda el historial
del padre), corre la subtarea con el loop de tools completo y devuelve **solo el texto final**.
La sesión hija es visible en el drawer bajo "Sub-agentes" — ejecuta tools reales sobre el
workspace, esconderla impediría auditarla. Hereda auto-aprobación como las tareas programadas,
y el anidamiento está topeado en 1 con un marcador de `CoroutineContext` (`SubAgentRun`) leído
desde `isAvailable()`. Detalle en `CLAUDE.md`, sección *Sub-agents*.

### 2.5 Búsqueda semántica del workspace — ✅ HECHO

`search_files` es grep: encuentra lo que ya sabés nombrar. Índice de embeddings
(`/v1/embeddings` del mismo endpoint) + tool `search_code_semantic`, que responde "¿dónde se
maneja el rate limiting?" sin conocer el nombre.

Dos decisiones que conviene no revisitar sin motivo: el índice va a un **archivo** en
`~/.localchatbot/semantic-index/` y no a SQLite (no hay migraciones de esquema que funcionen —
ver 1.1), con los vectores **cuantizados a int8** para que el archivo pese ~2 MB en vez de
~15 MB; y el indexado es **solo bajo demanda** e incremental, porque el modelo de embeddings
compite por memoria con el de chat en LM Studio. Sin modelo de embeddings la tool degrada con
un mensaje que apunta a `search_files`. Detalle en `CLAUDE.md`, sección *Semantic search*.

### 2.6 Hooks — ✅ HECHO (post-tool)

Hooks **post-tool** en `~/.localchatbot/hooks.json` (mismo patrón de archivo que `tools.md`
y `memory.md`: se edita a mano, sin pantalla de ajustes ni clave en settings). La salida del
hook se añade al resultado de la tool, así que el modelo la ve en la misma ronda: si el
formateador reescribió el archivo o el compilador falló, se entera antes de seguir encima.

**Falta el evento `after_turn`** (correr los tests al cerrar el turno). Lo interesante ahí es
engancharlo al mecanismo de *nudge* que ya existe, para que un fallo re-prompte al modelo con
la salida en vez de solo mostrarla. Es un cambio en el bucle, no en el store.

---

## 3. UX diaria

### 3.1 Búsqueda global en el historial ⭐ — ✅ HECHO

Vive en el buscador que **ya existía** en el drawer: la misma caja que filtraba por título
ahora busca también en el contenido y muestra las coincidencias bajo "En los mensajes", con
el fragmento resaltado. No se añadió una tercera búsqueda: ya había el filtro por título y la
búsqueda dentro del chat.

`message_fts` es una tabla FTS5 de **contenido externo** (no duplica el historial, lo lee de
`message`), sincronizada por tres triggers. La migración `2.sqm` la crea, la backfillea y —
antes— borra los mensajes huérfanos: en una base real eran el 94% de la tabla, inalcanzables
desde cualquier consulta, y habrían llenado los resultados de coincidencias en conversaciones
que ya no existen. Con el `VACUUM` posterior, 12 MB → 1,2 MB.

Dos detalles que conviene no revisitar sin motivo: la query del usuario **nunca** va cruda a
`MATCH` (`toFtsMatchQuery` entrecomilla cada término, porque `wi-fi` no da cero resultados
sino un error de sintaxis), y el resaltado sale de los delimitadores de `snippet()` y no de
buscar la query en el texto — el tokenizador ignora acentos, así que "sesion" coincide con
"sesión" y un `contains` literal no encontraría qué marcar. Detalle en `CLAUDE.md`, sección
*Global history search*.

**Obligó a subir `minSdk` de 24 a 26**: FTS5 no está garantizado en el SQLite del sistema de
Android antes de eso, y un esquema que no se puede crear es una app que no arranca.

**Deuda asumida**: el respaldo previo a migrar es solo de desktop (en móvil los drivers migran
por sus propios callbacks), así que en Android/iOS `2.sqm` borra los huérfanos sin red. Se
aceptó porque el uso real es desktop; si eso cambia, hay que copiar el archivo antes de crear
el driver cuando `user_version` esté por detrás del esquema.

### 3.2 Exportar conversación — ✅ HECHO

`ChatExport` (función pura) renderea la sesión — o un turno suelto — a Markdown. Copiar al
portapapeles en las tres plataformas; guardar `.md` con diálogo nativo solo en desktop. Los
mensajes `Tool` no se vuelcan (son JSON de cientos de líneas): cada tool invocada aparece como
una línea `🔧 nombre`. Entradas: menú `⋮` de la barra del chat, la paleta de comandos, y el
icono de copiar de una burbuja del usuario (= copiar ese turno).

**Lo que NO se hizo:** exportar desde el menú contextual de una sesión del drawer. Habría que
tocar el menú de `SessionRow` (duplicado en dos ramas) más el cableado de `DraggableSession`,
y hoy solo se exporta la conversación **activa**.

### 3.3 Command palette + atajos de teclado — ✅ HECHO

`CommandPalette` con Ctrl/Cmd+K: filtra acciones y conversaciones, ↑/↓ para moverse, Enter
para ejecutar, Esc para cerrar. Atajos globales en un único `onPreviewKeyEvent` de la raíz de
`MainScaffold`: **Ctrl+K**, **Ctrl+N** (nueva), **Ctrl+,** (ajustes) y **Esc** (cierra el
overlay de más arriba; solo si no hay ninguno corta el stream). `Ctrl+Enter` ya enviaba antes
de este cambio: `AppTextField` solo trata distinto a Shift+Enter.

### 3.4 Salida de `run_command` en streaming

Hoy esperás al timeout y recibís todo junto. Verla en vivo cambia la sensación de control
cuando el agente corre un build.

### 3.5 System prompt por proyecto

`Project` ya lleva `workspaceDir`, y el modo agente ya es por sesión; falta que el proyecto
lleve sus propias instrucciones. Un campo en `Project` y una línea en el system prompt.

---

## 4. Modelo y costos

### 4.1 Modelo por rol

Hoy el mismo modelo genera títulos, resúmenes de contexto y respuestas. Uno chico para las
tareas auxiliares libera al grande. `ConnectionProfile` ya soporta 3 perfiles: falta poder
marcar uno como "auxiliar".

### 4.2 Compactación manual del contexto — ✅ HECHO

`/compact` (el popup de `/` lista todos los comandos disponibles junto a las skills; también
está en el menú `⋮` y en la paleta) genera el resumen, **lo muestra y lo
deja editar** antes de aplicar nada; cancelar no toca nada. Aplicar **no borra mensajes**:
siguen visibles, solo dejan de enviarse al modelo, que a partir de ahí ve el resumen. Hay
"deshacer compactación".

El corte (`sessionId → último mensaje compactado`) vive en **preferencias**, no en una columna
de `session`: no hay migraciones de esquema que funcionen (ver 1.1). Si el id desaparece —
reenviaste un mensaje anterior y truncaste la sesión — el corte se ignora y vuelve el historial
completo: degradar a "sin compactar" es seguro, mandar de menos no. Detalle en `CLAUDE.md`,
sección *Manual context compaction*.

### 4.3 Panel de métricas por sesión — ✅ HECHO

`SessionMetricsScreen`, overlay sin ViewModel (patrón del inspector de red): agrega la sesión
activa que `ChatViewModel` ya colecta, con una función pura, sin consultas nuevas. Muestra
tokens, tok/s, coste estimado y las tools más usadas — el conteo de tools sale de la columna
`tool_name` que ya existía.

El desglose **por modelo** era una aproximación (todo atribuido al modelo actual de la
sesión, que se pisa al cambiar de modelo); con `message.model` de 1.1 ahora es real, y los
mensajes anteriores a esa migración caen al modelo de la sesión. El coste sale de una tabla
estática de precios (`ModelPricing`) por substring del id: los modelos locales no matchean y
se quedan sin coste en vez de con uno inventado; si solo algunos modelos de la sesión tienen
precio, el total se marca como parcial. Detalle en `CLAUDE.md`, sección *Session metrics panel*.

---

## 5. Distribución

- **5.1 Auto-update** en desktop (hoy hay que bajar el MSI a mano del pre-release `latest`).
- **5.2 CI para macOS y Linux** — hoy solo se compila el MSI en Windows.
- **5.3 Firma y notarización** — sin eso, cada instalación pasa por "app no reconocida".

---

## Orden sugerido

1. ~~**1.1** — partir el repositorio + migraciones de esquema + reducir el repo legacy~~ ✅
2. ~~**2.1 `fetch_url`**~~ ✅  ·  ~~**2.2 tools de git**~~ ✅  ·  ~~**2.3**~~ ✅  ·  ~~**2.4
   sub-agentes**~~ ✅  ·  ~~**2.5 búsqueda semántica**~~ ✅  ·  ~~**2.6 hooks post-tool**~~ ✅
   (falta el evento `after_turn`)
3. ~~**3.1 Búsqueda global (FTS5)**~~ ✅ — el historial ya es consultable.
4. ~~**3.3 Atajos de teclado + command palette**~~ ✅  ·  ~~**3.2 Exportar**~~ ✅  ·
   ~~**4.2 Compactación manual**~~ ✅  ·  ~~**4.3 Métricas por sesión**~~ ✅
5. **1.2 Tests** — no es glamoroso, pero todo lo anterior toca `UseCases.kt`.

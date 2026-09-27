package com.localchatbot.presentation.features.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.platform.resizeCursor
import com.localchatbot.core.terminal.SessionTerminal
import com.localchatbot.core.terminal.TerminalDock
import com.localchatbot.core.terminal.TerminalLineKind
import com.localchatbot.core.theme.Spacing

/**
 * Panel de terminal acoplado abajo o a la derecha de la ventana, como en VS Code: se
 * redimensiona arrastrando su borde interior (doble clic lo devuelve al tamaño por defecto)
 * y el botón de la cabecera lo cambia de lado.
 *
 * Sin ViewModel, por el mismo motivo que `NetworkInspectorScreen`: todo el estado con vida
 * propia (buffer, cwd, historial, proceso) ya vive en [SessionTerminal], que es de ámbito de
 * aplicación porque el shell tiene que sobrevivir a que cierres el panel. Lo único que queda
 * aquí es el texto que estás escribiendo.
 *
 * Muestra dos cosas mezcladas en orden cronológico, distinguidas por el prefijo:
 * `❯` lo que ejecutas tú, `⏵` lo que ejecuta el agente (espejo, ver [SessionTerminal]).
 */
@Composable
fun TerminalPanel(
    terminal: SessionTerminal,
    dock: TerminalDock,
    /** Alto si [dock] es Bottom, ancho si es Right. */
    size: Dp,
    /** Delta de tamaño durante el arrastre; positivo agranda el panel. */
    onResize: (Dp) -> Unit,
    /** Fin del arrastre: momento de persistir el tamaño. */
    onResizeEnd: () -> Unit,
    onResetSize: () -> Unit,
    onToggleDock: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val lines by terminal.lines.collectAsStateWithLifecycle()
    val running by terminal.running.collectAsStateWithLifecycle()
    val history by terminal.history.collectAsStateWithLifecycle()
    val workingDir by terminal.workingDir.collectAsStateWithLifecycle()

    var input by remember(terminal) { mutableStateOf(TextFieldValue()) }
    // -1 = escribiendo algo nuevo; 0..n-1 = posición dentro del historial, desde el final.
    var historyIndex by remember(terminal) { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    // `withFrameNanos` antes de pedir el foco: el cuerpo del LaunchedEffect corre tras la
    // composición pero puede adelantarse al layout, y pedirlo con el nodo aún sin adjuntar
    // lanza — el campo se quedaría sin cursor y parecería que la terminal no acepta texto.
    LaunchedEffect(terminal) {
        withFrameNanos { }
        runCatching { focusRequester.requestFocus() }
        // Arranca el shell ya, para que el coste de cargar el perfil no lo pague el primer
        // comando que escribas.
        terminal.warmUp()
    }

    fun applyHistory(delta: Int) {
        if (history.isEmpty()) return
        val next = (historyIndex + delta).coerceIn(-1, history.lastIndex)
        historyIndex = next
        val text = if (next < 0) "" else history[history.lastIndex - next]
        input = TextFieldValue(text, selection = androidx.compose.ui.text.TextRange(text.length))
    }

    // El asa va en el borde que da al chat: arriba si el panel está abajo, a la izquierda
    // si está a la derecha. En ambos casos arrastrar hacia el chat (delta negativo) agranda.
    val handle = @Composable {
        ResizeHandle(
            horizontal = dock == TerminalDock.Right,
            onDrag = { px -> onResize(with(density) { -px.toDp() }) },
            onDragEnd = onResizeEnd,
            onDoubleClick = onResetSize
        )
    }
    val body: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "Terminal",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = workingDir ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (running) {
                PanelIcon(Icons.Filled.Stop, "Detener el comando en curso", terminal::interrupt)
            }
            PanelIcon(Icons.Filled.Refresh, "Reiniciar el shell", terminal::restart)
            PanelIcon(Icons.Filled.DeleteSweep, "Limpiar la salida", terminal::clear)
            if (dock == TerminalDock.Bottom) {
                PanelIcon(Icons.Filled.VerticalSplit, "Mover la terminal a la derecha", onToggleDock)
            } else {
                PanelIcon(Icons.Filled.HorizontalSplit, "Mover la terminal abajo", onToggleDock)
            }
            PanelIcon(Icons.Filled.Close, "Cerrar la terminal", onClose)
        }

        // El `weight` va en este Box y NO en el SelectionContainer: pasándoselo a él, el
        // Column no lo ve como hijo ponderado, así que lo mide con todo el espacio que
        // queda, su LazyColumn (`fillMaxSize`) se lo come entero y la fila del prompt
        // acaba midiendo 0 de alto — invisible, sin cursor y sin poder clicarla, aunque
        // reciba el foco y las teclas.
        Box(modifier = Modifier.weight(1f)) {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = Spacing.md)
                ) {
                    items(lines, key = { it.id }) { line ->
                        Text(
                            text = when (line.kind) {
                                TerminalLineKind.UserCommand -> "❯ ${line.text}"
                                TerminalLineKind.AgentCommand -> "⏵ agente: ${line.text}"
                                else -> line.text
                            },
                            style = TERMINAL_TEXT_STYLE,
                            color = when (line.kind) {
                                TerminalLineKind.UserCommand -> MaterialTheme.colorScheme.primary
                                TerminalLineKind.AgentCommand -> MaterialTheme.colorScheme.tertiary
                                TerminalLineKind.System -> MaterialTheme.colorScheme.onSurfaceVariant
                                TerminalLineKind.Output -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                // Un tap en cualquier parte de la fila lleva el foco al campo, no solo el
                // ancho exacto del texto. `pointerInput` y no `clickable`: en desktop este
                // último también dispara con Espacio/Enter, que aquí son teclas de escritura.
                .pointerInput(Unit) {
                    detectTapGestures { runCatching { focusRequester.requestFocus() } }
                }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = "❯",
                style = TERMINAL_TEXT_STYLE,
                color = if (running) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary
            )
            // El campo NO se deshabilita mientras corre un comando: un servidor de desarrollo
            // no termina nunca, y deshabilitarlo dejaría la terminal muerta justo en el caso
            // más común. Se puede escribir y editar; lo único que espera es el envío.
            BasicTextField(
                value = input,
                onValueChange = { input = it; historyIndex = -1 },
                singleLine = true,
                textStyle = TERMINAL_TEXT_STYLE.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        // Ctrl+C / Ctrl+X detienen el comando en curso, como en cualquier
                        // terminal. Solo mientras corre algo: con el shell libre, Ctrl+C debe
                        // seguir copiando. Se mira Ctrl y no Cmd a propósito — en macOS
                        // Cmd+C es copiar y Ctrl+C interrumpir, y aquí valen las dos cosas.
                        if (running && event.isCtrlPressed && (event.key == Key.C || event.key == Key.X)) {
                            terminal.interrupt()
                            return@onPreviewKeyEvent true
                        }
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                when {
                                    // `exit` con algo corriendo significa "para esto". En una
                                    // terminal real el texto iría al stdin del proceso, pero
                                    // aquí el comando corre con stdin cerrado, así que sin
                                    // esto la palabra no haría nada en absoluto.
                                    running && input.text.trim().equals("exit", ignoreCase = true) -> {
                                        terminal.interrupt()
                                        input = TextFieldValue()
                                        historyIndex = -1
                                    }
                                    // Con otro texto y un comando en curso no se envía (el
                                    // shell es uno solo y está ocupado), pero se conserva lo
                                    // escrito: se manda al parar el proceso, sin reescribirlo.
                                    !running -> {
                                        terminal.submit(input.text)
                                        input = TextFieldValue()
                                        historyIndex = -1
                                    }
                                }
                                true
                            }
                            // Historial como en cualquier shell. Se consume el evento para
                            // que el foco no salte a otro control del scaffold.
                            Key.DirectionUp -> { applyHistory(+1); true }
                            Key.DirectionDown -> { applyHistory(-1); true }
                            else -> false
                        }
                    }
            )
            if (running) {
                Text(
                    // Acoplada a la derecha el panel es estrecho: el aviso largo dejaría el
                    // campo de texto sin ancho.
                    text = if (dock == TerminalDock.Right) "en curso"
                    else "en curso · Ctrl+C, «exit» o ⏹ para detener",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }

    val background = MaterialTheme.colorScheme.surfaceVariant
    when (dock) {
        TerminalDock.Bottom -> Column(
            modifier = modifier.fillMaxWidth().height(size).background(background)
        ) {
            handle()
            body()
        }
        TerminalDock.Right -> Row(
            modifier = modifier.fillMaxHeight().width(size).background(background)
        ) {
            handle()
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) { body() }
        }
    }
}

/**
 * Asa de redimensionado: 6 dp de zona agarrable con una línea de 1 dp que se resalta al
 * pasar el ratón o arrastrar, y cursor de resize en desktop. Doble clic resetea el tamaño.
 *
 * @param horizontal true si se arrastra en horizontal (panel a la derecha).
 */
@Composable
private fun ResizeHandle(
    horizontal: Boolean,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDoubleClick: () -> Unit
) {
    // `pointerInput` se lanza una sola vez por clave: sin esto usaría las lambdas del
    // primer frame para siempre.
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    val active = hovered || dragging

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .then(if (horizontal) Modifier.fillMaxHeight().width(6.dp) else Modifier.fillMaxWidth().height(6.dp))
            .hoverable(interaction)
            .pointerHoverIcon(resizeCursor(horizontal))
            .pointerInput(horizontal) {
                val end = { dragging = false; currentOnDragEnd() }
                if (horizontal) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = end,
                        onDragCancel = end
                    ) { change, amount -> change.consume(); currentOnDrag(amount) }
                } else {
                    detectVerticalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = end,
                        onDragCancel = end
                    ) { change, amount -> change.consume(); currentOnDrag(amount) }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { currentOnDoubleClick() })
            }
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (horizontal) Modifier.fillMaxHeight().width(if (active) 3.dp else 1.dp)
                    else Modifier.fillMaxWidth().height(if (active) 3.dp else 1.dp)
                )
                .background(
                    if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                )
        )
    }
}

@Composable
private fun PanelIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp)
        )
    }
}

private val TERMINAL_TEXT_STYLE = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 16.sp
)

/**
 * Espacio mínimo que el panel deja siempre al chat al agrandarse, para que nunca se pueda
 * tapar la conversación por completo.
 */
val TERMINAL_MIN_CONTENT_SPACE = 240.dp

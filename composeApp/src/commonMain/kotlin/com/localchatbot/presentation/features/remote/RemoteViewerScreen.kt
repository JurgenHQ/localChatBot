package com.localchatbot.presentation.features.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.webview.PlatformWebView
import com.localchatbot.presentation.components.atoms.AppTextField
import com.localchatbot.presentation.components.atoms.PrimaryButton

@Composable
fun RemoteViewerScreen(
    viewModel: RemoteViewerViewModel,
    onClose: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            // Barra superior: volver + título o URL cargada + acción para cambiar.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Volver",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    text = state.loadedUrl.ifBlank { "Visor remoto" },
                    style = if (state.loadedUrl.isBlank())
                        MaterialTheme.typography.headlineLarge
                    else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (state.loadedUrl.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .padding(start = Spacing.sm)
                            .clip(RoundedCornerShape(Radius.pill))
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outline,
                                RoundedCornerShape(Radius.pill)
                            )
                            .clickable(onClick = viewModel::disconnect)
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Cambiar",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }

            if (state.loadedUrl.isBlank()) {
                RemoteViewerForm(
                    inputUrl = state.inputUrl,
                    onInputChange = viewModel::onInputChange,
                    onConnect = viewModel::connect
                )
            } else {
                PlatformWebView(url = state.loadedUrl, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun RemoteViewerForm(
    inputUrl: String,
    onInputChange: (String) -> Unit,
    onConnect: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Text(
            "Conecta con el cliente remoto que sirve otro equipo en la misma red/VPN. " +
                "Pega la URL que muestra ese equipo en Ajustes → Acceso remoto.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        AppTextField(
            value = inputUrl,
            onValueChange = onInputChange,
            placeholder = "http://192.168.1.50:7676",
            keyboardType = KeyboardType.Uri,
            monospace = true
        )
        PrimaryButton(
            text = "Conectar",
            onClick = onConnect,
            enabled = inputUrl.isNotBlank()
        )
    }
}

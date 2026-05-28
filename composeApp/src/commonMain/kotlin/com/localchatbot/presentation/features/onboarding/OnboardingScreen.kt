package com.localchatbot.presentation.features.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.localchatbot.core.theme.Radius
import com.localchatbot.core.theme.Spacing
import com.localchatbot.core.theme.ThemeMode
import com.localchatbot.domain.model.ConnectionMode
import com.localchatbot.domain.model.ConnectionStatus
import com.localchatbot.presentation.components.atoms.AppLogo
import com.localchatbot.presentation.components.atoms.PrimaryButton
import com.localchatbot.presentation.components.atoms.SecondaryButton
import com.localchatbot.presentation.components.molecules.ConnectionStatusBadge
import com.localchatbot.presentation.components.molecules.LabeledField
import com.localchatbot.presentation.components.molecules.ModelPickerList
import com.localchatbot.presentation.preview.PreviewSurface
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OnboardingContent(
        state = state,
        onModeChange = viewModel::onModeChange,
        onIpChange = viewModel::onIpChange,
        onPortChange = viewModel::onPortChange,
        onDirectUrlChange = viewModel::onDirectUrlChange,
        onModelChange = viewModel::onModelChange,
        onModelSelected = viewModel::onModelSelected,
        onTest = viewModel::testConnection,
        onFinish = { viewModel.finish(onFinished) },
        modifier = modifier
    )
}

@Composable
fun OnboardingContent(
    state: OnboardingState,
    onModeChange: (ConnectionMode) -> Unit = {},
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onDirectUrlChange: (String) -> Unit = {},
    onModelChange: (String) -> Unit,
    onModelSelected: (String) -> Unit,
    onTest: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().padding(horizontal = Spacing.lg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 48.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppLogo(size = 40.dp)
                Spacer(Modifier.width(Spacing.md))
                Text(
                    "LOCALCHATBOT",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Conecta tu modelo",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                when (state.mode) {
                    ConnectionMode.LocalNetwork -> "Apunta a un endpoint compatible con OpenAI en tu red local."
                    ConnectionMode.DirectUrl    -> "Pega la URL de tu tunnel (Cloudflare, ngrok, etc.)."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Toggle de modo
            OnboardingModeToggle(selected = state.mode, onSelect = onModeChange)

            if (state.mode == ConnectionMode.LocalNetwork) {
                LabeledField(
                    label = "Dirección IP",
                    value = state.ip,
                    onValueChange = onIpChange,
                    placeholder = "192.168.1.42",
                    keyboardType = KeyboardType.Uri,
                    monospace = true
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    LabeledField(
                        label = "Puerto",
                        value = state.port,
                        onValueChange = onPortChange,
                        placeholder = "1234",
                        keyboardType = KeyboardType.Number,
                        monospace = true,
                        suffix = "/v1",
                        modifier = Modifier.width(160.dp)
                    )
                }
            } else {
                LabeledField(
                    label = "URL del servidor",
                    value = state.directUrl,
                    onValueChange = onDirectUrlChange,
                    placeholder = "https://abc.trycloudflare.com",
                    keyboardType = KeyboardType.Uri,
                    monospace = true
                )
            }

            LabeledField(
                label = "Modelo",
                value = state.model,
                onValueChange = onModelChange,
                placeholder = "llama-3.1-8b-instruct",
                monospace = true
            )

            if (state.availableModels.isNotEmpty() || state.loadingModels) {
                ModelPickerList(
                    models = state.availableModels,
                    selected = state.model,
                    onSelect = onModelSelected,
                    loading = state.loadingModels
                )
            }

            ConnectionStatusBadge(status = state.status)

            Spacer(Modifier.height(Spacing.xl))

            PrimaryButton(
                text = "Empezar a chatear",
                onClick = onFinish,
                enabled = state.canSubmit
            )
            SecondaryButton(
                text = "Probar conexión de nuevo",
                onClick = onTest,
                enabled = state.status !is ConnectionStatus.Checking &&
                    (state.ip.isNotBlank() || state.directUrl.isNotBlank())
            )
        }
    }
}

@Composable
private fun OnboardingModeToggle(
    selected: ConnectionMode,
    onSelect: (ConnectionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ConnectionMode.entries.forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (mode) {
                        ConnectionMode.LocalNetwork -> "Red local"
                        ConnectionMode.DirectUrl    -> "URL directa"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Preview
@Composable
private fun OnboardingEmptyPreview() = PreviewSurface {
    OnboardingContent(
        state = OnboardingState(),
        onIpChange = {}, onPortChange = {}, onModelChange = {}, onModelSelected = {}, onTest = {}, onFinish = {}
    )
}

@Preview
@Composable
private fun OnboardingFilledPreview() = PreviewSurface {
    OnboardingContent(
        state = OnboardingState(
            ip = "192.168.1.42",
            port = "1234",
            model = "llama-3.1-8b-instruct",
            status = ConnectionStatus.Connected(latencyMs = 42)
        ),
        onIpChange = {}, onPortChange = {}, onModelChange = {}, onModelSelected = {}, onTest = {}, onFinish = {}
    )
}

@Preview
@Composable
private fun OnboardingDirectUrlPreview() = PreviewSurface {
    OnboardingContent(
        state = OnboardingState(
            mode = ConnectionMode.DirectUrl,
            directUrl = "https://abc.trycloudflare.com",
            model = "llama-3.1-8b-instruct",
            status = ConnectionStatus.Connected(latencyMs = 80)
        ),
        onIpChange = {}, onPortChange = {}, onModelChange = {}, onModelSelected = {}, onTest = {}, onFinish = {}
    )
}

@Preview
@Composable
private fun OnboardingErrorPreview() = PreviewSurface {
    OnboardingContent(
        state = OnboardingState(
            ip = "192.168.1.42",
            port = "1234",
            model = "llama-3.1-8b-instruct",
            status = ConnectionStatus.Error("No se pudo contactar al servidor")
        ),
        onIpChange = {}, onPortChange = {}, onModelChange = {}, onModelSelected = {}, onTest = {}, onFinish = {}
    )
}

@Preview
@Composable
private fun OnboardingDarkPreview() = PreviewSurface(themeMode = ThemeMode.Dark) {
    OnboardingContent(
        state = OnboardingState(
            ip = "192.168.1.42",
            port = "1234",
            model = "llama-3.1-8b-instruct",
            status = ConnectionStatus.Connected(latencyMs = 42)
        ),
        onIpChange = {}, onPortChange = {}, onModelChange = {}, onModelSelected = {}, onTest = {}, onFinish = {}
    )
}

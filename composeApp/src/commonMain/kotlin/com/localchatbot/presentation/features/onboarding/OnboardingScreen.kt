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
import androidx.compose.material3.Switch
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
        onIpChange = viewModel::onIpChange,
        onPortChange = viewModel::onPortChange,
        onHttpsChange = viewModel::onHttpsChange,
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
    onIpChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onHttpsChange: (Boolean) -> Unit = {},
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
                "Apunta a un endpoint compatible con OpenAI: LM Studio, Ollama o llama.cpp en tu red " +
                    "o por VPN, un túnel, o un proveedor cloud. Activa HTTPS para túneles y cloud.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            LabeledField(
                label = "Host / IP",
                value = state.ip,
                onValueChange = onIpChange,
                placeholder = "192.168.1.42  o  api.openai.com",
                keyboardType = KeyboardType.Uri,
                monospace = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                LabeledField(
                    label = "Puerto",
                    value = state.port,
                    onValueChange = onPortChange,
                    placeholder = "1234",
                    keyboardType = KeyboardType.Number,
                    monospace = true,
                    modifier = Modifier.width(140.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    Text(
                        "HTTPS",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Switch(checked = state.useHttps, onCheckedChange = onHttpsChange)
                }
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
                enabled = state.status !is ConnectionStatus.Checking && state.ip.isNotBlank()
            )
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
private fun OnboardingHttpsPreview() = PreviewSurface {
    OnboardingContent(
        state = OnboardingState(
            ip = "abc.trycloudflare.com",
            port = "",
            useHttps = true,
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

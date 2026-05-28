package com.localchatbot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localchatbot.core.theme.AppTheme
import com.localchatbot.di.AppContainer
import com.localchatbot.presentation.features.onboarding.OnboardingScreen
import com.localchatbot.presentation.features.onboarding.OnboardingViewModel
import com.localchatbot.presentation.navigation.MainScaffold

@Composable
fun App(container: AppContainer = remember { AppContainer() }) {
    val prefs by container.preferencesRepository.preferences.collectAsStateWithLifecycle(
        initialValue = com.localchatbot.domain.model.AppPreferences.Default
    )

    AppTheme(themeMode = prefs.themeMode, accentSeed = prefs.accentSeed) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (!prefs.onboardingDone) {
                val onboardingVm = remember {
                    OnboardingViewModel(
                        preferences = container.preferencesRepository,
                        checkConnection = container.checkConnection,
                        listModels = container.listModels
                    )
                }
                OnboardingScreen(
                    viewModel = onboardingVm,
                    onFinished = { /* state changes via prefs flow */ }
                )
            } else {
                MainScaffold(container = container)
            }
        }
    }
}

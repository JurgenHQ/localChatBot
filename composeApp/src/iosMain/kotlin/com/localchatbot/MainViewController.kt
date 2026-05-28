package com.localchatbot

import androidx.compose.ui.window.ComposeUIViewController

// enforceStrictPlistSanityCheck = false: el proyecto Xcode usa GENERATE_INFOPLIST_FILE = YES,
// y INFOPLIST_KEY_CADisableMinimumFrameDurationOnPhone no se serializa bien. Decisión consciente:
// nos quedamos a 60Hz en ProMotion para no mantener un Info.plist manual. Si algún día se quiere
// 120Hz, añadir el plist manual y quitar este configure.
@Suppress("FunctionName")
fun MainViewController() = ComposeUIViewController(
    configure = { enforceStrictPlistSanityCheck = false }
) {
    App()
}

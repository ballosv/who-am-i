package de.ballosv.whoami

import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ballosv.whoami.ui.WhoAmIRoot
import de.ballosv.whoami.ui.theme.WhoAmITheme
import platform.UIKit.UIViewController

/** Einstieg für die iOS-App (aufgerufen aus Swift). */
fun MainViewController(): UIViewController = ComposeUIViewController {
    WhoAmITheme {
        WhoAmIRoot(viewModel { AppViewModel() })
    }
}

/** Einladungslink (whoami://join?code=ABCD), aus Swift weitergereicht. */
fun handleOpenUrl(url: String) {
    DeepLinks.handle(url)
}

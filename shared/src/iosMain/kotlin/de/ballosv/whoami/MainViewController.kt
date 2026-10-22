package de.ballosv.whoami

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.ComposeUIViewController
import androidx.lifecycle.viewmodel.compose.viewModel
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.ui.WhoAmIRoot
import de.ballosv.whoami.ui.theme.WhoAmITheme
import platform.UIKit.UIViewController

/**
 * Einstieg für die iOS-App (aufgerufen aus Swift). [autoStart] startet sofort ein Einzelspiel –
 * nur für den automatischen Probestart im Simulator (CI).
 */
fun MainViewController(autoStart: Boolean = false): UIViewController = ComposeUIViewController {
    WhoAmITheme {
        val vm = viewModel { AppViewModel() }
        if (autoStart) {
            LaunchedEffect(Unit) { vm.startSingle(vm.prefs.loadSettings(GameMode.SINGLE)) }
        }
        WhoAmIRoot(vm)
    }
}

/** Einladungslink (whoami://join?code=ABCD), aus Swift weitergereicht. */
fun handleOpenUrl(url: String) {
    DeepLinks.handle(url)
}

package de.ballosv.whoami

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import de.ballosv.whoami.ui.WhoAmIRoot
import de.ballosv.whoami.ui.theme.WhoAmITheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            WhoAmITheme {
                WhoAmIRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** Einladungslink (whoami://join?code=ABCD) oder angetippte Einladungs-Benachrichtigung. */
    private fun handleIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "whoami" && data.host == "join") {
            viewModel.openJoin(data.getQueryParameter("code"))
        }
    }
}

package de.ballosv.whoami.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import de.ballosv.whoami.AppViewModel
import de.ballosv.whoami.DeepLinks
import de.ballosv.whoami.Screen
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.Phase
import de.ballosv.whoami.platform.BackHandler
import de.ballosv.whoami.platform.KeepScreenOn
import de.ballosv.whoami.platform.hasSystemBack
import de.ballosv.whoami.session.HostSession
import de.ballosv.whoami.session.GameSession
import de.ballosv.whoami.session.SessionStatus

@Composable
fun WhoAmIRoot(vm: AppViewModel) {
    val joinCode by DeepLinks.joinCode.collectAsStateCompat()
    LaunchedEffect(joinCode) {
        val code = joinCode ?: return@LaunchedEffect
        DeepLinks.consume()
        vm.openJoin(code.ifBlank { null })
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            when (val screen = vm.screen) {
                Screen.Start -> StartScreen(vm)
                Screen.MultiMenu -> {
                    BackHandler(onBack = vm::back)
                    MultiMenuScreen(vm)
                }
                is Screen.Settings -> {
                    BackHandler(onBack = vm::back)
                    SettingsScreen(vm, screen.multiplayer)
                }
                is Screen.Join -> {
                    BackHandler(onBack = vm::back)
                    JoinScreen(vm, screen.code)
                }
                Screen.Session -> vm.session?.let { SessionContent(vm, it) } ?: StartScreen(vm)
            }
            ToastHost()
        }
    }
}

@Composable
private fun SessionContent(vm: AppViewModel, session: GameSession) {
    val snapshot by session.snapshot.collectAsStateCompat()
    val status by session.status.collectAsStateCompat()
    val notice by session.notice.collectAsStateCompat()
    var confirmLeave by remember { mutableStateOf(false) }
    // Der Toast ist kurz und abgeschnitten; die Ursache bleibt deshalb auch auf dem Fehlerbildschirm stehen.
    var lastNotice by remember { mutableStateOf<String?>(null) }

    // Während eines Spiels soll das Display nicht ausgehen.
    KeepScreenOn()
    LaunchedEffect(notice) {
        notice?.let {
            showToast(it)
            lastNotice = it
            session.consumeNotice()
        }
    }

    val s = snapshot
    val inGame = s != null && s.phase != Phase.LOBBY && s.phase != Phase.FINISHED
    val requestLeave = {
        if (inGame || session.isHost && s?.phase == Phase.LOBBY && s.players.size > 1) confirmLeave = true else vm.leaveSession()
    }
    BackHandler(onBack = requestLeave)
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Spiel verlassen?") },
            text = { Text(if (session.isHost) "Das Spiel wird für alle beendet." else "Du verlässt das laufende Spiel.") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.leaveSession() }) { Text("Verlassen") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("Weiterspielen") } },
        )
    }

    when {
        status is SessionStatus.Ended -> CenterMessage((status as SessionStatus.Ended).reason, "Zum Hauptmenü", vm::leaveSession)
        s == null -> CenterMessage("Verbinde …")
        s.phase == Phase.LOBBY && s.settings.mode == GameMode.SINGLE ->
            CenterMessage(
                listOfNotNull("Das Spiel konnte nicht gestartet werden.", lastNotice).joinToString("\n\n"),
                "Erneut versuchen",
            ) { (session as? HostSession)?.startGame() }
        s.phase == Phase.LOBBY -> LobbyScreen(vm, session, s)
        s.phase == Phase.FINISHED -> FinishedScreen(session, s, onMenu = vm::leaveSession)
        else -> GameScreen(session, s)
    }
    // Ohne Zurück-Taste (iPhone): sichtbare Schaltfläche zum Verlassen (Lobby und Ergebnis haben eigene)
    val needsClose = s == null || inGame || s.phase == Phase.LOBBY && s.settings.mode == GameMode.SINGLE
    if (!hasSystemBack && status !is SessionStatus.Ended && needsClose) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
            IconButton(onClick = requestLeave) {
                Icon(Icons.Default.Close, contentDescription = "Spiel verlassen", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

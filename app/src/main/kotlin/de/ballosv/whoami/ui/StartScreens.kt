package de.ballosv.whoami.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ballosv.whoami.AppViewModel
import de.ballosv.whoami.Screen
import de.ballosv.whoami.session.GuestSession
import de.ballosv.whoami.session.SessionStatus
import de.ballosv.whoami.ui.theme.Accent

@Composable
fun StartScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val requestInvites = rememberNearbyPermissionRequest(withNotifications = true) { granted ->
        if (granted) vm.setInviteListening(true)
        else Toast.makeText(context, "Ohne Berechtigung können keine Einladungen empfangen werden", Toast.LENGTH_LONG).show()
    }
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Who Am I?", fontSize = 48.sp, fontWeight = FontWeight.Black, color = Accent)
        Text(
            "Errate die Person, bevor alle Kacheln weg sind!",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(48.dp))
        BigMenuButton("Alleine spielen", Icons.Default.Person) { vm.navigate(Screen.Settings(multiplayer = false)) }
        Spacer(Modifier.height(16.dp))
        BigMenuButton("Mehrspieler", Icons.Default.Groups) { vm.navigate(Screen.MultiMenu) }
        Spacer(Modifier.height(40.dp))
        SectionCard(null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Für Einladungen erreichbar", fontWeight = FontWeight.Bold)
                    Text(
                        "Benachrichtigt dich, wenn dich jemand in der Nähe zu einem Spiel einlädt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = vm.inviteListening,
                    onCheckedChange = { if (it) requestInvites() else vm.setInviteListening(false) },
                )
            }
        }
    }
}

@Composable
private fun BigMenuButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.size(12.dp))
        Text(text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun MultiMenuScreen(vm: AppViewModel) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val request = rememberNearbyPermissionRequest { granted ->
        if (granted) pending?.invoke()
        else Toast.makeText(context, "Für den Mehrspieler-Modus werden Bluetooth/WLAN-Berechtigungen benötigt", Toast.LENGTH_LONG).show()
        pending = null
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("Mehrspieler", onBack = vm::back)
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BigMenuButton("Spiel erstellen", Icons.Default.Wifi) {
                pending = { vm.navigate(Screen.Settings(multiplayer = true)) }
                request()
            }
            Spacer(Modifier.height(16.dp))
            BigMenuButton("Spiel beitreten", Icons.Default.PersonAdd) {
                pending = { vm.openJoin(null) }
                request()
            }
            Spacer(Modifier.height(24.dp))
            Text(
                "Mehrspieler läuft direkt zwischen den Handys (Bluetooth/WLAN), alle sollten im selben Raum sein. " +
                    "Battle-Modus: Jeder bringt eigene Fotos mit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun JoinScreen(vm: AppViewModel, code: String?) {
    val guest = vm.session as? GuestSession ?: return
    val games by guest.games.collectAsStateCompat()
    val status by guest.status.collectAsStateCompat()
    val notice by guest.notice.collectAsStateCompat()
    var name by rememberSaveable { mutableStateOf(vm.prefs.playerName) }

    LaunchedEffect(status) {
        if (status == SessionStatus.Connected) vm.joined()
    }
    // Per Einladung geöffnet: passendes Spiel automatisch betreten.
    LaunchedEffect(games, code) {
        val match = games.firstOrNull { it.ad.code == code } ?: return@LaunchedEffect
        if (status == SessionStatus.Searching) guest.connect(match)
    }

    Column(Modifier.fillMaxSize()) {
        TopBar("Spiel beitreten", onBack = vm::back)
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it.take(24)
                    vm.setPlayerName(name)
                    guest.setName(name)
                },
                label = { Text("Dein Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            when (val st = status) {
                SessionStatus.Connecting -> StatusLine("Verbinde mit ${guest.hostName.value} …")
                is SessionStatus.Ended -> Text(st.reason, color = MaterialTheme.colorScheme.error)
                else -> StatusLine(if (code != null) "Suche Spiel $code …" else "Suche Spiele in der Nähe …")
            }
            notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (status is SessionStatus.Ended) {
                FilledTonalButton(onClick = { vm.openJoin(code) }) { Text("Erneut suchen") }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(games, key = { it.endpointId }) { game ->
                    SectionCard(null, Modifier.clickable(enabled = status == SessionStatus.Searching) { guest.connect(game) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(game.ad.hostName, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                Text("Code ${game.ad.code}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Button(
                                onClick = { guest.connect(game) },
                                enabled = status == SessionStatus.Searching,
                                colors = if (game.ad.code == code) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                            ) { Text("Beitreten") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.size(12.dp))
        Text(text)
    }
}

@Composable
fun CenterMessage(text: String, action: String? = null, onAction: () -> Unit = {}) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (action != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

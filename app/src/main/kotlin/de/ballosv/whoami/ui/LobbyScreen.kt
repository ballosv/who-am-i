package de.ballosv.whoami.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import de.ballosv.whoami.AppViewModel
import de.ballosv.whoami.content.Images
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.invite.InviteListenerService
import de.ballosv.whoami.session.BattleEntry
import de.ballosv.whoami.session.GameSession
import de.ballosv.whoami.session.HostSession
import de.ballosv.whoami.ui.theme.Accent
import de.ballosv.whoami.ui.theme.Good
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class Draft(val id: String, val jpeg: ByteArray, val preview: ImageBitmap, first: String, last: String) {
    var firstName by mutableStateOf(first)
    var lastName by mutableStateOf(last)
}

@Composable
fun LobbyScreen(vm: AppViewModel, session: GameSession, snapshot: GameSnapshot) {
    val me = snapshot.player(session.myId)
    val ready = me?.ready == true
    val battle = snapshot.settings.mode == GameMode.BATTLE
    var name by remember { mutableStateOf(me?.name ?: vm.prefs.playerName) }
    LaunchedEffect(name) {
        delay(500)
        if (name.isNotBlank() && name != me?.name) {
            vm.setPlayerName(name)
            session.setName(name)
        }
    }

    val drafts = remember(session) {
        mutableStateListOf<Draft>().apply {
            session.battleEntries.value.forEach { e ->
                val bmp = BitmapFactory.decodeByteArray(e.jpeg, 0, e.jpeg.size)?.asImageBitmap()
                if (bmp != null) add(Draft(e.id, e.jpeg, bmp, e.firstName, e.lastName))
            }
        }
    }
    val draftsValid = drafts.isNotEmpty() && drafts.all { it.lastName.isNotBlank() || it.firstName.isNotBlank() }
    val canReady = name.isNotBlank() && (!battle || draftsValid)

    Column(Modifier.fillMaxSize()) {
        TopBar("Lobby", onBack = vm::back)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(24) },
                label = { Text("Dein Name") },
                singleLine = true,
                enabled = !ready,
                modifier = Modifier.fillMaxWidth(),
            )
            if (session is HostSession) HostCards(session)
            SettingsSummary(snapshot)
            if (battle) BattlePicker(drafts, snapshot.settings.personCount, enabled = !ready)
            PlayerList(snapshot, session.myId)
            Text(
                "Das Spiel startet automatisch, sobald alle (mindestens 2) Spieler bereit sind.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            onClick = {
                if (!ready && battle) {
                    session.setBattleEntries(drafts.map { BattleEntry(it.id, it.jpeg, it.firstName.trim(), it.lastName.trim()) })
                }
                session.setReady(!ready)
            },
            enabled = ready || canReady,
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(60.dp),
            shape = RoundedCornerShape(20.dp),
            colors = if (ready) ButtonDefaults.filledTonalButtonColors() else ButtonDefaults.buttonColors(),
        ) {
            Text(if (ready) "Nicht mehr bereit" else "Bereit!", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun HostCards(host: HostSession) {
    val context = LocalContext.current
    val inviting by host.inviting.collectAsStateCompat()
    val tvUrl by host.tvUrl.collectAsStateCompat()
    SectionCard("Spieler einladen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spieler in der Nähe benachrichtigen")
                Text(
                    "Wer „Für Einladungen erreichbar“ aktiviert hat, bekommt eine Benachrichtigung.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = inviting, onCheckedChange = host::setInviting)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Code: ", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(host.inviteCode, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Accent)
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = {
                val link = InviteListenerService.joinUri(host.inviteCode)
                val text = "Spiel mit mir „Who Am I?“! Öffne die App → Mehrspieler → Spiel beitreten (Code ${host.inviteCode}) " +
                    "oder tippe auf: $link"
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Einladung teilen"))
            }) {
                Icon(Icons.Default.Share, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Teilen")
            }
        }
    }
    SectionCard("Auf TV streamen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Text("Spiel auf dem Smart-TV zeigen, Handys werden zu Buzzern", Modifier.weight(1f))
            Switch(checked = tvUrl != null, onCheckedChange = host::setTvMode)
        }
        tvUrl?.let {
            Text("Im TV-Browser (gleiches WLAN) öffnen:", style = MaterialTheme.typography.bodySmall)
            Text(it, fontWeight = FontWeight.Black, fontSize = 22.sp, color = Accent)
        }
    }
}

@Composable
private fun SettingsSummary(s: GameSnapshot) {
    val st = s.settings
    SectionCard("Einstellungen") {
        val mode = when (st.mode) {
            GameMode.BATTLE -> "Battle – ${st.personCount} Bild(er) pro Spieler"
            else -> "Promis – ${st.personCount} Personen"
        }
        Text(mode, fontWeight = FontWeight.Bold)
        if (st.mode != GameMode.BATTLE) Text("Kategorien: ${st.celebrityFilter.describe()}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "${st.tileCount} Kacheln (Abstand ${st.tileGapDp} dp) · alle ${st.revealIntervalMs} ms · ${st.answerTimeSeconds} s Antwortzeit\n" +
                "${st.maxPoints} Punkte, −${st.pointsStepPerTile} pro Kachel, min. ${st.minPoints}\n" +
                (if (st.accuracy == de.ballosv.whoami.core.AnswerAccuracy.LAST_NAME) "Nachname genügt" else "Vor- und Nachname") +
                " · Fehler: " + (if (st.halfPointsPenalty) "−50 %" else "−${st.fixedPenalty}"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BattlePicker(drafts: MutableList<Draft>, max: Int, enabled: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    val onPicked: (List<android.net.Uri>) -> Unit = { uris ->
        if (uris.isNotEmpty()) {
            loading = true
            scope.launch {
                val free = (max - drafts.size).coerceAtLeast(0)
                val loaded = withContext(Dispatchers.IO) {
                    uris.take(free).mapNotNull { uri ->
                        val jpeg = Images.fromUri(context, uri) ?: return@mapNotNull null
                        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() ?: return@mapNotNull null
                        Draft(HostSession.newImageId(), jpeg, bmp, "", "")
                    }
                }
                drafts.addAll(loaded)
                loading = false
            }
        }
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, max))) { onPicked(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { onPicked(listOfNotNull(it)) }

    SectionCard("Deine Bilder (${drafts.size}/$max)") {
        Text(
            "Wähle Fotos von Personen, die die anderen erraten sollen, und gib jeweils den Namen ein.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        drafts.forEach { d ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    d.preview, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                )
                Column(Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = d.firstName, onValueChange = { d.firstName = it }, enabled = enabled,
                        label = { Text("Vorname") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = d.lastName, onValueChange = { d.lastName = it }, enabled = enabled,
                        label = { Text("Nachname") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = { drafts.remove(d) }, enabled = enabled) {
                    Icon(Icons.Default.Close, contentDescription = "Entfernen")
                }
            }
        }
        if (drafts.size < max) {
            FilledTonalButton(
                enabled = enabled && !loading,
                onClick = {
                    val request = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    if (max - drafts.size > 1) pickMany.launch(request) else pickOne.launch(request)
                },
            ) {
                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(if (loading) "Lade …" else "Bilder auswählen")
            }
        }
    }
}

@Composable
private fun PlayerList(s: GameSnapshot, myId: String) {
    SectionCard("Spieler (${s.players.size})") {
        s.players.forEach { p ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (p.ready) Icons.Default.CheckCircle else Icons.Default.HourglassEmpty,
                    contentDescription = if (p.ready) "bereit" else "wartet",
                    tint = if (p.ready) Good else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(12.dp))
                Text(p.name.ifBlank { "…" } + if (p.id == myId) " (du)" else "", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                if (s.settings.mode == GameMode.BATTLE) {
                    Text("${p.imageCount} Bilder", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                }
                if (p.isHost) Icon(Icons.Default.Star, contentDescription = "Host", tint = Accent)
            }
        }
    }
}

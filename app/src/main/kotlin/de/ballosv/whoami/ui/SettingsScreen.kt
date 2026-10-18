package de.ballosv.whoami.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import de.ballosv.whoami.core.Area
import de.ballosv.whoami.core.CELEBRITIES
import de.ballosv.whoami.core.Decade
import de.ballosv.whoami.core.Origin
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import de.ballosv.whoami.ui.theme.TileA
import de.ballosv.whoami.ui.theme.TileB
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.ballosv.whoami.AppViewModel
import de.ballosv.whoami.core.AnswerAccuracy
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSettings
import de.ballosv.whoami.core.Scoring
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(vm: AppViewModel, multiplayer: Boolean) {
    var settings by remember {
        mutableStateOf(vm.prefs.loadSettings(if (multiplayer) GameMode.MULTIPLAYER else GameMode.SINGLE).let {
            if (multiplayer && it.mode == GameMode.SINGLE) it.copy(mode = GameMode.MULTIPLAYER) else it
        })
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(if (multiplayer) "Mehrspieler einrichten" else "Spiel einstellen", onBack = vm::back)
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsEditor(settings, multiplayer) { settings = it }
        }
        Button(
            onClick = {
                val final = settings.normalized()
                if (multiplayer) vm.hostMultiplayer(final) else vm.startSingle(final)
            },
            // Ohne passende Person kann kein Promi-Spiel starten (Battle bringt eigene Bilder mit).
            enabled = settings.mode == GameMode.BATTLE || settings.celebrityFilter.apply(CELEBRITIES).isNotEmpty(),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(60.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Text(if (multiplayer) "Weiter zur Lobby" else "Spiel starten", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun SettingsEditor(settings: GameSettings, multiplayer: Boolean, onChange: (GameSettings) -> Unit) {
    val s = settings
    if (multiplayer) {
        SectionCard("Spielmodus") {
            Choice(
                options = listOf("Promis" to GameMode.MULTIPLAYER, "Battle (eigene Bilder)" to GameMode.BATTLE),
                selected = s.mode,
            ) { onChange(s.copy(mode = it)) }
            Text(
                if (s.mode == GameMode.BATTLE) "Jeder wählt Fotos vom eigenen Handy; die anderen müssen raten."
                else "Bilder bekannter Persönlichkeiten aus der Wikipedia (Internet nötig).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (s.mode != GameMode.BATTLE) CategoryPicker(s, onChange)
    SectionCard("Spielrunde") {
        LabeledSlider(
            "Anzahl Kacheln", "${s.tileCount} (${s.gridSize}×${s.gridSize})",
            s.gridSize.toFloat(), GameSettings.MIN_GRID.toFloat()..GameSettings.MAX_GRID.toFloat(),
            steps = GameSettings.MAX_GRID - GameSettings.MIN_GRID - 1,
        ) { onChange(s.copy(gridSize = it.roundToInt())) }
        LabeledSlider(
            "Abstand zwischen den Kacheln", "${s.tileGapDp} dp",
            s.tileGapDp.toFloat(), 0f..GameSettings.MAX_TILE_GAP_DP.toFloat(),
            steps = GameSettings.MAX_TILE_GAP_DP - 1,
        ) { onChange(s.copy(tileGapDp = it.roundToInt())) }
        TileGapPreview(s.gridSize, s.tileGapDp, Modifier.align(Alignment.CenterHorizontally))
        LabeledSlider(
            if (s.mode == GameMode.BATTLE) "Bilder pro Spieler" else "Personen pro Spielrunde", "${s.personCount}",
            s.personCount.toFloat(), 1f..30f, steps = 28,
        ) { onChange(s.copy(personCount = it.roundToInt())) }
        LabeledSlider(
            "Zeit zwischen den Kacheln", "${s.revealIntervalMs} ms",
            s.revealIntervalMs.toFloat(), 200f..5000f, steps = 47,
        ) { onChange(s.copy(revealIntervalMs = (it / 100).roundToLong() * 100)) }
        LabeledSlider(
            "Zeit für die Eingabe", "${s.answerTimeSeconds} s",
            s.answerTimeSeconds.toFloat(), 3f..30f, steps = 26,
        ) { onChange(s.copy(answerTimeSeconds = it.roundToInt())) }
    }
    SectionCard("Genauigkeit der Lösung") {
        Choice(
            options = listOf("Nur Nachname" to AnswerAccuracy.LAST_NAME, "Vor- und Nachname" to AnswerAccuracy.FULL_NAME),
            selected = s.accuracy,
        ) { onChange(s.copy(accuracy = it)) }
    }
    SectionCard("Punkte-Abstufungen") {
        LabeledSlider("Punkte zu Beginn", "${s.maxPoints}", s.maxPoints.toFloat(), 100f..5000f, steps = 48) {
            val max = (it / 100).roundToInt() * 100
            onChange(s.copy(maxPoints = max, minPoints = s.minPoints.coerceAtMost(max), pointsStepPerTile = s.pointsStepPerTile.coerceAtMost(max)))
        }
        LabeledSlider("Abzug pro aufgedeckter Kachel", "−${s.pointsStepPerTile}", s.pointsStepPerTile.toFloat(), 0f..500f, steps = 99) {
            onChange(s.copy(pointsStepPerTile = (it / 5).roundToInt() * 5))
        }
        LabeledSlider("Mindestpunkte", "${s.minPoints}", s.minPoints.toFloat(), 0f..s.maxPoints.toFloat(), steps = 0) {
            onChange(s.copy(minPoints = (it / 10).roundToInt() * 10))
        }
        val half = Scoring.pointsFor(s, s.tileCount / 2)
        Text(
            "Verlauf: ${Scoring.pointsFor(s, 0)} → ${half} (halb aufgedeckt) → ${Scoring.pointsFor(s, s.tileCount)} (alles offen)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    SectionCard("Falsche Antwort / Zeit überschritten") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("50 % des aktuell möglichen Punktewerts abziehen", Modifier.weight(1f))
            Switch(checked = s.halfPointsPenalty, onCheckedChange = { onChange(s.copy(halfPointsPenalty = it)) })
        }
        if (!s.halfPointsPenalty) {
            LabeledSlider("Fester Punkteabzug", "−${s.fixedPenalty}", s.fixedPenalty.toFloat(), 0f..1000f, steps = 99) {
                onChange(s.copy(fixedPenalty = (it / 10).roundToInt() * 10))
            }
        }
    }
}

@Composable
fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        Row {
            Text(label, Modifier.weight(1f))
            Text(valueText, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
fun <T> Choice(options: List<Pair<String, T>>, selected: T, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (label, value) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onSelect(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** Vorschau des Kachelrasters mit dem eingestellten Abstand – so groß wie auf dem Spielbildschirm skaliert. */
@Composable
private fun TileGapPreview(gridSize: Int, gapDp: Int, modifier: Modifier = Modifier) {
    val brush = remember { Brush.linearGradient(listOf(TileA, TileB)) }
    Canvas(
        modifier
            .size(180.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF8D7B6A), Color(0xFF3B3A4A)))),
    ) {
        val cell = size.width / gridSize
        val gap = gapDp.dp.toPx() / 2
        for (tile in 0 until gridSize * gridSize) {
            val x = (tile % gridSize) * cell
            val y = (tile / gridSize) * cell
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x + gap, y + gap),
                size = Size((cell - 2 * gap).coerceAtLeast(0f), (cell - 2 * gap).coerceAtLeast(0f)),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
        }
    }
}

/**
 * Kategorien für die Promi-Bilder. Ohne Auswahl in einer Gruppe gilt dort „alle“.
 * Innerhalb einer Gruppe wird „oder“, zwischen den Gruppen „und“ verknüpft.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryPicker(s: GameSettings, onChange: (GameSettings) -> Unit) {
    val available = remember(s.areas, s.decades, s.origins) { s.celebrityFilter.apply(CELEBRITIES).size }
    SectionCard("Kategorien") {
        Text(
            "Ohne Auswahl sind alle Personen dabei. Mehrere Kategorien einer Gruppe werden kombiniert, " +
                "z. B. Musik + 80er = Musiker der 80er.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChipGroup("Bereich", Area.entries, s.areas, { it.label }) { onChange(s.copy(areas = it)) }
        ChipGroup("Jahrzehnt", Decade.entries, s.decades, { it.label }) { onChange(s.copy(decades = it)) }
        ChipGroup("Herkunft", Origin.entries, s.origins, { it.label }) { onChange(s.copy(origins = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when {
                    s.celebrityFilter.isAll -> "Alle $available Personen"
                    available == 1 -> "1 Person passt zur Auswahl"
                    else -> "$available Personen passen zur Auswahl"
                },
                fontWeight = FontWeight.Bold,
                color = if (available == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            if (!s.celebrityFilter.isAll) {
                TextButton(onClick = { onChange(s.copy(areas = emptySet(), decades = emptySet(), origins = emptySet())) }) {
                    Text("Alle")
                }
            }
        }
        when {
            available == 0 -> Text(
                "Keine Person passt – bitte Auswahl ändern.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            available < s.personCount -> Text(
                "Weniger Personen als pro Runde eingestellt – es werden nur so viele gezeigt, wie es gibt.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipGroup(title: String, options: List<T>, selected: Set<T>, label: (T) -> String, onChange: (Set<T>) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val isSelected = option in selected
            FilterChip(
                selected = isSelected,
                onClick = { onChange(if (isSelected) selected - option else selected + option) },
                label = { Text(label(option)) },
            )
        }
    }
}

package de.ballosv.whoami.session

import android.content.Context
import android.os.SystemClock
import de.ballosv.whoami.content.ImageStore
import de.ballosv.whoami.content.WikipediaImages
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSettings
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.core.HostEngine
import de.ballosv.whoami.core.ImageAssembler
import de.ballosv.whoami.core.Message
import de.ballosv.whoami.core.Phase
import de.ballosv.whoami.core.PlayerInfo
import de.ballosv.whoami.core.Question
import de.ballosv.whoami.net.NearbyConfig
import de.ballosv.whoami.net.NearbyHost
import de.ballosv.whoami.tv.TvServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Host eines Spiels: führt die [HostEngine] aus, verteilt den Spielzustand an alle
 * Mitspieler und stellt optional die TV-Ansicht bereit. Im Einzelspieler-Modus gibt es
 * keine Netzwerkverbindung, die Logik ist dieselbe.
 *
 * Alle Zugriffe auf die Engine laufen auf dem Main-Thread (Nearby liefert dort auch seine Callbacks).
 */
class HostSession(
    context: Context,
    settings: GameSettings,
    override val myId: String,
    myName: String,
) : GameSession, NearbyHost.Listener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val engine = HostEngine(settings, PlayerInfo(myId, myName.ifBlank { "Host" }, ready = false))
    private val nearby: NearbyHost? = if (settings.mode == GameMode.SINGLE) null else NearbyHost(appContext, this)

    override val isHost = true
    override val images = ImageStore()

    private val _snapshot = MutableStateFlow<GameSnapshot?>(engine.snapshot)
    override val snapshot: StateFlow<GameSnapshot?> = _snapshot
    override val status: StateFlow<SessionStatus> = MutableStateFlow(SessionStatus.Connected)
    private val _battle = MutableStateFlow<List<BattleEntry>>(emptyList())
    override val battleEntries: StateFlow<List<BattleEntry>> = _battle
    private val _notice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = _notice

    /** Code, der in Einladungen steht und das Spiel in der Nähe eindeutig erkennbar macht. */
    val inviteCode: String = (1..4).map { "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".random() }.joinToString("")

    private val _inviting = MutableStateFlow(false)
    val inviting: StateFlow<Boolean> = _inviting

    private val _tvUrl = MutableStateFlow<String?>(null)
    val tvUrl: StateFlow<String?> = _tvUrl
    private var tvServer: TvServer? = null

    private val endpointToPlayer = HashMap<String, String>()
    private val assemblers = HashMap<String, ImageAssembler>()
    private val pendingBattleMeta = HashMap<String, Message.BattleImage>()
    private val imagesAtPeer = HashMap<String, Set<String>>()
    private var starting = false

    val mode: GameMode get() = engine.snapshot.settings.mode

    init {
        engine.onChange = ::onSnapshot
        nearby?.startAdvertising(currentAd())
        scope.launch {
            while (isActive) {
                engine.tick(hostNow())
                delay(TICK_MS)
            }
        }
    }

    override fun hostNow(): Long = SystemClock.elapsedRealtime()

    private fun currentAd() = NearbyConfig.GameAd(inviteCode, engine.snapshot.player(myId)?.name ?: "Host", _inviting.value)

    private fun onSnapshot(s: GameSnapshot) {
        _snapshot.value = s
        tvServer?.snapshot = s
        nearby?.broadcast(Message.State(s))
        if (s.phase == Phase.LOBBY && mode != GameMode.SINGLE && !starting && engine.allReady(minPlayers = 2)) {
            // Alle bereit: Spiel startet automatisch (außerhalb des laufenden Engine-Updates).
            scope.launch { startGame() }
        }
    }

    // ------------------------------------------------------------------ Host-Aktionen

    /** Spieler in der Nähe mit aktiviertem Einladungsempfang erhalten eine Benachrichtigung. */
    fun setInviting(enabled: Boolean) {
        _inviting.value = enabled
        if (engine.snapshot.phase == Phase.LOBBY) nearby?.startAdvertising(currentAd())
    }

    fun updateSettings(settings: GameSettings) = engine.updateSettings(settings.copy(mode = mode))

    fun setTvMode(enabled: Boolean) {
        if (enabled && tvServer == null) {
            val server = TvServer(appContext, images, ::hostNow).also { it.snapshot = engine.snapshot }
            val url = server.start()
            if (url == null) {
                _notice.value = "TV-Ansicht konnte nicht gestartet werden. Ist das Handy im WLAN?"
                return
            }
            tvServer = server
            _tvUrl.value = url
        } else if (!enabled) {
            tvServer?.stop()
            tvServer = null
            _tvUrl.value = null
        }
        engine.setTvMode(enabled)
    }

    fun startGame() {
        if (starting || engine.snapshot.phase != Phase.LOBBY) return
        starting = true
        engine.beginPreparing()
        nearby?.stopAdvertising()
        scope.launch {
            try {
                val questions = if (mode == GameMode.BATTLE) {
                    engine.battleQuestions()
                } else {
                    engine.setPreparingProgress(0f, "Promi-Bilder werden geladen…")
                    WikipediaImages.loadQuestions(engine.snapshot.settings.personCount, images) { p ->
                        scope.launch { engine.setPreparingProgress(p * 0.7f, "Promi-Bilder werden geladen…") }
                    }
                }
                distribute(questions)
                engine.startCountdown(questions, hostNow())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _notice.value = e.message ?: "Spiel konnte nicht gestartet werden"
                engine.backToLobby()
                nearby?.startAdvertising(currentAd())
            } finally {
                starting = false
            }
        }
    }

    /** Überträgt alle Bilder an alle Mitspieler und wartet, bis sie angekommen sind. */
    private suspend fun distribute(questions: List<Question>) {
        val host = nearby ?: return
        if (host.peers.isEmpty()) return
        val ids = questions.map { it.imageId }.toSet()
        engine.setPreparingProgress(0.7f, "Bilder werden an die Mitspieler übertragen…")
        for (id in ids) {
            val bytes = images.bytes(id) ?: continue
            ImageAssembler.split(id, bytes).forEach(host::broadcast)
        }
        withTimeoutOrNull(DISTRIBUTE_TIMEOUT_MS) {
            while (true) {
                val peers = connectedPeerIds()
                val done = peers.count { imagesAtPeer[it]?.containsAll(ids) == true }
                engine.setPreparingProgress(0.7f + 0.3f * done / maxOf(1, peers.size), "Bilder werden an die Mitspieler übertragen…")
                if (done == peers.size) break
                delay(100)
            }
        }
    }

    private fun connectedPeerIds(): List<String> =
        engine.snapshot.players.filter { it.connected && it.id != myId }.map { it.id }

    fun playAgain() {
        if (engine.snapshot.phase != Phase.FINISHED) return
        images.clear()
        imagesAtPeer.clear()
        pendingBattleMeta.clear()
        _battle.value = emptyList()
        engine.backToLobby()
        nearby?.startAdvertising(currentAd())
    }

    // ------------------------------------------------------------------ Spieleraktionen (Host spielt mit)

    override fun setName(name: String) {
        engine.setName(myId, name)
        if (engine.snapshot.phase == Phase.LOBBY) nearby?.startAdvertising(currentAd())
    }

    override fun setReady(ready: Boolean) = engine.setReady(myId, ready)

    override fun setBattleEntries(entries: List<BattleEntry>) {
        engine.clearBattleImages(myId)
        entries.forEach {
            images.put(it.id, it.jpeg)
            engine.addBattleImage(myId, Question(it.id, it.firstName, it.lastName))
        }
        _battle.value = entries
    }

    override fun buzz() {
        val now = hostNow()
        engine.buzz(myId, now, now)
    }

    override fun answer(text: String) {
        engine.answer(myId, text, hostNow())
    }

    override fun consumeNotice() {
        _notice.value = null
    }

    override fun close() {
        nearby?.broadcast(Message.Bye("Der Host hat das Spiel beendet"))
        scope.cancel()
        tvServer?.stop()
        nearby?.stop()
    }

    // ------------------------------------------------------------------ Nearby

    override fun onPeerConnected(endpointId: String) {
        assemblers[endpointId] = ImageAssembler()
    }

    override fun onPeerMessage(endpointId: String, message: Message) {
        val now = hostNow()
        if (message is Message.Ping) {
            nearby?.send(endpointId, Message.Pong(message.sentLocal, now))
            return
        }
        if (message is Message.Hello) {
            onHello(endpointId, message)
            return
        }
        val playerId = endpointToPlayer[endpointId] ?: return
        when (message) {
            is Message.SetName -> engine.setName(playerId, message.name)
            is Message.Ready -> engine.setReady(playerId, message.ready)
            is Message.Buzz -> engine.buzz(playerId, message.hostTime, now)
            is Message.Answer -> engine.answer(playerId, message.text, now)
            is Message.BattleClear -> engine.clearBattleImages(playerId)
            is Message.BattleImage -> pendingBattleMeta[message.imageId] = message
            is Message.ImagesReady -> imagesAtPeer[playerId] = message.imageIds.toSet()
            is Message.ImageChunk -> {
                val bytes = assemblers.getOrPut(endpointId) { ImageAssembler() }.add(message) ?: return
                images.put(message.imageId, bytes)
                val meta = pendingBattleMeta.remove(message.imageId) ?: return
                engine.addBattleImage(playerId, Question(meta.imageId, meta.firstName, meta.lastName))
            }
            else -> Unit
        }
    }

    private fun onHello(endpointId: String, hello: Message.Hello) {
        val host = nearby ?: return
        val known = engine.snapshot.player(hello.playerId) != null
        if (!known && engine.snapshot.phase != Phase.LOBBY) {
            host.send(endpointId, Message.Bye("Das Spiel läuft bereits"))
            host.disconnect(endpointId)
            return
        }
        endpointToPlayer[endpointId] = hello.playerId
        engine.addOrUpdatePlayer(hello.playerId, hello.name.ifBlank { "Spieler" })
        host.send(endpointId, Message.Welcome(engine.snapshot.player(myId)?.name ?: "Host"))
        host.send(endpointId, Message.State(engine.snapshot))
    }

    override fun onPeerDisconnected(endpointId: String) {
        assemblers.remove(endpointId)
        val playerId = endpointToPlayer.remove(endpointId) ?: return
        imagesAtPeer.remove(playerId)
        engine.setConnected(playerId, false, hostNow())
    }

    override fun onError(text: String) {
        _notice.value = text
    }

    companion object {
        private const val TICK_MS = 20L
        private const val DISTRIBUTE_TIMEOUT_MS = 60_000L

        fun newImageId(): String = UUID.randomUUID().toString().take(12)
    }
}

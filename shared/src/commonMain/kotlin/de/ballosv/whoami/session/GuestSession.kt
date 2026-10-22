package de.ballosv.whoami.session

import de.ballosv.whoami.content.ImageStore
import de.ballosv.whoami.core.ClockSync
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.core.ImageAssembler
import de.ballosv.whoami.core.Message
import de.ballosv.whoami.core.Phase
import de.ballosv.whoami.net.CompositeGuest
import de.ballosv.whoami.net.FoundGame
import de.ballosv.whoami.net.GuestTransport
import de.ballosv.whoami.net.createGuestTransports
import de.ballosv.whoami.platform.monotonicMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Mitspieler: zeigt den vom Host verteilten Zustand an und schickt Eingaben an den Host. */
class GuestSession(
    override val myId: String,
    private var myName: String,
) : GameSession, GuestTransport.Listener {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val nearby: GuestTransport = CompositeGuest(createGuestTransports(this))
    private val clock = ClockSync()
    private val assembler = ImageAssembler()
    private var pingJob: Job? = null

    override val isHost = false
    override val images = ImageStore()

    private val _snapshot = MutableStateFlow<GameSnapshot?>(null)
    override val snapshot: StateFlow<GameSnapshot?> = _snapshot
    private val _status = MutableStateFlow<SessionStatus>(SessionStatus.Searching)
    override val status: StateFlow<SessionStatus> = _status
    private val _battle = MutableStateFlow<List<BattleEntry>>(emptyList())
    override val battleEntries: StateFlow<List<BattleEntry>> = _battle
    private val _notice = MutableStateFlow<String?>(null)
    override val notice: StateFlow<String?> = _notice

    val games: StateFlow<List<FoundGame>> = nearby.games

    private val _hostName = MutableStateFlow("")
    val hostName: StateFlow<String> = _hostName

    fun startSearching() {
        _status.value = SessionStatus.Searching
        nearby.startDiscovery { _notice.value = it }
    }

    fun connect(game: FoundGame) {
        if (_status.value != SessionStatus.Searching) return
        _status.value = SessionStatus.Connecting
        _hostName.value = game.ad.hostName
        nearby.connect(game, myName)
    }

    override fun hostNow(): Long = clock.toHostTime(monotonicMillis())

    override fun onConnected() {
        _status.value = SessionStatus.Connected
        nearby.send(Message.Hello(myId, myName, Message.PROTOCOL_VERSION))
        pingJob?.cancel()
        pingJob = scope.launch {
            var count = 0
            while (isActive) {
                nearby.send(Message.Ping(monotonicMillis()))
                // Anfangs schnell messen, danach regelmäßig nachjustieren.
                delay(if (count++ < 10) 150 else 2_000)
            }
        }
    }

    override fun onMessage(message: Message) {
        when (message) {
            is Message.Pong -> clock.addSample(message.sentLocal, message.hostTime, monotonicMillis())
            is Message.Welcome -> _hostName.value = message.hostName
            is Message.State -> {
                val old = _snapshot.value
                if (old != null && message.snapshot.version <= old.version) return
                if (old != null && old.phase != Phase.LOBBY && message.snapshot.phase == Phase.LOBBY) {
                    // Neue Runde: alte Bilder verwerfen.
                    images.clear()
                    _battle.value = emptyList()
                }
                _snapshot.value = message.snapshot
            }
            is Message.ImageChunk -> {
                val bytes = assembler.add(message) ?: return
                images.put(message.imageId, bytes)
                nearby.send(Message.ImagesReady(images.ids.value.toList()))
            }
            is Message.Bye -> {
                _status.value = SessionStatus.Ended(message.reason)
                nearby.stop()
            }
            else -> Unit
        }
    }

    override fun onDisconnected(reason: String) {
        pingJob?.cancel()
        if (_status.value !is SessionStatus.Ended) _status.value = SessionStatus.Ended(reason)
    }

    override fun setName(name: String) {
        myName = name
        nearby.send(Message.SetName(name))
    }

    override fun setReady(ready: Boolean) = nearby.send(Message.Ready(ready))

    override fun setBattleEntries(entries: List<BattleEntry>) {
        nearby.send(Message.BattleClear)
        entries.forEach { entry ->
            images.put(entry.id, entry.jpeg)
            nearby.send(Message.BattleImage(entry.id, entry.firstName, entry.lastName))
            ImageAssembler.split(entry.id, entry.jpeg).forEach(nearby::send)
        }
        _battle.value = entries
    }

    override fun buzz() = nearby.send(Message.Buzz(hostNow()))

    override fun answer(text: String) = nearby.send(Message.Answer(text))

    override fun setPassed(passed: Boolean) = nearby.send(Message.Pass(passed))

    override fun consumeNotice() {
        _notice.value = null
    }

    override fun close() {
        scope.cancel()
        nearby.stop()
    }
}

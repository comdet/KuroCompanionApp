package com.carcompanion.companion.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.carcompanion.companion.data.CoreTraits
import com.carcompanion.companion.data.ObdMessage
import com.carcompanion.companion.data.DriverProfile
import com.carcompanion.companion.data.DrivingStyle
import com.carcompanion.companion.data.repo.AppConfig
import com.carcompanion.companion.data.repo.ConfigRepository
import com.carcompanion.companion.data.repo.DriverProfileRepository
import com.carcompanion.companion.data.repo.SoulPersistence
import com.carcompanion.companion.data.repo.AssetLoader
import com.carcompanion.companion.data.repo.SoulRepository
import com.carcompanion.companion.domain.BroadcastEngine
import com.carcompanion.companion.domain.CharacterEngine
import com.carcompanion.companion.domain.Emotion
import com.carcompanion.companion.domain.Hypothesis
import com.carcompanion.companion.domain.HypothesisEngine
import com.carcompanion.companion.domain.LikesContext
import com.carcompanion.companion.domain.MilestoneAnalyzer
import com.carcompanion.companion.domain.Moodlet
import com.carcompanion.companion.domain.Quirk
import com.carcompanion.companion.domain.SemanticEvent
import com.carcompanion.companion.domain.SoulSnapshot
import com.carcompanion.companion.domain.SpontaneousTicker
import com.carcompanion.companion.domain.StyleAnalyzer
import com.carcompanion.companion.domain.TripLifecycleAnalyzer
import com.carcompanion.companion.domain.UnderstandingEngine
import com.carcompanion.companion.network.CompanionCcpClient
import com.carcompanion.companion.network.ObdConnection
import com.carcompanion.companion.network.ObdServer
import com.carcompanion.companion.network.RobotScanner
import com.carcompanion.companion.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class CarCompanionService : LifecycleService() {

    companion object {
        // Bangkok — used by the weather analyzer only when the location
        // provider hasn't returned a fix yet (no permission / no provider).
        private const val FALLBACK_LAT = 13.7563
        private const val FALLBACK_LON = 100.5018

        // Ping cadence used by the wake-up poller during PRE_DRIVE — short
        // enough that the wake reaction lands within a couple seconds of the
        // ESP32 finishing its boot+WiFi handshake.
        private const val WAKE_PING_INTERVAL_MS = 5_000L
        // After this much PRE_DRIVE time with no successful ping, give up:
        // the robot is unreachable for this ignition and any wake we emit
        // would be silent anyway.
        private const val WAKE_PING_TIMEOUT_MS = 90_000L

        // TIME_TICK cadence while DRIVING. Bumps curiosity periodically so
        // long uneventful trips still produce subtle internal change.
        private const val TIME_TICK_INTERVAL_MS = 15 * 60_000L

        private val _state = MutableStateFlow(ServiceState.Stopped)
        val state: StateFlow<ServiceState> = _state.asStateFlow()

        // Shared singletons so the UI can observe without binding.
        // Initialised in onCreate.
        private val _obdConnection = MutableStateFlow(ObdConnection.IDLE)
        val obdConnection: StateFlow<ObdConnection> = _obdConnection.asStateFlow()

        private val _obdStatus = MutableStateFlow<ObdMessage.Status?>(null)
        val obdStatus: StateFlow<ObdMessage.Status?> = _obdStatus.asStateFlow()

        private val _obdHello = MutableStateFlow<ObdMessage.Hello?>(null)
        val obdHello: StateFlow<ObdMessage.Hello?> = _obdHello.asStateFlow()

        @Volatile
        private var obdAcksRef: SharedFlow<ObdMessage.Ack>? = null
        val obdAcks: SharedFlow<ObdMessage.Ack>? get() = obdAcksRef

        // Companion (ESP32-S3) connection settings + last call result.
        private val _robotHost = MutableStateFlow("192.168.4.1")
        val robotHost: StateFlow<String> = _robotHost.asStateFlow()

        private val _lastCcpResponse = MutableStateFlow<CcpResult?>(null)
        val lastCcpResponse: StateFlow<CcpResult?> = _lastCcpResponse.asStateFlow()

        // Robot-scan state used by the Config sheet.
        private val _scanState = MutableStateFlow<ScanState>(ScanState.Idle)
        val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

        private val robotScanner = RobotScanner()

        // Scanner has to work *before* the service is running — the user opens
        // Config to find a robot in the first place, then starts the service.
        // So we keep an always-on application-scoped CoroutineScope here that
        // doesn't depend on serviceScope at all.
        private val scanScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()
        )

        fun startRobotScan() {
            if (_scanState.value is ScanState.Scanning) return
            scanScope.launch {
                _scanState.value = ScanState.Scanning
                val found = runCatching { robotScanner.scan() }
                    .onFailure { _scanState.value = ScanState.Err(it.message ?: it.javaClass.simpleName) }
                    .getOrNull() ?: return@launch
                _scanState.value = ScanState.Done(found)
            }
        }

        fun clearScanState() {
            _scanState.value = ScanState.Idle
        }

        fun setRobotHost(host: String) {
            _robotHost.value = host.trim()
        }

        @Volatile private var ccpClientRef: CompanionCcpClient? = null
        @Volatile private var serviceScope: CoroutineScope? = null

        // RobotPresence singleton ref so static CCP helpers can record a
        // successful round-trip without having to thread the service
        // instance through every call site.
        @Volatile internal var presenceRef:
            com.carcompanion.companion.domain.RobotPresence? = null

        // Mirror of [RobotPresence.isOnline] published for the UI (Soul
        // Debug + Cfg badge). Updated by an in-service collector so the
        // companion object's value is consistent with the running instance.
        private val _robotOnline = MutableStateFlow(false)
        val robotOnline: StateFlow<Boolean> = _robotOnline.asStateFlow()

        // Mirror of [TripPhaseAnalyzer.phase] for the same reason.
        private val _tripPhase = MutableStateFlow(
            com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase.IDLE_NO_TRIP
        )
        val tripPhase: StateFlow<com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase> =
            _tripPhase.asStateFlow()

        // Robot live state (used by overlay + main UI).
        private val _overlayVolume = MutableStateFlow(60)
        val overlayVolume: StateFlow<Int> = _overlayVolume.asStateFlow()

        private val _overlayMood = MutableStateFlow("🙂")
        val overlayMood: StateFlow<String> = _overlayMood.asStateFlow()

        // ESP32 robot status snapshot — refreshed on ping / status polling.
        // Read by Cfg panel to render battery, RSSI, current vol/bri/idle2.
        private val _robotStatus = MutableStateFlow<com.carcompanion.companion.network.RobotStatus?>(null)
        val robotStatus: StateFlow<com.carcompanion.companion.network.RobotStatus?> =
            _robotStatus.asStateFlow()

        // Phone's current coarse location. Null until permission is granted
        // AND a provider returns a fix; the weather analyzer falls back to a
        // Bangkok default in the meantime.
        private val _locationLatLon =
            MutableStateFlow<com.carcompanion.companion.data.repo.LatLon?>(null)
        val locationLatLon: StateFlow<com.carcompanion.companion.data.repo.LatLon?> =
            _locationLatLon.asStateFlow()

        // Snapshot of the most recent environmental analyzer outputs. Re-emitted
        // from the service's tick loops; useful for the Soul Debug dashboard
        // to show what context the soul is currently reacting to.
        private val _envSnapshot = MutableStateFlow(EnvSnapshot())
        val envSnapshot: StateFlow<EnvSnapshot> = _envSnapshot.asStateFlow()

        // Live accelerometer magnitude (throttled to ~4 Hz) + last-bump info.
        // Used by Soul Debug to verify the IMU is alive and tune threshold.
        private val _imuSnapshot = MutableStateFlow(ImuSnapshot())
        val imuSnapshot: StateFlow<ImuSnapshot> = _imuSnapshot.asStateFlow()

        // Rolling buffer of navigation notifications captured by
        // MapsNotificationListener. Cap at 20 so the debug card doesn't
        // grow unbounded while parking with Maps running.
        private val _navNotifications = MutableStateFlow<List<NavNotification>>(emptyList())
        val navNotifications: StateFlow<List<NavNotification>> = _navNotifications.asStateFlow()

        /** Called from MapsNotificationListener — service lifecycle independent. */
        fun publishNavNotification(n: NavNotification) {
            _navNotifications.value = (_navNotifications.value + n).takeLast(20)
        }

        private val _soulSnapshot = MutableStateFlow<SoulSnapshot?>(null)
        val soulSnapshot: StateFlow<SoulSnapshot?> = _soulSnapshot.asStateFlow()

        private val _emotion = MutableStateFlow(Emotion.NEUTRAL)
        val emotion: StateFlow<Emotion> = _emotion.asStateFlow()

        private val _hypothesis = MutableStateFlow(Hypothesis.RELAXED)
        val hypothesis: StateFlow<Hypothesis> = _hypothesis.asStateFlow()

        private val _activeMoodlets = MutableStateFlow<List<Moodlet>>(emptyList())
        val activeMoodlets: StateFlow<List<Moodlet>> = _activeMoodlets.asStateFlow()

        private val _activeQuirks = MutableStateFlow<Set<Quirk>>(emptySet())
        val activeQuirks: StateFlow<Set<Quirk>> = _activeQuirks.asStateFlow()

        // Bundled GIF / WAV filenames — surfaced to the manual-test dropdowns.
        private val _bundledGifs = MutableStateFlow<List<String>>(emptyList())
        val bundledGifs: StateFlow<List<String>> = _bundledGifs.asStateFlow()
        private val _bundledWavs = MutableStateFlow<List<String>>(emptyList())
        val bundledWavs: StateFlow<List<String>> = _bundledWavs.asStateFlow()

        @Volatile private var broadcastRef: com.carcompanion.companion.domain.BroadcastEngine? = null

        /** Manual "Test GIF" — fire-and-forget through BroadcastEngine. */
        fun testSendGif(name: String) {
            broadcastRef?.manualTest(gifName = name, wavName = null)
        }
        /** Manual "Test WAV" — fire-and-forget through BroadcastEngine. */
        fun testSendWav(name: String) {
            broadcastRef?.manualTest(gifName = null, wavName = name)
        }

        private val _driverProfile = MutableStateFlow(DriverProfile())
        val driverProfile: StateFlow<DriverProfile> = _driverProfile.asStateFlow()

        // Personality (constant per session) — exposed so SoulDebugActivity
        // can draw the radar without having to bind to the engine instance.
        private val _personalityTraits = MutableStateFlow(CoreTraits())
        val personalityTraits: StateFlow<CoreTraits> = _personalityTraits.asStateFlow()

        // History buffers for the debug dashboard.
        private val _stateHistory = MutableStateFlow<List<StateHistoryPoint>>(emptyList())
        val stateHistory: StateFlow<List<StateHistoryPoint>> = _stateHistory.asStateFlow()

        private val _reactionHistory = MutableStateFlow<List<ReactionLogEntry>>(emptyList())
        val reactionHistory: StateFlow<List<ReactionLogEntry>> = _reactionHistory.asStateFlow()

        internal fun appendStateHistory(point: StateHistoryPoint) {
            val updated = (_stateHistory.value + point).takeLast(300)
            _stateHistory.value = updated
        }

        internal fun appendReactionHistory(entry: ReactionLogEntry) {
            val updated = (_reactionHistory.value + entry).takeLast(50)
            _reactionHistory.value = updated
        }

        private val _debugLog = MutableStateFlow<List<String>>(emptyList())
        val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

        internal fun appendLog(line: String) {
            val tag = String.format("%tT", java.util.Date())
            val updated = (_debugLog.value + "[$tag] $line").takeLast(50)
            _debugLog.value = updated
        }

        /** Update the cached overlay volume display (Android stream % mirror). */
        fun setVolumeLocal(level: Int) {
            _overlayVolume.value = level.coerceIn(0, 100)
        }

        /** Apply an emoji to the overlay mood badge. Layer 3 will drive this later. */
        fun setMood(emoji: String) {
            _overlayMood.value = emoji
        }

        /**
         * Push robot (ESP32) PA volume via CCP. Driven by the Cfg-panel slider
         * in [com.carcompanion.companion.ui.main.MainActivity] — the floating
         * overlay does NOT call this (overlay controls phone audio only).
         */
        fun sendVolumeAsync(level: Int) {
            val clamped = level.coerceIn(0, 100)
            val client = ccpClientRef ?: return
            val scope = serviceScope ?: return
            val host = _robotHost.value
            scope.launch(Dispatchers.IO) {
                runCatching { client.setVolume(host, clamped) }
                    .onSuccess {
                        presenceRef?.markSeen()
                        _lastCcpResponse.value = CcpResult.Ok("robot vol=$clamped → $it")
                    }
                    .onFailure { _lastCcpResponse.value = CcpResult.Err(it.message ?: it.javaClass.simpleName) }
            }
        }

        @Volatile internal var audioManagerRef: AudioManager? = null

        /**
         * Float-overlay volume controls govern **Android** STREAM_MUSIC only
         * (the phone/HUD's own speaker). The ESP32 robot's PA has its own
         * volume slider in the in-app Cfg panel — that's a separate output
         * tied to CCP, kept deliberately out of the overlay so the user can
         * adjust the phone media volume from any app without nudging the
         * robot.
         */

        /** Sync `_overlayVolume` from the current Android stream % (0..100). */
        private fun refreshOverlayFromStream() {
            val am = audioManagerRef ?: return
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            _overlayVolume.value = (cur * 100 / max).coerceIn(0, 100)
        }

        /**
         * Step volume one notch.
         *
         * Why not `adjustStreamVolume(STREAM_MUSIC, ADJUST_RAISE)`? On Chinese
         * head-unit ROMs (SYU / XYAuto / FYT class, identified by the
         * presence of `com.syu.ms`), STREAM_MUSIC is force-held at max
         * because the actual amp lives outside Android — ROM listens for
         * `KEYCODE_VOLUME_UP/DOWN/MUTE` at the InputDispatcher level and
         * forwards them to the amp module. `adjustStreamVolume` never gets
         * there because the stream is already at max.
         *
         * `dispatchMediaKeyEvent` is the public-API equivalent that re-enters
         * the same system path as `adb shell input keyevent` (verified on
         * the target HUD — amp value 5 → 8 on three consecutive `VOLUME_UP`
         * dispatches), so it works on the SYU ROM AND falls back to normal
         * stream-volume behaviour on stock Android.
         */
        fun bumpVolume(delta: Int) {
            val am = audioManagerRef ?: return
            val keycode = if (delta >= 0) KeyEvent.KEYCODE_VOLUME_UP
                          else KeyEvent.KEYCODE_VOLUME_DOWN
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
            refreshOverlayFromStream()
        }

        /**
         * Toggle mute. Same reasoning as [bumpVolume] — on SYU ROMs the
         * amp module is what mutes; on stock Android the system handles
         * KEYCODE_VOLUME_MUTE the conventional way.
         */
        fun toggleMute() {
            val am = audioManagerRef ?: return
            am.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)
            )
            am.dispatchMediaKeyEvent(
                KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_MUTE)
            )
            refreshOverlayFromStream()
        }

        fun pingRobotAsync() {
            val client = ccpClientRef ?: return
            val scope = serviceScope ?: return
            val host = _robotHost.value
            scope.launch(Dispatchers.IO) {
                runCatching { client.ping(host) }
                    .onSuccess { raw ->
                        presenceRef?.markSeen()
                        _lastCcpResponse.value = CcpResult.Ok("ping → ${raw.take(60)}")
                        com.carcompanion.companion.network.RobotStatus.parse(raw)?.let {
                            _robotStatus.value = it
                        }
                    }
                    .onFailure { _lastCcpResponse.value = CcpResult.Err(it.message ?: it.javaClass.simpleName) }
            }
        }

        // ── ESP32 robot config controls (NVS-persisted, drive Cfg panel) ───
        //
        // All return immediately; the result lands in [lastCcpResponse] and
        // the visible value updates after the next [pingRobotAsync] refreshes
        // [robotStatus]. Callers don't need to await — fire and forget.

        private inline fun ccpAsync(
            label: String,
            crossinline block: suspend (CompanionCcpClient, String) -> String,
        ) {
            val client = ccpClientRef ?: return
            val scope = serviceScope ?: return
            val host = _robotHost.value
            scope.launch(Dispatchers.IO) {
                runCatching { block(client, host) }
                    .onSuccess {
                        presenceRef?.markSeen()
                        _lastCcpResponse.value = CcpResult.Ok("$label → ${it.take(40)}")
                    }
                    .onFailure { _lastCcpResponse.value = CcpResult.Err("$label: ${it.message ?: it.javaClass.simpleName}") }
            }
        }

        /** Volatile-until-reboot brightness (preview-style). */
        fun sendBrightnessAsync(level: Int) =
            ccpAsync("bri=$level") { c, h -> c.setBrightness(h, level) }

        /** Persisted power-on default volume (also applies live). */
        fun sendDefaultVolumeAsync(level: Int) =
            ccpAsync("def_vol=$level") { c, h -> c.setDefaultVolume(h, level) }

        /** Persisted power-on default brightness (also applies live). */
        fun sendDefaultBrightnessAsync(level: Int) =
            ccpAsync("def_bri=$level") { c, h -> c.setDefaultBrightness(h, level) }

        /** Probability % the firmware swaps idles/ random GIF between idle loops. */
        fun sendIdle2ChanceAsync(percent: Int) =
            ccpAsync("idle2=$percent%") { c, h -> c.setIdle2Chance(h, percent) }

        /** Save WiFi creds to NVS. Pass `reboot=true` so STA reconnects. */
        fun sendWifiConfigAsync(ssid: String, pass: String, reboot: Boolean = true) =
            ccpAsync("wifi='$ssid'${if (reboot) "+reboot" else ""}") { c, h ->
                c.setWifiConfig(h, ssid, pass, andReboot = reboot)
            }

        /** Manual reboot button (Cfg). */
        fun rebootRobotAsync() =
            ccpAsync("reboot") { c, h -> c.reboot(h) }
    }

    private lateinit var obdServer: ObdServer
    private lateinit var understanding: UnderstandingEngine
    private lateinit var character: CharacterEngine
    private lateinit var broadcast: BroadcastEngine
    private lateinit var hypothesisEngine: HypothesisEngine
    private lateinit var spontaneousTicker: SpontaneousTicker
    private lateinit var styleAnalyzer: StyleAnalyzer
    private lateinit var tripAnalyzer: TripLifecycleAnalyzer
    private lateinit var milestoneAnalyzer: MilestoneAnalyzer
    private lateinit var likesContext: LikesContext
    private lateinit var timeOfDay: com.carcompanion.companion.domain.TimeOfDayAnalyzer
    private lateinit var weather: com.carcompanion.companion.domain.WeatherAnalyzer
    private var imuAnalyzer: com.carcompanion.companion.domain.ImuAnalyzer? = null
    private lateinit var locationProvider: com.carcompanion.companion.data.repo.LocationProvider
    private lateinit var analyzerState: com.carcompanion.companion.data.repo.AnalyzerStateRepository
    private val navParser = com.carcompanion.companion.domain.NavNotificationParser()
    @Volatile private var lastSeenNavAt: Long = 0L
    private lateinit var robotPresence: com.carcompanion.companion.domain.RobotPresence
    private lateinit var tripPhaseAnalyzer: com.carcompanion.companion.domain.TripPhaseAnalyzer
    private lateinit var wakeOrchestrator: com.carcompanion.companion.domain.WakeOrchestrator
    private lateinit var reactionPolicy: com.carcompanion.companion.domain.ReactionPolicy
    private lateinit var episodeTracker: com.carcompanion.companion.domain.EpisodeTracker
    private lateinit var patternDetector: com.carcompanion.companion.domain.PatternDetector
    private var musicListener: MusicSessionListener? = null
    @Volatile private var pingPollJob: kotlinx.coroutines.Job? = null
    @Volatile private var timeTickJob: kotlinx.coroutines.Job? = null
    private lateinit var configRepo: ConfigRepository
    private lateinit var soulPersistence: SoulPersistence
    private lateinit var driverRepo: DriverProfileRepository

    @Volatile private var engineStartMs: Long = 0L
    @Volatile private var sessionHarshBrakes: Int = 0
    @Volatile private var sessionSpeedSum: Float = 0f
    @Volatile private var sessionSpeedSamples: Int = 0

    // Latched copies of the most recent OBD-derived environment so the
    // NAV / synthetic-event paths (which don't know the current status)
    // can still feed [tripPhaseAnalyzer] / [episodeTracker] the
    // door/lock/speed/brake/engine state they need.
    @Volatile private var lastSpeedKmh: Int = 0
    @Volatile private var lastDoorOpen: Boolean = false
    @Volatile private var lastLocked: Boolean = false
    @Volatile private var lastBrakePedal: Boolean = false
    @Volatile private var lastEngineOn: Boolean = false

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannel(this)
        startInForeground()
        _state.value = ServiceState.Running

        ccpClientRef = CompanionCcpClient()
        serviceScope = lifecycleScope
        audioManagerRef = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Seed overlay volume from the current Android stream so the UI
        // matches the system at startup.
        audioManagerRef?.let { am ->
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            _overlayVolume.value = (cur * 100 / max).coerceIn(0, 100)
        }
        configRepo = ConfigRepository(applicationContext)
        soulPersistence = SoulPersistence(applicationContext)
        driverRepo = DriverProfileRepository(applicationContext)

        // Soul engines (load JSON definition + logic).
        // Persona name is resolved from DataStore inside the lifecycleScope
        // launch below; until then we build with the default persona so the
        // engine fields are not lateinit-unsafe during super.onCreate side
        // effects.
        val soulRepo = SoulRepository(this)
        understanding = UnderstandingEngine()
        hypothesisEngine = HypothesisEngine()
        spontaneousTicker = SpontaneousTicker()
        styleAnalyzer = StyleAnalyzer()
        tripAnalyzer = TripLifecycleAnalyzer()
        milestoneAnalyzer = MilestoneAnalyzer()
        likesContext = LikesContext()
        // Wake-up stack — presence tracks if the ESP32 is currently answering
        // CCP traffic; tripPhase tracks pre/driving/post; wake combines them
        // so WAKE_ROBOT only fires when both gates are green (robot online ∧
        // car not yet rolling).
        robotPresence = com.carcompanion.companion.domain.RobotPresence()
        tripPhaseAnalyzer = com.carcompanion.companion.domain.TripPhaseAnalyzer()
        wakeOrchestrator = com.carcompanion.companion.domain.WakeOrchestrator(
            presence = robotPresence,
            tripPhase = tripPhaseAnalyzer,
        )
        // Reaction policy gates broadcast.onReaction so high-priority
        // reactions can suppress chatty atomics, and a per-minute budget
        // (adjusted by stress/bond) keeps the soul from talking over
        // itself. See domain/ReactionPolicy.kt.
        reactionPolicy = com.carcompanion.companion.domain.ReactionPolicy(
            budgetPerMinute = {
                val snap = _soulSnapshot.value
                if (snap != null)
                    com.carcompanion.companion.domain.ReactionPolicy.budgetFor(snap)
                else
                    com.carcompanion.companion.domain.ReactionPolicy.DEFAULT_BUDGET_PER_MINUTE
            },
        )
        // EpisodeTracker turns sustained-state streams (rough road, brake-held,
        // stuck-in-traffic) into START/TICK/END events that fire at P3 and
        // thus suppress atomic chatter under them via [reactionPolicy].
        episodeTracker = com.carcompanion.companion.domain.EpisodeTracker()
        // PatternDetector turns short atomic sequences into composite
        // events (UNSAFE_OVERTAKE = throttle spike + no turn signal +
        // speed gain; EMERGENCY_BRAKE = severe brake without aggressive
        // precursor). EMERGENCY_BRAKE supersedes the raw CAN_HARSH_BRAKE
        // — the detector reports it via `suppressed`, and we filter the
        // raw event stream before downstream analyzers see it.
        patternDetector = com.carcompanion.companion.domain.PatternDetector()
        presenceRef = robotPresence
        // Seed analyzer state from disk so transition-emitters (time bucket,
        // weather bucket) don't fire duplicates after a service restart and
        // do fire any boundary they crossed while the service was offline.
        analyzerState = com.carcompanion.companion.data.repo.AnalyzerStateRepository(applicationContext)
        val seed = kotlinx.coroutines.runBlocking { runCatching { analyzerState.load() }.getOrNull() }
        timeOfDay = com.carcompanion.companion.domain.TimeOfDayAnalyzer(
            initialBucket = seed?.timeBucket,
        )
        weather = com.carcompanion.companion.domain.WeatherAnalyzer(
            initialWeatherBucket = seed?.weatherBucket,
            initialTempBucket    = seed?.tempBucket,
        )
        locationProvider = com.carcompanion.companion.data.repo.LocationProvider(applicationContext)
        // Push the location StateFlow up so the Cfg panel can display it.
        _locationLatLon.value = null
        // Mirror StateFlow regardless of when start() succeeds.
        lifecycleScope.launch {
            locationProvider.current.collect { _locationLatLon.value = it }
        }
        // First attempt now; if it fails (no permission, provider disabled),
        // the retry loop below will re-attempt after the user grants via UI
        // or enables location in Settings.
        if (locationProvider.start()) {
            appendLog("Location tracking started (coarse)")
        } else {
            appendLog("Location: no permission/provider yet — fallback active, will retry")
            lifecycleScope.launch {
                while (isActive) {
                    delay(30_000)
                    if (_locationLatLon.value != null) break
                    if (!locationProvider.hasPermission()) continue
                    if (locationProvider.start()) {
                        appendLog("Location tracking started (delayed)")
                        break
                    }
                }
            }
        }
        val initialPersona = kotlinx.coroutines.runBlocking {
            runCatching { configRepo.current().personaName }.getOrDefault("kuro")
        }
        character = CharacterEngine(
            definition = soulRepo.loadDefinition(initialPersona),
            logic = soulRepo.loadLogic(),
        )
        appendLog("Persona loaded: ${character.definition.persona}")
        val assetLoader = AssetLoader(applicationContext)
        _bundledGifs.value = assetLoader.listGifs()
        _bundledWavs.value = assetLoader.listWavs()
        broadcast = BroadcastEngine(
            assetLoader = assetLoader,
            ccpClient = ccpClientRef!!,
            getHost = { _robotHost.value },
            onMoodEmoji = { _overlayMood.value = it },
            scope = lifecycleScope,
            onSendResult = { appendLog(it) },
            onCcpSuccess = { robotPresence.markSeen() },
        )
        broadcastRef = broadcast

        _activeQuirks.value = character.quirks
        _personalityTraits.value = character.traits

        // Seed snapshot (will be overridden by persisted state below)
        _soulSnapshot.value = character.state.snapshot()
        _emotion.value = character.currentEmotion()
        _overlayMood.value = _emotion.value.emoji

        appendLog("Service started")

        // Config-dependent bring-up: load config → start ObdServer on configured port
        lifecycleScope.launch {
            val initialConfig = configRepo.current()
            _robotHost.value = initialConfig.robotHost

            // Restore driver profile (Tier 5)
            val profile = runCatching { driverRepo.load() }.getOrNull() ?: DriverProfile()
            _driverProfile.value = profile
            appendLog("Driver: ${profile.nickname ?: profile.id} (bond=${profile.bond.toInt()})")

            // Auto-pair: if the saved host is still the factory default
            // (or empty), kick a LAN scan and adopt the first responder.
            // If the host is already user-configured, just verify with a
            // background ping so the debug log records whether it's alive.
            launch { autoPairRobot(initialConfig) }

            // Restore persisted soul (if any)
            val saved = runCatching { soulPersistence.load() }.getOrNull()
            if (saved != null) {
                character.state.restoreAbsolute(
                    valence = saved.valence,
                    arousal = saved.arousal,
                    hunger = saved.hunger,
                    energy = saved.energy,
                    curiosity = saved.curiosity,
                    bond = saved.bond,
                    vitality = saved.vitality,
                    stress = saved.stress,
                )
                appendLog("Soul restored: v=%.2f a=%.2f energy=%.0f".format(saved.valence, saved.arousal, saved.energy))
                publishSoul()
            }

            obdServer = ObdServer(port = initialConfig.obdPort)
            launch { obdServer.run() }
            launch { obdServer.connection.collect { _obdConnection.value = it } }
            launch { obdServer.hello.collect {
                _obdHello.value = it
                if (it != null) appendLog("OBD hello fw=${it.fw} car=${it.car}")
            } }
            launch {
                obdServer.status.filterNotNull().collect { status ->
                    _obdStatus.value = status
                    feedSoul(status)
                }
            }
            obdAcksRef = obdServer.acks
            launch { obdServer.acks.collect { appendLog("OBD ack: ${it.cmd}=${it.ok}") } }

            // Live-update config: when robotHost changes externally, mirror it.
            launch { configRepo.configFlow.collect { cfg -> _robotHost.value = cfg.robotHost } }
        }

        // Long-running decay tick (30s)
        lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                val runtimeSec = if (engineStartMs > 0) {
                    (System.currentTimeMillis() - engineStartMs) / 1000L
                } else 0L
                character.decay(runtimeSec)
                publishSoul()
            }
        }

        // Spontaneous tick — character "thinks out loud" without an external event.
        // Cadence is modulated by both personality (quirks/traits) AND the
        // current emotion zone — HAPPY/EXCITED Kuro chatters more, SAD/ANGRY
        // Kuro gets quieter. See SpontaneousTicker.EMOTION_TALK_BOOST.
        lifecycleScope.launch {
            while (isActive) {
                val delayMs = spontaneousTicker.nextDelayMs(
                    traits = character.traits,
                    quirks = character.quirks,
                    currentEmotion = _emotion.value,
                )
                delay(delayMs)
                val entry = spontaneousTicker.pick(
                    definition = character.definition,
                    hypothesis = _hypothesis.value,
                    zone = _emotion.value,
                )
                if (entry != null) {
                    // Phase gate: when the driver isn't in the car (IDLE_NO_TRIP
                    // or LEFT) Kuro should be asleep, not chattering to an empty
                    // cabin. The spontaneous "idle thought" loop is independent
                    // of OBD, so without this gate the soul would keep talking
                    // to a powered-but-driverless robot all night.
                    val phase = tripPhaseAnalyzer.phase.value
                    val driverPresent = phase != com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase.IDLE_NO_TRIP &&
                        phase != com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase.LEFT
                    if (!driverPresent) {
                        // Silent — no log spam either, this fires often
                        // enough that logging would drown the debug strip.
                        continue
                    }
                    // Synthetic IDLE_<zone> event so the reaction policy
                    // can treat spontaneous as P5 and silently drop it
                    // when a higher-tier reaction just fired.
                    val syntheticName = "IDLE_${_emotion.value.name}"
                    val decision = reactionPolicy.decide(
                        SemanticEvent(name = syntheticName)
                    )
                    if (decision == com.carcompanion.companion.domain.ReactionPolicy.Decision.ALLOW) {
                        appendLog("Spontaneous: ${entry.gif ?: "?"} (${_hypothesis.value.label}/${_emotion.value.name})")
                        broadcast.onSpontaneous(entry, _emotion.value)
                        character.state.adjustCuriosity(-5f)
                        character.state.adjustBond(+0.5f)
                        publishSoul()
                    } else {
                        appendLog("Spontaneous silenced by policy [$decision]")
                    }
                }
            }
        }
        // Periodic soul persistence (60s + on stop)
        lifecycleScope.launch {
            while (isActive) {
                delay(60_000)
                runCatching { soulPersistence.save(character.state.snapshot()) }
            }
        }

        // ── Environmental analyzers ────────────────────────────────────
        // IMU bumps: phone accelerometer → CAN_IMU_BUMP (the same event the
        // soul logic already has reactions for; it was previously
        // unreachable). Threshold tuned conservatively — adjust after road
        // testing.
        imuAnalyzer = com.carcompanion.companion.domain.ImuAnalyzer(
            context = applicationContext,
            onBump = { mag ->
                val sev = com.carcompanion.companion.domain.Severity.forBumpMagnitude(mag)
                // Speed gate — phone IMU is sensitive enough to fire on
                // hand movement. Only count bumps when the car is
                // actually rolling (engine on AND moving). Without this
                // the soul reacts to the driver picking the phone up.
                val isDriving = lastEngineOn && lastSpeedKmh > 5
                if (!isDriving) {
                    // Still update debug snapshot so the IMU page works,
                    // just don't push an event through the soul.
                    _imuSnapshot.value = _imuSnapshot.value.copy(
                        lastBumpMag = mag,
                        lastBumpEpochMs = System.currentTimeMillis(),
                    )
                    return@ImuAnalyzer
                }
                appendLog("IMU bump %.1f m/s² [%s]".format(mag, sev.name))
                processSyntheticEvent(
                    SemanticEvent("CAN_IMU_BUMP", value = mag, severity = sev)
                )
                _imuSnapshot.value = _imuSnapshot.value.copy(
                    lastBumpMag = mag,
                    lastBumpEpochMs = System.currentTimeMillis(),
                )
            },
            onLiveSample = { mag ->
                _imuSnapshot.value = _imuSnapshot.value.copy(liveMag = mag)
            },
        ).also { it.start() }

        // Media-session observation — watches Spotify / YouTube Music / Apple
        // Music / podcast players via MediaSessionManager and emits
        // MUSIC_STARTED / MUSIC_STOPPED / MUSIC_SONG_CHANGED. Piggy-backs on
        // the existing notification-listener permission grant (same toggle
        // unlocks Maps + media sessions). If permission isn't granted yet
        // we retry on the same 30s cadence as LocationProvider.
        musicListener = MusicSessionListener(
            context = applicationContext,
            notificationListenerComponent = ComponentName(
                applicationContext, MapsNotificationListener::class.java
            ),
            onEvent = { ev ->
                appendLog("Music → ${ev.name}${ev.detail?.let { " ($it)" } ?: ""}")
                processSyntheticEvent(ev)
            },
        )
        if (musicListener?.start() == true) {
            appendLog("Music session observation armed")
        } else {
            appendLog("Music: notification-listener perm not granted — retry pending")
            lifecycleScope.launch {
                while (isActive) {
                    delay(30_000)
                    if (musicListener?.isActive == true) break
                    if (musicListener?.start() == true) {
                        appendLog("Music session observation armed (delayed)")
                        break
                    }
                }
            }
        }

        // Time-of-day tick — fires immediately at start (catches transitions
        // crossed while offline), then once per minute. AnalyzerStateRepo
        // persists the bucket so the next service start won't re-emit it.
        lifecycleScope.launch {
            while (isActive) {
                timeOfDay.tick().forEach { e ->
                    processSyntheticEvent(e)
                    runCatching { analyzerState.saveTimeBucket(e.name) }
                }
                publishEnvSnapshot()
                delay(60_000)
            }
        }

        // Nav notification → SemanticEvent pipeline. Watches the rolling buffer
        // populated by MapsNotificationListener and parses any entries that
        // arrived since our last cursor. We use timestamp ordering (not list
        // index) because the buffer is capped at 20 and old entries get
        // rotated out.
        lifecycleScope.launch {
            _navNotifications.collect { list ->
                val freshOnes = list.filter { it.postedAt > lastSeenNavAt }
                if (freshOnes.isEmpty()) return@collect
                freshOnes.forEach { entry ->
                    val events = navParser.consume(entry)
                    if (events.isNotEmpty()) {
                        appendLog("Nav → ${events.joinToString { it.name }}  raw='${entry.title}'")
                    }
                    events.forEach { processSyntheticEvent(it) }
                }
                lastSeenNavAt = freshOnes.maxOf { it.postedAt }
            }
        }

        // Weather poll — every 10 min using the current phone location when
        // permission is granted, falling back to Bangkok if the location
        // provider hasn't returned a fix yet.
        lifecycleScope.launch {
            while (isActive) {
                if (weather.shouldFetch()) {
                    val live = _locationLatLon.value
                    val lat = live?.lat ?: FALLBACK_LAT
                    val lon = live?.lon ?: FALLBACK_LON
                    val src = if (live != null) "live" else "fallback"
                    val events = runCatching { weather.fetch(lat, lon) }.getOrNull().orEmpty()
                    if (events.isNotEmpty()) {
                        appendLog("Weather poll @${"%.3f,%.3f".format(lat, lon)} ($src) → ${events.joinToString { it.name }}")
                    }
                    events.forEach { e ->
                        processSyntheticEvent(e)
                        runCatching {
                            // Names are prefixed WEATHER_* and split into two
                            // streams (condition + temp). Re-route into the
                            // right persisted slot.
                            when {
                                e.name in setOf("WEATHER_HOT", "WEATHER_COLD", "WEATHER_MILD") ->
                                    analyzerState.saveTempBucket(e.name)
                                e.name.startsWith("WEATHER_") ->
                                    analyzerState.saveWeatherBucket(e.name)
                            }
                        }
                    }
                    publishEnvSnapshot()
                }
                delay(com.carcompanion.companion.domain.WeatherAnalyzer.DEFAULT_POLL_MS)
            }
        }

        // ── Wake orchestration + time-tick observers ──────────────────
        // Mirror TripPhase to the companion StateFlow; start/stop the
        // ping poller around PRE_DRIVE and the time-tick around DRIVING.
        lifecycleScope.launch {
            tripPhaseAnalyzer.phase.collect { p ->
                _tripPhase.value = p
                if (p == com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase.PRE_DRIVE) {
                    startWakePingPoller()
                } else {
                    stopWakePingPoller()
                }
                if (p == com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase.DRIVING) {
                    startTimeTicker()
                } else {
                    stopTimeTicker()
                }
            }
        }

        // Mirror RobotPresence to the companion StateFlow; when it flips
        // online during PRE_DRIVE, poke the wake orchestrator so it can
        // emit WAKE_ROBOT even if no OBD event arrived this moment.
        lifecycleScope.launch {
            robotPresence.isOnline.collect { online ->
                _robotOnline.value = online
                if (online) {
                    wakeOrchestrator.poll().forEach { processSyntheticEvent(it) }
                }
            }
        }

        // Staleness sweep — flips presence back to offline when no CCP
        // round-trip has succeeded recently. Runs cheap; the orchestrator
        // ignores already-offline flips.
        lifecycleScope.launch {
            while (isActive) {
                delay(5_000)
                if (robotPresence.tickStaleness()) {
                    appendLog("Robot presence stale → offline")
                }
            }
        }
    }

    /**
     * Ping the robot every 5 s while we're in PRE_DRIVE so [RobotPresence]
     * notices the ESP32 the moment it finishes booting + joining WiFi.
     * Cancelled when phase leaves PRE_DRIVE (managed by the phase
     * collector in onCreate) or after [WAKE_PING_TIMEOUT_MS] of unanswered
     * pings — at that point the robot is unreachable enough that the wake
     * window is effectively missed.
     */
    private fun startWakePingPoller() {
        if (pingPollJob?.isActive == true) return
        val client = ccpClientRef ?: return
        val startedAt = System.currentTimeMillis()
        appendLog("Wake ping poll started (PRE_DRIVE)")
        pingPollJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (System.currentTimeMillis() - startedAt > WAKE_PING_TIMEOUT_MS) {
                    appendLog("Wake ping poll timeout (${WAKE_PING_TIMEOUT_MS / 1000}s)")
                    break
                }
                val host = _robotHost.value
                runCatching { client.ping(host) }
                    .onSuccess { raw ->
                        robotPresence.markSeen()
                        com.carcompanion.companion.network.RobotStatus.parse(raw)?.let {
                            _robotStatus.value = it
                        }
                    }
                // onFailure: silent — the ESP32 is probably still booting.
                delay(WAKE_PING_INTERVAL_MS)
            }
        }
    }

    private fun stopWakePingPoller() {
        pingPollJob?.cancel()
        pingPollJob = null
    }

    /**
     * Emit a synthetic `TIME_TICK` event every [TIME_TICK_INTERVAL_MS]
     * while phase is DRIVING. Adds a small curiosity bump (state delta
     * in EventStateDeltas) so the soul gets a periodic "thinking about
     * something new" nudge during long drives. The event name ends in
     * `_TICK` so ReactionPolicy bypasses it (no budget cost, no
     * suppression trip).
     */
    private fun startTimeTicker() {
        if (timeTickJob?.isActive == true) return
        appendLog("Time ticker started (every ${TIME_TICK_INTERVAL_MS / 60_000} min)")
        timeTickJob = lifecycleScope.launch {
            while (isActive) {
                delay(TIME_TICK_INTERVAL_MS)
                processSyntheticEvent(SemanticEvent(name = "TIME_TICK"))
            }
        }
    }

    private fun stopTimeTicker() {
        timeTickJob?.cancel()
        timeTickJob = null
    }

    /**
     * Run the robot scan on startup if the saved IP is still the factory
     * default — otherwise just verify the saved one with a background ping.
     */
    private suspend fun autoPairRobot(cfg: AppConfig) {
        val needsScan = cfg.robotHost.isBlank() ||
            cfg.robotHost == AppConfig.DEFAULT_ROBOT_HOST
        if (needsScan) {
            appendLog("Auto-pair: scanning LAN…")
            startRobotScan()
            val terminal = scanState.first { it is ScanState.Done || it is ScanState.Err }
            when (terminal) {
                is ScanState.Done -> {
                    val pick = terminal.found.firstOrNull()
                    if (pick != null) {
                        configRepo.update { it.copy(robotHost = pick.ip) }
                        _robotHost.value = pick.ip
                        appendLog("Auto-paired: ${pick.ip}")
                    } else {
                        appendLog("Auto-pair: no CCP responder on LAN")
                    }
                }
                is ScanState.Err -> appendLog("Auto-pair failed: ${terminal.message}")
                else -> Unit
            }
        } else {
            val host = cfg.robotHost
            val client = ccpClientRef ?: return
            val pingResult = runCatching { client.ping(host) }
            val resp = pingResult.getOrNull()
            if (pingResult.isSuccess && resp != null && resp.startsWith("CP ")) {
                robotPresence.markSeen()
                appendLog("Robot $host alive (cached config)")
            } else {
                appendLog("Robot $host not responding; re-scan from Cfg if needed")
            }
        }
    }

    /**
     * Run a batch of semantic events through the soul pipeline — single
     * source of truth for both OBD-driven and synthetic (IMU/weather/time)
     * events. Side-effect gating is by event name, so injecting a synthetic
     * `TIME_LATE_NIGHT` will not accidentally bump the engine-runtime clock.
     *
     * Returns true if at least one reaction fired.
     */
    private fun processEvents(events: List<SemanticEvent>, hypothesis: Hypothesis): Boolean {
        var any = false
        for (e in events) {
            when (e.name) {
                "CAN_ENGINE_START" -> {
                    engineStartMs = System.currentTimeMillis()
                    sessionHarshBrakes = 0
                    sessionSpeedSum = 0f
                    sessionSpeedSamples = 0
                }
                "CAN_ENGINE_STOP" -> {
                    commitDriverSession()
                    engineStartMs = 0L
                }
                "CAN_HARSH_BRAKE" -> sessionHarshBrakes++
            }

            val reaction = character.handle(e, currentHypothesis = hypothesis.name) ?: continue
            // State delta was applied inside character.handle → applyReaction.
            // Policy gate decides only whether the audible side-effect ships.
            // The internal soul still moves whether or not we broadcast.
            //
            // Episode TICK events are "still active" heartbeats — they should
            // contribute state delta (which they did via handle() above) but
            // must NOT trip suppression or consume budget, otherwise long
            // episodes would silence every other reaction in the trip.
            // Bypass the policy entirely for those.
            val isTick = e.name.endsWith("_TICK")
            val decision = if (isTick)
                com.carcompanion.companion.domain.ReactionPolicy.Decision.ALLOW
            else
                reactionPolicy.decide(e)
            if (decision == com.carcompanion.companion.domain.ReactionPolicy.Decision.ALLOW) {
                broadcast.onReaction(reaction, e.name)
            }
            character.lastDecision?.let { d ->
                val tag = if (d.wasSurprise) "[surprise]" else "[w=%.1f]".format(d.weight)
                val gateTag = if (decision == com.carcompanion.companion.domain.ReactionPolicy.Decision.ALLOW)
                    ""
                else
                    " [silenced:$decision]"
                appendLog("${e.name} → ${d.reaction.gif ?: "?"} $tag$gateTag")
                appendReactionHistory(
                    ReactionLogEntry(
                        ts = System.currentTimeMillis(),
                        event = e.name,
                        gif = d.reaction.gif,
                        weight = d.weight,
                        wasSurprise = d.wasSurprise,
                        emotion = _emotion.value,
                        hypothesis = hypothesis,
                    )
                )
            }
            any = true
        }
        return any
    }

    /**
     * Inject a synthetic event from a non-OBD source (IMU bump, weather
     * change, time-of-day boundary, NAV notification). Routes through
     * [tripPhaseAnalyzer] AND [episodeTracker] so:
     *   - NAV_NEAR_DESTINATION can flip DRIVING → ARRIVING + emit
     *     TRIP_ARRIVING
     *   - CAN_IMU_BUMP feeds the ROUGH_ROAD detector even when the
     *     bumps don't ride the OBD stream
     */
    private fun processSyntheticEvent(event: SemanticEvent) {
        val phaseDownstream = tripPhaseAnalyzer.consume(
            events = listOf(event),
            speedKmh = lastSpeedKmh,
            doorOpen = lastDoorOpen,
            locked = lastLocked,
        )
        val episodeDownstream = episodeTracker.consume(
            events = listOf(event),
            speedKmh = lastSpeedKmh,
            brakePedal = lastBrakePedal,
            engineOn = lastEngineOn,
        )
        val allEvents = listOf(event) + phaseDownstream + episodeDownstream
        if (processEvents(allEvents, _hypothesis.value)) publishSoul()
    }

    /** Mirror analyzer state into the public StateFlow for the debug dashboard. */
    private fun publishEnvSnapshot() {
        _envSnapshot.value = EnvSnapshot(
            timeBucket = timeOfDay.currentBucket,
            weatherBucket = weather.currentWeatherBucket,
            tempBucket = weather.currentTempBucket,
            tempC = weather.currentTempC,
            wmoCode = weather.currentWmoCode,
            lastWeatherFetchMs = weather.lastFetchEpochMs,
        )
    }

    private fun feedSoul(status: ObdMessage.Status) {
        // Feed the rolling window + reclassify (cheap)
        hypothesisEngine.feed(status)
        val newHypothesis = hypothesisEngine.classify()
        if (newHypothesis != _hypothesis.value) {
            _hypothesis.value = newHypothesis
            appendLog("Hypothesis → ${newHypothesis.label}")
        }

        val rawEvents = understanding.consume(status)
        // Latch the bits other code paths (NAV collector, IMU callback)
        // need to feed [tripPhaseAnalyzer] correctly. Order: door/lock
        // from status, speed coerced to Int. These are read by
        // [processSyntheticEvent] when a non-OBD event needs phase
        // routing (e.g. NAV_NEAR_DESTINATION).
        val curDoorOpen = (status.doors?.driver == true ||
            status.doors?.passenger == true ||
            status.doors?.rearLeft == true ||
            status.doors?.rearRight == true)
        val curLocked = status.locked == true
        lastSpeedKmh = status.speed ?: 0
        lastDoorOpen = curDoorOpen
        lastLocked = curLocked
        lastBrakePedal = status.brakePedal == true
        lastEngineOn = status.engineRunning ?: ((status.rpm ?: 0) > 0)

        // PatternDetector runs first because it may suppress raw events
        // (e.g. CAN_HARSH_BRAKE becomes EMERGENCY_BRAKE — we don't want
        // downstream analyzers like StyleAnalyzer to also count it as an
        // aggressive-driving signal).
        val pattern = patternDetector.consume(
            events = rawEvents,
            speedKmh = lastSpeedKmh,
            turnLeft = status.lights?.turnLeft == true,
            turnRight = status.lights?.turnRight == true,
        )
        val baseRaw = if (pattern.suppressed.isEmpty()) rawEvents
                      else rawEvents.filterNot { it.name in pattern.suppressed }
        if (pattern.suppressed.isNotEmpty()) {
            appendLog("PatternDetector suppressed ${pattern.suppressed.joinToString()}")
        }
        val styleEvents = styleAnalyzer.consume(baseRaw)
        val tripEvents = tripAnalyzer.consume(baseRaw, _driverProfile.value.lastEngineStopMs)
        // Trip-phase machine watches engine + door signals, sustained
        // speed, and NAV_NEAR_DESTINATION (the latter arrives via the
        // NAV collector — see [processSyntheticEvent]). Pass full
        // door/lock context so time-based transitions (ENTERING→IDLE,
        // POST_DRIVE→LEFT) tick correctly.
        val phaseEvents = tripPhaseAnalyzer.consume(
            events = baseRaw,
            speedKmh = lastSpeedKmh,
            doorOpen = curDoorOpen,
            locked = curLocked,
        )
        // Wake orchestrator inspects the same rawEvents for ignition
        // bookkeeping; the actual WAKE_ROBOT emit also depends on
        // [RobotPresence] which a separate observer feeds through poll().
        val wakeEvents = wakeOrchestrator.consume(baseRaw)
        // EpisodeTracker turns dense atomic streams (bumps, brake-hold,
        // crawling speed) into START/TICK/END events at P3 priority.
        val episodeEvents = episodeTracker.consume(
            events = baseRaw,
            speedKmh = lastSpeedKmh,
            brakePedal = lastBrakePedal,
            engineOn = lastEngineOn,
        )
        // Internal milestones run on the soul snapshot we publish later; collect
        // here so any persistent + transient milestones fold into the same
        // event stream as the OBD-derived ones.
        val internalEvents = milestoneAnalyzer.checkInternal(
            snap = character.state.snapshot(),
            hadReactionThisTick = false,
        )
        val events = baseRaw + styleEvents + tripEvents + phaseEvents + wakeEvents + episodeEvents + pattern.emitted + internalEvents
        val anyReaction = processEvents(events, newHypothesis)

        // Driving-style sampling (cheap; only contributes to running session avg)
        status.speed?.let {
            sessionSpeedSum += it.toFloat()
            sessionSpeedSamples++
        }

        // Tier 4: spawn liked / disliked moodlets when context changes
        val contexts = likesContext.resolveContexts(status, newHypothesis)
        for ((id, def) in likesContext.moodletsForContextChange(contexts, character.definition)) {
            character.moodlets.spawn(def, source = "likes:${def.trigger}")
            appendLog("Context → $id (${"%+.2f".format(def.valence)} valence)")
        }

        // Cabin-temperature moodlets are independent of likes/dislikes
        when {
            "weather:hot" in contexts -> character.trySpawnMoodlet("ambient_hot")
            "weather:cold" in contexts -> character.trySpawnMoodlet("ambient_cold")
        }

        if (anyReaction || _soulSnapshot.value == null) publishSoul()
    }

    /**
     * Roll the in-progress session into the persisted [DriverProfile].
     * Called on CAN_ENGINE_STOP and on service teardown.
     */
    private fun commitDriverSession() {
        if (engineStartMs <= 0L) return
        val nowMs = System.currentTimeMillis()
        val durationSec = ((nowMs - engineStartMs) / 1000L).coerceAtLeast(0L)
        if (durationSec < 5) return

        val prev = _driverProfile.value
        val sessAvgSpeed = if (sessionSpeedSamples > 0)
            sessionSpeedSum / sessionSpeedSamples else 0f
        val sessionBrakeRatio = if (durationSec > 0)
            sessionHarshBrakes / (durationSec / 60f) else 0f
        val nightWeight = run {
            val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 22..23 || hour in 0..5) 1f else 0f
        }

        // Exponential moving average so older sessions still matter
        fun ema(prev: Float, new: Float, alpha: Float = 0.2f) = (1 - alpha) * prev + alpha * new
        val newStyle = DrivingStyle(
            avgSpeed = ema(prev.drivingStyle.avgSpeed, sessAvgSpeed),
            harshBrakeRatio = ema(prev.drivingStyle.harshBrakeRatio, sessionBrakeRatio),
            nightDriveRatio = ema(prev.drivingStyle.nightDriveRatio, nightWeight),
            avgTripDurationSec = ((prev.drivingStyle.avgTripDurationSec * 4 + durationSec) / 5),
        )

        val updated = prev.copy(
            lastSeenMs = nowMs,
            lastEngineStopMs = nowMs,
            totalDrivetimeSec = prev.totalDrivetimeSec + durationSec,
            totalTrips = prev.totalTrips + 1,
            bond = (prev.bond + (durationSec / 600f)).coerceAtMost(100f),       // +1 per 10min of driving
            trust = (prev.trust + 1f - sessionBrakeRatio * 2f).coerceIn(0f, 100f),
            familiarity = (prev.familiarity + 0.5f).coerceAtMost(100f),
            drivingStyle = newStyle,
        )
        // Persistent milestone crossings between prev and updated
        val milestoneEvents = milestoneAnalyzer.checkPersistent(prev, updated)
        for (e in milestoneEvents) {
            character.handle(e, currentHypothesis = _hypothesis.value.name)
            appendLog("Milestone → ${e.name}")
            character.lastDecision?.let { d ->
                appendReactionHistory(
                    ReactionLogEntry(
                        ts = nowMs,
                        event = e.name,
                        gif = d.reaction.gif,
                        weight = d.weight,
                        wasSurprise = d.wasSurprise,
                        emotion = _emotion.value,
                        hypothesis = _hypothesis.value,
                    )
                )
            }
        }
        publishSoul()

        _driverProfile.value = updated
        appendLog("Trip done: ${durationSec}s avgSpd=${sessAvgSpeed.toInt()} brakes=${sessionHarshBrakes} bond=${updated.bond.toInt()}")
        lifecycleScope.launch { runCatching { driverRepo.save(updated) } }
    }

    private fun publishSoul() {
        val snap = character.state.snapshot()
        _soulSnapshot.value = snap
        _activeMoodlets.value = character.moodlets.active()
        val newEmotion = character.currentEmotion()
        if (newEmotion != _emotion.value) {
            _emotion.value = newEmotion
            broadcast.onEmotionChanged(newEmotion)
        }
        appendStateHistory(
            StateHistoryPoint(
                ts = System.currentTimeMillis(),
                valence = snap.valence,
                arousal = snap.arousal,
                emotion = newEmotion,
                hypothesis = _hypothesis.value,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onDestroy() {
        appendLog("Service stopping — saving soul + driver")
        imuAnalyzer?.stop()
        imuAnalyzer = null
        musicListener?.stop()
        musicListener = null
        if (::locationProvider.isInitialized) locationProvider.stop()
        stopWakePingPoller()
        stopTimeTicker()
        if (::robotPresence.isInitialized) robotPresence.reset()
        if (::wakeOrchestrator.isInitialized) wakeOrchestrator.reset()
        if (::tripPhaseAnalyzer.isInitialized) tripPhaseAnalyzer.reset()
        if (::reactionPolicy.isInitialized) reactionPolicy.reset()
        if (::episodeTracker.isInitialized) episodeTracker.reset()
        if (::patternDetector.isInitialized) patternDetector.reset()
        if (engineStartMs > 0L) commitDriverSession()
        // Best-effort save; the lifecycle is already torn down so we can't suspend.
        kotlinx.coroutines.runBlocking {
            runCatching { soulPersistence.save(character.state.snapshot()) }
            runCatching { driverRepo.save(_driverProfile.value) }
        }
        _state.value = ServiceState.Stopped
        _obdConnection.value = ObdConnection.IDLE
        _obdStatus.value = null
        _obdHello.value = null
        _soulSnapshot.value = null
        _emotion.value = Emotion.NEUTRAL
        _hypothesis.value = Hypothesis.RELAXED
        _robotOnline.value = false
        _tripPhase.value = com.carcompanion.companion.domain.TripPhaseAnalyzer.Phase.IDLE_NO_TRIP
        obdAcksRef = null
        ccpClientRef = null
        broadcastRef = null
        presenceRef = null
        serviceScope = null
        super.onDestroy()
    }

    fun configRepo(): ConfigRepository = configRepo

    private fun startInForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_ID)
            .setContentTitle("CarCompanion active")
            .setContentText("Listening for car state · idle")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(
            NotificationHelper.FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }
}

enum class ServiceState { Stopped, Running }

/**
 * Snapshot of Layer-2 environmental analyzers (weather, time-of-day).
 * Updated whenever an analyzer fires; consumed by the Soul Debug screen so
 * the user can see what context drives the current reactions.
 */
data class EnvSnapshot(
    val timeBucket: String? = null,           // TIME_AFTERNOON, …
    val weatherBucket: String? = null,        // WEATHER_SUNNY, …
    val tempBucket: String? = null,           // WEATHER_HOT/COLD/MILD
    val tempC: Float? = null,                 // raw outdoor temp
    val wmoCode: Int? = null,                 // WMO 4677 code
    val lastWeatherFetchMs: Long = 0L,
)

/** Live + last-bump accelerometer state for the debug dashboard. */
data class ImuSnapshot(
    val liveMag: Float = 0f,             // current magnitude (m/s², 4 Hz)
    val lastBumpMag: Float = 0f,         // peak that triggered the last bump
    val lastBumpEpochMs: Long = 0L,      // 0 = no bump yet this session
)

sealed class CcpResult {
    data class Ok(val response: String) : CcpResult()
    data class Err(val message: String) : CcpResult()
}

sealed class ScanState {
    object Idle : ScanState()
    object Scanning : ScanState()
    data class Done(val found: List<com.carcompanion.companion.network.RobotScanner.Found>) : ScanState()
    data class Err(val message: String) : ScanState()
}

/** One point in the valence/arousal history used by the debug chart. */
data class StateHistoryPoint(
    val ts: Long,
    val valence: Float,
    val arousal: Float,
    val emotion: Emotion,
    val hypothesis: Hypothesis,
)

/** One reaction the picker resolved — surfaced to the debug timeline. */
data class ReactionLogEntry(
    val ts: Long,
    val event: String,
    val gif: String?,
    val weight: Float,
    val wasSurprise: Boolean,
    val emotion: Emotion,
    val hypothesis: Hypothesis,
)

internal object ServiceLauncher {
    fun start(context: android.content.Context) {
        val intent = Intent(context, CarCompanionService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: android.content.Context) {
        context.stopService(Intent(context, CarCompanionService::class.java))
    }
}

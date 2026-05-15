package com.carcompanion.companion.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.carcompanion.companion.data.Doors
import com.carcompanion.companion.data.DriverProfile
import com.carcompanion.companion.data.ObdMessage
import com.carcompanion.companion.data.repo.AppConfig
import com.carcompanion.companion.data.repo.ConfigRepository
import com.carcompanion.companion.domain.Emotion
import com.carcompanion.companion.domain.Moodlet
import com.carcompanion.companion.domain.Quirk
import com.carcompanion.companion.domain.SoulSnapshot
import com.carcompanion.companion.network.ObdConnection
import com.carcompanion.companion.service.CarCompanionService
import com.carcompanion.companion.service.CcpResult
import com.carcompanion.companion.service.OverlayController
import com.carcompanion.companion.service.ScanState
import com.carcompanion.companion.service.ServiceLauncher
import com.carcompanion.companion.service.ServiceState
import com.carcompanion.companion.ui.debug.SensorDebugActivity
import com.carcompanion.companion.ui.debug.SoulDebugActivity
import com.carcompanion.companion.ui.theme.CompanionTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Auto-start the foreground service the moment the app opens — no
        // need for the user to find the Start button. Idempotent: if the
        // service is already running ContextCompat.startForegroundService is
        // a no-op aside from delivering an empty intent.
        if (CarCompanionService.state.value == ServiceState.Stopped) {
            ServiceLauncher.start(this)
        }

        // Asset pack gate: APK no longer bundles audio/gif (~190 MB stripped).
        // First launch must download the persona pack from GitHub Releases
        // before the soul can broadcast anything audible. OUTDATED is
        // skippable; MISSING is blocking.
        val skipAssetCheck = intent?.getBooleanExtra(EXTRA_SKIP_ASSET_CHECK, false) ?: false
        if (!skipAssetCheck) {
            val store = com.carcompanion.companion.data.repo.AssetStore(this)
            val assetState = store.classify(
                persona = com.carcompanion.companion.data.repo.AssetLoader.ACTIVE_PERSONA,
                minVersion = com.carcompanion.companion.BuildConfig.MIN_ASSETS_VERSION,
            )
            if (assetState !is com.carcompanion.companion.data.repo.AssetStore.State.OK) {
                val downloader = com.carcompanion.companion.data.repo.AssetDownloader(this, store)
                setContent {
                    CompanionTheme {
                        Scaffold { padding ->
                            com.carcompanion.companion.ui.onboarding.AssetDownloadScreen(
                                state = assetState,
                                persona = com.carcompanion.companion.data.repo.AssetLoader.ACTIVE_PERSONA,
                                downloader = downloader,
                                onDone = { recreate() },
                                onSkip = if (assetState is com.carcompanion.companion.data.repo.AssetStore.State.OUTDATED) {
                                    {
                                        val i = Intent(this@MainActivity, MainActivity::class.java)
                                            .putExtra(EXTRA_SKIP_ASSET_CHECK, true)
                                        startActivity(i)
                                        finish()
                                    }
                                } else null,
                                modifier = Modifier.padding(padding).fillMaxSize(),
                            )
                        }
                    }
                }
                return
            }
        }

        // Headless mode: when permissions are all set and the launch wasn't
        // triggered by the overlay's debug button, slip straight into the
        // floating overlay and close the activity. The user gets the
        // compact controls instead of the wall of debug UI. The debug
        // button on the overlay launches us with EXTRA_MANUAL=true to
        // bypass this auto-finish.
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        if (!manual && allPermissionsGranted(this)) {
            OverlayController.show(this)
            finish()
            return
        }

        setContent {
            CompanionTheme {
                Scaffold { padding ->
                    HomeScreen(modifier = Modifier.padding(padding).fillMaxSize())
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // If the user just finished granting the last missing permission
        // (via Settings, which only signals us through onResume), close
        // out of the wall-of-grants UI and drop into the floating overlay
        // automatically — no need to restart the app.
        val manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        if (!manual && !isFinishing && allPermissionsGranted(this)) {
            OverlayController.show(this)
            finish()
        }
    }

    companion object {
        /**
         * Set by [OverlayService] when it launches us from the floating
         * overlay's debug button. Tells onCreate to skip the auto-overlay
         * gate so the user can interact with the main UI.
         */
        const val EXTRA_MANUAL = "manual"

        /**
         * Set when the user pressed "Skip" on an OUTDATED asset download
         * prompt. The next onCreate doesn't re-show the prompt; the user
         * sees the regular UI and can trigger a manual update later from
         * the Cfg panel.
         */
        const val EXTRA_SKIP_ASSET_CHECK = "skip_asset_check"
    }
}

/**
 * True iff every permission the headless / overlay-only mode needs is
 * already granted. Missing any of these → MainActivity stays open so
 * the user can complete the grants.
 *
 *   1. SYSTEM_ALERT_WINDOW   — overlay window can't render without it
 *   2. ACCESS_COARSE_LOCATION — weather analyzer; service degrades to a
 *      Bangkok fallback if missing
 *   3. POST_NOTIFICATIONS (API 33+) — foreground service notification
 *   4. NotificationListener — Maps NAV + media sessions
 */
private fun allPermissionsGranted(context: android.content.Context): Boolean {
    val overlay = Settings.canDrawOverlays(context)
    val location = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val postNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else true
    val notifListener = isNotificationListenerEnabled(context, context.packageName)
    return overlay && location && postNotif && notifListener
}

@Composable
private fun HomeScreen(modifier: Modifier = Modifier) {
    val serviceState by CarCompanionService.state.collectAsState()
    val obdConn by CarCompanionService.obdConnection.collectAsState()
    val status by CarCompanionService.obdStatus.collectAsState()
    val hello by CarCompanionService.obdHello.collectAsState()
    val robotHost by CarCompanionService.robotHost.collectAsState()
    val ccpResult by CarCompanionService.lastCcpResponse.collectAsState()

    val context = LocalContext.current
    val configRepo = remember { ConfigRepository(context.applicationContext) }
    val config by configRepo.configFlow.collectAsState(initial = AppConfig())

    var configOpen by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    // When Config opens, scroll the page so the card actually comes into view —
    // otherwise the toggle looks dead because the new card is below the fold.
    LaunchedEffect(configOpen) {
        if (configOpen) scrollState.animateScrollTo(scrollState.maxValue)
    }

    Column(
        modifier = modifier
            .padding(12.dp)
            .verticalScroll(scrollState),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ServicePanel(
                    serviceState = serviceState,
                    obdConn = obdConn,
                    hello = hello,
                    configOpenIndicator = configOpen,
                    onConfigClick = { configOpen = !configOpen },
                )
                HorizontalDivider()
                RobotPanel(robotHost, ccpResult, enabled = serviceState == ServiceState.Running)
            }

            Card(
                modifier = Modifier.weight(1.3f).fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Box(modifier = Modifier.padding(14.dp).fillMaxWidth()) {
                    StatusCard(status)
                }
            }
        }

        if (configOpen) {
            Spacer(Modifier.size(12.dp))
            ConfigCard(config, configRepo) { configOpen = false }
        }

        Spacer(Modifier.size(12.dp))
        DebugLogCard(modifier = Modifier.fillMaxWidth().height(110.dp))
    }
}

@Composable
private fun ServicePanel(
    serviceState: ServiceState,
    obdConn: ObdConnection,
    hello: ObdMessage.Hello?,
    configOpenIndicator: Boolean,
    onConfigClick: () -> Unit,
) {
    val context = LocalContext.current

    var needsNotificationPerm by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        )
    }
    var needsLocationPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) !=
                PackageManager.PERMISSION_GRANTED
        )
    }
    // Both perms requested together so the user clears them in one prompt
    // flow. Location is optional — service starts regardless of grant
    // result (weather falls back to the Bangkok default).
    val permLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        needsNotificationPerm = needsNotificationPerm &&
            results[Manifest.permission.POST_NOTIFICATIONS] != true
        needsLocationPerm = needsLocationPerm &&
            results[Manifest.permission.ACCESS_COARSE_LOCATION] != true
        // Service start needs notification perm (foreground svc) on API 33+,
        // but coarse location is best-effort — never block on it.
        if (!needsNotificationPerm) ServiceLauncher.start(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "CarCompanion",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, SoulDebugActivity::class.java))
            }) { Text("Soul") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, SensorDebugActivity::class.java))
            }) { Text("Sensors") }
            OutlinedButton(onClick = onConfigClick) { Text(if (configOpenIndicator) "Cfg ▴" else "Cfg ▾") }
        }
        Text(
            "Service: ${serviceState.label()}  ·  ESP32-OBD: ${obdConn.label()}",
            style = MaterialTheme.typography.titleSmall,
        )
        hello?.let {
            Text(
                "OBD fw ${it.fw}  ·  ${it.car}  ·  up ${it.uptime}s",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    val toRequest = buildList {
                        if (needsNotificationPerm) add(Manifest.permission.POST_NOTIFICATIONS)
                        if (needsLocationPerm) add(Manifest.permission.ACCESS_COARSE_LOCATION)
                    }
                    if (toRequest.isEmpty()) {
                        ServiceLauncher.start(context)
                    } else {
                        permLauncher.launch(toRequest.toTypedArray())
                    }
                },
                enabled = serviceState == ServiceState.Stopped,
            ) {
                Text(
                    when {
                        needsNotificationPerm && needsLocationPerm -> "Grant 2 + start"
                        needsNotificationPerm -> "Grant + start"
                        needsLocationPerm     -> "Grant location + start"
                        else                  -> "Start"
                    }
                )
            }

            // Standalone permission button — visible whenever location is
            // still denied, regardless of service state. Without this the
            // Start-button merge becomes unreachable once the service is
            // already running from a prior session (boot autostart, etc.).
            if (needsLocationPerm) {
                OutlinedButton(
                    onClick = {
                        permLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                    },
                ) { Text("Grant location") }
            }

            OutlinedButton(
                onClick = { ServiceLauncher.stop(context) },
                enabled = serviceState == ServiceState.Running,
            ) { Text("Stop") }
        }
        // Notification listener (Google Maps / Waze) — has no programmatic
        // grant; we open the system Settings page and let the user toggle us.
        NotificationListenerHint()
    }
}

@Composable
private fun NotificationListenerHint() {
    val context = LocalContext.current
    val pkg = context.packageName
    var enabled by remember {
        mutableStateOf(isNotificationListenerEnabled(context, pkg))
    }
    // The notification-listener toggle lives in system Settings (not an
    // in-app dialog), so RememberLauncher callbacks never fire. We need
    // to re-poll on every activity resume — that's the moment the user
    // gets back from Settings.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                enabled = isNotificationListenerEnabled(context, pkg)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (enabled) {
        // Listener may be in a "granted but not bound" state right after an
        // APK reinstall. Poking requestRebind() each time the activity opens
        // makes us reconnect transparently.
        LaunchedEffect(enabled) {
            com.carcompanion.companion.service.MapsNotificationListener.requestRebindNow(context)
        }
        Text(
            "Maps notification access: ✓ granted",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Maps nav events: needs notification access",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            ) { Text("Grant notif") }
        }
    }
}

/**
 * Returns true if our [MapsNotificationListener] is in the user's
 * enabled-notification-listeners set. Uses Settings.Secure (the same source
 * NotificationManagerCompat reads, but without the AndroidX dep).
 */
private fun isNotificationListenerEnabled(
    context: android.content.Context,
    pkg: String,
): Boolean {
    val flat = android.provider.Settings.Secure.getString(
        context.contentResolver, "enabled_notification_listeners",
    ) ?: return false
    return flat.split(':').any { it.startsWith("$pkg/") }
}

@Composable
private fun RobotPanel(
    robotHost: String,
    ccpResult: CcpResult?,
    enabled: Boolean,
) {
    val status by CarCompanionService.robotStatus.collectAsState()

    // Local slider state — seeded from the last status snapshot so the slider
    // reflects the firmware's actual current values after Ping.
    var volume by remember { mutableFloatStateOf(60f) }
    var brightness by remember { mutableFloatStateOf(255f) }
    var idle2 by remember { mutableFloatStateOf(25f) }

    LaunchedEffect(status) {
        status?.let {
            volume = it.volume.toFloat().coerceIn(0f, 100f)
            brightness = it.brightness.toFloat().coerceIn(0f, 255f)
            idle2 = it.idle2Chance.toFloat().coerceIn(0f, 100f)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Robot (ESP32-S3)", style = MaterialTheme.typography.titleMedium)
        Text(
            "→ $robotHost:8080",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )

        RobotStatusLine(status, enabled)
        LocationStatusLine(enabled)

        // Slider 1 — runtime volume (CCP 0x10). Persisted version below.
        LabeledSlider(
            label = "Volume",
            value = volume,
            onValueChange = { volume = it },
            range = 0f..100f,
            steps = 9,
            onCommit = { CarCompanionService.sendVolumeAsync(volume.toInt()) },
            valueFormatter = { "${it.toInt()}" },
            enabled = enabled,
        )
        // Slider 2 — brightness (CCP 0x11). 0..255 is the firmware's range.
        LabeledSlider(
            label = "Bright",
            value = brightness,
            onValueChange = { brightness = it },
            range = 0f..255f,
            steps = 0,
            onCommit = { CarCompanionService.sendBrightnessAsync(brightness.toInt()) },
            valueFormatter = { "${it.toInt()}" },
            enabled = enabled,
        )
        // Slider 3 — idle2 chance % (CCP 0x24).
        LabeledSlider(
            label = "Idle2 %",
            value = idle2,
            onValueChange = { idle2 = it },
            range = 0f..100f,
            steps = 9,
            onCommit = { CarCompanionService.sendIdle2ChanceAsync(idle2.toInt()) },
            valueFormatter = { "${it.toInt()}%" },
            enabled = enabled,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = {
                    CarCompanionService.sendDefaultVolumeAsync(volume.toInt())
                    CarCompanionService.sendDefaultBrightnessAsync(brightness.toInt())
                },
                enabled = enabled,
            ) { Text("Save as default") }
            OutlinedButton(
                onClick = { CarCompanionService.pingRobotAsync() },
                enabled = enabled,
            ) { Text("Ping") }
        }

        WifiConfigSection(enabled = enabled)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TestAssetMenu(
                kind = "GIF",
                items = CarCompanionService.bundledGifs,
                enabled = enabled,
                onPick = { CarCompanionService.testSendGif(it) },
            )
            TestAssetMenu(
                kind = "WAV",
                items = CarCompanionService.bundledWavs,
                enabled = enabled,
                onPick = { CarCompanionService.testSendWav(it) },
            )
            RebootButton(enabled = enabled)
        }

        ccpResult?.let { Resp(it) }

        OverlayControls(enabled = enabled)
    }
}

@Composable
private fun LocationStatusLine(enabled: Boolean) {
    val loc by CarCompanionService.locationLatLon.collectAsState()
    val text = when {
        !enabled -> "location: service stopped"
        loc == null -> "location: waiting for fix (using Bangkok fallback)"
        else -> "location: %.4f, %.4f".format(loc!!.lat, loc!!.lon)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RobotStatusLine(
    status: com.carcompanion.companion.network.RobotStatus?,
    enabled: Boolean,
) {
    val text = if (status == null) {
        if (enabled) "status: tap Ping to load" else "status: service stopped"
    } else {
        val power = when {
            !status.usbPresent -> "batt ${status.batteryPct}%"
            status.charging    -> "charging ${status.batteryPct}%"
            else               -> "usb ${status.batteryPct}%"
        }
        "state=${status.state} · $power · rssi ${status.rssi} dBm · psram ${status.psramFree / 1024} KB"
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onCommit: () -> Unit,
    valueFormatter: (Float) -> String,
    enabled: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(72.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onCommit,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        Text(valueFormatter(value), modifier = Modifier.width(52.dp))
    }
}

@Composable
private fun WifiConfigSection(enabled: Boolean) {
    var ssid by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var rebootAfter by remember { mutableStateOf(true) }
    var passVisible by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    Spacer(Modifier.size(4.dp))
    OutlinedButton(
        onClick = { expanded = !expanded },
        enabled = enabled,
    ) { Text(if (expanded) "▴ WiFi config" else "▾ WiFi config") }

    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("SSID") },
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = pass,
                onValueChange = { pass = it },
                label = { Text("Password (empty = open)") },
                singleLine = true,
                enabled = enabled,
                visualTransformation = if (passVisible)
                    VisualTransformation.None
                else
                    PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = passVisible,
                    onCheckedChange = { passVisible = it },
                    enabled = enabled,
                )
                Text("Show password", modifier = Modifier.padding(end = 16.dp))
                Checkbox(
                    checked = rebootAfter,
                    onCheckedChange = { rebootAfter = it },
                    enabled = enabled,
                )
                Text("Reboot after save")
            }
            Button(
                onClick = {
                    CarCompanionService.sendWifiConfigAsync(ssid, pass, rebootAfter)
                },
                enabled = enabled && ssid.isNotBlank(),
            ) { Text("Save WiFi to robot") }
        }
    }
}

@Composable
private fun RebootButton(enabled: Boolean) {
    var confirming by remember { mutableStateOf(false) }

    OutlinedButton(
        onClick = { confirming = true },
        enabled = enabled,
    ) { Text("Reboot") }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reboot robot?") },
            text = { Text("ESP32 จะ restart ~3s, idle จะกลับมาเอง. ใช้ตอนเปลี่ยน WiFi หรือ unstable.") },
            confirmButton = {
                Button(onClick = {
                    CarCompanionService.rebootRobotAsync()
                    confirming = false
                }) { Text("Reboot") }
            },
            dismissButton = {
                OutlinedButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun OverlayControls(enabled: Boolean) {
    val context = LocalContext.current
    var hasPerm by remember { mutableStateOf(OverlayController.isPermissionGranted(context)) }

    val overlayPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasPerm = OverlayController.isPermissionGranted(context)
    }

    Spacer(Modifier.size(8.dp))
    Text("Floating overlay", style = MaterialTheme.typography.titleSmall)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = enabled,
            onClick = {
                if (!hasPerm) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                    overlayPermLauncher.launch(intent)
                } else {
                    OverlayController.show(context)
                }
            },
        ) { Text(if (hasPerm) "Show overlay" else "Grant overlay perm") }

        OutlinedButton(
            enabled = enabled && hasPerm,
            onClick = { OverlayController.hide(context) },
        ) { Text("Hide overlay") }
    }
}

@Composable
private fun ConfigCard(
    config: AppConfig,
    configRepo: ConfigRepository,
    onClose: () -> Unit,
) {
    var hostInput by remember(config.robotHost) { mutableStateOf(config.robotHost) }
    var robotPortInput by remember(config.robotPort) { mutableStateOf(config.robotPort.toString()) }
    var obdPortInput by remember(config.obdPort) { mutableStateOf(config.obdPort.toString()) }
    var autoStart by remember(config.autoStart) { mutableStateOf(config.autoStart) }

    val scope = rememberCoroutineScope()

    Card(
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Configuration", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onClose) { Text("Close") }
            }

            OutlinedTextField(
                value = hostInput,
                onValueChange = { hostInput = it },
                label = { Text("Robot IP (ESP32-S3)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            ScanRobotRow(
                onPick = { ip ->
                    hostInput = ip
                    scope.launch {
                        configRepo.update { it.copy(robotHost = ip) }
                        CarCompanionService.setRobotHost(ip)
                    }
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = robotPortInput,
                    onValueChange = { robotPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("Robot port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = obdPortInput,
                    onValueChange = { obdPortInput = it.filter(Char::isDigit).take(5) },
                    label = { Text("OBD listen port") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto-start on boot", modifier = Modifier.weight(1f))
                Switch(checked = autoStart, onCheckedChange = { autoStart = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val rp = robotPortInput.toIntOrNull() ?: AppConfig.DEFAULT_ROBOT_PORT
                    val op = obdPortInput.toIntOrNull() ?: AppConfig.DEFAULT_OBD_PORT
                    scope.launch {
                        configRepo.update {
                            it.copy(
                                robotHost = hostInput.trim(),
                                robotPort = rp,
                                obdPort = op,
                                autoStart = autoStart,
                            )
                        }
                        CarCompanionService.setRobotHost(hostInput.trim())
                    }
                }) { Text("Save") }
                Text(
                    "OBD port applies on next service restart",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp).align(Alignment.CenterVertically),
                )
            }
        }
    }
}

@Composable
private fun DebugLogCard(modifier: Modifier = Modifier) {
    val log by CarCompanionService.debugLog.collectAsState()
    val state = rememberLazyListState()

    // Autoscroll to bottom on append
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) state.animateScrollToItem(log.lastIndex)
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp).fillMaxWidth()) {
            Text("Debug log", style = MaterialTheme.typography.titleSmall)
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(log) { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
        }
    }
}

@Composable
private fun TestAssetMenu(
    kind: String,
    items: kotlinx.coroutines.flow.StateFlow<List<String>>,
    enabled: Boolean,
    onPick: (String) -> Unit,
) {
    val list by items.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled && list.isNotEmpty(),
        ) { Text("Test $kind") }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            for (name in list) {
                DropdownMenuItem(
                    text = { Text(name, style = MaterialTheme.typography.bodySmall) },
                    onClick = {
                        expanded = false
                        onPick(name)
                    },
                )
            }
        }
    }
}

@Composable
private fun ScanRobotRow(onPick: (String) -> Unit) {
    val scan by CarCompanionService.scanState.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { CarCompanionService.startRobotScan() },
                enabled = scan !is ScanState.Scanning,
            ) {
                Text(if (scan is ScanState.Scanning) "Scanning…" else "Scan LAN")
            }
            if (scan is ScanState.Scanning) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            OutlinedButton(onClick = { CarCompanionService.clearScanState() }) {
                Text("Clear")
            }
        }
        when (val s = scan) {
            is ScanState.Done -> {
                if (s.found.isEmpty()) {
                    Text(
                        "No CCP responder found on this /24.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    for (f in s.found) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(f.ip) }
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                "→ ${f.ip}",
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                f.status.removePrefix("CP ").take(80),
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            )
                        }
                    }
                }
            }
            is ScanState.Err -> Text(
                "Scan error: ${s.message}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            else -> Unit
        }
    }
}

@Composable
private fun Resp(result: CcpResult) {
    val (label, color) = when (result) {
        is CcpResult.Ok -> result.response to MaterialTheme.colorScheme.primary
        is CcpResult.Err -> "err: ${result.message}" to MaterialTheme.colorScheme.error
    }
    Text(
        label,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = color,
    )
}

@Composable
private fun StatusCard(status: ObdMessage.Status?) {
    val soul by CarCompanionService.soulSnapshot.collectAsState()
    val emotion by CarCompanionService.emotion.collectAsState()
    val hypothesis by CarCompanionService.hypothesis.collectAsState()
    val moodlets by CarCompanionService.activeMoodlets.collectAsState()
    val quirks by CarCompanionService.activeQuirks.collectAsState()
    val driver by CarCompanionService.driverProfile.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoulRow(soul, emotion)
        HypothesisRow(hypothesis.emoji + " " + hypothesis.label, quirks)
        DriverRow(driver)
        if (moodlets.isNotEmpty()) MoodletsRow(moodlets)
        HorizontalDivider()
        if (status == null) {
            Text("Waiting for status…", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Text("State: ${status.state}", style = MaterialTheme.typography.titleMedium)
        StatusLine("RPM", status.rpm?.toString())
        StatusLine("Speed", status.speed?.let { "$it km/h" })
        StatusLine("Throttle", status.throttle?.let { "%.0f%%".format(it) })
        StatusLine("Gear", status.gear)
        StatusLine("Locked", status.locked?.toString())
        StatusLine("Engine running", status.engineRunning?.toString())
        StatusLine("Coolant", status.coolant?.let { "${it}°C" })
        StatusLine("Battery", status.battery?.let { "%.2fV".format(it) })
        StatusLine("Doors open", openDoors(status.doors))
        StatusLine("MIL", status.mil?.toString())
        StatusLine("WiFi", status.wifi?.let { "${it.ip} (${it.rssi}dBm)" })
    }
}

@Composable
private fun SoulRow(soul: SoulSnapshot?, emotion: Emotion) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(emotion.emoji, style = MaterialTheme.typography.displaySmall)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
            Text("Soul: $emotion", style = MaterialTheme.typography.titleSmall)
            if (soul != null) {
                Text(
                    "v=%.2f a=%.2f  ·  mood=%d  ·  hunger=%.0f  ·  bond=%.0f"
                        .format(soul.valence, soul.arousal, soul.mood, soul.hunger, soul.bond),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
    }
}

@Composable
private fun HypothesisRow(label: String, quirks: Set<Quirk>) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hypothesis:", style = MaterialTheme.typography.labelSmall)
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (quirks.isNotEmpty()) {
            Text(
                "·  " + quirks.joinToString(" ") { it.tag },
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun DriverRow(driver: DriverProfile) {
    val tripCount = driver.totalTrips
    val totalMins = driver.totalDrivetimeSec / 60
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Driver:", style = MaterialTheme.typography.labelSmall)
        Text(
            "%s · bond=%d · trust=%d · trips=%d · %dmin".format(
                driver.nickname ?: driver.id,
                driver.bond.toInt(),
                driver.trust.toInt(),
                tripCount,
                totalMins,
            ),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun MoodletsRow(moodlets: List<Moodlet>) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text("Moodlets:", style = MaterialTheme.typography.labelSmall)
        for (m in moodlets) {
            Text(
                "  %s  v%+.2f a%+.2f".format(m.id, m.valenceShift, m.arousalShift),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun openDoors(doors: Doors?): String? {
    if (doors == null) return null
    val open = listOfNotNull(
        if (doors.driver == true) "driver" else null,
        if (doors.passenger == true) "passenger" else null,
        if (doors.rearLeft == true) "rl" else null,
        if (doors.rearRight == true) "rr" else null,
        if (doors.trunk == true) "trunk" else null,
    )
    return if (open.isEmpty()) "none" else open.joinToString(", ")
}

private fun ServiceState.label(): String = when (this) {
    ServiceState.Running -> "running"
    ServiceState.Stopped -> "stopped"
}

private fun ObdConnection.label(): String = when (this) {
    ObdConnection.IDLE -> "idle"
    ObdConnection.LISTENING -> "listening :35000"
    ObdConnection.CLIENT_CONNECTED -> "connected"
}

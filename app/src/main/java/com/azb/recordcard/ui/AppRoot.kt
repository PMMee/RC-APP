package com.azb.recordcard.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.azb.recordcard.app.AppController
import com.azb.recordcard.ui.components.Glyph
import com.azb.recordcard.ui.components.GlyphIcon
import com.azb.recordcard.ui.screens.DeviceDetailScreen
import com.azb.recordcard.ui.screens.DevicesScreen
import com.azb.recordcard.ui.screens.FilesScreen
import com.azb.recordcard.ui.screens.LogScreen
import com.azb.recordcard.ui.screens.RecordingsScreen
import com.azb.recordcard.ui.screens.ScanScreen
import com.azb.recordcard.ui.screens.SettingsScreen
import com.azb.recordcard.ui.screens.TransferSettingsScreen

private const val SEP = "\u001F"

private data class TabSpec(val key: String, val label: String, val glyph: Glyph)

private val TABS = listOf(
    TabSpec("devices", "我的设备", Glyph.CARD),
    TabSpec("recordings", "我的录音", Glyph.WAVE),
    TabSpec("settings", "设置", Glyph.SETTINGS),
)

@Composable
fun RecordCardApp(controller: AppController) {
    val session by controller.session.collectAsState()
    val ap by controller.ap.collectAsState()
    val settings by controller.settings.collectAsState()
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    LaunchedEffect(Unit) {
        if (needsNotificationPermission() && !hasNotificationPermission(context)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        controller.messages.collect { message ->
            snackbarHost.showSnackbar(message)
        }
    }

    var navRaw by rememberSaveable { mutableStateOf("tab:devices") }
    val stack = remember(navRaw) { navRaw.split(SEP).filter { it.isNotBlank() } }
    val currentRoute = stack.lastOrNull() ?: "tab:devices"
    val selectedTab = stack.firstOrNull()?.removePrefix("tab:") ?: "devices"

    fun push(route: String) {
        navRaw = (stack + route).joinToString(SEP)
    }

    fun pop() {
        if (stack.size > 1) navRaw = stack.dropLast(1).joinToString(SEP)
    }

    fun switchTab(key: String) {
        navRaw = "tab:$key"
    }

    BackHandler(enabled = stack.size > 1) { pop() }

    val view = LocalView.current
    val keepScreenOn = settings.keepScreenOn && (session?.transfer != null || ap.running)
    LaunchedEffect(keepScreenOn) {
        view.keepScreenOn = keepScreenOn
    }
    DisposableEffect(Unit) {
        onDispose { view.keepScreenOn = false }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHost) },
        bottomBar = {
            if (stack.size == 1) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 0.dp,
                ) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = selectedTab == tab.key,
                            onClick = { switchTab(tab.key) },
                            icon = {
                                GlyphIcon(
                                    glyph = tab.glyph,
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 1.7.dp,
                                )
                            },
                            label = {
                                Text(tab.label, style = MaterialTheme.typography.labelSmall)
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                currentRoute == "scan" -> ScanScreen(
                    controller = controller,
                    onBack = ::pop,
                    onConnected = { address ->
                        navRaw = listOf("tab:devices", "device:$address").joinToString(SEP)
                    },
                )

                currentRoute.startsWith("device:") -> {
                    val address = currentRoute.removePrefix("device:")
                    DeviceDetailScreen(
                        controller = controller,
                        address = address,
                        onBack = ::pop,
                        onOpenFiles = { push("files:$address") },
                        onOpenTransferSettings = { push("transfer:$address") },
                        onOpenLogs = { push("logs") },
                    )
                }

                currentRoute.startsWith("files:") -> {
                    val address = currentRoute.removePrefix("files:")
                    FilesScreen(
                        controller = controller,
                        address = address,
                        onBack = ::pop,
                    )
                }

                currentRoute.startsWith("transfer:") -> {
                    val address = currentRoute.removePrefix("transfer:")
                    TransferSettingsScreen(
                        controller = controller,
                        address = address,
                        onBack = ::pop,
                    )
                }

                currentRoute == "logs" -> LogScreen(controller = controller, onBack = ::pop)

                else -> when (currentRoute) {
                    "tab:recordings" -> RecordingsScreen(
                        controller = controller,
                        onOpenDevice = { address ->
                            navRaw = listOf("tab:devices", "device:$address").joinToString(SEP)
                        },
                    )

                    "tab:settings" -> SettingsScreen(
                        controller = controller,
                        onOpenLogs = { push("logs") },
                    )

                    else -> DevicesScreen(
                        controller = controller,
                        onAddDevice = { push("scan") },
                        onOpenDevice = { address -> push("device:$address") },
                    )
                }
            }
        }
    }
}

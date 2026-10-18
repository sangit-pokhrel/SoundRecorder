package com.vocalis.recorder

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vocalis.recorder.audio.RecorderStatus
import com.vocalis.recorder.ui.MainViewModel
import com.vocalis.recorder.ui.components.NavItem
import com.vocalis.recorder.ui.components.RecordingActions
import com.vocalis.recorder.ui.components.VocalisBottomBar
import com.vocalis.recorder.ui.screens.EffectsScreen
import com.vocalis.recorder.ui.screens.HomeScreen
import com.vocalis.recorder.ui.screens.PlayerSheet
import com.vocalis.recorder.ui.screens.RecorderActions
import com.vocalis.recorder.ui.screens.RecordingsScreen
import com.vocalis.recorder.ui.screens.SettingsScreen
import com.vocalis.recorder.ui.theme.Vc
import com.vocalis.recorder.ui.theme.VocalisTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Vc.Background.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Vc.Background.toArgb()),
        )
        setContent {
            VocalisTheme {
                VocalisRoot(viewModel)
            }
        }
    }
}

private val navItems = listOf(
    NavItem("Home", Icons.Rounded.Home),
    NavItem("Recordings", Icons.Rounded.Folder),
    NavItem("Settings", Icons.Rounded.Settings),
)

private const val TAB_HOME = 0
private const val TAB_RECORDINGS = 1
private const val TAB_SETTINGS = 2

@Composable
private fun VocalisRoot(vm: MainViewModel) {
    val context = LocalContext.current
    val recorder by vm.recorder.collectAsStateWithLifecycle()
    val effects by vm.effects.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val recordings by vm.recordings.collectAsStateWithLifecycle()
    val player by vm.player.collectAsStateWithLifecycle()
    val task by vm.task.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(TAB_HOME) }
    var showEffects by rememberSaveable { mutableStateOf(false) }
    var focusSearch by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) vm.startRecording()
        else Toast.makeText(context, "Vocalis needs microphone access to record", Toast.LENGTH_LONG).show()
    }
    val startRecording = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.startRecording()
        } else {
            val perms = buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            permissionLauncher.launch(perms.toTypedArray())
        }
    }

    val recordingActions = remember(vm) {
        RecordingActions(
            onOpen = vm::open,
            onEnhance = vm::enhance,
            onExportM4a = vm::exportM4a,
            onSaveToMusic = vm::saveToMusicLibrary,
            onShare = { context.startActivity(vm.shareIntent(it)) },
            onRename = vm::rename,
            onDelete = vm::delete,
        )
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(recorder.error) {
        recorder.error?.let {
            snackbar.showSnackbar(it)
            vm.clearError()
        }
    }

    BackHandler(enabled = showEffects) { showEffects = false }
    BackHandler(enabled = !showEffects && tab != TAB_HOME) { tab = TAB_HOME }

    Scaffold(
        containerColor = Vc.Background,
        bottomBar = {
            if (!showEffects) {
                VocalisBottomBar(navItems, tab) { tab = it }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            task?.let { t ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Vc.SurfaceHigher,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${t.label}… ${(t.progress * 100).toInt()}%")
                        LinearProgressIndicator(
                            progress = { t.progress },
                            color = Vc.Red,
                            trackColor = Vc.Surface,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
            when {
                showEffects -> EffectsScreen(
                    effects = effects,
                    onChange = vm::updateEffects,
                    onPreset = vm::applyPreset,
                    onBack = { showEffects = false },
                )
                tab == TAB_HOME -> HomeScreen(
                    state = recorder,
                    effects = effects,
                    config = config,
                    recent = recordings.take(4),
                    playingFile = player.recording?.file?.takeIf { player.playing },
                    recorder = RecorderActions(
                        onRecord = startRecording,
                        onStop = vm::stopRecording,
                        onPause = vm::pauseRecording,
                        onResume = vm::resumeRecording,
                        onMark = vm::addMarker,
                        onRelearn = vm::relearnNoise,
                    ),
                    recordingActions = recordingActions,
                    onPreset = vm::applyPreset,
                    onConfig = vm::updateConfig,
                    onOpenEffects = { showEffects = true },
                    onSearch = {
                        focusSearch = true
                        tab = TAB_RECORDINGS
                    },
                    onSeeAll = { tab = TAB_RECORDINGS },
                )
                tab == TAB_RECORDINGS -> RecordingsScreen(
                    recordings = recordings,
                    playingFile = player.recording?.file?.takeIf { player.playing },
                    actions = recordingActions,
                    focusSearch = focusSearch,
                    onSearchFocused = { focusSearch = false },
                )
                else -> SettingsScreen(
                    config = config,
                    effects = effects,
                    recordingActive = recorder.status != RecorderStatus.IDLE,
                    storagePath = vm.storagePath,
                    onConfig = vm::updateConfig,
                    onEffects = vm::updateEffects,
                    onOpenEffects = { showEffects = true },
                )
            }
        }
    }

    if (player.recording != null) {
        PlayerSheet(
            state = player,
            onDismiss = vm::closePlayer,
            onToggle = vm::togglePlay,
            onSeek = vm::seekTo,
            onSkip = vm::skip,
            onOriginal = vm::setPlayOriginal,
        )
    }
}

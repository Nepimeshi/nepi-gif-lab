package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.UserPreferencesRepository
import com.example.engine.GalleryExporter
import com.example.engine.IncomingShareResult
import com.example.engine.ShareIntentParser
import com.example.ui.components.LocalStudioHaptics
import com.example.ui.components.rememberStudioHaptics
import com.example.ui.screens.BatchConversionScreen
import com.example.ui.screens.EditorScreen
import com.example.ui.screens.ExportScreen
import com.example.ui.screens.HistoryScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsBottomSheet
import com.example.ui.screens.SupportNepiScreen
import com.example.ui.theme.GifStudioTheme
import com.example.viewmodel.ExportUiState
import com.example.viewmodel.GifStudioViewModel
import com.example.worker.ConversionNotificationHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StudioRoutes {
    const val HOME = "home"
    const val EDITOR = "editor"
    const val EXPORT = "export"
    const val BATCH = "batch"
    const val HISTORY = "history"
    const val SUPPORT = "support"
}

class MainActivity : ComponentActivity() {

    private val _incomingIntent = MutableStateFlow<Intent?>(null)
    private val incomingIntent: StateFlow<Intent?> = _incomingIntent.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && intent != null) {
            _incomingIntent.value = intent
        }
        setContent {
            GifStudioApp(
                incomingIntentFlow = incomingIntent,
                onConsumeIncomingIntent = { _incomingIntent.value = null }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        _incomingIntent.value = intent
    }
}

@Composable
fun GifStudioApp(
    viewModel: GifStudioViewModel = viewModel(),
    incomingIntentFlow: StateFlow<Intent?> = remember { MutableStateFlow(null) },
    onConsumeIncomingIntent: () -> Unit = {}
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val hapticsPreference by viewModel.hapticsPreference.collectAsStateWithLifecycle()
    val recentHistory by viewModel.recentHistory.collectAsStateWithLifecycle()
    val allHistory by viewModel.allHistory.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val editorState by viewModel.editorState.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val batchState by viewModel.batchState.collectAsStateWithLifecycle()
    val pendingIntent by incomingIntentFlow.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }

    val studioHaptics = rememberStudioHaptics(hapticsPreference)
    val effectiveHapticsToggle = hapticsPreference
        ?: UserPreferencesRepository.isSystemHapticsEnabled(context)

    // Android 13+ optional notification permission request (conversion runs either way)
    var pendingNotificationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var notificationPermissionAsked by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        notificationPermissionAsked = true
        pendingNotificationAction?.invoke()
        pendingNotificationAction = null
    }

    fun runWithOptionalNotificationPermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !notificationPermissionAsked &&
            !ConversionNotificationHelper.canPostNotifications(context)
        ) {
            pendingNotificationAction = action
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }

    // Handle incoming Android Share intents (ACTION_SEND / ACTION_SEND_MULTIPLE) and notification taps
    LaunchedEffect(pendingIntent) {
        val currentIntent = pendingIntent ?: return@LaunchedEffect
        onConsumeIncomingIntent()

        val navTarget = currentIntent.getStringExtra(ConversionNotificationHelper.EXTRA_NAV_DESTINATION)
        if (!navTarget.isNullOrBlank()) {
            when (navTarget) {
                StudioRoutes.EXPORT -> navController.navigate(StudioRoutes.EXPORT) { launchSingleTop = true }
                StudioRoutes.BATCH -> navController.navigate(StudioRoutes.BATCH) { launchSingleTop = true }
            }
            return@LaunchedEffect
        }

        val shareResult = withContext(Dispatchers.IO) {
            ShareIntentParser.parse(context, currentIntent)
        }
        when (shareResult) {
            IncomingShareResult.None -> Unit
            is IncomingShareResult.Single -> {
                viewModel.loadVideoForEditor(shareResult.uri)
                navController.navigate(StudioRoutes.EDITOR) {
                    launchSingleTop = true
                }
            }
            is IncomingShareResult.Multiple -> {
                viewModel.addBatchVideos(shareResult.uris)
                navController.navigate(StudioRoutes.BATCH) {
                    launchSingleTop = true
                }
                if (shareResult.skippedCount > 0) {
                    snackbarHostState.showSnackbar(
                        "Loaded ${shareResult.uris.size} file(s); skipped ${shareResult.skippedCount} unsupported item(s)."
                    )
                }
            }
            is IncomingShareResult.Unsupported -> {
                snackbarHostState.showSnackbar(shareResult.message)
            }
        }
    }

    // Zero-permission Android Photo Picker for single video import
    val singleVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.loadVideoForEditor(uri)
            navController.navigate(StudioRoutes.EDITOR) {
                launchSingleTop = true
            }
        }
    }

    // System file picker for single animated GIF import (preserves complete animation bytes)
    val singleGifPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            takePersistableReadPermissionBestEffort(context, uri)
            viewModel.loadVideoForEditor(uri)
            navController.navigate(StudioRoutes.EDITOR) {
                launchSingleTop = true
            }
        }
    }

    // System multi-document picker for mixed video & animated GIF batch conversion
    val multiMediaPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { takePersistableReadPermissionBestEffort(context, it) }
            viewModel.addBatchVideos(uris)
            if (navController.currentDestination?.route != StudioRoutes.BATCH) {
                navController.navigate(StudioRoutes.BATCH) {
                    launchSingleTop = true
                }
            }
        }
    }

    fun openExternalMedia(uri: Uri, mimeType: String = "image/gif") {
        val label = if (mimeType == "image/webp") "WebP" else "GIF"
        launchSafeIntent(context, GalleryExporter.buildOpenViewerIntent(uri, mimeType)) {
            scope.launch {
                snackbarHostState.showSnackbar("No external $label viewer app found on this device.")
            }
        }
    }

    fun shareMedia(uri: Uri, mimeType: String = "image/gif") {
        val title = if (mimeType == "image/webp") "Share Animated WebP" else "Share GIF"
        launchSafeIntent(context, GalleryExporter.buildShareIntent(uri, title, mimeType)) {
            scope.launch {
                snackbarHostState.showSnackbar("Unable to open Android share sheet.")
            }
        }
    }

    fun openExternalGif(uri: Uri) = openExternalMedia(uri, "image/gif")

    fun shareGif(uri: Uri) = shareMedia(uri, "image/gif")

    CompositionLocalProvider(LocalStudioHaptics provides studioHaptics) {
        GifStudioTheme(themeMode = themeMode) {
            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                modifier = Modifier.fillMaxSize()
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = StudioRoutes.HOME,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    composable(StudioRoutes.HOME) {
                        HomeScreen(
                            recentHistory = recentHistory,
                            onImportSingleVideo = {
                                singleVideoPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                                )
                            },
                            onImportSingleGif = {
                                singleGifPicker.launch(arrayOf("image/gif"))
                            },
                            onImportBatchVideos = {
                                multiMediaPicker.launch(arrayOf("video/*", "image/gif"))
                            },
                            onOpenHistory = {
                                navController.navigate(StudioRoutes.HISTORY) {
                                    launchSingleTop = true
                                }
                            },
                            onOpenSettings = { showSettings = true },
                            onOpenGif = ::openExternalGif,
                            onShareGif = ::shareGif,
                            onOpenMedia = ::openExternalMedia,
                            onShareMedia = ::shareMedia,
                            onOpenSupport = {
                                navController.navigate(StudioRoutes.SUPPORT) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }

                    composable(StudioRoutes.EDITOR) {
                        EditorScreen(
                            state = editorState,
                            onBack = { navController.popBackStack() },
                            onExportClick = {
                                runWithOptionalNotificationPermission {
                                    viewModel.startSingleExport()
                                    navController.navigate(StudioRoutes.EXPORT) {
                                        launchSingleTop = true
                                    }
                                }
                            },
                            onPreviewModeChange = viewModel::setPreviewMode,
                            onTabChange = viewModel::setActiveEditorTab,
                            onUpdateSettings = viewModel::updateEditorSettings,
                            onSeek = viewModel::setPlaybackPosition,
                            onTogglePlay = viewModel::togglePlayPause,
                            onStepFrame = viewModel::stepFrame,
                            onApplyAspectRatio = viewModel::applyAspectRatioPreset,
                            onRotate90 = viewModel::rotate90Degrees,
                            onToggleCropOverlay = viewModel::toggleCropOverlay,
                            onResetDefaults = {
                                viewModel.resetSettingsToDefaults()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Conversion settings reset to defaults")
                                }
                            },
                            onRequestOriginalFrame = viewModel::decodeOriginalFrame,
                            onRetryGifPreview = viewModel::retryLiveGifPreview,
                            presets = presets,
                            onApplyPreset = { preset ->
                                viewModel.applyPresetToEditor(preset)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Applied preset \"${preset.name}\"")
                                }
                            },
                            onSavePreset = { name ->
                                viewModel.saveCurrentEditorSettingsAsPreset(name)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Saved preset \"$name\"")
                                }
                            },
                            onRenamePreset = viewModel::renamePreset,
                            onDeletePreset = { id ->
                                viewModel.deletePreset(id)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Deleted preset")
                                }
                            },
                            onAnalyzeTargetSize = viewModel::analyzeTargetFileSize,
                            onAcceptTargetSizeRecommendation = {
                                viewModel.acceptTargetSizeRecommendation()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Applied target file size settings")
                                }
                            },
                            onDismissTargetSizeRecommendation = viewModel::dismissTargetSizeRecommendation,
                            onPreviewViewportChanged = viewModel::onPreviewViewportSizeChanged
                        )
                    }

                    composable(StudioRoutes.EXPORT) {
                        val activeMime = (exportState as? ExportUiState.Success)
                            ?.result?.stats?.outputFormat?.mimeType
                            ?: editorState.settings.outputFormat.mimeType
                        ExportScreen(
                            state = exportState,
                            outputFormat = editorState.settings.outputFormat,
                            onCancelExport = viewModel::cancelSingleExport,
                            onRetryExport = {
                                runWithOptionalNotificationPermission {
                                    viewModel.startSingleExport()
                                }
                            },
                            onShareGif = { uri -> shareMedia(uri, activeMime) },
                            onOpenGif = { uri -> openExternalMedia(uri, activeMime) },
                            onDone = {
                                viewModel.resetExportState()
                                navController.popBackStack(StudioRoutes.HOME, inclusive = false)
                            },
                            onBackToEditor = {
                                viewModel.resetExportState()
                                navController.popBackStack()
                            }
                        )
                    }

                    composable(StudioRoutes.BATCH) {
                        BatchConversionScreen(
                            state = batchState,
                            onBack = { navController.popBackStack() },
                            onAddVideos = {
                                multiMediaPicker.launch(arrayOf("video/*", "image/gif"))
                            },
                            onRemoveItem = viewModel::removeBatchItem,
                            onClearQueue = viewModel::clearBatchQueue,
                            onUpdateSharedSettings = viewModel::updateBatchSharedSettings,
                            onResetDefaults = {
                                viewModel.resetSettingsToDefaults()
                                scope.launch {
                                    snackbarHostState.showSnackbar("Batch settings reset to defaults")
                                }
                            },
                            onStartBatch = {
                                runWithOptionalNotificationPermission {
                                    viewModel.startBatchConversion()
                                }
                            },
                            onCancelBatch = viewModel::cancelBatchConversion,
                            onOpenGif = { uri ->
                                val mime = batchState.items
                                    .firstOrNull { it.savedResult?.contentUri == uri }
                                    ?.savedResult?.stats?.outputFormat?.mimeType
                                    ?: batchState.sharedSettings.outputFormat.mimeType
                                openExternalMedia(uri, mime)
                            },
                            onShareGif = { uri ->
                                val mime = batchState.items
                                    .firstOrNull { it.savedResult?.contentUri == uri }
                                    ?.savedResult?.stats?.outputFormat?.mimeType
                                    ?: batchState.sharedSettings.outputFormat.mimeType
                                shareMedia(uri, mime)
                            },
                            presets = presets,
                            onApplyPresetToBatch = { preset ->
                                viewModel.applyPresetToBatch(preset)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Applied preset \"${preset.name}\" to batch")
                                }
                            },
                            onSaveBatchPreset = { name ->
                                viewModel.saveBatchSettingsAsPreset(name)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Saved preset \"$name\"")
                                }
                            },
                            onRenamePreset = viewModel::renamePreset,
                            onDeletePreset = viewModel::deletePreset,
                            onMoveItemUp = viewModel::moveBatchItemUp,
                            onMoveItemDown = viewModel::moveBatchItemDown,
                            onRetryItem = { id ->
                                runWithOptionalNotificationPermission {
                                    viewModel.retryBatchItem(id)
                                }
                            },
                            onCancelItem = viewModel::cancelActiveBatchItem,
                            onRemoveCompleted = viewModel::removeCompletedBatchItems,
                            onSetItemCustomSettings = viewModel::setBatchItemCustomSettings
                        )
                    }

                    composable(StudioRoutes.HISTORY) {
                        HistoryScreen(
                            items = allHistory,
                            onBack = { navController.popBackStack() },
                            onOpenGif = ::openExternalGif,
                            onShareGif = ::shareGif,
                            onOpenMedia = ::openExternalMedia,
                            onShareMedia = ::shareMedia,
                            onDeleteItem = { item, deleteGallery ->
                                viewModel.deleteHistoryItem(item, deleteGallery)
                                scope.launch {
                                    snackbarHostState.showSnackbar("Removed from history")
                                }
                            }
                        )
                    }

                    composable(StudioRoutes.SUPPORT) {
                        SupportNepiScreen(
                            onBack = { navController.popBackStack() },
                            onAddressCopied = { option ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        "Copied ${option.ticker} (${option.networkName}) address"
                                    )
                                }
                            }
                        )
                    }
                }

                if (showSettings) {
                    SettingsBottomSheet(
                        currentThemeMode = themeMode,
                        hapticsEnabled = effectiveHapticsToggle,
                        onSelectThemeMode = viewModel::setThemeMode,
                        onToggleHaptics = viewModel::setHapticsEnabled,
                        onResetDefaults = {
                            viewModel.resetSettingsToDefaults()
                            Toast.makeText(context, "Default settings restored", Toast.LENGTH_SHORT).show()
                        },
                        onDismiss = { showSettings = false }
                    )
                }
            }
        }
    }
}

private fun takePersistableReadPermissionBestEffort(context: Context, uri: Uri) {
    try {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: Throwable) {
        // Not all document providers grant persistable permissions; transient grants still work.
    }
}

private fun launchSafeIntent(
    context: Context,
    intent: Intent,
    onFailure: () -> Unit
) {
    try {
        context.startActivity(intent)
    } catch (_: Throwable) {
        onFailure()
    }
}

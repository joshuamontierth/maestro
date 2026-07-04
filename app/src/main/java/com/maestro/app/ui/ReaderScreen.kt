package com.maestro.app.ui

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maestro.app.ReaderUiState
import com.maestro.app.ReaderViewModel
import com.maestro.app.face.CameraGestureEffect
import com.maestro.app.face.EngineStatus
import com.maestro.app.face.GestureType

@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val config by viewModel.config.collectAsStateWithLifecycle()
    // A direction set to "None" is fully disabled: no gesture AND no edge-tap turn.
    val prevEnabled = config.prevGesture != GestureType.NONE
    val nextEnabled = config.nextGesture != GestureType.NONE

    val pdfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.importPdf(uri) }
    val openPicker = { pdfPicker.launch(arrayOf("application/pdf")) }

    var chromeHidden by remember { mutableStateOf(false) }
    // Only go immersive while a document is open; never strand the user on a bare screen.
    val immersive = chromeHidden && state.hasDocument
    val chromeVisible = !immersive

    val detectionActive = state.detectionEnabled && hasCameraPermission && state.hasDocument
    CameraGestureEffect(active = detectionActive, analyzer = viewModel.faceAnalyzer)
    ImmersiveSystemBars(hidden = immersive)

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (!state.hasDocument) {
            EmptyState(
                isLoading = state.isLoading,
                onOpen = openPicker,
                onOpenSample = viewModel::openSample
            )
        } else {
            PageArea(
                state = state,
                viewModel = viewModel,
                onToggleChrome = { chromeHidden = !chromeHidden },
                prevEnabled = prevEnabled,
                nextEnabled = nextEnabled
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ControlBar(
                state = state,
                onOpen = openPicker,
                onToggleDetection = viewModel::setDetectionEnabled,
                onOpenSettings = onOpenSettings
            )
        }

        // Status stays visible even in immersive mode, so you always know whether the
        // camera is searching / ready / waiting while reading full-screen.
        if (state.hasDocument) {
            StatusOverlay(
                state = state,
                hasCameraPermission = hasCameraPermission,
                onRequestPermission = onRequestPermission,
                modifier = Modifier.align(Alignment.BottomEnd)
            )
        }
    }
}

/** Hides/shows the Android status & navigation bars for an immersive reading view. */
@Composable
private fun ImmersiveSystemBars(hidden: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    val context = LocalContext.current
    LaunchedEffect(hidden) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun EmptyState(isLoading: Boolean, onOpen: () -> Unit, onOpenSample: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Maestro", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "Open a PDF score, then turn pages with a glance.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.size(24.dp))
            if (isLoading) {
                CircularProgressIndicator(color = Color.White)
            } else {
                Button(onClick = onOpen) { Text("Open PDF") }
                Spacer(Modifier.size(12.dp))
                TextButton(onClick = onOpenSample) { Text("Open sample score") }
            }
        }
    }
}

@Composable
private fun PageArea(
    state: ReaderUiState,
    viewModel: ReaderViewModel,
    onToggleChrome: () -> Unit,
    prevEnabled: Boolean,
    nextEnabled: Boolean
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.White)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx().toInt() }
        LaunchedEffect(widthPx) { viewModel.setRenderWidth(widthPx) }

        val bitmap = state.pageBitmap
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Page ${state.pageIndex + 1}",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }

        // Tap layer: tap the left/right edge to turn pages; double-tap toggles immersive mode.
        // A direction disabled via "None" ignores its edge tap too.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(prevEnabled, nextEnabled) {
                    detectTapGestures(
                        onDoubleTap = { onToggleChrome() },
                        onTap = { offset ->
                            val w = size.width
                            when {
                                offset.x < w * 0.4f -> if (prevEnabled) viewModel.prevPage()
                                offset.x > w * 0.6f -> if (nextEnabled) viewModel.nextPage()
                            }
                        }
                    )
                }
        )
    }
}

@Composable
private fun ControlBar(
    state: ReaderUiState,
    onOpen: () -> Unit,
    onToggleDetection: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onOpen) { Text("Open", color = Color.White) }
        Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                text = state.fileName ?: "No document",
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleSmall
            )
            if (state.hasDocument) {
                Text(
                    text = "Page ${state.pageIndex + 1} / ${state.pageCount}",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Text("Gestures", color = Color.White, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.width(4.dp))
        Switch(checked = state.detectionEnabled, onCheckedChange = onToggleDetection)
        TextButton(onClick = onOpenSettings) { Text("Settings", color = Color.White) }
    }
}

@Composable
private fun StatusOverlay(
    state: ReaderUiState,
    hasCameraPermission: Boolean,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (color, label) = statusVisual(state, hasCameraPermission)
    val noRipple = remember { MutableInteractionSource() }

    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.End
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                .clickable(interactionSource = noRipple, indication = null) {
                    if (!hasCameraPermission) onRequestPermission()
                }
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelMedium)
        }

        if (state.detectionEnabled && hasCameraPermission && state.lastSignals.present) {
            Spacer(Modifier.size(4.dp))
            val s = state.lastSignals
            Text(
                text = "yaw ${s.yaw.toInt()}°  pitch ${s.pitch.toInt()}°  ·  L ${eye(s.leftEyeOpen)}  R ${eye(s.rightEyeOpen)}",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

private fun eye(v: Float): String = if (v < 0f) "--" else String.format("%.2f", v)

private fun statusVisual(state: ReaderUiState, hasCameraPermission: Boolean): Pair<Color, String> {
    if (!hasCameraPermission) return Color(0xFFE53935) to "Tap to allow camera"
    if (!state.detectionEnabled) return Color(0xFF9E9E9E) to "Gestures off"
    return when (state.status) {
        EngineStatus.NO_FACE -> Color(0xFF9E9E9E) to "Searching for face"
        EngineStatus.READY -> Color(0xFF43A047) to "Ready"
        EngineStatus.ARMING -> Color(0xFFFFB300) to "Hold…"
        EngineStatus.COOLDOWN -> Color(0xFF1E88E5) to "Wait"
    }
}

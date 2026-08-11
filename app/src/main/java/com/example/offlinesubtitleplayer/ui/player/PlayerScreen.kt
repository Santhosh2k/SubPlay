package com.example.offlinesubtitleplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView

fun Context.findActivity(): Activity? {
    var context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

@Composable
fun PlayerScreen(
    videoUri: String,
    videoTitle: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // unique key scopes the viewModel specifically to the selected video
    val viewModel: PlayerViewModel = viewModel(key = videoUri) {
        PlayerViewModel(
            context = context.applicationContext,
            videoUriString = videoUri,
            videoTitle = videoTitle
        )
    }

    val isSubtitleEnabled by viewModel.isSubtitleEnabled.collectAsStateWithLifecycle()
    val activeSubtitleText by viewModel.activeSubtitleText.collectAsStateWithLifecycle()
    val statusText by viewModel.statusText.collectAsStateWithLifecycle()
    val isExternalSrtActive by viewModel.isExternalSrtActive.collectAsStateWithLifecycle()

    var controlsVisible by remember { mutableStateOf(true) }

    val activity = remember(context) { context.findActivity() }

    // Toggle system status and navigation bars along with controls
    LaunchedEffect(controlsVisible) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (controlsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Ensure system UI is restored to normal when leaving player screen
    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val srtPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { stream ->
                    viewModel.loadExternalSrt(stream)
                }
            } catch (e: Exception) {
                Log.e("PlayerScreen", "Error loading SRT file", e)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 1. AndroidView wrapping native Media3 PlayerView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = viewModel.playerController.exoPlayer
                    useController = true
                    keepScreenOn = true
                    // Default timeout is 3000ms. We listen to the controller's visibility
                    // state to sync our custom compose controls overlays.
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controlsVisible = (visibility == View.VISIBLE)
                    })
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Custom Transparent Control Overlay
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Bar Overlay
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        IconButton(onClick = onBackClick) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = videoTitle,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }

                    if (isExternalSrtActive) {
                        Button(
                            onClick = { viewModel.clearExternalSubtitles() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFD32F2F),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear SRT",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear SRT", fontSize = 12.sp)
                        }
                    } else {
                        Button(
                            onClick = { srtPickerLauncher.launch("*/*") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF333333),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Import SRT",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import SRT", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Bottom Overlay: Subtitles & Toggle Controls
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Shift subtitles down dynamically when controls are hidden
                    .padding(bottom = if (controlsVisible) 64.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Subtitle display box
                AnimatedVisibility(
                    visible = isSubtitleEnabled && activeSubtitleText.isNotEmpty(),
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .widthIn(max = 640.dp)
                    ) {
                        Text(
                            text = activeSubtitleText,
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Controls fade out with controlsVisible
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column {
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = "Status",
                                    tint = Color(0xFF00E676),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isExternalSrtActive) "SRT: $statusText" else "AI: $statusText",
                                    color = Color.LightGray,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }

                            Button(
                                onClick = { viewModel.toggleSubtitles() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSubtitleEnabled) Color(0xFF00E676) else Color(0xFF555555),
                                    contentColor = if (isSubtitleEnabled) Color.Black else Color.White
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = if (isSubtitleEnabled) "CC ON" else "CC OFF",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

package com.example.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.theme.CoralEnd
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.TealPulse
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun CallScreen(
    viewModel: CallViewModel,
    onExitCall: () -> Unit
) {
    val context = LocalContext.current

    val callPhase by viewModel.callPhase.collectAsStateWithLifecycle()
    val peerName by viewModel.peerName.collectAsStateWithLifecycle()
    val peerPlatform by viewModel.peerPlatform.collectAsStateWithLifecycle()
    val elapsedSeconds by viewModel.elapsedSeconds.collectAsStateWithLifecycle()
    val localVideoTrack by viewModel.localVideoTrack.collectAsStateWithLifecycle()
    val remoteVideoTrack by viewModel.remoteVideoTrack.collectAsStateWithLifecycle()
    val isMicMuted by viewModel.isMicMuted.collectAsStateWithLifecycle()
    val isCameraEnabled by viewModel.isCameraEnabled.collectAsStateWithLifecycle()
    val isFrontCamera by viewModel.isFrontCamera.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val hasLocalCamera by viewModel.hasLocalCamera.collectAsStateWithLifecycle()

    var permissionsGranted by remember {
        mutableStateOf(hasCameraAndMicPermissions(context))
    }
    var permissionDeniedExplicitly by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val cameraOk = results[Manifest.permission.CAMERA] == true
        val audioOk = results[Manifest.permission.RECORD_AUDIO] == true
        if (cameraOk && audioOk) {
            permissionsGranted = true
            permissionDeniedExplicitly = false
            viewModel.onPermissionsGranted()
        } else {
            permissionsGranted = false
            permissionDeniedExplicitly = true
            viewModel.onPermissionsDenied()
        }
    }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) {
            viewModel.onPermissionsGranted()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            )
        }
    }

    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    BackHandler {
        viewModel.endCall(onExitCall)
    }

    var copiedNotice by remember { mutableStateOf(false) }
    LaunchedEffect(copiedNotice) {
        if (copiedNotice) {
            kotlinx.coroutines.delay(2000L)
            copiedNotice = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
    ) {
        if (!permissionsGranted) {
            PermissionGateOverlay(
                denied = permissionDeniedExplicitly,
                onRequestPermissions = {
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
                    )
                },
                onBack = { viewModel.endCall(onExitCall) }
            )
        } else {
            // 1. Remote Video Fullscreen Layer (or Waiting / Ended / Error Stage)
            val remoteTrack = remoteVideoTrack
            if (remoteTrack != null && callPhase is CallPhase.Connected) {
                WebRtcVideoSurface(
                    eglBase = viewModel.eglBase,
                    videoTrack = remoteTrack,
                    mirror = false,
                    isOverlay = false,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CallStageBackdrop(
                    phase = callPhase,
                    roomCode = viewModel.roomCode,
                    peerName = peerName,
                    copiedNotice = copiedNotice,
                    onCopyRoomCode = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("PulseCall Room Code", viewModel.roomCode))
                        copiedNotice = true
                    },
                    onShareInvite = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "Join my PulseCall video call on iOS or Android! Room Code: ${viewModel.roomCode}"
                            )
                        }
                        context.startActivity(Intent.createChooser(intent, "Share Room Code"))
                    },
                    onExit = { viewModel.endCall(onExitCall) }
                )
            }

            // Top & bottom subtle gradient scrims for legible controls over live video
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.68f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.78f))
                        )
                    )
            )

            // 2. Top Status Bar + Floating Local Camera PiP
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                CallStatusBadge(
                    phase = callPhase,
                    roomCode = viewModel.roomCode,
                    peerName = peerName,
                    peerPlatform = peerPlatform,
                    elapsedSeconds = elapsedSeconds,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Floating Local Preview PiP
                LocalCameraPip(
                    eglBase = viewModel.eglBase,
                    localTrack = localVideoTrack,
                    isCameraEnabled = isCameraEnabled && hasLocalCamera,
                    isFrontCamera = isFrontCamera
                )
            }

            // 3. Bottom Call Control Dock
            if (callPhase !is CallPhase.PeerEnded && callPhase !is CallPhase.Error) {
                CallControlDock(
                    isMicMuted = isMicMuted,
                    isCameraEnabled = isCameraEnabled,
                    isSpeakerOn = isSpeakerOn,
                    onToggleMic = viewModel::toggleMic,
                    onToggleCamera = viewModel::toggleCamera,
                    onSwitchCamera = viewModel::switchCamera,
                    onToggleSpeaker = viewModel::toggleSpeaker,
                    onEndCall = { viewModel.endCall(onExitCall) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                )
            }
        }
    }
}

@Composable
private fun CallStatusBadge(
    phase: CallPhase,
    roomCode: String,
    peerName: String,
    peerPlatform: String,
    elapsedSeconds: Long,
    modifier: Modifier = Modifier
) {
    val dotColor = when (phase) {
        is CallPhase.Connected -> TealPulse
        is CallPhase.PeerEnded, is CallPhase.Error -> CoralEnd
        else -> Color(0xFFF59E0B)
    }
    val statusText = when (phase) {
        is CallPhase.RequestingPermissions -> "Permissions"
        is CallPhase.Initializing -> "Starting camera..."
        is CallPhase.WaitingForPeer -> "Waiting · $roomCode"
        is CallPhase.Connecting -> "Connecting peer..."
        is CallPhase.Connected -> formatDuration(elapsedSeconds)
        is CallPhase.PeerEnded -> "Call ended"
        is CallPhase.Error -> "Connection error"
    }

    Surface(
        color = Color.Black.copy(alpha = 0.58f),
        shape = RoundedCornerShape(20.dp),
        modifier = modifier.border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.14f),
            shape = RoundedCornerShape(20.dp)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White
                )
            }
            if (phase is CallPhase.Connected || phase is CallPhase.Connecting) {
                val platformTag = when (peerPlatform.lowercase()) {
                    "ios" -> " · iOS"
                    "android" -> " · Android"
                    else -> ""
                }
                Text(
                    text = "$peerName$platformTag",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun LocalCameraPip(
    eglBase: EglBase,
    localTrack: VideoTrack?,
    isCameraEnabled: Boolean,
    isFrontCamera: Boolean
) {
    Box(
        modifier = Modifier
            .size(width = 112.dp, height = 158.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF141C2E))
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.25f),
                shape = RoundedCornerShape(20.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (localTrack != null && isCameraEnabled) {
            WebRtcVideoSurface(
                eglBase = eglBase,
                videoTrack = localTrack,
                mirror = isFrontCamera,
                isOverlay = true,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.VideocamOff,
                    contentDescription = "Camera off",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(26.dp)
                )
                Text(
                    text = "Cam Off",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun CallStageBackdrop(
    phase: CallPhase,
    roomCode: String,
    peerName: String,
    copiedNotice: Boolean,
    onCopyRoomCode: () -> Unit,
    onShareInvite: () -> Unit,
    onExit: () -> Unit
) {
    val pulseTransition = rememberInfiniteTransition(label = "waitingPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_waiting_room),
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.32f),
            contentScale = ContentScale.Crop
        )

        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (phase) {
                is CallPhase.WaitingForPeer -> {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(TealPulse.copy(alpha = 0.2f))
                            .border(2.dp, TealPulse, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = TealPulse,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Text(
                        text = "Room Ready",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )

                    Text(
                        text = "Share this code with your iPhone or Android peer to connect live.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f),
                        textAlign = TextAlign.Center
                    )

                    Surface(
                        color = Color(0xFF131D31).copy(alpha = 0.92f),
                        shape = RoundedCornerShape(22.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, TealPulse.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = roomCode,
                                fontFamily = JetBrainsMonoFamily,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = TealPulse,
                                modifier = Modifier.testTag("active_room_code")
                            )

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                OutlinedButton(
                                    onClick = onCopyRoomCode,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("copy_room_code_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (copiedNotice) "Copied" else "Copy")
                                }

                                Button(
                                    onClick = onShareInvite,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("share_room_code_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Share,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }
                        }
                    }
                }
                is CallPhase.Initializing, is CallPhase.Connecting -> {
                    CircularProgressIndicator(color = TealPulse)
                    Text(
                        text = if (phase is CallPhase.Initializing) "Preparing camera & audio..." else "Connecting to $peerName...",
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                }
                is CallPhase.Connected -> {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Person,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                    }
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = "Audio connected · Waiting for peer video frame",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                }
                is CallPhase.PeerEnded -> {
                    Text(
                        text = "Call Ended",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = "$peerName left the call room.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onExit,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("return_home_button")
                    ) {
                        Text("Return to Studio")
                    }
                }
                is CallPhase.Error -> {
                    Text(
                        text = "Unable to Connect",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text(
                        text = phase.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFECACA),
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = onExit,
                        colors = ButtonDefaults.buttonColors(containerColor = CoralEnd),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .height(50.dp)
                            .testTag("error_exit_button")
                    ) {
                        Text("Back to Home", color = Color.White)
                    }
                }
                is CallPhase.RequestingPermissions -> Unit
            }
        }
    }
}

@Composable
private fun CallControlDock(
    isMicMuted: Boolean,
    isCameraEnabled: Boolean,
    isSpeakerOn: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onEndCall: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xFF111827).copy(alpha = 0.88f),
        shape = RoundedCornerShape(32.dp),
        modifier = modifier.border(
            width = 1.dp,
            color = Color.White.copy(alpha = 0.14f),
            shape = RoundedCornerShape(32.dp)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DockIconButton(
                icon = if (isMicMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                contentDescription = if (isMicMuted) "Unmute microphone" else "Mute microphone",
                active = !isMicMuted,
                onClick = onToggleMic,
                testTag = "toggle_mic_button"
            )
            DockIconButton(
                icon = if (isCameraEnabled) Icons.Rounded.Videocam else Icons.Rounded.VideocamOff,
                contentDescription = if (isCameraEnabled) "Turn camera off" else "Turn camera on",
                active = isCameraEnabled,
                onClick = onToggleCamera,
                testTag = "toggle_camera_button"
            )
            DockIconButton(
                icon = Icons.Rounded.Cameraswitch,
                contentDescription = "Switch front or back camera",
                active = true,
                onClick = onSwitchCamera,
                testTag = "switch_camera_button"
            )
            DockIconButton(
                icon = if (isSpeakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                contentDescription = if (isSpeakerOn) "Switch to earpiece" else "Switch to speakerphone",
                active = isSpeakerOn,
                onClick = onToggleSpeaker,
                testTag = "toggle_speaker_button"
            )

            FilledIconButton(
                onClick = onEndCall,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = CoralEnd,
                    contentColor = Color.White
                ),
                modifier = Modifier
                    .size(54.dp)
                    .minimumInteractiveComponentSize()
                    .testTag("end_call_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.CallEnd,
                    contentDescription = stringResource(R.string.end_call),
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

@Composable
private fun DockIconButton(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) Color.White.copy(alpha = 0.14f) else Color(0xFF334155),
            contentColor = if (active) Color.White else Color(0xFFFCA5A5)
        ),
        modifier = Modifier
            .size(50.dp)
            .minimumInteractiveComponentSize()
            .testTag(testTag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun PermissionGateOverlay(
    denied: Boolean,
    onRequestPermissions: () -> Unit,
    onBack: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(26.dp),
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Videocam,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Text(
                    text = stringResource(R.string.permissions_required_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = if (denied) {
                        "Camera and microphone access were denied. Grant both permissions to stream video and audio in this call."
                    } else {
                        stringResource(R.string.permissions_required_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onRequestPermissions,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("grant_permissions_button")
                ) {
                    Text(stringResource(R.string.grant_permissions))
                }
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun WebRtcVideoSurface(
    eglBase: EglBase,
    videoTrack: VideoTrack?,
    mirror: Boolean,
    isOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    var boundTrack by remember { mutableStateOf<VideoTrack?>(null) }
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            boundTrack?.removeSink(rendererRef)
            rendererRef?.release()
            boundTrack = null
            rendererRef = null
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setEnableHardwareScaler(true)
                setZOrderMediaOverlay(isOverlay)
                setMirror(mirror)
                rendererRef = this
            }
        },
        update = { renderer ->
            renderer.setMirror(mirror)
            if (boundTrack != videoTrack) {
                boundTrack?.removeSink(renderer)
                videoTrack?.addSink(renderer)
                boundTrack = videoTrack
            }
        }
    )
}

private fun hasCameraAndMicPermissions(context: Context): Boolean {
    val cam = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED
    val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
    return cam && mic
}

private fun formatDuration(totalSeconds: Long): String {
    val mins = totalSeconds / 60
    val secs = totalSeconds % 60
    return String.format("%02d:%02d", mins, secs)
}

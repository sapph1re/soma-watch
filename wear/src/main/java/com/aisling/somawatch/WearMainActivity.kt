package com.aisling.somawatch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.AutoCenteringParams
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File

private object SomaColors {
    val Background = Color(0xFF000000) // Pure AMOLED black for seamless bezel blend
    val Surface = Color(0xFF141923)
    val SurfaceElevated = Color(0xFF1E2636)
    val Border = Color(0xFF283347)
    val Cyan = Color(0xFF38BDF8)
    val Indigo = Color(0xFF6366F1)
    val IndigoSoft = Color(0xFF818CF8)
    val Emerald = Color(0xFF10B981)
    val EmeraldContainer = Color(0xFF0C3B34)
    val Coral = Color(0xFFF43F5E)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("just_updated", false) == true) {
            SomaWearState.finishOtaMessage("Обновлено до v${BuildConfig.VERSION_NAME} ✓")
        }
        setContent {
            MaterialTheme {
                SomaDashboardScreen()
            }
        }
    }
}

@Composable
fun SomaDashboardScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)

    val statusMessage by SomaWearState.statusMessage.collectAsState()
    val lastSyncInfo by SomaWearState.lastSyncInfo.collectAsState()
    val otaState by SomaWearState.otaState.collectAsState()
    val pendingPrompt by SomaWearState.pendingVoicePrompt.collectAsState()

    val voiceRecorder = remember { WearVoiceRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        onDispose { voiceRecorder.stopAndDiscard() }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordSeconds = 0
            while (isRecording) {
                delay(1000L)
                recordSeconds++
                if (recordSeconds >= 90) {
                    isRecording = false
                    val file = voiceRecorder.stopAndGetFile()
                    if (file != null) {
                        scope.launch { sendVoiceAudioToPhone(context, file) }
                    }
                }
            }
        }
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (voiceRecorder.startRecording()) {
                triggerVibration(context, 70)
                isRecording = true
                SomaWearState.updateStatus("Слушаю...")
            } else {
                SomaWearState.updateStatus("Ошибка микрофона")
            }
        } else {
            SomaWearState.updateStatus("Нет доступа к микрофону")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SomaColors.Background)
    ) {
        Scaffold(
            timeText = { TimeText() },
            vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
            positionIndicator = {
                if (!otaState.isActive && !isRecording) {
                    PositionIndicator(scalingLazyListState = listState)
                }
            }
        ) {
            when {
                // Dedicated full-screen OTA progress view with circular bezel ring
                otaState.isActive -> {
                    OtaProgressOverlay(otaState = otaState)
                }

                // Dedicated full-screen Voice Recording view with pulsing coral bezel ring
                isRecording -> {
                    VoiceRecordingView(
                        seconds = recordSeconds,
                        onConfirm = {
                            isRecording = false
                            triggerVibration(context, 90)
                            val file = voiceRecorder.stopAndGetFile()
                            if (file != null) {
                                scope.launch { sendVoiceAudioToPhone(context, file) }
                            } else {
                                SomaWearState.updateStatus("Слишком коротко")
                            }
                        },
                        onCancel = {
                            isRecording = false
                            triggerVibration(context, 40)
                            voiceRecorder.stopAndDiscard()
                            SomaWearState.updateStatus("Отменено")
                        }
                    )
                }

                // Dedicated Gemini Transcription Confirmation view
                pendingPrompt != null -> {
                    VoiceConfirmView(
                        promptText = pendingPrompt ?: "",
                        onSend = {
                            val text = pendingPrompt ?: return@VoiceConfirmView
                            triggerVibration(context, 90)
                            SomaWearState.setPendingVoicePrompt(null)
                            scope.launch { sendVoicePromptToPhone(context, text) }
                        },
                        onCancel = {
                            triggerVibration(context, 40)
                            SomaWearState.setPendingVoicePrompt(null)
                            SomaWearState.updateStatus("Отменено")
                        }
                    )
                }

                // Main Soma Watch Dashboard (Wear OS Material Design Guidelines)
                else -> {
                    ScalingLazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        state = listState,
                        autoCentering = AutoCenteringParams(itemIndex = 1),
                        contentPadding = PaddingValues(top = 34.dp, bottom = 36.dp, start = 12.dp, end = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Brand & Version Pill
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(SomaColors.Surface)
                                    .border(1.dp, SomaColors.Border, RoundedCornerShape(50))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(SomaColors.Cyan)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "SOMA",
                                    color = SomaColors.TextPrimary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    letterSpacing = 1.4.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "v${BuildConfig.VERSION_NAME}",
                                    color = SomaColors.TextMuted,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Primary Action: Voice Idea -> Gemini -> Agent
                        item {
                            Chip(
                                onClick = {
                                    val hasMic = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasMic) {
                                        if (voiceRecorder.startRecording()) {
                                            triggerVibration(context, 70)
                                            isRecording = true
                                            SomaWearState.updateStatus("Слушаю...")
                                        } else {
                                            SomaWearState.updateStatus("Ошибка микрофона")
                                        }
                                    } else {
                                        micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp),
                                colors = ChipDefaults.gradientBackgroundChipColors(
                                    startBackgroundColor = SomaColors.Indigo,
                                    endBackgroundColor = Color(0xFF4F46E5)
                                ),
                                icon = {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Mic,
                                            contentDescription = "Диктовка",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                },
                                label = {
                                    Text(
                                        text = "Идея агенту",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                secondaryLabel = {
                                    Text(
                                        text = "Голос • Gemini AI",
                                        color = Color.White.copy(alpha = 0.78f),
                                        fontSize = 10.sp,
                                        maxLines = 1
                                    )
                                }
                            )
                        }

                        // Optional dismissible OTA completion/error banner
                        otaState.bannerText?.let { banner ->
                            item {
                                Chip(
                                    onClick = { SomaWearState.finishOtaMessage(null) },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ChipDefaults.primaryChipColors(
                                        backgroundColor = SomaColors.EmeraldContainer
                                    ),
                                    icon = {
                                        Icon(
                                            imageVector = Icons.Rounded.AutoAwesome,
                                            contentDescription = null,
                                            tint = SomaColors.Emerald,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = banner,
                                            color = SomaColors.TextPrimary,
                                            fontSize = 11.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                )
                            }
                        }

                        // Symmetrical Action Row: Ping Phone + Cloud OTA
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 2.dp)
                            ) {
                                Button(
                                    onClick = {
                                        triggerVibration(context, 60)
                                        scope.launch { sendPingToPhone(context) }
                                    },
                                    modifier = Modifier.size(50.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = SomaColors.SurfaceElevated
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Smartphone,
                                        contentDescription = "Пинг телефона",
                                        tint = SomaColors.Cyan,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }

                                Button(
                                    onClick = {
                                        triggerVibration(context, 70)
                                        scope.launch { requestOtaCheckFromPhone(context) }
                                    },
                                    modifier = Modifier.size(50.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        backgroundColor = SomaColors.EmeraldContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SystemUpdate,
                                        contentDescription = "Обновить OTA",
                                        tint = SomaColors.Emerald,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }

                        // Glanceable Status Footer
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = statusMessage,
                                    color = SomaColors.TextSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = lastSyncInfo,
                                    color = SomaColors.TextMuted,
                                    fontSize = 9.sp,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OtaProgressOverlay(otaState: OtaUiState) {
    val animatedProgress by animateFloatAsState(
        targetValue = otaState.progress ?: 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "otaProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF0A1929), SomaColors.Background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Bezel-hugging circular progress ring around the entire round display
        if (otaState.progress != null) {
            CircularProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                startAngle = 270f,
                endAngle = 270f,
                indicatorColor = SomaColors.Cyan,
                trackColor = Color(0xFF1E293B),
                strokeWidth = 5.dp
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                indicatorColor = SomaColors.Cyan,
                trackColor = Color(0xFF1E293B),
                strokeWidth = 5.dp
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 24.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = SomaColors.Cyan,
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            if (otaState.progress != null) {
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    color = SomaColors.TextPrimary,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            } else {
                Text(
                    text = "OTA",
                    color = SomaColors.TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.5.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = otaState.bannerText ?: "Загрузка обновления...",
                color = SomaColors.TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
            otaState.versionLabel?.let { ver ->
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = ver,
                    color = SomaColors.Cyan.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun VoiceRecordingView(
    seconds: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "recPulse")
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ringAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF220A12), SomaColors.Background)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = (seconds / 90f).coerceIn(0.03f, 1f),
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            startAngle = 270f,
            endAngle = 270f,
            indicatorColor = SomaColors.Coral.copy(alpha = ringAlpha),
            trackColor = Color(0xFF2D141C),
            strokeWidth = 5.dp
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Mic,
                contentDescription = null,
                tint = SomaColors.Coral.copy(alpha = ringAlpha),
                modifier = Modifier.size(26.dp)
            )
            Text(
                text = String.format("%02d:%02d", seconds / 60, seconds % 60),
                color = SomaColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "Диктуй задачу",
                color = SomaColors.TextSecondary,
                fontSize = 11.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.size(50.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.SurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Отмена",
                        tint = SomaColors.Coral,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.size(50.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.Emerald)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = "Отправить в Gemini",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VoiceConfirmView(
    promptText: String,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SomaColors.Background),
        state = listState,
        contentPadding = PaddingValues(top = 34.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = null,
                    tint = SomaColors.IndigoSoft,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "GEMINI",
                    color = SomaColors.IndigoSoft,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp
                )
            }
        }
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SomaColors.Surface)
                    .border(1.dp, SomaColors.Border, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Text(
                    text = promptText,
                    color = SomaColors.TextPrimary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.SurfaceElevated)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Отмена",
                        tint = SomaColors.Coral,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Button(
                    onClick = onSend,
                    modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.Emerald)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Send,
                        contentDescription = "Создать Issue",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun triggerVibration(context: Context, ms: Long) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vm.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
}

private suspend fun sendVoiceAudioToPhone(context: Context, audioFile: File) = withContext(Dispatchers.IO) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaWearState.updateStatus("Телефон не найден")
            return@withContext
        }
        val sizeKb = audioFile.length() / 1024
        SomaWearState.updateStatus("Аудио ($sizeKb КБ) → Gemini...")
        val targetNode = nodes.first()
        val channelClient = Wearable.getChannelClient(context)
        val channel = channelClient.openChannel(targetNode.id, "/soma/voice_audio").await()
        val outputStream = channelClient.getOutputStream(channel).await()
        audioFile.inputStream().use { input ->
            outputStream.use { output ->
                input.copyTo(output)
                output.flush()
            }
        }
        SomaWearState.updateStatus("Gemini расшифровывает...")
    } catch (e: Exception) {
        SomaWearState.updateStatus("Ошибка аудио: ${e.localizedMessage}")
    } finally {
        runCatching { audioFile.delete() }
    }
}

private suspend fun sendVoicePromptToPhone(context: Context, prompt: String) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaWearState.updateStatus("Телефон не найден")
            return
        }
        SomaWearState.updateStatus("Создаём задачу...")
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/voice_prompt", prompt.toByteArray(Charsets.UTF_8))
                .await()
        }
    } catch (e: Exception) {
        SomaWearState.updateStatus("Ошибка: ${e.localizedMessage}")
    }
}

private suspend fun sendPingToPhone(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaWearState.updateStatus("Телефон не найден")
            return
        }
        val msg = "Пинг с Soma Watch v${BuildConfig.VERSION_NAME}"
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/ping", msg.toByteArray(Charsets.UTF_8))
                .await()
        }
        SomaWearState.updateStatus("Пинг → ${nodes.first().displayName}")
    } catch (e: Exception) {
        SomaWearState.updateStatus("Ошибка: ${e.localizedMessage}")
    }
}

private suspend fun requestOtaCheckFromPhone(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaWearState.finishOtaMessage("Телефон не подключен")
            return
        }
        SomaWearState.startOtaIndeterminate("Проверяем релиз...")
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/request_ota", "check".toByteArray(Charsets.UTF_8))
                .await()
        }
    } catch (e: Exception) {
        SomaWearState.finishOtaMessage("Ошибка: ${e.localizedMessage}")
    }
}

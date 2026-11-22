package com.aisling.somawatch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.rounded.DirectionsRun
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
    val Background = Color(0xFF000000)
    val Surface = Color(0xFF121722)
    val SurfaceElevated = Color(0xFF1C2433)
    val Border = Color(0xFF263145)
    val Cyan = Color(0xFF38BDF8)
    val Indigo = Color(0xFF6366F1)
    val IndigoSoft = Color(0xFF818CF8)
    val Emerald = Color(0xFF10B981)
    val EmeraldContainer = Color(0xFF0B3831)
    val Amber = Color(0xFFF59E0B)
    val Orange = Color(0xFFF97316)
    val Coral = Color(0xFFF43F5E)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private fun zoneColor(zone: Int): Color = when (zone) {
    1 -> SomaColors.TextSecondary
    2 -> SomaColors.Emerald
    3 -> SomaColors.Amber
    4 -> SomaColors.Orange
    5 -> SomaColors.Coral
    else -> SomaColors.Cyan
}

private fun zoneLabel(zone: Int): String = when (zone) {
    1 -> "Z1 • Разминка"
    2 -> "Z2 • Аэробная"
    3 -> "Z3 • Темп"
    4 -> "Z4 • Порог"
    5 -> "Z5 • VO2 Max"
    else -> "Измерение..."
}

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SomaBiometricsEngine.init(this)
        SomaNoiseMonitor.init(this)
        if (intent?.getBooleanExtra("just_updated", false) == true) {
            SomaWearState.finishOtaMessage("Обновлено до v${BuildConfig.VERSION_NAME} ✓")
            SomaWearState.setAvailableUpdate(null)
        }
        setContent {
            MaterialTheme {
                SomaRootScreen()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        SomaBiometricsEngine.stopPassiveMonitoringIfIdle()
    }
}

private fun requiredHrPermissions(): Array<String> {
    val perms = mutableListOf(Manifest.permission.BODY_SENSORS)
    if (Build.VERSION.SDK_INT >= 36) {
        perms.add("android.permission.health.READ_HEART_RATE")
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        perms.add(Manifest.permission.ACTIVITY_RECOGNITION)
    }
    return perms.toTypedArray()
}

private fun hasHrPermission(context: Context): Boolean {
    val bodyOk = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.BODY_SENSORS
    ) == PackageManager.PERMISSION_GRANTED
    val healthHrOk = if (Build.VERSION.SDK_INT >= 36) {
        ContextCompat.checkSelfPermission(
            context,
            "android.permission.health.READ_HEART_RATE"
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
    return bodyOk || healthHrOk
}

@Composable
fun SomaRootScreen() {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    val statusMessage by SomaWearState.statusMessage.collectAsState()
    val lastSyncInfo by SomaWearState.lastSyncInfo.collectAsState()
    val otaState by SomaWearState.otaState.collectAsState()
    val availableUpdate by SomaWearState.availableUpdate.collectAsState()
    val pendingPrompt by SomaWearState.pendingVoicePrompt.collectAsState()
    val bio by SomaBiometricsEngine.state.collectAsState()
    val noise by SomaNoiseMonitor.state.collectAsState()

    val voiceRecorder = remember { WearVoiceRecorder(context) }
    var isRecording by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableIntStateOf(0) }
    var pendingWorkoutStart by remember { mutableStateOf(false) }

    // Keep screen ON and non-minimizing during active Workout or HRR Recovery!
    val keepAwake = bio.phase == WorkoutPhase.ACTIVE || bio.phase == WorkoutPhase.HRR_RECOVERY
    DisposableEffect(keepAwake) {
        if (keepAwake) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity?.setShowWhenLocked(true)
                activity?.setTurnScreenOn(true)
            }
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity?.setShowWhenLocked(false)
                activity?.setTurnScreenOn(false)
            }
        }
        onDispose {
            if (!keepAwake) {
                activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    val hrPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results.values.any { it } || hasHrPermission(context)
        if (granted) {
            SomaBiometricsEngine.startPassiveMonitoring(context)
            if (pendingWorkoutStart) {
                pendingWorkoutStart = false
                SomaBiometricsEngine.startWorkout(context)
            }
        } else {
            pendingWorkoutStart = false
            SomaWearState.updateStatus("Нужен доступ к пульсу")
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

    // On open: start passive HR/HRV monitoring (if permitted) + silently check for newer OTA!
    LaunchedEffect(Unit) {
        if (hasHrPermission(context)) {
            SomaBiometricsEngine.startPassiveMonitoring(context)
        } else {
            hrPermLauncher.launch(requiredHrPermissions())
        }
        checkOtaVersionSilently(context)
    }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SomaColors.Background)
    ) {
        when {
            otaState.isActive -> {
                OtaProgressOverlay(otaState = otaState)
            }

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

            bio.phase == WorkoutPhase.ACTIVE -> {
                ActiveWorkoutScreen(
                    bio = bio,
                    onStartHrr = { SomaBiometricsEngine.beginHrrRecovery(context) },
                    onStopNow = {
                        triggerVibration(context, 110)
                        SomaBiometricsEngine.finishWorkoutNow(context)
                    }
                )
            }

            bio.phase == WorkoutPhase.HRR_RECOVERY -> {
                HrrRecoveryScreen(
                    bio = bio,
                    onFinishEarly = {
                        triggerVibration(context, 80)
                        SomaBiometricsEngine.finishWorkoutNow(context)
                    }
                )
            }

            bio.phase == WorkoutPhase.SUMMARY -> {
                WorkoutSummaryScreen(
                    bio = bio,
                    onClose = { SomaBiometricsEngine.dismissSummary() }
                )
            }

            else -> {
                MainDashboardView(
                    bio = bio,
                    noise = noise,
                    statusMessage = statusMessage,
                    lastSyncInfo = lastSyncInfo,
                    otaState = otaState,
                    availableUpdate = availableUpdate,
                    onStartWorkout = {
                        triggerVibration(context, 80)
                        SomaBiometricsEngine.dismissAutoWorkoutSuggestion()
                        if (hasHrPermission(context)) {
                            SomaBiometricsEngine.startWorkout(context)
                        } else {
                            pendingWorkoutStart = true
                            hrPermLauncher.launch(requiredHrPermissions())
                        }
                    },
                    onDismissAutoWorkout = {
                        SomaBiometricsEngine.dismissAutoWorkoutSuggestion()
                    },
                    onToggleNoiseMonitor = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            triggerVibration(context, 50)
                            SomaNoiseMonitor.toggleMonitoring(context, !noise.isMonitoring)
                        } else {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onMeasureNoiseOnce = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            triggerVibration(context, 40)
                            SomaNoiseMonitor.measureOnce(context)
                        } else {
                            micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onStartVoice = {
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
                    onInstallOta = {
                        triggerVibration(context, 70)
                        scope.launch { requestOtaDownloadFromPhone(context) }
                    },
                    onCheckOtaManual = {
                        triggerVibration(context, 40)
                        SomaWearState.updateStatus("Проверка обновлений...")
                        scope.launch { checkOtaVersionSilently(context) }
                    },
                    onPingPhone = {
                        triggerVibration(context, 60)
                        scope.launch { sendPingToPhone(context) }
                    },
                    onDismissBanner = { SomaWearState.finishOtaMessage(null) }
                )
            }
        }
    }
}

@Composable
private fun MainDashboardView(
    bio: BiometricsUiState,
    noise: NoiseUiState,
    statusMessage: String,
    lastSyncInfo: String,
    otaState: OtaUiState,
    availableUpdate: AvailableOta?,
    onStartWorkout: () -> Unit,
    onDismissAutoWorkout: () -> Unit,
    onToggleNoiseMonitor: () -> Unit,
    onMeasureNoiseOnce: () -> Unit,
    onStartVoice: () -> Unit,
    onInstallOta: () -> Unit,
    onCheckOtaManual: () -> Unit,
    onPingPhone: () -> Unit,
    onDismissBanner: () -> Unit
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)
    val liveZoneColor by animateColorAsState(
        targetValue = zoneColor(bio.currentZone),
        animationSpec = tween(300),
        label = "dashZoneColor"
    )

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            autoCentering = AutoCenteringParams(itemIndex = 1),
            contentPadding = PaddingValues(top = 34.dp, bottom = 36.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Brand Pill (tap to silently check for cloud OTA updates)
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(SomaColors.Surface)
                        .border(1.dp, SomaColors.Border, RoundedCornerShape(50))
                        .clickable { onCheckOtaManual() }
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (bio.isSensorActive) SomaColors.Emerald else SomaColors.Cyan)
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
                        fontSize = 9.sp
                    )
                }
            }

            // 2. Live Biometrics Hero Card (HR + HRV + HRR)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(SomaColors.Surface)
                        .border(1.dp, SomaColors.Border, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = "Пульс",
                            tint = liveZoneColor,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (bio.currentHr > 0) "${bio.currentHr}" else "--",
                            color = SomaColors.TextPrimary,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "BPM",
                            color = SomaColors.TextMuted,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricMiniBadge(
                            label = "HRV",
                            value = bio.currentHrvMs?.let { "${it}ms" } ?: "--",
                            accent = SomaColors.Cyan
                        )
                        MetricMiniBadge(
                            label = "HRR-1",
                            value = bio.weekly.lastHrr1?.let { "-$it" } ?: "--",
                            accent = SomaColors.Emerald
                        )
                    }
                }
            }

            // Auto-detected Dynamic/Strength Workout Prompt (if detected by HR + Linear Accel)
            bio.autoWorkoutSuggestion?.let { suggestion ->
                item {
                    Chip(
                        onClick = onStartWorkout,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ChipDefaults.gradientBackgroundChipColors(
                            startBackgroundColor = SomaColors.Orange,
                            endBackgroundColor = SomaColors.Coral
                        ),
                        icon = {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DirectionsRun,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        label = {
                            Text(
                                text = suggestion,
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2
                            )
                        }
                    )
                }
            }

            // 3. Primary Action: Non-Minimizing Always-On Workout / Run
            item {
                Chip(
                    onClick = onStartWorkout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    colors = ChipDefaults.gradientBackgroundChipColors(
                        startBackgroundColor = SomaColors.Emerald,
                        endBackgroundColor = Color(0xFF059669)
                    ),
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.DirectionsRun,
                                contentDescription = "Бег",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            text = "Бег / Кардио",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "Экран всегда активен",
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 10.sp,
                            maxLines = 1
                        )
                    }
                )
            }

            // 4. Weekly Cardio Budget (Bryan Johnson / Blueprint Zone 2 + Zone 4-5)
            item {
                val w = bio.weekly
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SomaColors.Surface)
                        .border(1.dp, SomaColors.Border, RoundedCornerShape(16.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "КАРДИО ЗА НЕДЕЛЮ",
                        color = SomaColors.TextMuted,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    ZoneProgressRow(
                        label = "Zone 2",
                        minutes = w.zone2Minutes,
                        goal = w.zone2GoalMin,
                        color = SomaColors.Emerald
                    )
                    ZoneProgressRow(
                        label = "Z4–5 HIIT",
                        minutes = w.vigorousMinutes,
                        goal = w.vigorousGoalMin,
                        color = SomaColors.Coral
                    )
                    if (w.zone3Minutes > 0) {
                        Text(
                            text = "Zone 3 (темп): ${w.zone3Minutes} мин",
                            color = SomaColors.Amber.copy(alpha = 0.85f),
                            fontSize = 9.sp
                        )
                    }
                }
            }

            // 4b. Battery-Smart Duty-Cycled Decibel Meter Card
            item {
                val dbColor = when {
                    noise.currentDb >= noise.thresholdDb -> SomaColors.Coral
                    noise.currentDb >= 70 -> SomaColors.Amber
                    noise.currentDb > 0 -> SomaColors.Emerald
                    else -> SomaColors.TextMuted
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SomaColors.Surface)
                        .border(
                            1.dp,
                            if (noise.isLoudAlert) SomaColors.Coral else SomaColors.Border,
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onMeasureNoiseOnce() }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = "Шум",
                                tint = dbColor,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (noise.currentDb > 0) "${noise.currentDb} дБ" else "-- дБ",
                                color = SomaColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (noise.isMonitoring) SomaColors.EmeraldContainer else SomaColors.SurfaceElevated)
                                .clickable { onToggleNoiseMonitor() }
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = if (noise.isMonitoring) "ФОН ВКЛ" else "АЛЕРТ >${noise.thresholdDb}",
                                color = if (noise.isMonitoring) SomaColors.Emerald else SomaColors.TextSecondary,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    val dbFraction = ((noise.currentDb - 30f) / 80f).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(SomaColors.SurfaceElevated)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(dbFraction)
                                .height(4.dp)
                                .clip(RoundedCornerShape(50))
                                .background(dbColor)
                        )
                    }
                }
            }

            // 5. Conditional Cloud OTA Update Chip — ONLY shown when a NEWER version exists!
            if (availableUpdate != null) {
                item {
                    Chip(
                        onClick = onInstallOta,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        colors = ChipDefaults.gradientBackgroundChipColors(
                            startBackgroundColor = Color(0xFF0284C7),
                            endBackgroundColor = Color(0xFF0369A1)
                        ),
                        icon = {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = "Обновить",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        label = {
                            Text(
                                text = "Обновить OTA",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = availableUpdate.label,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 10.sp
                            )
                        }
                    )
                }
            }

            // 6. Voice Prompt -> Gemini -> GitHub Issue
            item {
                Chip(
                    onClick = onStartVoice,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    colors = ChipDefaults.gradientBackgroundChipColors(
                        startBackgroundColor = SomaColors.Indigo,
                        endBackgroundColor = Color(0xFF4F46E5)
                    ),
                    icon = {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Mic,
                                contentDescription = "Диктовка",
                                tint = Color.White,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    },
                    label = {
                        Text(
                            text = "Идея агенту",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    secondaryLabel = {
                        Text(
                            text = "Голос • Gemini AI",
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 10.sp
                        )
                    }
                )
            }

            // Optional dismissible OTA completion banner
            otaState.bannerText?.let { banner ->
                item {
                    Chip(
                        onClick = onDismissBanner,
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
                                maxLines = 2
                            )
                        }
                    )
                }
            }

            // 7. Compact Phone Ping & Status Footer
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp)
                ) {
                    Button(
                        onClick = onPingPhone,
                        modifier = Modifier.size(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = SomaColors.SurfaceElevated
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Smartphone,
                            contentDescription = "Пинг телефона",
                            tint = SomaColors.Cyan,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = statusMessage,
                        color = SomaColors.TextSecondary,
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                    Text(
                        text = lastSyncInfo,
                        color = SomaColors.TextMuted,
                        fontSize = 9.sp,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricMiniBadge(label: String, value: String, accent: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            color = SomaColors.TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = value,
            color = accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

@Composable
private fun ZoneProgressRow(label: String, minutes: Int, goal: Int, color: Color) {
    val fraction = (minutes.toFloat() / goal.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, color = SomaColors.TextPrimary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            Text(text = "$minutes / $goal м", color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(50))
                .background(SomaColors.SurfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(if (minutes > 0) 0.04f else 0f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ActiveWorkoutScreen(
    bio: BiometricsUiState,
    onStartHrr: () -> Unit,
    onStopNow: () -> Unit
) {
    val activeZoneColor by animateColorAsState(
        targetValue = zoneColor(bio.currentZone),
        animationSpec = tween(350),
        label = "workoutZoneColor"
    )
    val hrFraction by animateFloatAsState(
        targetValue = if (bio.currentHr > 0) (bio.currentHr.toFloat() / bio.weekly.maxHr.toFloat()).coerceIn(0.15f, 1f) else 0.1f,
        animationSpec = tween(300),
        label = "hrRingProgress"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SomaColors.Background),
        contentAlignment = Alignment.Center
    ) {
        // Outer Zone-Colored Bezel Ring
        CircularProgressIndicator(
            progress = hrFraction,
            modifier = Modifier
                .fillMaxSize()
                .padding(3.dp),
            startAngle = 270f,
            endAngle = 270f,
            indicatorColor = activeZoneColor,
            trackColor = Color(0xFF151C28),
            strokeWidth = 5.dp
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Elapsed Time + Zone Pill
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = formatDuration(bio.elapsedSeconds),
                    color = SomaColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = zoneLabel(bio.currentZone),
                    color = activeZoneColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Middle: Giant Glanceable BPM + Live HRV + Sparkline Graph
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (bio.currentHr > 0) "${bio.currentHr}" else "--",
                        color = SomaColors.TextPrimary,
                        fontSize = 42.sp,
                        fontWeight = FontWeight.Black
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column(modifier = Modifier.padding(bottom = 7.dp)) {
                        Text(
                            text = "BPM",
                            color = activeZoneColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = bio.currentHrvMs?.let { "HRV ${it}ms" } ?: "HRV --",
                            color = SomaColors.Cyan,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Live HR Sparkline Graph with shaded Zone 2 band
                HeartRateSparkline(
                    samples = bio.hrHistory,
                    maxHr = bio.weekly.maxHr,
                    lineColor = activeZoneColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SomaColors.Surface)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Z2: ${bio.sessionZone2Sec / 60}м  •  HIIT: ${bio.sessionVigorousSec / 60}м  •  Пик: ${bio.peakHr}",
                    color = SomaColors.TextSecondary,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }

            // Bottom: HRR Cooldown + Stop Controls
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onStartHrr,
                    modifier = Modifier.size(44.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.EmeraldContainer)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SelfImprovement,
                        contentDescription = "Заминка HRR",
                        tint = SomaColors.Emerald,
                        modifier = Modifier.size(21.dp)
                    )
                }
                Button(
                    onClick = onStopNow,
                    modifier = Modifier.size(44.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = Color(0xFF3A121D))
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = "Стоп",
                        tint = SomaColors.Coral,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeartRateSparkline(
    samples: List<Int>,
    maxHr: Int,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val minVal = 60f
        val maxVal = maxHr.toFloat().coerceAtLeast(170f)
        val range = (maxVal - minVal).coerceAtLeast(1f)

        // Draw shaded Zone 2 band (60%–70% Max HR)
        val z2Low = maxHr * 0.60f
        val z2High = maxHr * 0.70f
        val yTop = h - ((z2High - minVal) / range).coerceIn(0f, 1f) * h
        val yBot = h - ((z2Low - minVal) / range).coerceIn(0f, 1f) * h
        drawRect(
            color = SomaColors.Emerald.copy(alpha = 0.16f),
            topLeft = Offset(0f, yTop),
            size = Size(w, (yBot - yTop).coerceAtLeast(2f))
        )

        if (samples.size < 2) return@Canvas
        val path = Path()
        val stepX = w / (samples.size - 1).coerceAtLeast(1)
        samples.forEachIndexed { idx, bpm ->
            val x = idx * stepX
            val y = h - ((bpm.toFloat() - minVal) / range).coerceIn(0f, 1f) * h
            if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

@Composable
private fun HrrRecoveryScreen(
    bio: BiometricsUiState,
    onFinishEarly: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = (bio.recoveryElapsedSec / 120f).coerceIn(0f, 1f),
        animationSpec = tween(250),
        label = "hrrProgress"
    )
    val liveDrop = if (bio.recoveryStartHr > 0 && bio.currentHr > 0) {
        (bio.recoveryStartHr - bio.currentHr).coerceAtLeast(0)
    } else {
        0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(listOf(Color(0xFF07231E), SomaColors.Background))
            ),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = progress,
            modifier = Modifier
                .fillMaxSize()
                .padding(4.dp),
            startAngle = 270f,
            endAngle = 270f,
            indicatorColor = SomaColors.Emerald,
            trackColor = Color(0xFF162C27),
            strokeWidth = 5.dp
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 20.dp)
        ) {
            Text(
                text = "ЗАМИНКА • HRR",
                color = SomaColors.Emerald,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
            Text(
                text = "-$liveDrop BPM",
                color = SomaColors.TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black
            )
            Text(
                text = "${bio.recoveryStartHr} → ${bio.currentHr} BPM  •  ${formatDuration(bio.recoveryElapsedSec)} / 02:00",
                color = SomaColors.TextSecondary,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
            bio.hrr1Drop?.let { d1 ->
                Text(
                    text = "HRR-1: -$d1 BPM ✓",
                    color = SomaColors.Cyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onFinishEarly,
                modifier = Modifier.size(44.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.SurfaceElevated)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Завершить",
                    tint = SomaColors.Emerald,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun WorkoutSummaryScreen(
    bio: BiometricsUiState,
    onClose: () -> Unit
) {
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SomaColors.Background),
        state = listState,
        contentPadding = PaddingValues(top = 30.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = "ИТОГИ ТРЕНИРОВКИ",
                color = SomaColors.Emerald,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp
            )
        }
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SomaColors.Surface)
                    .border(1.dp, SomaColors.Border, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Время: ${formatDuration(bio.elapsedSeconds)}", color = SomaColors.TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("Пульс ср/пик: ${bio.avgHr} / ${bio.peakHr} BPM", color = SomaColors.TextSecondary, fontSize = 11.sp)
                Text("Zone 2: +${bio.sessionZone2Sec / 60} мин  •  HIIT: +${bio.sessionVigorousSec / 60} мин", color = SomaColors.Emerald, fontSize = 11.sp)
                if (bio.hrr1Drop != null || bio.hrr2Drop != null) {
                    Text(
                        text = "HRR-1: ${bio.hrr1Drop?.let { "-$it" } ?: "--"}  •  HRR-2: ${bio.hrr2Drop?.let { "-$it" } ?: "--"}",
                        color = SomaColors.Cyan,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        item {
            Button(
                onClick = onClose,
                modifier = Modifier.size(48.dp),
                colors = ButtonDefaults.buttonColors(backgroundColor = SomaColors.Emerald)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "Готово",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp)
                )
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
                text = formatDuration(seconds),
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
                        imageVector = Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "Создать Issue",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

private fun formatDuration(totalSec: Int): String {
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
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

private suspend fun checkOtaVersionSilently(context: Context) {
    runCatching {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val versionCodeStr = BuildConfig.VERSION_CODE.toString()
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/check_ota_version", versionCodeStr.toByteArray(Charsets.UTF_8))
                .await()
        }
    }
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

private suspend fun requestOtaDownloadFromPhone(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaWearState.finishOtaMessage("Телефон не подключен")
            return
        }
        SomaWearState.startOtaIndeterminate("Запрос обновления...")
        val versionCodeStr = BuildConfig.VERSION_CODE.toString()
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/request_ota", versionCodeStr.toByteArray(Charsets.UTF_8))
                .await()
        }
    } catch (e: Exception) {
        SomaWearState.finishOtaMessage("Ошибка: ${e.localizedMessage}")
    }
}

package com.aisling.somawatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class WearMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("just_updated", false) == true) {
            SomaWearState.updateOtaProgress("Обновлено до v${BuildConfig.VERSION_NAME} ✓")
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
    val listState = rememberScalingLazyListState()

    val statusMessage by SomaWearState.statusMessage.collectAsState()
    val lastSyncInfo by SomaWearState.lastSyncInfo.collectAsState()
    val otaProgress by SomaWearState.otaProgress.collectAsState()
    val isOtaBusy by SomaWearState.isOtaBusy.collectAsState()
    val pendingPrompt by SomaWearState.pendingVoicePrompt.collectAsState()

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                triggerVibration(context, 70)
                SomaWearState.setPendingVoicePrompt(spoken)
            } else {
                SomaWearState.updateStatus("Речь не распознана")
            }
        }
    }

    Scaffold(
        timeText = { TimeText() },
        vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
        positionIndicator = { PositionIndicator(scalingLazyListState = listState) }
    ) {
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF070B14)),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 26.dp)
                ) {
                    Text(
                        text = "SOMA WATCH",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE})",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp
                    )
                }
            }

            if (pendingPrompt != null) {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "«$pendingPrompt»",
                            color = Color(0xFFF8FAFC),
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    val text = pendingPrompt ?: return@Button
                                    triggerVibration(context, 90)
                                    SomaWearState.setPendingVoicePrompt(null)
                                    scope.launch { sendVoicePromptToPhone(context, text) }
                                },
                                colors = ButtonDefaults.primaryButtonColors(
                                    backgroundColor = Color(0xFF10B981)
                                )
                            ) {
                                Text("🚀", fontSize = 16.sp)
                            }
                            Button(
                                onClick = {
                                    triggerVibration(context, 40)
                                    SomaWearState.setPendingVoicePrompt(null)
                                },
                                colors = ButtonDefaults.primaryButtonColors(
                                    backgroundColor = Color(0xFFEF4444)
                                )
                            ) {
                                Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                item {
                    Chip(
                        onClick = {
                            triggerVibration(context, 60)
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Идея для Soma Watch...")
                            }
                            runCatching { speechLauncher.launch(intent) }
                                .onFailure { SomaWearState.updateStatus("Нет голосового ввода") }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = Color(0xFF6366F1)
                        ),
                        label = {
                            Text(
                                text = "🎙️ Идея для агента",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    )
                }
            }

            if (otaProgress != null) {
                item {
                    Chip(
                        onClick = { SomaWearState.updateOtaProgress(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        colors = ChipDefaults.primaryChipColors(
                            backgroundColor = Color(0xFF0284C7)
                        ),
                        label = {
                            Text(
                                text = otaProgress ?: "",
                                color = Color.White,
                                fontSize = 11.sp,
                                maxLines = 2
                            )
                        }
                    )
                }
            }

            item {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 12.dp)
                ) {
                    Text(
                        text = statusMessage,
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = lastSyncInfo,
                        color = Color(0xFF64748B),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            triggerVibration(context, 70)
                            scope.launch { sendPingToPhone(context) }
                        },
                        colors = ButtonDefaults.primaryButtonColors(
                            backgroundColor = Color(0xFF1E293B)
                        )
                    ) {
                        Text("📱", fontSize = 16.sp)
                    }

                    Button(
                        onClick = {
                            if (isOtaBusy) return@Button
                            triggerVibration(context, 70)
                            scope.launch { requestOtaCheckFromPhone(context) }
                        },
                        enabled = !isOtaBusy,
                        colors = ButtonDefaults.primaryButtonColors(
                            backgroundColor = Color(0xFF0F766E)
                        )
                    ) {
                        Text(if (isOtaBusy) "..." else "OTA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(18.dp))
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

private suspend fun sendVoicePromptToPhone(context: Context, prompt: String) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaWearState.updateStatus("Телефон не найден по BT")
            return
        }
        SomaWearState.updateStatus("Отправляю задачу в Jules...")
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
            SomaWearState.updateStatus("Телефон не найден по BT")
            return
        }
        val msg = "Пинг с Soma Watch (v${BuildConfig.VERSION_NAME})"
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
            SomaWearState.updateOtaProgress("Телефон не подключен")
            return
        }
        SomaWearState.updateOtaProgress("Запрос OTA на телефон...")
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/request_ota", "check".toByteArray(Charsets.UTF_8))
                .await()
        }
    } catch (e: Exception) {
        SomaWearState.updateOtaProgress("Ошибка запроса: ${e.localizedMessage}")
    }
}

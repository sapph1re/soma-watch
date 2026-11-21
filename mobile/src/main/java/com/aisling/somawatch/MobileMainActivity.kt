package com.aisling.somawatch

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MobileMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF070B14)
                ) {
                    SomaCompanionScreen()
                }
            }
        }
    }
}

@Composable
fun SomaCompanionScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectedWatch by SomaMobileState.connectedWatchName.collectAsState()
    val lastWatchMessage by SomaMobileState.lastWatchMessage.collectAsState()
    val julesStatus by SomaMobileState.julesStatus.collectAsState()
    val otaStatus by SomaMobileState.otaStatus.collectAsState()
    val latestRelease by SomaMobileState.latestRelease.collectAsState()
    val isBusy by SomaMobileState.isBusy.collectAsState()

    var promptDraft by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(GitHubJulesClient.getSavedToken(context)) }
    var tokenSavedNotice by remember { mutableStateOf(tokenInput.isNotEmpty()) }
    var geminiKeyInput by remember { mutableStateOf(GeminiVoiceClient.getSavedApiKey(context)) }
    var geminiSavedNotice by remember { mutableStateOf(geminiKeyInput.isNotEmpty()) }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                promptDraft = spoken
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshConnectedWatch(context)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF070B14))
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "SOMA WATCH",
            color = Color(0xFF38BDF8),
            fontSize = 26.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.5.sp
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.GITHUB_REPO}",
            color = Color(0xFF94A3B8),
            fontSize = 13.sp
        )

        // 1. Voice-to-Jules Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🎙️ Эволюция Soma Watch (→ Jules)",
                    color = Color(0xFFF8FAFC),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = julesStatus,
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Идея новой фичи или метрики") },
                    placeholder = { Text("Например: добавь децибелометр с алертом >85 дБ") },
                    maxLines = 4
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Надиктуй задачу для Soma Watch...")
                            }
                            runCatching { speechLauncher.launch(intent) }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF334155),
                            contentColor = Color.White
                        )
                    ) {
                        Text("🎙️ Голос")
                    }

                    Button(
                        onClick = {
                            val text = promptDraft
                            scope.launch {
                                val res = GitHubJulesClient.createJulesIssue(
                                    context = context,
                                    promptText = text,
                                    source = "Phone"
                                )
                                if (res.isSuccess) promptDraft = ""
                            }
                        },
                        enabled = !isBusy && promptDraft.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF6366F1),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Отправить в Jules 🚀")
                    }
                }

                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = {
                        tokenInput = it
                        tokenSavedNotice = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (tokenSavedNotice) "GitHub Token (сохранён ✓)" else "GitHub Token (для создания Issues)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = geminiKeyInput,
                    onValueChange = {
                        geminiKeyInput = it
                        geminiSavedNotice = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (geminiSavedNotice) "Gemini API Key (сохранён ✓)" else "Gemini API Key (для расшифровки голоса)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                Button(
                    onClick = {
                        GitHubJulesClient.saveToken(context, tokenInput)
                        GeminiVoiceClient.saveApiKey(context, geminiKeyInput)
                        tokenSavedNotice = tokenInput.isNotBlank()
                        geminiSavedNotice = geminiKeyInput.isNotBlank()
                        SomaMobileState.setJulesStatus("Ключи сохранены локально!")
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E293B),
                        contentColor = Color.White
                    )
                ) {
                    Text("Сохранить ключи")
                }
            }
        }

        // 2. Cloud OTA Updater Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "☁️ Облачный OTA-Апдейтер (GitHub Releases)",
                    color = Color(0xFFF8FAFC),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = otaStatus,
                    color = Color(0xFF38BDF8),
                    fontSize = 13.sp
                )

                Button(
                    onClick = {
                        scope.launch { GitHubOtaManager.checkLatestRelease() }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    )
                ) {
                    Text("Проверить свежий билд в облаке")
                }

                latestRelease?.let { release ->
                    Text(
                        text = "Релиз: ${release.name} (${release.tagName})",
                        color = Color(0xFF10B981),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (release.wearApkUrl != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    GitHubOtaManager.pushWearOtaToWatch(context, release.wearApkUrl)
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Установить свежий APK на Galaxy Watch 8 по BT")
                        }
                    }

                    if (release.mobileApkUrl != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    GitHubOtaManager.downloadAndInstallMobileOta(context, release.mobileApkUrl)
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF8B5CF6),
                                contentColor = Color.White
                            )
                        ) {
                            Text("Обновить Soma Watch на телефоне")
                        }
                    }
                }
            }
        }

        // 3. Watch Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111827))
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "⌚ Связь с Galaxy Watch 8",
                    color = Color(0xFFF8FAFC),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Узел: $connectedWatch",
                    color = Color(0xFF38BDF8),
                    fontSize = 14.sp
                )
                Text(
                    text = "Последнее: $lastWatchMessage",
                    color = Color(0xFFCBD5E1),
                    fontSize = 13.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { scope.launch { refreshConnectedWatch(context) } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF334155),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Обновить")
                    }

                    Button(
                        onClick = { scope.launch { pingWatch(context) } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0F766E),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Пинг + Вибро ⌚")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private suspend fun refreshConnectedWatch(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaMobileState.setConnectedWatch("Не подключены (проверьте Bluetooth)")
        } else {
            SomaMobileState.setConnectedWatch(nodes.joinToString { "${it.displayName} (${it.id})" })
        }
    } catch (e: Exception) {
        SomaMobileState.setConnectedWatch("Ошибка Data Layer: ${e.localizedMessage}")
    }
}

private suspend fun pingWatch(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaMobileState.recordWatchMessage("Часы не найдены")
            return
        }
        val payload = "Пинг с телефона (v${BuildConfig.VERSION_NAME})"
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/ping", payload.toByteArray(Charsets.UTF_8))
                .await()
        }
        SomaMobileState.recordWatchMessage("Пинг отправлен на ${nodes.first().displayName}")
    } catch (e: Exception) {
        SomaMobileState.recordWatchMessage("Ошибка пинга: ${e.localizedMessage}")
    }
}

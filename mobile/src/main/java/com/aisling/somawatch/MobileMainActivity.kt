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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private object CompanionColors {
    val Bg = Color(0xFF050810)
    val CardBg = Color(0xFF0E1420)
    val CardBorder = Color(0xFF1E293B)
    val Cyan = Color(0xFF38BDF8)
    val Indigo = Color(0xFF6366F1)
    val Emerald = Color(0xFF10B981)
    val Coral = Color(0xFFF43F5E)
    val Purple = Color(0xFF8B5CF6)
    val SlateButton = Color(0xFF1E293B)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
}

class MobileMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileBiometricsStore.loadLastSummary(this)?.let {
            SomaMobileState.setLastBioSummary(it)
        }
        SomaMobileState.setHealthDbStats(SomaHealthDb.get(this).loadStats())
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = CompanionColors.Bg
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
    val agentStatus by SomaMobileState.julesStatus.collectAsState()
    val otaStatus by SomaMobileState.otaStatus.collectAsState()
    val otaProgress by SomaMobileState.otaProgress.collectAsState()
    val latestRelease by SomaMobileState.latestRelease.collectAsState()
    val lastBio by SomaMobileState.lastBioSummary.collectAsState()
    val dbStats by SomaMobileState.healthDbStats.collectAsState()
    val hcStatus by SomaMobileState.healthConnectStatus.collectAsState()
    val localApiUrl by SomaLocalApiServer.runningUrl.collectAsState()
    val isBusy by SomaMobileState.isBusy.collectAsState()

    val savedGoals = remember { MobileBiometricsStore.loadGoals(context) }
    var maxHrInput by remember { mutableStateOf(savedGoals.first.toString()) }
    var z2GoalInput by remember { mutableStateOf(savedGoals.second.toString()) }
    var vigGoalInput by remember { mutableStateOf(savedGoals.third.toString()) }
    var noiseDbInput by remember { mutableStateOf(MobileBiometricsStore.loadNoiseThreshold(context).toString()) }

    val hcPermLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.isNotEmpty()) {
            scope.launch {
                SomaMobileState.setHealthConnectStatus("Синхронизация сна и HRV за 30 дней...")
                val res = HealthConnectManager.syncLast30Days(context)
                SomaMobileState.setHealthDbStats(SomaHealthDb.get(context).loadStats())
                SomaMobileState.setHealthConnectStatus(
                    res.fold(onSuccess = { it }, onFailure = { "Ошибка: ${it.localizedMessage}" })
                )
            }
        } else {
            SomaMobileState.setHealthConnectStatus("Разрешения Health Connect не выданы")
        }
    }

    var promptDraft by remember { mutableStateOf("") }
    var tokenInput by remember { mutableStateOf(GitHubJulesClient.getSavedToken(context)) }
    var tokenSavedNotice by remember { mutableStateOf(tokenInput.isNotEmpty()) }
    var geminiKeyInput by remember { mutableStateOf(GeminiVoiceClient.getSavedApiKey(context)) }
    var geminiSavedNotice by remember { mutableStateOf(geminiKeyInput.isNotEmpty()) }
    var showKeysSection by remember { mutableStateOf(!(tokenSavedNotice && geminiSavedNotice)) }

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
            .background(CompanionColors.Bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        // Header
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(CompanionColors.Cyan)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "SOMA WATCH",
                    color = CompanionColors.TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.6.sp
                )
            }
            Text(
                text = "v${BuildConfig.VERSION_NAME} (#${BuildConfig.VERSION_CODE}) • ${BuildConfig.GITHUB_REPO}",
                color = CompanionColors.TextSecondary,
                fontSize = 12.sp
            )
        }

        // 1. Biometrics & Weekly Cardio Budget Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CompanionColors.CardBorder),
            colors = CardDefaults.cardColors(containerColor = CompanionColors.CardBg)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = CompanionColors.Emerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Биометрия и Кардио-бюджет",
                        color = CompanionColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (lastBio != null) {
                    val b = lastBio!!
                    val z2Frac = (b.weeklyZ2Min.toFloat() / b.weeklyZ2Goal.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val vigFrac = (b.weeklyVigMin.toFloat() / b.weeklyVigGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

                    Text(
                        text = "Zone 2: ${b.weeklyZ2Min} / ${b.weeklyZ2Goal} мин",
                        color = CompanionColors.Emerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinearProgressIndicator(
                        progress = { z2Frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = CompanionColors.Emerald,
                        trackColor = CompanionColors.SlateButton
                    )

                    Text(
                        text = "Zone 4–5 (HIIT): ${b.weeklyVigMin} / ${b.weeklyVigGoal} мин",
                        color = CompanionColors.Coral,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    LinearProgressIndicator(
                        progress = { vigFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = CompanionColors.Coral,
                        trackColor = CompanionColors.SlateButton
                    )

                    val hrrText = buildString {
                        if (b.hrr1 >= 0) append("HRR-1: -${b.hrr1}  ")
                        if (b.hrr2 >= 0) append("HRR-2: -${b.hrr2}  ")
                        if (b.hrvMs > 0) append("HRV: ${b.hrvMs}ms")
                    }
                    Text(
                        text = "Последняя сессия: ${b.durationSec / 60} мин • ср/пик ${b.avgHr}/${b.peakHr} BPM" +
                            if (hrrText.isNotBlank()) "\n$hrrText" else "",
                        color = CompanionColors.TextSecondary,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        text = "Запусти тренировку «Бег / Кардио» на часах — итоги, HRV, HRR и недельный прогресс синхронизируются сюда автоматически.",
                        color = CompanionColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = maxHrInput,
                        onValueChange = { maxHrInput = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("Max HR", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = z2GoalInput,
                        onValueChange = { z2GoalInput = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("Z2 мин", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = vigGoalInput,
                        onValueChange = { vigGoalInput = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("HIIT", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = noiseDbInput,
                        onValueChange = { noiseDbInput = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.weight(1f),
                        label = { Text("Шум дБ", fontSize = 11.sp) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true
                    )
                }

                Button(
                    onClick = {
                        val mHr = maxHrInput.toIntOrNull() ?: 185
                        val z2 = z2GoalInput.toIntOrNull() ?: 150
                        val vig = vigGoalInput.toIntOrNull() ?: 75
                        val noiseDb = noiseDbInput.toIntOrNull() ?: 85
                        MobileBiometricsStore.saveGoals(context, mHr, z2, vig, noiseDb)
                        scope.launch { syncGoalsToWatch(context, mHr, z2, vig, noiseDb) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = CompanionColors.SlateButton,
                        contentColor = Color.White
                    )
                ) {
                    Text("Синхронизировать цели и порог шума с часами")
                }
            }
        }

        // 1b. Sleep (Health Connect), Longitudinal HRV & Claude Code Export / Local API Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CompanionColors.CardBorder),
            colors = CardDefaults.cardColors(containerColor = CompanionColors.CardBg)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Bedtime,
                        contentDescription = null,
                        tint = CompanionColors.Purple,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Сон, HRV и интерфейс Claude Code",
                        color = CompanionColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = hcStatus,
                    color = CompanionColors.Cyan,
                    fontSize = 12.sp
                )

                Text(
                    text = buildString {
                        append("HRV (7д / 30д): ${dbStats.avgHrv7d?.let { "${it}ms" } ?: "--"} / ${dbStats.avgHrv30d?.let { "${it}ms" } ?: "--"}")
                        dbStats.latestSleepSummary?.let { append("\nСон: $it") }
                        append("\nБД SQLite: ${dbStats.sleepCount} ночей • ${dbStats.workoutCount} трен. • ${dbStats.hrrEventsCount} HRR • ${dbStats.noiseEventsCount} шум")
                    },
                    color = CompanionColors.TextSecondary,
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (!HealthConnectManager.isAvailable(context)) {
                                    SomaMobileState.setHealthConnectStatus("Установи/включи Health Connect в настройках Android")
                                    return@launch
                                }
                                if (HealthConnectManager.hasAllPermissions(context)) {
                                    SomaMobileState.setHealthConnectStatus("Синхронизация сна и HRV...")
                                    val res = HealthConnectManager.syncLast30Days(context)
                                    SomaMobileState.setHealthDbStats(SomaHealthDb.get(context).loadStats())
                                    SomaMobileState.setHealthConnectStatus(
                                        res.fold(onSuccess = { it }, onFailure = { "Ошибка: ${it.localizedMessage}" })
                                    )
                                } else {
                                    hcPermLauncher.launch(HealthConnectManager.REQUIRED_PERMISSIONS)
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.Purple,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Синхр. сон/HRV", fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            runCatching {
                                val file = SomaHealthDb.get(context).writeExportFile(context)
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "Экспорт Soma Health JSON"))
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.SlateButton,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Share,
                            contentDescription = "Экспорт",
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("JSON", fontSize = 12.sp)
                    }
                }

                // Local Wi-Fi HTTP API toggle for Claude Code
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Hub,
                            contentDescription = null,
                            tint = if (localApiUrl != null) CompanionColors.Emerald else CompanionColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Wi-Fi API для Claude Code",
                                color = CompanionColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = localApiUrl ?: "Порт 8787 (/api/health, /api/summary.md)",
                                color = if (localApiUrl != null) CompanionColors.Emerald else CompanionColors.TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Switch(
                        checked = localApiUrl != null,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                SomaLocalApiServer.start(context)
                            } else {
                                SomaLocalApiServer.stop()
                            }
                        }
                    )
                }
            }
        }

        // 2. Voice & Agent Evolution Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CompanionColors.CardBorder),
            colors = CardDefaults.cardColors(containerColor = CompanionColors.CardBg)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = CompanionColors.Indigo,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Эволюция Soma Watch",
                        color = CompanionColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = agentStatus,
                    color = CompanionColors.Cyan,
                    fontSize = 13.sp
                )

                OutlinedTextField(
                    value = promptDraft,
                    onValueChange = { promptDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    label = { Text("Идея новой фичи или метрики") },
                    placeholder = { Text("Например: добавь децибелометр > 85 дБ") },
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.SlateButton,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Mic,
                            contentDescription = "Голос",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Голос")
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.Indigo,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Send,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("В GitHub Issue")
                    }
                }
            }
        }

        // 3. Cloud OTA Updater Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CompanionColors.CardBorder),
            colors = CardDefaults.cardColors(containerColor = CompanionColors.CardBg)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.CloudDownload,
                        contentDescription = null,
                        tint = CompanionColors.Cyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Облачный OTA-Апдейтер",
                        color = CompanionColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = otaStatus,
                    color = CompanionColors.Cyan,
                    fontSize = 13.sp
                )

                if (isBusy) {
                    if (otaProgress != null) {
                        LinearProgressIndicator(
                            progress = { otaProgress ?: 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = CompanionColors.Cyan,
                            trackColor = CompanionColors.SlateButton
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(50)),
                            color = CompanionColors.Cyan,
                            trackColor = CompanionColors.SlateButton
                        )
                    }
                }

                Button(
                    onClick = { scope.launch { GitHubOtaManager.checkLatestRelease() } },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    )
                ) {
                    Text("Проверить свежий билд в облаке")
                }

                latestRelease?.let { release ->
                    val isPhoneNewer = release.remoteVersionCode > BuildConfig.VERSION_CODE
                    Text(
                        text = "Релиз: ${release.name} (${release.tagName})",
                        color = CompanionColors.Emerald,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    if (release.wearApkUrl != null) {
                        Button(
                            onClick = {
                                scope.launch {
                                    GitHubOtaManager.pushWearOtaToWatch(
                                        context = context,
                                        wearApkUrl = release.wearApkUrl,
                                        versionLabel = release.tagName
                                    )
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CompanionColors.Emerald,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SystemUpdate,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Отправить APK на Galaxy Watch 8 по BT")
                        }
                    }

                    if (release.mobileApkUrl != null && isPhoneNewer) {
                        Button(
                            onClick = {
                                scope.launch {
                                    GitHubOtaManager.downloadAndInstallMobileOta(context, release.mobileApkUrl)
                                }
                            },
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CompanionColors.Purple,
                                contentColor = Color.White
                            )
                        ) {
                            Text("Обновить Soma Watch на телефоне")
                        }
                    } else if (!isPhoneNewer) {
                        Text(
                            text = "На телефоне установлена актуальная версия ✓",
                            color = CompanionColors.TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 4. Watch Connection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CompanionColors.CardBorder),
            colors = CardDefaults.cardColors(containerColor = CompanionColors.CardBg)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Watch,
                        contentDescription = null,
                        tint = CompanionColors.Emerald,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Связь с Galaxy Watch 8",
                        color = CompanionColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Text(
                    text = connectedWatch,
                    color = CompanionColors.Cyan,
                    fontSize = 13.sp
                )
                Text(
                    text = lastWatchMessage,
                    color = CompanionColors.TextSecondary,
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { scope.launch { refreshConnectedWatch(context) } },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.SlateButton,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Обновить")
                    }

                    Button(
                        onClick = { scope.launch { pingWatch(context) } },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0F766E),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Пинг + Вибро")
                    }
                }
            }
        }

        // 5. Collapsible API Keys Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, CompanionColors.CardBorder),
            colors = CardDefaults.cardColors(containerColor = CompanionColors.CardBg)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showKeysSection = !showKeysSection }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (tokenSavedNotice && geminiSavedNotice) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
                            contentDescription = null,
                            tint = if (tokenSavedNotice && geminiSavedNotice) CompanionColors.Emerald else CompanionColors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (tokenSavedNotice && geminiSavedNotice) "Ключи API настроены" else "Настройка ключей API",
                            color = CompanionColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Icon(
                        imageVector = if (showKeysSection) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = CompanionColors.TextSecondary
                    )
                }

                if (showKeysSection) {
                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = {
                            tokenInput = it
                            tokenSavedNotice = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(if (tokenSavedNotice) "GitHub Token ✓" else "GitHub Token") },
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
                        shape = RoundedCornerShape(12.dp),
                        label = { Text(if (geminiSavedNotice) "Gemini API Key ✓" else "Gemini API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )

                    Button(
                        onClick = {
                            GitHubJulesClient.saveToken(context, tokenInput)
                            GeminiVoiceClient.saveApiKey(context, geminiKeyInput)
                            tokenSavedNotice = tokenInput.isNotBlank()
                            geminiSavedNotice = geminiKeyInput.isNotBlank()
                            if (tokenSavedNotice && geminiSavedNotice) {
                                showKeysSection = false
                            }
                            SomaMobileState.setJulesStatus("Ключи сохранены локально!")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.SlateButton,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Сохранить ключи")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private suspend fun syncGoalsToWatch(context: Context, maxHr: Int, z2Goal: Int, vigGoal: Int, noiseDb: Int) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaMobileState.recordWatchMessage("Часы не найдены")
            return
        }
        val payload = "$maxHr|$z2Goal|$vigGoal|$noiseDb|true"
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/update_goals", payload.toByteArray(Charsets.UTF_8))
                .await()
        }
        SomaMobileState.recordWatchMessage("Синхронизировано: MaxHR $maxHr, Z2 ${z2Goal}м, HIIT ${vigGoal}м, Шум >${noiseDb}дБ")
    } catch (e: Exception) {
        SomaMobileState.recordWatchMessage("Ошибка: ${e.localizedMessage}")
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

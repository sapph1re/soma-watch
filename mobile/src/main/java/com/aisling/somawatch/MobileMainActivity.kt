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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.rounded.Refresh
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
                SomaMobileState.setHealthConnectStatus("Syncing 30 days of sleep & HRV...")
                val res = HealthConnectManager.syncLast30Days(context)
                SomaMobileState.setHealthDbStats(SomaHealthDb.get(context).loadStats())
                SomaMobileState.setHealthConnectStatus(
                    res.fold(onSuccess = { it }, onFailure = { "Error: ${it.localizedMessage}" })
                )
            }
        } else {
            SomaMobileState.setHealthConnectStatus("Health Connect permissions denied")
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

    val isWatchConnected = !connectedWatch.startsWith("Not connected") &&
        !connectedWatch.startsWith("Searching") &&
        !connectedWatch.startsWith("Data Layer error")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(CompanionColors.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + Quick Watch Connection Pill
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Surface(
                shape = RoundedCornerShape(50),
                color = CompanionColors.CardBg,
                border = BorderStroke(
                    1.dp,
                    if (isWatchConnected) CompanionColors.Emerald.copy(alpha = 0.45f) else CompanionColors.CardBorder
                ),
                modifier = Modifier.clickable { scope.launch { refreshConnectedWatch(context) } }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isWatchConnected) CompanionColors.Emerald else CompanionColors.TextSecondary)
                    )
                    Text(
                        text = connectedWatch,
                        color = if (isWatchConnected) CompanionColors.Emerald else CompanionColors.TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh watch link",
                        tint = CompanionColors.TextSecondary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
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
                        text = "Biometrics & Cardio Budget",
                        color = CompanionColors.TextPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (lastBio != null) {
                    val b = lastBio!!
                    val loadFrac = (b.weeklyCardioLoadMin.toFloat() / b.weeklyCardioLoadGoal.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val aeroFrac = (b.weeklyAerobicMin.toFloat() / b.weeklyZ2Goal.coerceAtLeast(1)).coerceIn(0f, 1f)
                    val vigFrac = (b.weeklyVigMin.toFloat() / b.weeklyVigGoal.coerceAtLeast(1)).coerceIn(0f, 1f)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Blueprint Cardio Load",
                            color = CompanionColors.TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${b.weeklyCardioLoadMin} / ${b.weeklyCardioLoadGoal} eq (${(loadFrac * 100).toInt()}%)",
                            color = CompanionColors.Cyan,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { loadFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp)
                            .clip(RoundedCornerShape(50)),
                        color = CompanionColors.Cyan,
                        trackColor = CompanionColors.SlateButton
                    )
                    Text(
                        text = "Weighted eq.min: Z2 ×1.0 • Z3 ×1.5 • Z4–5 ×2.0",
                        color = CompanionColors.TextSecondary,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Aerobic Z2–Z3 (Z2 ${b.weeklyZ2Min}m + Z3 ${b.weeklyZ3Min}m)",
                            color = CompanionColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${b.weeklyAerobicMin} / ${b.weeklyZ2Goal} min (${(aeroFrac * 100).toInt()}%)",
                            color = CompanionColors.Emerald,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { aeroFrac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(50)),
                        color = CompanionColors.Emerald,
                        trackColor = CompanionColors.SlateButton
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Zone 4–5 (HIIT)",
                            color = CompanionColors.TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "${b.weeklyVigMin} / ${b.weeklyVigGoal} min (${(vigFrac * 100).toInt()}%)",
                            color = CompanionColors.Coral,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
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
                    val zonesText = buildString {
                        append("Load +${b.sessionCardioLoadMin} eq")
                        append(" • Z2 ${formatMinSec(b.sessionZ2Sec)}")
                        if (b.sessionZ3Sec > 0) append(" • Z3 ${formatMinSec(b.sessionZ3Sec)}")
                        append(" • HIIT ${formatMinSec(b.sessionVigSec)}")
                        if (b.sessionZ1Sec > 0) append(" • Z1 ${formatMinSec(b.sessionZ1Sec)}")
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF131B2B),
                        border = BorderStroke(1.dp, CompanionColors.CardBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = "Last Session: ${formatMinSec(b.durationSec)} • Avg/Peak ${b.avgHr}/${b.peakHr} BPM",
                                color = CompanionColors.TextPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = zonesText,
                                color = CompanionColors.TextSecondary,
                                fontSize = 12.sp
                            )
                            if (hrrText.isNotBlank()) {
                                Text(
                                    text = hrrText,
                                    color = CompanionColors.Cyan,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        text = "Start a \"Run / Cardio\" workout on your watch — session stats, HRV, HRR, and weekly progress sync here automatically.",
                        color = CompanionColors.TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // 2x2 Grid so labels never clip or crowd each other
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = maxHrInput,
                            onValueChange = { maxHrInput = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            label = { Text("Max HR (bpm)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = noiseDbInput,
                            onValueChange = { noiseDbInput = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            label = { Text("Noise Alert (dB)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = z2GoalInput,
                            onValueChange = { z2GoalInput = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            label = { Text("Zone 2 Goal (min)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = vigGoalInput,
                            onValueChange = { vigGoalInput = it.filter { ch -> ch.isDigit() } },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            label = { Text("HIIT Goal (min)", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true
                        )
                    }
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
                    Text("Sync Goals & Threshold to Watch")
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
                        text = "Sleep, HRV & Claude Code",
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

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF131B2B),
                        border = BorderStroke(1.dp, CompanionColors.CardBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("7d Avg HRV", color = CompanionColors.TextSecondary, fontSize = 11.sp)
                            Text(
                                text = dbStats.avgHrv7d?.let { "${it}ms" } ?: "--",
                                color = CompanionColors.Cyan,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF131B2B),
                        border = BorderStroke(1.dp, CompanionColors.CardBorder),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text("30d Avg HRV", color = CompanionColors.TextSecondary, fontSize = 11.sp)
                            Text(
                                text = dbStats.avgHrv30d?.let { "${it}ms" } ?: "--",
                                color = CompanionColors.Purple,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                Text(
                    text = buildString {
                        dbStats.latestSleepSummary?.let { append("Latest sleep: $it\n") }
                        append("SQLite DB: ${dbStats.sleepCount} nights • ${dbStats.workoutCount} workouts • ${dbStats.hrrEventsCount} HRR • ${dbStats.noiseEventsCount} noise")
                    },
                    color = CompanionColors.TextSecondary,
                    fontSize = 12.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                if (!HealthConnectManager.isAvailable(context)) {
                                    SomaMobileState.setHealthConnectStatus("Install or enable Health Connect in Android Settings")
                                    return@launch
                                }
                                if (HealthConnectManager.hasAllPermissions(context)) {
                                    SomaMobileState.setHealthConnectStatus("Syncing sleep & HRV...")
                                    val res = HealthConnectManager.syncLast30Days(context)
                                    SomaMobileState.setHealthDbStats(SomaHealthDb.get(context).loadStats())
                                    SomaMobileState.setHealthConnectStatus(
                                        res.fold(onSuccess = { it }, onFailure = { "Error: ${it.localizedMessage}" })
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
                        Text("Sync Sleep / HRV", fontSize = 12.sp)
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
                                context.startActivity(Intent.createChooser(share, "Export Soma Health JSON"))
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
                            contentDescription = "Export",
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
                                text = "Wi-Fi API for Claude Code",
                                color = CompanionColors.TextPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = localApiUrl ?: "Port 8787 (/api/health, /api/summary.md)",
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
                        text = "Soma Watch Evolution",
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
                    label = { Text("Feature or metric idea") },
                    placeholder = { Text("e.g. add a breathing timer before sleep") },
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
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dictate a task for Soma Watch...")
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
                            contentDescription = "Voice",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Voice")
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
                        Text("Create Issue")
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
                        text = "Cloud OTA Updater",
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
                    onClick = { scope.launch { GitHubOtaManager.checkLatestRelease(notifyWatchContext = context) } },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7),
                        contentColor = Color.White
                    )
                ) {
                    Text("Check Latest Cloud Build")
                }

                latestRelease?.let { release ->
                    val isPhoneNewer = release.remoteVersionCode > BuildConfig.VERSION_CODE
                    Text(
                        text = "Release: ${release.name} (${release.tagName})",
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
                            Text("Push APK to Galaxy Watch 8 via BT")
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
                            Text("Update Soma Watch on Phone")
                        }
                    } else if (!isPhoneNewer) {
                        Text(
                            text = "Phone app is up to date ✓",
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
                        text = "Galaxy Watch 8 Connection",
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
                        Text("Refresh")
                    }

                    Button(
                        onClick = { scope.launch { pingWatch(context) } },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0F766E),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Ping + Vibrate")
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
                            text = if (tokenSavedNotice && geminiSavedNotice) "API Keys Configured" else "Configure API Keys",
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
                            SomaMobileState.setJulesStatus("Keys saved locally!")
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CompanionColors.SlateButton,
                            contentColor = Color.White
                        )
                    ) {
                        Text("Save Keys")
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
            SomaMobileState.recordWatchMessage("Watch not found")
            return
        }
        val payload = "$maxHr|$z2Goal|$vigGoal|$noiseDb|true"
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/update_goals", payload.toByteArray(Charsets.UTF_8))
                .await()
        }
        SomaMobileState.recordWatchMessage("Synced: MaxHR $maxHr, Z2 ${z2Goal}m, HIIT ${vigGoal}m, Noise >${noiseDb}dB")
    } catch (e: Exception) {
        SomaMobileState.recordWatchMessage("Error: ${e.localizedMessage}")
    }
}

private suspend fun refreshConnectedWatch(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaMobileState.setConnectedWatch("Not connected (check Bluetooth)")
        } else {
            SomaMobileState.setConnectedWatch(nodes.joinToString { "${it.displayName} (${it.id})" })
        }
    } catch (e: Exception) {
        SomaMobileState.setConnectedWatch("Data Layer error: ${e.localizedMessage}")
    }
}

private suspend fun pingWatch(context: Context) {
    try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        if (nodes.isEmpty()) {
            SomaMobileState.recordWatchMessage("Watch not found")
            return
        }
        val payload = "Ping from phone (v${BuildConfig.VERSION_NAME})"
        nodes.forEach { node ->
            Wearable.getMessageClient(context)
                .sendMessage(node.id, "/soma/ping", payload.toByteArray(Charsets.UTF_8))
                .await()
        }
        SomaMobileState.recordWatchMessage("Ping sent to ${nodes.first().displayName}")
    } catch (e: Exception) {
        SomaMobileState.recordWatchMessage("Ping error: ${e.localizedMessage}")
    }
}

private fun formatMinSec(totalSec: Int): String {
    if (totalSec <= 0) return "0m"
    val m = totalSec / 60
    val s = totalSec % 60
    return when {
        m == 0 -> "${s}s"
        s == 0 -> "${m}m"
        else -> "${m}m ${s}s"
    }
}

package com.mark.timerboard

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon as AndroidIcon
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: TimerBoardViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(
            this,
            TimerBoardViewModel.Factory(application)
        )[TimerBoardViewModel::class.java]
        handleShortcutIntent(intent)

        setContent {
            TimerBoardTheme {
                TimerBoardApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action == ACTION_START_TIMER) {
            viewModel.startTimerWhenLoaded(intent.getLongExtra(EXTRA_TIMER_ID, -1L))
        }
    }
}

data class TimerPreset(
    val id: Long,
    val name: String,
    val durationMillis: Long,
    val color: Long,
    val alarmId: String = DEFAULT_ALARM_ID,
    val alarmUri: String? = null,
    val mode: String = TIMER_MODE_COUNTDOWN,
    val warmupMillis: Long = 0L,
    val workMillis: Long = 0L,
    val restMillis: Long = 0L,
    val cooldownMillis: Long = 0L,
    val rounds: Int = 1
)

data class TimerItem(
    val preset: TimerPreset,
    val remainingMillis: Long = preset.totalDurationMillis(),
    val isRunning: Boolean = false,
    val endElapsedRealtime: Long = 0L
)

data class AlarmSound(
    val id: String,
    val label: String,
    val description: String,
    val toneEvents: List<ToneEvent>,
    val vibrationPattern: LongArray,
    val vibrationAmplitudes: IntArray
)

data class ToneEvent(
    val delayMillis: Long,
    val tone: Int,
    val durationMillis: Int
)

class TimerBoardViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = TimerRepository(application)
    private val alertPlayer = TimerAlertPlayer(application)
    private var tickerJob: Job? = null

    val timers = mutableStateListOf<TimerItem>()
    var isLoading by mutableStateOf(true)
        private set
    var todaySummary by mutableStateOf(CompletionSummary())
        private set
    var pomodoroTodaySummary by mutableStateOf(CompletionSummary())
        private set
    val historyItems = mutableStateListOf<TimerHistoryItem>()
    var isHistoryLoading by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val presets = repository.loadPresets()
            timers.addAll(repository.restoreRuntimeState(presets))
            refreshTodaySummary()
            refreshHistoryItems()
            isLoading = false
            updateShortcuts()
            ensureTicker()
            syncRuntimeAndNotification()
        }
        TimerCommandBus.register(
            onPauseAll = ::pauseAll,
            onResetAll = ::resetAll,
            onResumeAll = ::resumeAll
        )
    }

    fun addTimer(
        name: String,
        minutes: Int,
        seconds: Int,
        color: Long,
        alarmId: String,
        alarmUri: String?
    ) {
        val duration = ((minutes * 60L) + seconds).coerceAtLeast(1L) * 1000L
        val preset = TimerPreset(
            id = System.currentTimeMillis(),
            name = name.ifBlank { "Timer" },
            durationMillis = duration,
            color = color,
            alarmId = alarmById(alarmId).id,
            alarmUri = alarmUri
        )
        timers.add(0, TimerItem(preset))
        saveAsync()
        syncRuntimeAndNotification()
    }

    fun addIntervalTimer(
        name: String,
        warmupSeconds: Int,
        workSeconds: Int,
        restSeconds: Int,
        cooldownSeconds: Int,
        rounds: Int,
        color: Long,
        alarmId: String,
        alarmUri: String?
    ) {
        val normalizedRounds = rounds.coerceAtLeast(1)
        val preset = TimerPreset(
            id = System.currentTimeMillis(),
            name = name.ifBlank { "Interval" },
            durationMillis = intervalDurationMillis(
                warmupSeconds = warmupSeconds,
                workSeconds = workSeconds,
                restSeconds = restSeconds,
                cooldownSeconds = cooldownSeconds,
                rounds = normalizedRounds
            ),
            color = color,
            alarmId = alarmById(alarmId).id,
            alarmUri = alarmUri,
            mode = TIMER_MODE_INTERVAL,
            warmupMillis = warmupSeconds.coerceAtLeast(0) * 1000L,
            workMillis = workSeconds.coerceAtLeast(1) * 1000L,
            restMillis = restSeconds.coerceAtLeast(0) * 1000L,
            cooldownMillis = cooldownSeconds.coerceAtLeast(0) * 1000L,
            rounds = normalizedRounds
        )
        timers.add(0, TimerItem(preset))
        saveAsync()
        syncRuntimeAndNotification()
    }

    fun addPomodoroTimer(
        name: String,
        focusMinutes: Int,
        breakMinutes: Int,
        sessions: Int,
        longBreakMinutes: Int,
        color: Long,
        alarmId: String,
        alarmUri: String?
    ) {
        val normalizedSessions = sessions.coerceAtLeast(1)
        val focusMillis = focusMinutes.coerceAtLeast(1) * 60_000L
        val breakMillis = breakMinutes.coerceAtLeast(0) * 60_000L
        val longBreakMillis = longBreakMinutes.coerceAtLeast(0) * 60_000L
        val duration = (focusMillis + breakMillis) * normalizedSessions + longBreakMillis
        val preset = TimerPreset(
            id = System.currentTimeMillis(),
            name = name.ifBlank { "Pomodoro" },
            durationMillis = duration,
            color = color,
            alarmId = alarmById(alarmId).id,
            alarmUri = alarmUri,
            mode = TIMER_MODE_POMODORO,
            workMillis = focusMillis,
            restMillis = breakMillis,
            cooldownMillis = longBreakMillis,
            rounds = normalizedSessions
        )
        timers.add(0, TimerItem(preset))
        saveAsync()
        syncRuntimeAndNotification()
    }

    fun updateTimerDuration(
        id: Long,
        hours: Int,
        minutes: Int,
        seconds: Int,
        alarmId: String,
        alarmUri: String?
    ) {
        val duration = ((hours * 3600L) + (minutes * 60L) + seconds).coerceAtLeast(1L) * 1000L
        updateTimer(id) { item ->
            item.copy(
                preset = item.preset.copy(
                    durationMillis = duration,
                    alarmId = alarmById(alarmId).id,
                    alarmUri = alarmUri
                ),
                remainingMillis = duration,
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
        saveAsync()
        syncRuntimeAndNotification()
    }

    fun deleteTimer(id: Long) {
        timers.removeAll { it.preset.id == id }
        saveAsync()
        syncRuntimeAndNotification()
    }

    fun duplicateTimer(id: Long) {
        val timer = timers.firstOrNull { it.preset.id == id } ?: return
        val duplicatePreset = timer.preset.copy(
            id = System.currentTimeMillis(),
            name = "${timer.preset.name} copy"
        )
        timers.add(0, TimerItem(duplicatePreset))
        saveAsync()
        syncRuntimeAndNotification()
    }

    fun snoozeTimer(id: Long) {
        alertPlayer.stop()
        updateTimer(id) { item ->
            item.copy(
                remainingMillis = SNOOZE_MILLIS,
                isRunning = true,
                endElapsedRealtime = SystemClock.elapsedRealtime() + SNOOZE_MILLIS
            )
        }
        ensureTicker()
        syncRuntimeAndNotification()
    }

    fun moveTimerUp(id: Long) {
        moveTimer(id, -1)
    }

    fun moveTimerDown(id: Long) {
        moveTimer(id, 1)
    }

    fun startTimer(id: Long) {
        alertPlayer.stop()
        updateTimer(id) { item ->
            val duration = if (item.remainingMillis <= 0L) {
                item.preset.totalDurationMillis()
            } else {
                item.remainingMillis
            }
            item.copy(
                isRunning = true,
                remainingMillis = duration,
                endElapsedRealtime = SystemClock.elapsedRealtime() + duration
            )
        }
        ensureTicker()
        syncRuntimeAndNotification()
    }

    fun pauseTimer(id: Long) {
        alertPlayer.stop()
        updateTimer(id) { item ->
            if (!item.isRunning) item else item.copy(
                remainingMillis = max(0L, item.endElapsedRealtime - SystemClock.elapsedRealtime()),
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
        syncRuntimeAndNotification()
    }

    fun resetTimer(id: Long) {
        alertPlayer.stop()
        updateTimer(id) { item ->
            item.copy(
                remainingMillis = item.preset.totalDurationMillis(),
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
        syncRuntimeAndNotification()
    }

    fun startAll() {
        timers.forEach { startTimer(it.preset.id) }
    }

    fun pauseAll() {
        timers.forEach { pauseTimer(it.preset.id) }
    }

    fun resetAll() {
        timers.forEach { resetTimer(it.preset.id) }
    }

    fun resumeAll() {
        timers
            .filter { !it.isRunning && it.remainingMillis in 1L until it.preset.totalDurationMillis() }
            .forEach { startTimer(it.preset.id) }
    }

    fun startTimerWhenLoaded(id: Long) {
        if (id <= 0L) return
        viewModelScope.launch {
            while (isLoading) {
                delay(100L)
            }
            startTimer(id)
        }
    }

    fun exportPresetsText(): String {
        return timers.map { it.preset }.toBackupJson()
    }

    fun importPresetsText(raw: String): Boolean {
        val presets = raw.toTimerPresetsOrNull() ?: return false
        timers.clear()
        timers.addAll(presets.map { TimerItem(it) })
        saveAsync()
        syncRuntimeAndNotification()
        return true
    }

    private fun updateTimer(id: Long, transform: (TimerItem) -> TimerItem) {
        val index = timers.indexOfFirst { it.preset.id == id }
        if (index >= 0) timers[index] = transform(timers[index])
    }

    private fun moveTimer(id: Long, offset: Int) {
        val fromIndex = timers.indexOfFirst { it.preset.id == id }
        val toIndex = fromIndex + offset
        if (fromIndex !in timers.indices || toIndex !in timers.indices) return
        val timer = timers.removeAt(fromIndex)
        timers.add(toIndex, timer)
        saveAsync()
        syncRuntimeAndNotification()
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (timers.any { it.isRunning }) {
                val now = SystemClock.elapsedRealtime()
                var didFinishTimer = false
                timers.forEachIndexed { index, item ->
                    if (item.isRunning) {
                        val remaining = max(0L, item.endElapsedRealtime - now)
                        timers[index] = item.copy(
                            remainingMillis = remaining,
                            isRunning = remaining > 0L
                        )
                        if (remaining == 0L) {
                            didFinishTimer = true
                            recordCompletion(item.preset)
                            alertPlayer.play(item.preset.alarmId, item.preset.alarmUri)
                        }
                    }
                }
                if (didFinishTimer) {
                    syncRuntimeAndNotification()
                }
                delay(250L)
            }
            syncRuntimeAndNotification()
        }
    }

    private fun saveAsync() {
        val presets = timers.map { it.preset }
        viewModelScope.launch {
            repository.savePresets(presets)
            updateShortcuts()
        }
    }

    private fun recordCompletion(preset: TimerPreset) {
        viewModelScope.launch {
            repository.recordCompletion(preset)
            refreshTodaySummary()
            refreshHistoryItems()
        }
    }

    fun reloadHistory() {
        viewModelScope.launch {
            refreshTodaySummary()
            refreshHistoryItems()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            repository.clearHistory()
            refreshTodaySummary()
            refreshHistoryItems()
        }
    }

    private suspend fun refreshTodaySummary() {
        todaySummary = repository.todaySummary()
        pomodoroTodaySummary = repository.todaySummaryForMode(TIMER_MODE_POMODORO)
    }

    private suspend fun refreshHistoryItems() {
        isHistoryLoading = true
        historyItems.clear()
        historyItems.addAll(repository.recentHistory())
        isHistoryLoading = false
    }

    private fun syncRuntimeAndNotification() {
        repository.saveRuntimeState(timers.toList())
        TimerForegroundService.sync(appContext, timers.toList())
    }

    private fun updateShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutManager = appContext.getSystemService(ShortcutManager::class.java)
        shortcutManager.dynamicShortcuts = timers.take(4).map { timer ->
            ShortcutInfo.Builder(appContext, "timer_${timer.preset.id}")
                .setShortLabel(timer.preset.name.take(10).ifBlank { "Timer" })
                .setLongLabel("Start ${timer.preset.name}")
                .setIcon(AndroidIcon.createWithResource(appContext, android.R.drawable.ic_media_play))
                .setIntent(
                    Intent(appContext, MainActivity::class.java).apply {
                        action = ACTION_START_TIMER
                        putExtra(EXTRA_TIMER_ID, timer.preset.id)
                        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                )
                .build()
        }
    }

    override fun onCleared() {
        syncRuntimeAndNotification()
        TimerCommandBus.clear()
        alertPlayer.release()
        super.onCleared()
    }

    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return TimerBoardViewModel(application) as T
        }
    }
}

class TimerAlertPlayer(private val context: Context) {
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
    private val handler = Handler(Looper.getMainLooper())
    private var activeRingtone: Ringtone? = null

    fun play(alarmId: String, alarmUri: String?) {
        alarmUri?.let { uriText ->
            val ringtone = RingtoneManager.getRingtone(context, Uri.parse(uriText))
            if (ringtone != null) {
                activeRingtone?.stop()
                activeRingtone = ringtone
                ringtone.play()
                handler.postDelayed({ ringtone.stop() }, 10_000L)
                vibrateSystemSound()
                return
            }
        }

        val alarm = alarmById(alarmId)
        alarm.toneEvents.forEach { event ->
            handler.postDelayed(
                { toneGenerator.startTone(event.tone, event.durationMillis) },
                event.delayMillis
            )
        }
        vibrator.vibrate(
            VibrationEffect.createWaveform(
                alarm.vibrationPattern,
                alarm.vibrationAmplitudes,
                -1
            )
        )
    }

    private fun vibrateSystemSound() {
        vibrator.vibrate(VibrationEffect.createOneShot(450L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        activeRingtone?.stop()
        toneGenerator.stopTone()
    }

    private val vibrator: Vibrator
        get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    fun release() {
        stop()
        toneGenerator.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerBoardApp(viewModel: TimerBoardViewModel) {
    val context = LocalContext.current
    var showCreateDialog by remember { mutableStateOf(false) }
    var timerBeingEdited by remember { mutableStateOf<TimerItem?>(null) }
    var timerPendingDelete by remember { mutableStateOf<TimerItem?>(null) }
    var fullScreenTimerId by remember { mutableStateOf<Long?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    var showBackupDialog by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    fun requestTimerNotificationPermissionIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun startTimerWithPermission(timerId: Long) {
        requestTimerNotificationPermissionIfNeeded()
        viewModel.startTimer(timerId)
    }

    fun startAllWithPermission() {
        requestTimerNotificationPermissionIfNeeded()
        viewModel.startAll()
    }

    if (showHistory) {
        HistoryScreen(
            summary = viewModel.todaySummary,
            pomodoroSummary = viewModel.pomodoroTodaySummary,
            items = viewModel.historyItems,
            isLoading = viewModel.isHistoryLoading,
            onBack = { showHistory = false },
            onRefresh = viewModel::reloadHistory,
            onClear = viewModel::clearHistory
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TimerBoard", fontWeight = FontWeight.SemiBold)
                        Text(
                            appStatusText(
                                runningCount = viewModel.timers.count { it.isRunning },
                                summary = viewModel.todaySummary
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = {
                        viewModel.reloadHistory()
                        showHistory = true
                    }) {
                        Icon(Icons.Default.History, contentDescription = "View history")
                    }
                    TextButton(onClick = { showBackupDialog = true }) {
                        Text("Backup")
                    }
                    TextButton(onClick = ::startAllWithPermission) {
                        Text("Start all")
                    }
                    TextButton(onClick = viewModel::pauseAll) {
                        Text("Pause all")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add timer")
            }
        }
    ) { padding ->
        if (viewModel.isLoading) {
            LoadingTimers(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (viewModel.timers.isEmpty()) {
            EmptyTimers(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onCreate = { showCreateDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(viewModel.timers, key = { it.preset.id }) { timer ->
                    val timerIndex = viewModel.timers.indexOfFirst { it.preset.id == timer.preset.id }
                    TimerCard(
                        timer = timer,
                        canMoveUp = timerIndex > 0,
                        canMoveDown = timerIndex >= 0 && timerIndex < viewModel.timers.lastIndex,
                        onStart = { startTimerWithPermission(timer.preset.id) },
                        onPause = { viewModel.pauseTimer(timer.preset.id) },
                        onReset = { viewModel.resetTimer(timer.preset.id) },
                        onDelete = { timerPendingDelete = timer },
                        onDuplicate = { viewModel.duplicateTimer(timer.preset.id) },
                        onMoveUp = { viewModel.moveTimerUp(timer.preset.id) },
                        onMoveDown = { viewModel.moveTimerDown(timer.preset.id) },
                        onSnooze = { viewModel.snoozeTimer(timer.preset.id) },
                        onEditDuration = { timerBeingEdited = timer },
                        onOpenFullScreen = { fullScreenTimerId = timer.preset.id }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTimerDialog(
            onDismiss = { showCreateDialog = false },
            onCreateCountdown = { name, minutes, seconds, color, alarmId, alarmUri ->
                viewModel.addTimer(name, minutes, seconds, color, alarmId, alarmUri)
                showCreateDialog = false
            },
            onCreatePomodoro = { name, focusMinutes, breakMinutes, sessions, longBreakMinutes, color, alarmId, alarmUri ->
                viewModel.addPomodoroTimer(
                    name = name,
                    focusMinutes = focusMinutes,
                    breakMinutes = breakMinutes,
                    sessions = sessions,
                    longBreakMinutes = longBreakMinutes,
                    color = color,
                    alarmId = alarmId,
                    alarmUri = alarmUri
                )
                showCreateDialog = false
            },
            onCreateInterval = { name, warmup, work, rest, cooldown, rounds, color, alarmId, alarmUri ->
                viewModel.addIntervalTimer(
                    name = name,
                    warmupSeconds = warmup,
                    workSeconds = work,
                    restSeconds = rest,
                    cooldownSeconds = cooldown,
                    rounds = rounds,
                    color = color,
                    alarmId = alarmId,
                    alarmUri = alarmUri
                )
                showCreateDialog = false
            }
        )
    }

    if (showBackupDialog) {
        BackupDialog(
            backupText = viewModel.exportPresetsText(),
            onDismiss = { showBackupDialog = false },
            onExport = { sharePresetBackup(context, viewModel.exportPresetsText()) },
            onImport = { raw -> viewModel.importPresetsText(raw) }
        )
    }

    timerPendingDelete?.let { timer ->
        DeleteTimerDialog(
            timerName = timer.preset.name,
            onDismiss = { timerPendingDelete = null },
            onDelete = {
                viewModel.deleteTimer(timer.preset.id)
                timerPendingDelete = null
            }
        )
    }

    timerBeingEdited?.let { timer ->
        EditDurationDialog(
            timer = timer,
            onDismiss = { timerBeingEdited = null },
            onSave = { hours, minutes, seconds, alarmId, alarmUri ->
                viewModel.updateTimerDuration(
                    timer.preset.id,
                    hours,
                    minutes,
                    seconds,
                    alarmId,
                    alarmUri
                )
                timerBeingEdited = null
            }
        )
    }

    fullScreenTimerId?.let { timerId ->
        viewModel.timers.firstOrNull { it.preset.id == timerId }?.let { timer ->
            Dialog(
                onDismissRequest = { fullScreenTimerId = null },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                IntervalFullScreenView(
                    timer = timer,
                    onDismiss = { fullScreenTimerId = null },
                    onStart = { startTimerWithPermission(timer.preset.id) },
                    onPause = { viewModel.pauseTimer(timer.preset.id) },
                    onReset = { viewModel.resetTimer(timer.preset.id) }
                )
            }
        } ?: run {
            fullScreenTimerId = null
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    summary: CompletionSummary,
    pomodoroSummary: CompletionSummary,
    items: List<TimerHistoryItem>,
    isLoading: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    var confirmClear by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf(HISTORY_FILTER_ALL) }
    val context = LocalContext.current
    val filteredItems = remember(items, selectedFilter) {
        filterHistoryItems(items, selectedFilter)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to timers")
                    }
                },
                title = {
                    Column {
                        Text("History", fontWeight = FontWeight.SemiBold)
                        Text(
                            historyStatusText(summary),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh history")
                    }
                    TextButton(
                        enabled = filteredItems.isNotEmpty(),
                        onClick = {
                            shareHistory(
                                context = context,
                                items = filteredItems,
                                filter = selectedFilter
                            )
                        }
                    ) {
                        Text("Export")
                    }
                    TextButton(
                        enabled = items.isNotEmpty(),
                        onClick = { confirmClear = true }
                    ) {
                        Text("Clear")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            LoadingTimers(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else if (items.isEmpty()) {
            EmptyHistory(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    HistorySummaryCard(summary, pomodoroSummary)
                }
                item {
                    HistoryFilterControls(
                        selectedFilter = selectedFilter,
                        onSelected = { selectedFilter = it }
                    )
                }
                if (filteredItems.isEmpty()) {
                    item {
                        EmptyFilteredHistory()
                    }
                } else {
                    items(filteredItems, key = { it.id }) { history ->
                        HistoryItemCard(history)
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("Remove all completed timer history? This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClear()
                        confirmClear = false
                    }
                ) {
                    Text("Clear")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun HistoryFilterControls(
    selectedFilter: String,
    onSelected: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Filter",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilterButton(
                    label = "All",
                    selected = selectedFilter == HISTORY_FILTER_ALL,
                    onClick = { onSelected(HISTORY_FILTER_ALL) },
                    modifier = Modifier.weight(1f)
                )
                HistoryFilterButton(
                    label = "Countdown",
                    selected = selectedFilter == TIMER_MODE_COUNTDOWN,
                    onClick = { onSelected(TIMER_MODE_COUNTDOWN) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HistoryFilterButton(
                    label = "Pomodoro",
                    selected = selectedFilter == TIMER_MODE_POMODORO,
                    onClick = { onSelected(TIMER_MODE_POMODORO) },
                    modifier = Modifier.weight(1f)
                )
                HistoryFilterButton(
                    label = "Interval",
                    selected = selectedFilter == TIMER_MODE_INTERVAL,
                    onClick = { onSelected(TIMER_MODE_INTERVAL) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun HistoryFilterButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun HistorySummaryCard(summary: CompletionSummary, pomodoroSummary: CompletionSummary) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryMetric(
                    label = "Completed",
                    value = summary.count.toString(),
                    modifier = Modifier.weight(1f)
                )
                HistoryMetric(
                    label = "Total time",
                    value = summary.totalMillis.formatTimer(),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Pomodoro",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HistoryMetric(
                    label = "Sessions",
                    value = pomodoroSummary.count.toString(),
                    modifier = Modifier.weight(1f)
                )
                HistoryMetric(
                    label = "Focus time",
                    value = pomodoroSummary.totalMillis.formatTimer(),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun HistoryMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun HistoryItemCard(history: TimerHistoryItem) {
    val modeLabel = modeLabel(history.mode)

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    history.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${history.completedAtMillis.formatHistoryTime()} | $modeLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                history.durationMillis.formatTimer(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(44.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No history yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Completed timers will appear here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun EmptyFilteredHistory() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "No matching history",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Try another filter.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TimerCard(
    timer: TimerItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onSnooze: () -> Unit,
    onEditDuration: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    val totalDuration = timer.preset.totalDurationMillis()
    val progress = 1f - (timer.remainingMillis.toFloat() / totalDuration.toFloat())
        .coerceIn(0f, 1f)
    val accent = Color(timer.preset.color)
    val phaseText = timer.intervalPhaseText()
    val modeLabel = modeLabel(timer.preset.mode)
    val isComplete = !timer.isRunning && timer.remainingMillis == 0L

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(accent)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = timer.preset.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    modeLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(onClick = onEditDuration) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${timer.preset.name}")
                }
                IconButton(
                    enabled = canMoveUp,
                    onClick = onMoveUp
                ) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move ${timer.preset.name} up")
                }
                IconButton(
                    enabled = canMoveDown,
                    onClick = onMoveDown
                ) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move ${timer.preset.name} down")
                }
                IconButton(onClick = onDuplicate) {
                    Icon(Icons.Default.FileCopy, contentDescription = "Duplicate ${timer.preset.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete ${timer.preset.name}")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = timer.remainingMillis.formatTimer(),
                fontSize = 46.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.clickable(onClick = onEditDuration)
            )
            if (phaseText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    phaseText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress)
                        .height(8.dp)
                        .background(accent)
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = if (timer.isRunning) onPause else onStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (timer.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        when {
                            timer.isRunning -> "Pause"
                            isComplete -> "Restart"
                            else -> "Start"
                        }
                    )
                }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }
            if (timer.preset.mode == TIMER_MODE_INTERVAL || timer.preset.mode == TIMER_MODE_POMODORO) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onOpenFullScreen,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInFull, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Full screen")
                }
            }
            if (timer.isRunning) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Alarm will signal at ${timer.signalTimeText()}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            } else if (isComplete) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Complete",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = onSnooze) {
                        Text("Snooze 5 min")
                    }
                }
            }
        }
    }
}

@Composable
fun BackupDialog(
    backupText: String,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: (String) -> Boolean
) {
    var importText by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Backup timers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Export saved timer presets or paste a TimerBoard backup to replace the current board.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = backupText,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Current backup") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = importText,
                    onValueChange = {
                        importText = it
                        importError = false
                    },
                    label = { Text("Paste backup to import") },
                    isError = importError,
                    minLines = 3,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
                if (importError) {
                    Text(
                        "Backup could not be read.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onExport) {
                Text("Export")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                TextButton(
                    enabled = importText.isNotBlank(),
                    onClick = {
                        if (onImport(importText)) {
                            onDismiss()
                        } else {
                            importError = true
                        }
                    }
                ) {
                    Text("Import")
                }
            }
        }
    )
}

@Composable
fun LoadingTimers(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            "Loading timers",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun DeleteTimerDialog(
    timerName: String,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete timer?") },
        text = { Text("Delete \"$timerName\"? This cannot be undone.") },
        confirmButton = {
            Button(onClick = onDelete) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun IntervalFullScreenView(
    timer: TimerItem,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit
) {
    val accent = Color(timer.preset.color)
    val totalDuration = timer.preset.totalDurationMillis()
    val progress = 1f - (timer.remainingMillis.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f)
    val phaseText = timer.intervalPhaseText() ?: modeLabel(timer.preset.mode)

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        timer.preset.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "Alarm will signal at ${timer.signalTimeText()}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close full screen")
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    phaseText.uppercase(Locale.US),
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                    color = accent
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    timer.remainingMillis.formatTimer(),
                    fontSize = 76.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(18.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress)
                            .height(14.dp)
                            .background(accent)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    timer.preset.intervalSummary(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = if (timer.isRunning) onPause else onStart,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        if (timer.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (timer.isRunning) "Pause" else "Start")
                }
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Reset")
                }
            }
        }
    }
}

@Composable
fun EmptyTimers(modifier: Modifier = Modifier, onCreate: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "No timers yet",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Create a saved timer to start building your board.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreate) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Create timer")
        }
    }
}

@Composable
fun EditDurationDialog(
    timer: TimerItem,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, String, String?) -> Unit
) {
    val durationSeconds = timer.preset.durationMillis / 1000L
    var hours by remember(timer.preset.id) {
        mutableStateOf((durationSeconds / 3600L).toString())
    }
    var minutes by remember(timer.preset.id) {
        mutableStateOf(((durationSeconds % 3600L) / 60L).toString())
    }
    var seconds by remember(timer.preset.id) {
        mutableStateOf((durationSeconds % 60L).toString())
    }
    var selectedAlarmId by remember(timer.preset.id) {
        mutableStateOf(alarmById(timer.preset.alarmId).id)
    }
    var selectedAlarmUri by remember(timer.preset.id) {
        mutableStateOf(timer.preset.alarmUri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${timer.preset.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TimeNumberField(
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(2) },
                        label = "Hours",
                        modifier = Modifier.weight(1f)
                    )
                    TimeNumberField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                        label = "Minutes",
                        modifier = Modifier.weight(1f)
                    )
                    TimeNumberField(
                        value = seconds,
                        onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                        label = "Seconds",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Saving resets this timer to the new duration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                AlarmSelector(
                    selectedAlarmId = selectedAlarmId,
                    selectedAlarmUri = selectedAlarmUri,
                    onSelected = {
                        selectedAlarmId = it
                        selectedAlarmUri = null
                    },
                    onSystemSoundSelected = { selectedAlarmUri = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        hours.toIntOrNull() ?: 0,
                        (minutes.toIntOrNull() ?: 0).coerceIn(0, 59),
                        (seconds.toIntOrNull() ?: 0).coerceIn(0, 59),
                        selectedAlarmId,
                        selectedAlarmUri
                    )
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun CreateTimerDialog(
    onDismiss: () -> Unit,
    onCreateCountdown: (String, Int, Int, Long, String, String?) -> Unit,
    onCreatePomodoro: (String, Int, Int, Int, Int, Long, String, String?) -> Unit,
    onCreateInterval: (String, Int, Int, Int, Int, Int, Long, String, String?) -> Unit
) {
    var mode by remember { mutableStateOf(TIMER_MODE_COUNTDOWN) }
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("5") }
    var seconds by remember { mutableStateOf("0") }
    var pomodoroFocusMinutes by remember { mutableStateOf("25") }
    var pomodoroBreakMinutes by remember { mutableStateOf("5") }
    var pomodoroSessions by remember { mutableStateOf("4") }
    var pomodoroLongBreakMinutes by remember { mutableStateOf("15") }
    var warmupSeconds by remember { mutableStateOf("30") }
    var workSeconds by remember { mutableStateOf("45") }
    var restSeconds by remember { mutableStateOf("15") }
    var cooldownSeconds by remember { mutableStateOf("60") }
    var rounds by remember { mutableStateOf("4") }
    var selectedColor by remember { mutableLongStateOf(timerColors.first()) }
    var selectedAlarmId by remember { mutableStateOf(DEFAULT_ALARM_ID) }
    var selectedAlarmUri by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(
                        text = "Countdown",
                        selected = mode == TIMER_MODE_COUNTDOWN,
                        onClick = { mode = TIMER_MODE_COUNTDOWN },
                        modifier = Modifier.weight(1f)
                    )
                    ModeButton(
                        text = "Pomodoro",
                        selected = mode == TIMER_MODE_POMODORO,
                        onClick = { mode = TIMER_MODE_POMODORO },
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ModeButton(
                        text = "Interval",
                        selected = mode == TIMER_MODE_INTERVAL,
                        onClick = { mode = TIMER_MODE_INTERVAL },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (mode == TIMER_MODE_COUNTDOWN) {
                    Text(
                        "Quick duration",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickCountdownPresets.take(3).forEach { preset ->
                            QuickPresetButton(
                                label = preset.label,
                                onClick = {
                                    minutes = preset.minutes.toString()
                                    seconds = "0"
                                    if (name.isBlank()) name = preset.label
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickCountdownPresets.drop(3).forEach { preset ->
                            QuickPresetButton(
                                label = preset.label,
                                onClick = {
                                    minutes = preset.minutes.toString()
                                    seconds = "0"
                                    if (name.isBlank()) name = preset.label
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeNumberField(
                            value = minutes,
                            onValueChange = { minutes = it.filter(Char::isDigit).take(3) },
                            label = "Minutes",
                            modifier = Modifier.weight(1f)
                        )
                        TimeNumberField(
                            value = seconds,
                            onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                            label = "Seconds",
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else if (mode == TIMER_MODE_POMODORO) {
                    Text(
                        "Pomodoro template",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pomodoroTemplates.forEach { template ->
                            QuickPresetButton(
                                label = template.label,
                                onClick = {
                                    pomodoroFocusMinutes = template.focusMinutes.toString()
                                    pomodoroBreakMinutes = template.breakMinutes.toString()
                                    pomodoroSessions = template.sessions.toString()
                                    pomodoroLongBreakMinutes = template.longBreakMinutes.toString()
                                    if (name.isBlank()) name = template.label
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeNumberField(
                            value = pomodoroFocusMinutes,
                            onValueChange = { pomodoroFocusMinutes = it.filter(Char::isDigit).take(3) },
                            label = "Focus min",
                            modifier = Modifier.weight(1f)
                        )
                        TimeNumberField(
                            value = pomodoroBreakMinutes,
                            onValueChange = { pomodoroBreakMinutes = it.filter(Char::isDigit).take(3) },
                            label = "Break min",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeNumberField(
                            value = pomodoroSessions,
                            onValueChange = { pomodoroSessions = it.filter(Char::isDigit).take(2) },
                            label = "Sessions",
                            modifier = Modifier.weight(1f)
                        )
                        TimeNumberField(
                            value = pomodoroLongBreakMinutes,
                            onValueChange = { pomodoroLongBreakMinutes = it.filter(Char::isDigit).take(3) },
                            label = "Long break min",
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Text(
                        "Interval templates",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervalTemplates.take(2).forEach { template ->
                            QuickPresetButton(
                                label = template.label,
                                onClick = {
                                    warmupSeconds = template.warmupSeconds.toString()
                                    workSeconds = template.workSeconds.toString()
                                    restSeconds = template.restSeconds.toString()
                                    cooldownSeconds = template.cooldownSeconds.toString()
                                    rounds = template.rounds.toString()
                                    if (name.isBlank()) name = template.label
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        intervalTemplates.drop(2).forEach { template ->
                            QuickPresetButton(
                                label = template.label,
                                onClick = {
                                    warmupSeconds = template.warmupSeconds.toString()
                                    workSeconds = template.workSeconds.toString()
                                    restSeconds = template.restSeconds.toString()
                                    cooldownSeconds = template.cooldownSeconds.toString()
                                    rounds = template.rounds.toString()
                                    if (name.isBlank()) name = template.label
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeNumberField(
                            value = warmupSeconds,
                            onValueChange = { warmupSeconds = it.filter(Char::isDigit).take(4) },
                            label = "Warmup sec",
                            modifier = Modifier.weight(1f)
                        )
                        TimeNumberField(
                            value = workSeconds,
                            onValueChange = { workSeconds = it.filter(Char::isDigit).take(4) },
                            label = "Work sec",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TimeNumberField(
                            value = restSeconds,
                            onValueChange = { restSeconds = it.filter(Char::isDigit).take(4) },
                            label = "Rest sec",
                            modifier = Modifier.weight(1f)
                        )
                        TimeNumberField(
                            value = cooldownSeconds,
                            onValueChange = { cooldownSeconds = it.filter(Char::isDigit).take(4) },
                            label = "Cooldown sec",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    TimeNumberField(
                        value = rounds,
                        onValueChange = { rounds = it.filter(Char::isDigit).take(3) },
                        label = "Rounds",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timerColors.forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
                AlarmSelector(
                    selectedAlarmId = selectedAlarmId,
                    selectedAlarmUri = selectedAlarmUri,
                    onSelected = {
                        selectedAlarmId = it
                        selectedAlarmUri = null
                    },
                    onSystemSoundSelected = { selectedAlarmUri = it }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (mode == TIMER_MODE_COUNTDOWN) {
                        onCreateCountdown(
                            name.trim(),
                            minutes.toIntOrNull() ?: 0,
                            (seconds.toIntOrNull() ?: 0).coerceIn(0, 59),
                            selectedColor,
                            selectedAlarmId,
                            selectedAlarmUri
                        )
                    } else if (mode == TIMER_MODE_POMODORO) {
                        onCreatePomodoro(
                            name.trim(),
                            pomodoroFocusMinutes.toIntOrNull() ?: 25,
                            pomodoroBreakMinutes.toIntOrNull() ?: 5,
                            pomodoroSessions.toIntOrNull() ?: 4,
                            pomodoroLongBreakMinutes.toIntOrNull() ?: 15,
                            selectedColor,
                            selectedAlarmId,
                            selectedAlarmUri
                        )
                    } else {
                        onCreateInterval(
                            name.trim(),
                            warmupSeconds.toIntOrNull() ?: 0,
                            (workSeconds.toIntOrNull() ?: 1).coerceAtLeast(1),
                            restSeconds.toIntOrNull() ?: 0,
                            cooldownSeconds.toIntOrNull() ?: 0,
                            (rounds.toIntOrNull() ?: 1).coerceAtLeast(1),
                            selectedColor,
                            selectedAlarmId,
                            selectedAlarmUri
                        )
                    }
                }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun QuickPresetButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
    ) {
        Text(label)
    }
}

@Composable
fun TimeNumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier
    )
}

@Composable
fun ModeButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(text)
        }
    }
}

@Composable
fun AlarmSelector(
    selectedAlarmId: String,
    selectedAlarmUri: String?,
    onSelected: (String) -> Unit,
    onSystemSoundSelected: (String?) -> Unit
) {
    val context = LocalContext.current
    val ringtonePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(
                RingtoneManager.EXTRA_RINGTONE_PICKED_URI
            )
            onSystemSoundSelected(uri?.toString())
        }
    }
    val selectedSystemSoundTitle = selectedAlarmUri?.let { uriText ->
        RingtoneManager.getRingtone(context, Uri.parse(uriText))?.getTitle(context)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Alarm sound",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
        alarmSounds.forEach { alarm ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onSelected(alarm.id) }
                    .padding(vertical = 6.dp)
            ) {
                RadioButton(
                    selected = selectedAlarmId == alarm.id,
                    onClick = { onSelected(alarm.id) }
                )
                Column(Modifier.weight(1f)) {
                    Text(alarm.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        alarm.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        OutlinedButton(
            onClick = {
                val existingUri = selectedAlarmUri?.let(Uri::parse)
                ringtonePicker.launch(
                    Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                        putExtra(
                            RingtoneManager.EXTRA_RINGTONE_TYPE,
                            RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION
                        )
                        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose timer sound")
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                        putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existingUri)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selectedSystemSoundTitle == null) {
                    "Choose phone sound"
                } else {
                    "Phone sound: $selectedSystemSoundTitle"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ColorSwatch(color: Long, selected: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Box(
            Modifier
                .size(if (selected) 34.dp else 28.dp)
                .clip(CircleShape)
                .background(Color(color))
        )
    }
}

@Composable
fun TimerBoardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = androidx.compose.material3.lightColorScheme(
            primary = Color(0xFF315F72),
            secondary = Color(0xFF785A2F),
            tertiary = Color(0xFF436B4F),
            background = Color(0xFFFAF9F6),
            surface = Color(0xFFFFFBFF),
            surfaceVariant = Color(0xFFF0ECE5)
        ),
        content = {
            Surface(color = MaterialTheme.colorScheme.background) {
                content()
            }
        }
    )
}

val timerColors = listOf(
    0xFF315F72,
    0xFFB15C37,
    0xFF3E7455,
    0xFF8A5A9E,
    0xFF70733E
)

const val DEFAULT_ALARM_ID = "chime"
const val HISTORY_FILTER_ALL = "all"
const val TIMER_MODE_COUNTDOWN = "countdown"
const val TIMER_MODE_INTERVAL = "interval"
const val TIMER_MODE_POMODORO = "pomodoro"
const val ACTION_START_TIMER = "com.mark.timerboard.START_TIMER"
const val EXTRA_TIMER_ID = "timer_id"
const val SNOOZE_MILLIS = 5 * 60_000L

fun modeLabel(mode: String): String {
    return when (mode) {
        TIMER_MODE_INTERVAL -> "Interval"
        TIMER_MODE_POMODORO -> "Pomodoro"
        else -> "Countdown"
    }
}

fun filterHistoryItems(items: List<TimerHistoryItem>, filter: String): List<TimerHistoryItem> {
    if (filter == HISTORY_FILTER_ALL) return items
    return items.filter { it.mode == filter }
}

fun shareHistory(context: Context, items: List<TimerHistoryItem>, filter: String) {
    val filterLabel = if (filter == HISTORY_FILTER_ALL) "All" else modeLabel(filter)
    val body = buildString {
        appendLine("TimerBoard history export")
        appendLine("Filter: $filterLabel")
        appendLine("Exported at: ${System.currentTimeMillis().formatHistoryTime()}")
        appendLine()
        appendLine("completed_at,name,mode,duration")
        items.forEach { item ->
            appendLine(
                listOf(
                    item.completedAtMillis.formatHistoryTime(),
                    item.name,
                    modeLabel(item.mode),
                    item.durationMillis.formatTimer()
                ).joinToString(",") { it.csvCell() }
            )
        }
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "TimerBoard history")
        putExtra(Intent.EXTRA_TEXT, body)
    }
    context.startActivity(Intent.createChooser(intent, "Export history"))
}

fun sharePresetBackup(context: Context, backupText: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "TimerBoard timer backup")
        putExtra(Intent.EXTRA_TEXT, backupText)
    }
    context.startActivity(Intent.createChooser(intent, "Export timer backup"))
}

fun List<TimerPreset>.toBackupJson(): String {
    val array = JSONArray()
    forEach { preset ->
        array.put(
            JSONObject()
                .put("id", preset.id)
                .put("name", preset.name)
                .put("durationMillis", preset.durationMillis)
                .put("color", preset.color)
                .put("alarmId", alarmById(preset.alarmId).id)
                .put("alarmUri", preset.alarmUri)
                .put("mode", preset.mode)
                .put("warmupMillis", preset.warmupMillis)
                .put("workMillis", preset.workMillis)
                .put("restMillis", preset.restMillis)
                .put("cooldownMillis", preset.cooldownMillis)
                .put("rounds", preset.rounds)
        )
    }
    return array.toString(2)
}

fun String.toTimerPresetsOrNull(): List<TimerPreset>? {
    return runCatching {
        val array = JSONArray(this)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            TimerPreset(
                id = item.optLong("id").takeIf { it > 0L } ?: System.currentTimeMillis() + index,
                name = item.optString("name", "Timer").ifBlank { "Timer" },
                durationMillis = item.optLong("durationMillis", 60_000L).coerceAtLeast(1_000L),
                color = item.optLong("color", timerColors.first()),
                alarmId = alarmById(item.optString("alarmId", DEFAULT_ALARM_ID)).id,
                alarmUri = item.optString("alarmUri", "").ifBlank { null },
                mode = item.optString("mode", TIMER_MODE_COUNTDOWN),
                warmupMillis = item.optLong("warmupMillis", 0L).coerceAtLeast(0L),
                workMillis = item.optLong("workMillis", 0L).coerceAtLeast(0L),
                restMillis = item.optLong("restMillis", 0L).coerceAtLeast(0L),
                cooldownMillis = item.optLong("cooldownMillis", 0L).coerceAtLeast(0L),
                rounds = item.optInt("rounds", 1).coerceAtLeast(1)
            )
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }
}

fun String.csvCell(): String {
    return "\"${replace("\"", "\"\"")}\""
}

data class QuickCountdownPreset(
    val label: String,
    val minutes: Int
)

val quickCountdownPresets = listOf(
    QuickCountdownPreset("1 min", 1),
    QuickCountdownPreset("3 min", 3),
    QuickCountdownPreset("5 min", 5),
    QuickCountdownPreset("10 min", 10),
    QuickCountdownPreset("15 min", 15),
    QuickCountdownPreset("25 min", 25)
)

data class PomodoroTemplate(
    val label: String,
    val focusMinutes: Int,
    val breakMinutes: Int,
    val sessions: Int,
    val longBreakMinutes: Int
)

val pomodoroTemplates = listOf(
    PomodoroTemplate("Classic", 25, 5, 4, 15),
    PomodoroTemplate("Short", 15, 3, 4, 10),
    PomodoroTemplate("Deep", 50, 10, 2, 20)
)

data class IntervalTemplate(
    val label: String,
    val warmupSeconds: Int,
    val workSeconds: Int,
    val restSeconds: Int,
    val cooldownSeconds: Int,
    val rounds: Int
)

val intervalTemplates = listOf(
    IntervalTemplate("Tabata", 10, 20, 10, 30, 8),
    IntervalTemplate("HIIT", 60, 45, 15, 60, 8),
    IntervalTemplate("Boxing", 60, 180, 60, 60, 3),
    IntervalTemplate("Focus Sprint", 30, 300, 60, 60, 4)
)

val alarmSounds = listOf(
    AlarmSound(
        id = "beacon",
        label = "Beacon",
        description = "Jarring triple beep",
        toneEvents = listOf(
            ToneEvent(0L, ToneGenerator.TONE_PROP_NACK, 220),
            ToneEvent(320L, ToneGenerator.TONE_PROP_NACK, 220),
            ToneEvent(640L, ToneGenerator.TONE_PROP_NACK, 320)
        ),
        vibrationPattern = longArrayOf(0L, 180L, 90L, 180L, 90L, 260L),
        vibrationAmplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
    ),
    AlarmSound(
        id = "pulse",
        label = "Pulse",
        description = "Sharp repeating alert",
        toneEvents = listOf(
            ToneEvent(0L, ToneGenerator.TONE_PROP_BEEP, 160),
            ToneEvent(220L, ToneGenerator.TONE_PROP_BEEP, 160),
            ToneEvent(440L, ToneGenerator.TONE_PROP_BEEP, 160),
            ToneEvent(660L, ToneGenerator.TONE_PROP_BEEP, 220)
        ),
        vibrationPattern = longArrayOf(0L, 120L, 80L, 120L, 80L, 120L, 80L, 180L),
        vibrationAmplitudes = intArrayOf(0, 230, 0, 230, 0, 230, 0, 255)
    ),
    AlarmSound(
        id = "chime",
        label = "Chime",
        description = "Smooth two-note signal",
        toneEvents = listOf(
            ToneEvent(0L, ToneGenerator.TONE_PROP_ACK, 220),
            ToneEvent(340L, ToneGenerator.TONE_PROP_PROMPT, 360)
        ),
        vibrationPattern = longArrayOf(0L, 120L, 140L, 180L),
        vibrationAmplitudes = intArrayOf(0, 120, 0, 150)
    ),
    AlarmSound(
        id = "soft",
        label = "Soft",
        description = "Gentle single reminder",
        toneEvents = listOf(
            ToneEvent(0L, ToneGenerator.TONE_PROP_ACK, 250)
        ),
        vibrationPattern = longArrayOf(0L, 220L),
        vibrationAmplitudes = intArrayOf(0, 90)
    )
)

fun alarmById(id: String): AlarmSound {
    return alarmSounds.firstOrNull { it.id == id } ?: alarmSounds.first { it.id == DEFAULT_ALARM_ID }
}

val Int.minutes: Long
    get() = this * 60_000L

fun TimerPreset.totalDurationMillis(): Long {
    if (mode == TIMER_MODE_POMODORO && workMillis <= 0L) return durationMillis.coerceAtLeast(1_000L)
    if (mode != TIMER_MODE_INTERVAL && mode != TIMER_MODE_POMODORO) return durationMillis.coerceAtLeast(1_000L)
    return intervalDurationMillis(
        warmupSeconds = (warmupMillis / 1000L).toInt(),
        workSeconds = (workMillis / 1000L).toInt(),
        restSeconds = (restMillis / 1000L).toInt(),
        cooldownSeconds = (cooldownMillis / 1000L).toInt(),
        rounds = rounds
    )
}

fun intervalDurationMillis(
    warmupSeconds: Int,
    workSeconds: Int,
    restSeconds: Int,
    cooldownSeconds: Int,
    rounds: Int
): Long {
    val normalizedRounds = rounds.coerceAtLeast(1)
    val work = workSeconds.coerceAtLeast(1) * 1000L
    val rest = restSeconds.coerceAtLeast(0) * 1000L
    return (warmupSeconds.coerceAtLeast(0) * 1000L) +
        ((work + rest) * normalizedRounds) +
        (cooldownSeconds.coerceAtLeast(0) * 1000L)
}

fun TimerItem.intervalPhaseText(): String? {
    val preset = preset
    if (preset.mode != TIMER_MODE_INTERVAL && preset.mode != TIMER_MODE_POMODORO) return null
    if (!isRunning && remainingMillis == 0L) return "Complete"
    if (preset.mode == TIMER_MODE_POMODORO && preset.workMillis <= 0L) return null

    var elapsedMillis = (preset.totalDurationMillis() - remainingMillis).coerceAtLeast(0L)
    if (preset.warmupMillis > 0L) {
        if (elapsedMillis < preset.warmupMillis) return "Warmup"
        elapsedMillis -= preset.warmupMillis
    }

    val workMillis = preset.workMillis.coerceAtLeast(1_000L)
    val restMillis = preset.restMillis.coerceAtLeast(0L)
    val roundMillis = workMillis + restMillis
    val rounds = preset.rounds.coerceAtLeast(1)
    val intervalMillis = roundMillis * rounds

    if (elapsedMillis < intervalMillis) {
        val roundIndex = (elapsedMillis / roundMillis).toInt().coerceIn(0, rounds - 1)
        val roundElapsed = elapsedMillis % roundMillis
        val phase = when {
            preset.mode == TIMER_MODE_POMODORO && (roundElapsed < workMillis || restMillis == 0L) -> "Focus"
            preset.mode == TIMER_MODE_POMODORO -> "Break"
            roundElapsed < workMillis || restMillis == 0L -> "Work"
            else -> "Rest"
        }
        return "$phase round ${roundIndex + 1} of $rounds"
    }

    return if (preset.cooldownMillis > 0L) {
        if (preset.mode == TIMER_MODE_POMODORO) "Long break" else "Cooldown"
    } else {
        "Finishing"
    }
}

fun TimerPreset.intervalSummary(): String {
    if (mode == TIMER_MODE_POMODORO && workMillis > 0L) {
        return "Focus ${(workMillis / 60_000L)}m | Break ${(restMillis / 60_000L)}m | " +
            "Long break ${(cooldownMillis / 60_000L)}m | ${rounds.coerceAtLeast(1)} sessions"
    }
    if (mode != TIMER_MODE_INTERVAL) return ""
    return "Warmup ${(warmupMillis / 1000L)}s | Work ${(workMillis / 1000L)}s | " +
        "Rest ${(restMillis / 1000L)}s | Cooldown ${(cooldownMillis / 1000L)}s | " +
        "${rounds.coerceAtLeast(1)} rounds"
}

fun TimerItem.signalTimeText(): String {
    return timeFormatter.format(Date(System.currentTimeMillis() + remainingMillis))
}

fun appStatusText(runningCount: Int, summary: CompletionSummary): String {
    val completionText = if (summary.count == 1) {
        "1 completed today"
    } else {
        "${summary.count} completed today"
    }
    return if (summary.totalMillis > 0L) {
        "$runningCount running | $completionText | ${summary.totalMillis.formatTimer()} total"
    } else {
        "$runningCount running | $completionText"
    }
}

fun historyStatusText(summary: CompletionSummary): String {
    val completionText = if (summary.count == 1) {
        "1 completed today"
    } else {
        "${summary.count} completed today"
    }
    return if (summary.totalMillis > 0L) {
        "$completionText | ${summary.totalMillis.formatTimer()} total"
    } else {
        completionText
    }
}

val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)
val historyTimeFormatter = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

fun Long.formatHistoryTime(): String {
    return historyTimeFormatter.format(Date(this))
}

fun Long.formatTimer(): String {
    val totalSeconds = this / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

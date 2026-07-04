package com.mark.timerboard

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileCopy
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        val viewModel = ViewModelProvider(
            this,
            TimerBoardViewModel.Factory(application)
        )[TimerBoardViewModel::class.java]

        setContent {
            TimerBoardTheme {
                TimerBoardApp(viewModel)
            }
        }
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                10
            )
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

    init {
        viewModelScope.launch {
            timers.addAll(repository.loadPresets().map { TimerItem(it) })
            isLoading = false
            syncActiveTimerNotification()
        }
        TimerCommandBus.register(
            onPauseAll = ::pauseAll,
            onResetAll = ::resetAll
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
        syncActiveTimerNotification()
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
        syncActiveTimerNotification()
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
        syncActiveTimerNotification()
    }

    fun deleteTimer(id: Long) {
        timers.removeAll { it.preset.id == id }
        saveAsync()
        syncActiveTimerNotification()
    }

    fun duplicateTimer(id: Long) {
        val timer = timers.firstOrNull { it.preset.id == id } ?: return
        val duplicatePreset = timer.preset.copy(
            id = System.currentTimeMillis(),
            name = "${timer.preset.name} copy"
        )
        timers.add(0, TimerItem(duplicatePreset))
        saveAsync()
        syncActiveTimerNotification()
    }

    fun startTimer(id: Long) {
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
        syncActiveTimerNotification()
    }

    fun pauseTimer(id: Long) {
        updateTimer(id) { item ->
            if (!item.isRunning) item else item.copy(
                remainingMillis = max(0L, item.endElapsedRealtime - SystemClock.elapsedRealtime()),
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
        syncActiveTimerNotification()
    }

    fun resetTimer(id: Long) {
        updateTimer(id) { item ->
            item.copy(
                remainingMillis = item.preset.totalDurationMillis(),
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
        syncActiveTimerNotification()
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

    private fun updateTimer(id: Long, transform: (TimerItem) -> TimerItem) {
        val index = timers.indexOfFirst { it.preset.id == id }
        if (index >= 0) timers[index] = transform(timers[index])
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
                            alertPlayer.play(item.preset.alarmId, item.preset.alarmUri)
                        }
                    }
                }
                if (didFinishTimer) {
                    syncActiveTimerNotification()
                }
                delay(250L)
            }
            syncActiveTimerNotification()
        }
    }

    private fun saveAsync() {
        val presets = timers.map { it.preset }
        viewModelScope.launch {
            repository.savePresets(presets)
        }
    }

    private fun syncActiveTimerNotification() {
        TimerForegroundService.sync(appContext, timers.toList())
    }

    override fun onCleared() {
        syncActiveTimerNotification()
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

    private val vibrator: Vibrator
        get() = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        activeRingtone?.stop()
        toneGenerator.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerBoardApp(viewModel: TimerBoardViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var timerBeingEdited by remember { mutableStateOf<TimerItem?>(null) }
    var timerPendingDelete by remember { mutableStateOf<TimerItem?>(null) }
    var fullScreenTimerId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("TimerBoard", fontWeight = FontWeight.SemiBold)
                        Text(
                            "${viewModel.timers.count { it.isRunning }} running",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    TextButton(onClick = viewModel::startAll) {
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
                    TimerCard(
                        timer = timer,
                        onStart = { viewModel.startTimer(timer.preset.id) },
                        onPause = { viewModel.pauseTimer(timer.preset.id) },
                        onReset = { viewModel.resetTimer(timer.preset.id) },
                        onDelete = { timerPendingDelete = timer },
                        onDuplicate = { viewModel.duplicateTimer(timer.preset.id) },
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
                    onStart = { viewModel.startTimer(timer.preset.id) },
                    onPause = { viewModel.pauseTimer(timer.preset.id) },
                    onReset = { viewModel.resetTimer(timer.preset.id) }
                )
            }
        } ?: run {
            fullScreenTimerId = null
        }
    }
}

@Composable
fun TimerCard(
    timer: TimerItem,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    onEditDuration: () -> Unit,
    onOpenFullScreen: () -> Unit
) {
    val totalDuration = timer.preset.totalDurationMillis()
    val progress = 1f - (timer.remainingMillis.toFloat() / totalDuration.toFloat())
        .coerceIn(0f, 1f)
    val accent = Color(timer.preset.color)
    val phaseText = timer.intervalPhaseText()
    val modeLabel = if (timer.preset.mode == TIMER_MODE_INTERVAL) "Interval" else "Countdown"
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
                IconButton(onClick = onEditDuration) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit ${timer.preset.name}")
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
            if (timer.preset.mode == TIMER_MODE_INTERVAL) {
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
                Text(
                    "Complete",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
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
    val phaseText = timer.intervalPhaseText() ?: "Interval"

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
    onCreateInterval: (String, Int, Int, Int, Int, Int, Long, String, String?) -> Unit
) {
    var mode by remember { mutableStateOf(TIMER_MODE_COUNTDOWN) }
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("5") }
    var seconds by remember { mutableStateOf("0") }
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
                        text = "Interval",
                        selected = mode == TIMER_MODE_INTERVAL,
                        onClick = { mode = TIMER_MODE_INTERVAL },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (mode == TIMER_MODE_COUNTDOWN) {
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
                } else {
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
const val TIMER_MODE_COUNTDOWN = "countdown"
const val TIMER_MODE_INTERVAL = "interval"

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
    if (mode != TIMER_MODE_INTERVAL) return durationMillis.coerceAtLeast(1_000L)
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
    if (preset.mode != TIMER_MODE_INTERVAL) return null
    if (!isRunning && remainingMillis == 0L) return "Complete"

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
        val phase = if (roundElapsed < workMillis || restMillis == 0L) "Work" else "Rest"
        return "$phase round ${roundIndex + 1} of $rounds"
    }

    return if (preset.cooldownMillis > 0L) "Cooldown" else "Finishing"
}

fun TimerPreset.intervalSummary(): String {
    if (mode != TIMER_MODE_INTERVAL) return ""
    return "Warmup ${(warmupMillis / 1000L)}s | Work ${(workMillis / 1000L)}s | " +
        "Rest ${(restMillis / 1000L)}s | Cooldown ${(cooldownMillis / 1000L)}s | " +
        "${rounds.coerceAtLeast(1)} rounds"
}

fun TimerItem.signalTimeText(): String {
    return timeFormatter.format(Date(System.currentTimeMillis() + remainingMillis))
}

val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.US)

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

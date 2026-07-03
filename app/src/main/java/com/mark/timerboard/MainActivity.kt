package com.mark.timerboard

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
}

data class TimerPreset(
    val id: Long,
    val name: String,
    val durationMillis: Long,
    val color: Long
)

data class TimerItem(
    val preset: TimerPreset,
    val remainingMillis: Long = preset.durationMillis,
    val isRunning: Boolean = false,
    val endElapsedRealtime: Long = 0L
)

class TimerRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("timer_board", Context.MODE_PRIVATE)

    fun loadPresets(): List<TimerPreset> {
        val raw = prefs.getString("presets", null) ?: return defaultPresets()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                TimerPreset(
                    id = item.getLong("id"),
                    name = item.getString("name"),
                    durationMillis = item.getLong("durationMillis"),
                    color = item.getLong("color")
                )
            }
        }.getOrDefault(defaultPresets())
    }

    fun savePresets(presets: List<TimerPreset>) {
        val array = JSONArray()
        presets.forEach { preset ->
            array.put(
                JSONObject()
                    .put("id", preset.id)
                    .put("name", preset.name)
                    .put("durationMillis", preset.durationMillis)
                    .put("color", preset.color)
            )
        }
        prefs.edit().putString("presets", array.toString()).apply()
    }

    private fun defaultPresets(): List<TimerPreset> = listOf(
        TimerPreset(1L, "Coffee", 4.minutes, 0xFF8B5E3C),
        TimerPreset(2L, "Stretch", 10.minutes, 0xFF2F7D69),
        TimerPreset(3L, "Focus", 25.minutes, 0xFF365D8C)
    )
}

class TimerBoardViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TimerRepository(application)
    private val alertPlayer = TimerAlertPlayer(application)
    private var tickerJob: Job? = null

    val timers = mutableStateListOf<TimerItem>()

    init {
        timers.addAll(repository.loadPresets().map { TimerItem(it) })
    }

    fun addTimer(name: String, minutes: Int, seconds: Int, color: Long) {
        val duration = ((minutes * 60L) + seconds).coerceAtLeast(1L) * 1000L
        val preset = TimerPreset(
            id = System.currentTimeMillis(),
            name = name.ifBlank { "Timer" },
            durationMillis = duration,
            color = color
        )
        timers.add(0, TimerItem(preset))
        save()
    }

    fun updateTimerDuration(id: Long, hours: Int, minutes: Int, seconds: Int) {
        val duration = ((hours * 3600L) + (minutes * 60L) + seconds).coerceAtLeast(1L) * 1000L
        updateTimer(id) { item ->
            item.copy(
                preset = item.preset.copy(durationMillis = duration),
                remainingMillis = duration,
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
        save()
    }

    fun deleteTimer(id: Long) {
        timers.removeAll { it.preset.id == id }
        save()
    }

    fun startTimer(id: Long) {
        updateTimer(id) { item ->
            item.copy(
                isRunning = true,
                endElapsedRealtime = SystemClock.elapsedRealtime() + item.remainingMillis
            )
        }
        ensureTicker()
    }

    fun pauseTimer(id: Long) {
        updateTimer(id) { item ->
            if (!item.isRunning) item else item.copy(
                remainingMillis = max(0L, item.endElapsedRealtime - SystemClock.elapsedRealtime()),
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
    }

    fun resetTimer(id: Long) {
        updateTimer(id) { item ->
            item.copy(
                remainingMillis = item.preset.durationMillis,
                isRunning = false,
                endElapsedRealtime = 0L
            )
        }
    }

    fun startAll() {
        timers.forEach { startTimer(it.preset.id) }
    }

    fun pauseAll() {
        timers.forEach { pauseTimer(it.preset.id) }
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
                timers.forEachIndexed { index, item ->
                    if (item.isRunning) {
                        val remaining = max(0L, item.endElapsedRealtime - now)
                        timers[index] = item.copy(
                            remainingMillis = remaining,
                            isRunning = remaining > 0L
                        )
                        if (remaining == 0L) {
                            alertPlayer.play()
                        }
                    }
                }
                delay(250L)
            }
        }
    }

    private fun save() {
        repository.savePresets(timers.map { it.preset })
    }

    override fun onCleared() {
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

    fun play() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 350)
        val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(350L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    fun release() {
        toneGenerator.release()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimerBoardApp(viewModel: TimerBoardViewModel) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var timerBeingEdited by remember { mutableStateOf<TimerItem?>(null) }

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
        if (viewModel.timers.isEmpty()) {
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
                        onDelete = { viewModel.deleteTimer(timer.preset.id) },
                        onEditDuration = { timerBeingEdited = timer }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateTimerDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, minutes, seconds, color ->
                viewModel.addTimer(name, minutes, seconds, color)
                showCreateDialog = false
            }
        )
    }

    timerBeingEdited?.let { timer ->
        EditDurationDialog(
            timer = timer,
            onDismiss = { timerBeingEdited = null },
            onSave = { hours, minutes, seconds ->
                viewModel.updateTimerDuration(timer.preset.id, hours, minutes, seconds)
                timerBeingEdited = null
            }
        )
    }
}

@Composable
fun TimerCard(
    timer: TimerItem,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onDelete: () -> Unit,
    onEditDuration: () -> Unit
) {
    val progress = 1f - (timer.remainingMillis.toFloat() / timer.preset.durationMillis.toFloat())
        .coerceIn(0f, 1f)
    val accent = Color(timer.preset.color)

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
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete timer")
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (timer.isRunning) onPause else onStart) {
                    Icon(
                        if (timer.isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (timer.isRunning) "Pause" else "Start")
                }
                OutlinedButton(onClick = onReset) {
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
    onSave: (Int, Int, Int) -> Unit
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(
                        hours.toIntOrNull() ?: 0,
                        (minutes.toIntOrNull() ?: 0).coerceIn(0, 59),
                        (seconds.toIntOrNull() ?: 0).coerceIn(0, 59)
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
    onCreate: (String, Int, Int, Long) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("5") }
    var seconds by remember { mutableStateOf("0") }
    var selectedColor by remember { mutableLongStateOf(timerColors.first()) }

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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    timerColors.forEach { color ->
                        ColorSwatch(
                            color = color,
                            selected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onCreate(
                        name.trim(),
                        minutes.toIntOrNull() ?: 0,
                        (seconds.toIntOrNull() ?: 0).coerceIn(0, 59),
                        selectedColor
                    )
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

val Int.minutes: Long
    get() = this * 60_000L

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

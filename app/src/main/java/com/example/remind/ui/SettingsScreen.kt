package com.example.remind.ui

import androidx.compose.material.icons.filled.Alarm

import com.example.remind.notify.DigestScheduler

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.runtime.mutableIntStateOf

import com.example.remind.Lang
import com.example.remind.tr

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.remind.BuildConfig
import com.example.remind.RemindApp
import com.example.remind.data.Backup
import com.example.remind.data.Digest
import com.example.remind.data.Repository
import com.example.remind.data.Scheduler
import com.example.remind.data.ThemeMode
import com.example.remind.notify.Notifications
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class SettingsViewModel(val repo: Repository, private val backup: Backup) : ViewModel() {
    val digests = repo.observeDigests().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val themeMode = repo.themeMode
    val dynamicColor = repo.dynamicColor
    val remindersEnabled = repo.remindersEnabled

    fun setRemindersEnabled(enabled: Boolean) = viewModelScope.launch { repo.setRemindersEnabled(enabled) }

    val randomEnabled = repo.randomEnabled
    val randomStart = repo.randomStart
    val randomEnd = repo.randomEnd
    val randomCount = repo.randomCount

    fun setRandom(enabled: Boolean? = null, start: Int? = null, end: Int? = null, count: Int? = null) =
        viewModelScope.launch { repo.setRandom(enabled, start, end, count) }

    /** The day's random reminder times, in minutes of the day. */
    fun randomTimes(day: LocalDate): List<Int> = DigestScheduler.randomTimes(day, repo.randomPlan())

    var intervalsText by mutableStateOf(Scheduler.formatIntervals(repo.defaultIntervals))
    val intervalsValid get() = Scheduler.parseIntervals(intervalsText) != null
    val intervalsChanged get() = Scheduler.parseIntervals(intervalsText) != repo.defaultIntervals

    fun saveIntervals() {
        Scheduler.parseIntervals(intervalsText)?.let {
            repo.defaultIntervals = it
            intervalsText = Scheduler.formatIntervals(it)
        }
    }

    fun addDigest(hour: Int, minute: Int) = viewModelScope.launch { repo.addDigest(hour, minute) }
    fun setEnabled(d: Digest, enabled: Boolean) = viewModelScope.launch { repo.setDigestEnabled(d, enabled) }
    fun remove(d: Digest) = viewModelScope.launch { repo.removeDigest(d) }

    suspend fun testNotification(context: android.content.Context) {
        Notifications.showDue(context, repo, force = true)
    }

    suspend fun export(uri: Uri): Result<Int> = withContext(Dispatchers.IO) { runCatching { backup.export(uri) } }
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) { runCatching { backup.import(uri) } }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RemindApp
                SettingsViewModel(app.repository, app.backup)
            }
        }
    }
}

private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenHistory: () -> Unit, vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val digests by vm.digests.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val remindersOn by vm.remindersEnabled.collectAsStateWithLifecycle()
    val randomOn by vm.randomEnabled.collectAsStateWithLifecycle()
    val randomStart by vm.randomStart.collectAsStateWithLifecycle()
    val randomEnd by vm.randomEnd.collectAsStateWithLifecycle()
    val randomCount by vm.randomCount.collectAsStateWithLifecycle()
    /** 0 = closed, 1 = picking the window start, 2 = picking the window end. */
    var pickWindow by rememberSaveable { mutableIntStateOf(0) }

    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var canNotify by remember { mutableStateOf(Notifications.canPost(context)) }
    var canExact by remember { mutableStateOf(canExactAlarms(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        canNotify = Notifications.canPost(context)
        canExact = canExactAlarms(context)
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { canNotify = it }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            vm.export(uri)
                .onSuccess { snackbar.showSnackbar("Backup ready: $it notes") }
                .onFailure { snackbar.showSnackbar("Backup fail: ${it.message}") }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pendingImport = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val options = listOf(
                        Triple(ThemeMode.SYSTEM, "System", Icons.Default.PhoneAndroid),
                        Triple(ThemeMode.LIGHT, "Day", Icons.Default.LightMode),
                        Triple(ThemeMode.DARK, "Night", Icons.Default.DarkMode),
                    )
                    options.forEachIndexed { index, (mode, label, icon) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { vm.repo.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            icon = { Icon(icon, contentDescription = null, Modifier.padding(0.dp)) },
                        ) { Text(label) }
                    }
                }
                Text(tr("Bhasha", "Language"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    val languages = listOf(false to "Hinglish", true to "English")
                    languages.forEachIndexed { index, (english, label) ->
                        SegmentedButton(
                            selected = Lang.english == english,
                            onClick = { vm.repo.setEnglish(english) },
                            shape = SegmentedButtonDefaults.itemShape(index, languages.size),
                            icon = { Icon(Icons.Default.Translate, contentDescription = null, Modifier.padding(0.dp)) },
                        ) { Text(label) }
                    }
                }
                if (supportsDynamicColor) {
                    ListItem(
                        headlineContent = { Text("Material You colours") },
                        supportingContent = { Text(tr("Wallpaper se colours lo", "Use colours from your wallpaper")) },
                        leadingContent = { Icon(Icons.Default.Palette, null) },
                        trailingContent = { Switch(checked = dynamicColor, onCheckedChange = vm.repo::setDynamicColor) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }

            SettingsSection("Daily digest") {
                ListItem(
                    headlineContent = { Text("Reminders") },
                    supportingContent = { Text(if (remindersOn) tr("Notifications aayenge", "Notifications are on") else tr("Sab notifications band hain", "All notifications are off")) },
                    leadingContent = {
                        Icon(
                            if (remindersOn) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                            null,
                            tint = if (remindersOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = { Switch(checked = remindersOn, onCheckedChange = { vm.setRemindersEnabled(it) }) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                AnimatedVisibility(remindersOn) {
                Column {
                if (!canNotify) {
                    ListItem(
                        headlineContent = { Text(tr("Notifications band hai", "Notifications are blocked")) },
                        supportingContent = { Text(tr("Reminder ke liye permission do", "Allow notifications to get reminders")) },
                        leadingContent = { Icon(Icons.Default.NotificationsOff, null, tint = MaterialTheme.colorScheme.error) },
                        trailingContent = {
                            TextButton(onClick = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }) { Text("Allow") }
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
                Text(
                    tr(
                        "Har due note ka alag notification aayega (ek baar me max 10). Wahin se Yaad tha / Thoda / Bhool gaya daba sakte ho.",
                        "Each due note gets its own notification (up to 10 at a time). You can tap Remembered / Partly / Forgot right there.",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                )
                ListItem(
                    headlineContent = { Text(tr("Random time pe", "Random times")) },
                    supportingContent = {
                        Text(tr("Fixed time ki jagah, roz window me random time pe", "Instead of fixed times, at random times in a daily window"))
                    },
                    leadingContent = { Icon(Icons.Default.Shuffle, null) },
                    trailingContent = { Switch(checked = randomOn, onCheckedChange = { vm.setRandom(enabled = it) }) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                if (randomOn) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tr("Kab se kab tak", "Window"), Modifier.weight(1f))
                        OutlinedButton(onClick = { pickWindow = 1 }) { Text(minuteLabel(randomStart)) }
                        Text("–")
                        OutlinedButton(onClick = { pickWindow = 2 }) { Text(minuteLabel(randomEnd)) }
                    }
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr("Din me kitni baar", "Times per day"), Modifier.weight(1f))
                        IconButton(onClick = { vm.setRandom(count = randomCount - 1) }, enabled = randomCount > 1) {
                            Icon(Icons.Default.Remove, contentDescription = "Less")
                        }
                        Text("$randomCount", style = MaterialTheme.typography.titleLarge)
                        IconButton(onClick = { vm.setRandom(count = randomCount + 1) }, enabled = randomCount < 10) {
                            Icon(Icons.Default.Add, contentDescription = "More")
                        }
                    }
                    val today = LocalDate.now()
                    val todayTimes = remember(randomStart, randomEnd, randomCount, today) { vm.randomTimes(today) }
                    Text(
                        tr("Aaj ke time: ", "Today's times: ") + todayTimes.joinToString(", ") { minuteLabel(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (!randomOn && digests.isEmpty()) {
                    Text(
                        tr("Koi time set nahi hai. Reminders nahi aayenge.", "No time set. You won't get reminders."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                if (!randomOn) digests.forEach { digest ->
                    ListItem(
                        headlineContent = {
                            Text(
                                LocalTime.of(digest.hour, digest.minute).format(timeFormat),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (digest.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = { Text(if (digest.enabled) tr("Roz", "Daily") else "Off") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(checked = digest.enabled, onCheckedChange = { vm.setEnabled(digest, it) })
                                IconButton(onClick = { vm.remove(digest) }) { Icon(Icons.Default.Delete, contentDescription = "Remove") }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (!randomOn) {
                        OutlinedButton(onClick = { showTimePicker = true }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("Time add karo", "Add time"))
                        }
                    }
                    TextButton(onClick = { scope.launch { vm.testNotification(context) } }, enabled = canNotify) {
                        Icon(Icons.Default.NotificationsActive, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Test")
                    }
                }
                }
                }
                ListItem(
                    headlineContent = { Text(tr("Exact time pe reminder", "Exact-time reminders")) },
                    supportingContent = {
                        Text(
                            if (canExact) tr("On hai. Iske liye alag permission nahi maangni padti.", "On. No separate permission prompt is needed.")
                            else tr("Band hai, reminder thoda late aa sakta hai", "Off, reminders may come a little late")
                        )
                    },
                    leadingContent = {
                        Icon(Icons.Default.Alarm, null, tint = if (canExact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    },
                    trailingContent = if (canExact) null else ({
                        TextButton(onClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                try {
                                    context.startActivity(
                                        Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))
                                    )
                                } catch (e: ActivityNotFoundException) {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                                }
                            }
                        }) { Text("Allow") }
                    }),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text(tr("Battery optimization se Yaad ko hata do", "Remove Yaad from battery optimization")) },
                    supportingContent = { Text(tr("Taaki reminder time pe aaye", "So reminders arrive on time")) },
                    leadingContent = { Icon(Icons.Default.BatteryAlert, null) },
                    modifier = Modifier.padding(top = 4.dp),
                    trailingContent = {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e: ActivityNotFoundException) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }) { Text(tr("Kholo", "Open")) }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }

            SettingsSection(tr("Notification history", "Notification history")) {
                ListItem(
                    headlineContent = { Text(tr("Purane notifications", "Past notifications")) },
                    supportingContent = { Text(tr("Pichhle 30 din ke reminders aur unpe kya dabaya", "Reminders from the last 30 days and what you tapped")) },
                    leadingContent = { Icon(Icons.Default.History, null) },
                    modifier = Modifier.clickable(onClick = onOpenHistory),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }

            SettingsSection("Revision schedule") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.intervalsText,
                        onValueChange = { vm.intervalsText = it },
                        label = { Text(tr("Default gaps (din)", "Default gaps (days)")) },
                        isError = !vm.intervalsValid,
                        supportingText = {
                            Text(if (vm.intervalsValid) tr("Naye notes ke liye. Purane notes apna schedule rakhenge.", "For new notes. Existing notes keep their schedule.") else tr("Sirf positive numbers, comma se alag", "Positive numbers only, comma separated"))
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                vm.saveIntervals()
                                scope.launch { snackbar.showSnackbar(tr("Default schedule save ho gaya", "Default schedule saved")) }
                            },
                            enabled = vm.intervalsValid && vm.intervalsChanged,
                        ) { Text("Save") }
                        TextButton(onClick = { vm.intervalsText = Scheduler.formatIntervals(Scheduler.DEFAULT_INTERVALS) }) {
                            Text("Reset")
                        }
                    }
                }
            }

            SettingsSection("Backup") {
                Text(
                    tr("Sab kuch phone me hi rehta hai. Phone badalne ya kho jaane se pehle backup file bana ke rakho (Drive/PC pe).", "Everything stays on this phone. Keep a backup file (on Drive/PC) before you switch or lose your phone."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ListItem(
                    headlineContent = { Text("Backup export") },
                    supportingContent = { Text(tr("Notes + photos + voice + video, ek .zip me", "Notes + photos + voice + video in one .zip")) },
                    leadingContent = { Icon(Icons.Default.Upload, null) },
                    modifier = Modifier.padding(top = 4.dp),
                    trailingContent = {
                        TextButton(onClick = { exportLauncher.launch("yaad-backup-${LocalDate.now()}.zip") }) { Text("Export") }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text("Backup import") },
                    supportingContent = { Text(tr("Abhi ka sara data replace ho jayega", "Replaces all current data")) },
                    leadingContent = { Icon(Icons.Default.Download, null) },
                    trailingContent = {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }) {
                            Text("Import")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }

            SettingsSection("Sync") {
                SyncSection()
            }

            SettingsSection("Update") {
                AppUpdateSection()
            }

            Text(
                "Yaad v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }

    if (showTimePicker) {
        val pickerState = rememberTimePickerState(initialHour = 20, initialMinute = 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    showTimePicker = false
                    if (digests.any { it.hour == pickerState.hour && it.minute == pickerState.minute }) {
                        scope.launch { snackbar.showSnackbar(tr("Ye time pehle se hai", "This time already exists")) }
                    } else {
                        vm.addDigest(pickerState.hour, pickerState.minute)
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }

    if (pickWindow != 0) {
        val initial = if (pickWindow == 1) randomStart else randomEnd
        val pickerState = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = false)
        AlertDialog(
            onDismissRequest = { pickWindow = 0 },
            title = { Text(if (pickWindow == 1) tr("Kab se", "From") else tr("Kab tak", "Until")) },
            text = { TimePicker(state = pickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val minute = pickerState.hour * 60 + pickerState.minute
                    val start = if (pickWindow == 1) minute else randomStart
                    val end = if (pickWindow == 2) minute else randomEnd
                    if (end - start < 30) {
                        scope.launch { snackbar.showSnackbar(tr("Window kam se kam 30 minute ki honi chahiye", "The window must be at least 30 minutes")) }
                    } else if (pickWindow == 1) {
                        vm.setRandom(start = minute)
                    } else {
                        vm.setRandom(end = minute)
                    }
                    pickWindow = 0
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickWindow = 0 }) { Text("Cancel") } },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Backup import karein?") },
            text = { Text(tr("Is phone pe abhi jo bhi notes hain, wo hata ke backup wale aa jayenge.", "All notes on this phone will be replaced by the ones in the backup.")) },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        vm.import(uri)
                            .onSuccess {
                                vm.intervalsText = Scheduler.formatIntervals(vm.repo.defaultIntervals)
                                snackbar.showSnackbar(tr("Import ho gaya: $it notes", "Imported: $it notes"))
                            }
                            .onFailure { snackbar.showSnackbar("Import fail: ${it.message}") }
                    }
                }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
}

private fun canExactAlarms(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

private fun minuteLabel(minuteOfDay: Int): String =
    LocalTime.of(minuteOfDay / 60, minuteOfDay % 60).format(timeFormat)

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) { content() }
        }
    }
}

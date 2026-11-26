package com.example.remind.ui

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
        Notifications.showDigest(context, repo.dueTitles(), force = true)
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
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val digests by vm.digests.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val remindersOn by vm.remindersEnabled.collectAsStateWithLifecycle()

    var showTimePicker by rememberSaveable { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<Uri?>(null) }
    var canNotify by remember { mutableStateOf(Notifications.canPost(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { canNotify = Notifications.canPost(context) }

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
                if (supportsDynamicColor) {
                    ListItem(
                        headlineContent = { Text("Material You colours") },
                        supportingContent = { Text("Wallpaper se colours lo") },
                        leadingContent = { Icon(Icons.Default.Palette, null) },
                        trailingContent = { Switch(checked = dynamicColor, onCheckedChange = vm.repo::setDynamicColor) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    )
                }
            }

            SettingsSection("Daily digest") {
                ListItem(
                    headlineContent = { Text("Reminders") },
                    supportingContent = { Text(if (remindersOn) "Notifications aayenge" else "Sab notifications band hain") },
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
                        headlineContent = { Text("Notifications band hai") },
                        supportingContent = { Text("Reminder ke liye permission do") },
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
                    "Har time pe ek notification aayega jisme likha hoga kitne notes due hain.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (digests.isEmpty()) {
                    Text(
                        "Koi time set nahi hai. Reminders nahi aayenge.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                digests.forEach { digest ->
                    ListItem(
                        headlineContent = {
                            Text(
                                LocalTime.of(digest.hour, digest.minute).format(timeFormat),
                                style = MaterialTheme.typography.titleLarge,
                                color = if (digest.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        supportingContent = { Text(if (digest.enabled) "Roz" else "Off") },
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
                    OutlinedButton(onClick = { showTimePicker = true }) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Time add karo")
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
                    headlineContent = { Text("Reminder late aa rahe hain?") },
                    supportingContent = { Text("Kuch phones (Xiaomi, Oppo, Vivo…) background apps rok dete hain. Battery optimization se Yaad ko hata do.") },
                    leadingContent = { Icon(Icons.Default.BatteryAlert, null) },
                    modifier = Modifier.padding(top = 4.dp),
                    trailingContent = {
                        TextButton(onClick = {
                            try {
                                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (e: ActivityNotFoundException) {
                                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                            }
                        }) { Text("Kholo") }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
            }

            SettingsSection("Revision schedule") {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = vm.intervalsText,
                        onValueChange = { vm.intervalsText = it },
                        label = { Text("Default gaps (din)") },
                        isError = !vm.intervalsValid,
                        supportingText = {
                            Text(if (vm.intervalsValid) "Naye notes ke liye. Purane notes apna schedule rakhenge." else "Sirf positive numbers, comma se alag")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                vm.saveIntervals()
                                scope.launch { snackbar.showSnackbar("Default schedule save ho gaya") }
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
                    "Sab kuch phone me hi rehta hai. Phone badalne ya kho jaane se pehle backup file bana ke rakho (Drive/PC pe).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ListItem(
                    headlineContent = { Text("Backup export") },
                    supportingContent = { Text("Notes + photos + voice + video, ek .zip me") },
                    leadingContent = { Icon(Icons.Default.Upload, null) },
                    modifier = Modifier.padding(top = 4.dp),
                    trailingContent = {
                        TextButton(onClick = { exportLauncher.launch("yaad-backup-${LocalDate.now()}.zip") }) { Text("Export") }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text("Backup import") },
                    supportingContent = { Text("Abhi ka sara data replace ho jayega") },
                    leadingContent = { Icon(Icons.Default.Download, null) },
                    trailingContent = {
                        TextButton(onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }) {
                            Text("Import")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
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
                        scope.launch { snackbar.showSnackbar("Ye time pehle se hai") }
                    } else {
                        vm.addDigest(pickerState.hour, pickerState.minute)
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }

    pendingImport?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Backup import karein?") },
            text = { Text("Is phone pe abhi jo bhi notes hain, wo hata ke backup wale aa jayenge.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        vm.import(uri)
                            .onSuccess {
                                vm.intervalsText = Scheduler.formatIntervals(vm.repo.defaultIntervals)
                                snackbar.showSnackbar("Import ho gaya: $it notes")
                            }
                            .onFailure { snackbar.showSnackbar("Import fail: ${it.message}") }
                    }
                }) { Text("Replace", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }
}

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

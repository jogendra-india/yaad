package com.example.remind.ui

import com.example.remind.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.remind.RemindApp
import com.example.remind.data.NoteWithAttachments
import com.example.remind.data.Repository
import com.example.remind.data.ReviewLog
import com.example.remind.data.ReviewResult
import com.example.remind.data.today
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NoteDetailViewModel(val repo: Repository, state: SavedStateHandle) : ViewModel() {
    val noteId: Long = state["noteId"] ?: -1L

    /** null until loaded; the inner null means the note no longer exists. */
    val note = repo.observe(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val logs = repo.observeLogs(noteId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun toggleStar(item: NoteWithAttachments) = viewModelScope.launch {
        repo.setStarred(item.note.id, !item.note.starred)
    }

    fun reset(item: NoteWithAttachments) = viewModelScope.launch { repo.resetProgress(item.note) }
    fun dueToday(item: NoteWithAttachments) = viewModelScope.launch { repo.makeDueToday(item.note) }

    fun delete(item: NoteWithAttachments, onDone: () -> Unit) = viewModelScope.launch {
        repo.delete(item)
        onDone()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                NoteDetailViewModel((this[APPLICATION_KEY] as RemindApp).repository, createSavedStateHandle())
            }
        }
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    onRevise: (Long) -> Unit,
    vm: NoteDetailViewModel = viewModel(factory = NoteDetailViewModel.Factory),
) {
    val item by vm.note.collectAsStateWithLifecycle()
    val logs by vm.logs.collectAsStateWithLifecycle()
    var menu by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val current = item ?: return@TopAppBar
                    IconButton(onClick = { vm.toggleStar(current) }) {
                        AnimatedContent(
                            targetState = current.note.starred,
                            transitionSpec = {
                                scaleIn(spring(Spring.DampingRatioMediumBouncy)) togetherWith scaleOut()
                            },
                            label = "star",
                        ) { starred ->
                            Icon(
                                if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (starred) "Unstar" else "Star",
                                tint = if (starred) StarColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    IconButton(onClick = { onEdit(current.note.id) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(tr("Aaj due karo", "Make due today")) },
                                leadingIcon = { Icon(Icons.Default.Today, null) },
                                onClick = { menu = false; vm.dueToday(current) },
                            )
                            DropdownMenuItem(
                                text = { Text("Progress reset") },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                onClick = { menu = false; vm.reset(current) },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val current = item ?: return@Scaffold
            Surface(tonalElevation = 3.dp) {
                Button(
                    onClick = { onRevise(current.note.id) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(16.dp),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Abhi revise karo", "Revise now"))
                }
            }
        },
    ) { padding ->
        val current = item ?: return@Scaffold
        val note = current.note
        val day = today()
        val store = vm.repo.store

        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(note.title, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AssistChip(onClick = {}, label = { Text(deckName(note.subject)) }, leadingIcon = { Icon(Icons.Default.Folder, null, Modifier.size(18.dp)) })
                Text(
                    if (note.mastered) "Mastered" else tr("Agla revision: ${dueLabel(note.nextDueDay, day)}", "Next revision: ${dueLabel(note.nextDueDay, day)}"),
                    style = MaterialTheme.typography.labelLarge,
                    color = dueColor(note.nextDueDay, day, note.mastered),
                )
            }

            ScheduleCard(note.intervals, note.stage, note.mastered)

            KeywordChips(note.keywords)

            if (note.body.isNotBlank()) {
                NotesText(note.body, style = MaterialTheme.typography.bodyLarge)
            }

            if (current.attachments.isNotEmpty()) {
                AttachmentGallery(current.attachments.map { store.file(it.fileName) to it.type })
            }

            if (logs.isNotEmpty()) {
                HorizontalDivider()
                Text("History", style = MaterialTheme.typography.titleMedium)
                logs.forEach { LogRow(it) }
            }
            Text(
                tr("Banaya: ", "Created: ") + dateFormat.format(Instant.ofEpochMilli(note.createdAt).atZone(ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    if (confirmDelete) {
        val current = item
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Note delete karein?", "Delete note?")) },
            text = { Text(tr("Note, uski photos, voice aur video sab hat jayenge. Ye undo nahi hoga.", "The note and its photos, voice and video will all be removed. This can't be undone.")) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    if (current != null && !deleting) {
                        deleting = true
                        vm.delete(current, onBack)
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleCard(intervals: List<Int>, stage: Int, mastered: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Progress", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    "${stage.coerceAtMost(intervals.size)}/${intervals.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            LinearProgressIndicator(
                progress = { stage.coerceAtMost(intervals.size).toFloat() / intervals.size },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                intervals.forEachIndexed { index, days ->
                    val done = index < stage || mastered
                    val currentStep = index == stage && !mastered
                    val bg = when {
                        done -> MaterialTheme.colorScheme.primary
                        currentStep -> MaterialTheme.colorScheme.primaryContainer
                        else -> MaterialTheme.colorScheme.surfaceVariant
                    }
                    val fg = when {
                        done -> MaterialTheme.colorScheme.onPrimary
                        currentStep -> MaterialTheme.colorScheme.onPrimaryContainer
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        Modifier
                            .background(bg, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("${days}d", style = MaterialTheme.typography.labelMedium, color = fg)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogRow(log: ReviewLog) {
    val (label, color) = resultStyle(log.result)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            dateFormat.format(Instant.ofEpochMilli(log.reviewedAt).atZone(ZoneId.systemDefault())),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun resultStyle(result: ReviewResult): Pair<String, Color> = when (result) {
    ReviewResult.GOOD -> tr("Yaad tha", "Remembered") to MaterialTheme.colorScheme.tertiary
    ReviewResult.HARD -> tr("Thoda thoda", "Partly") to StarColor
    ReviewResult.FORGOT -> tr("Bhool gaya", "Forgot") to MaterialTheme.colorScheme.error
}

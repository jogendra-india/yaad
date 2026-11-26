package com.example.remind.ui

import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.mohamedrejeb.richeditor.ui.material3.OutlinedRichTextEditor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButtonDefaults

import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted

import java.time.LocalDate
import java.time.format.DateTimeFormatter

import com.example.remind.tr

import android.content.ActivityNotFoundException
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.remind.RemindApp
import com.example.remind.data.Attachment
import com.example.remind.data.AttachmentType
import com.example.remind.data.DraftAttachment
import com.example.remind.data.Note
import com.example.remind.data.Repository
import com.example.remind.data.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditNoteViewModel(private val repo: Repository, state: SavedStateHandle) : ViewModel() {
    private val noteId: Long = state["noteId"] ?: -1L
    val isNew = noteId < 0

    var title by mutableStateOf("")
    var body by mutableStateOf("")
    var subject by mutableStateOf("")
    var keywordInput by mutableStateOf("")
    val keywords = mutableStateListOf<String>()
    var intervalsText by mutableStateOf(Scheduler.formatIntervals(repo.defaultIntervals))
    val attachments = mutableStateListOf<DraftAttachment>()
    var loaded by mutableStateOf(isNew)
        private set
    var saved by mutableStateOf(false)
        private set

    private var existing: Note? = null
    private val existingAttachments = mutableMapOf<Long, Attachment>()
    private val removed = mutableListOf<Attachment>()

    val store get() = repo.store
    val subjects = repo.observeSubjects()

    init {
        if (!isNew) viewModelScope.launch {
            repo.get(noteId)?.let { item ->
                existing = item.note
                title = item.note.title
                body = item.note.body
                subject = item.note.subject
                keywords.addAll(item.note.keywords)
                intervalsText = Scheduler.formatIntervals(item.note.intervals)
                item.attachments.forEach {
                    existingAttachments[it.id] = it
                    attachments += DraftAttachment(it.fileName, it.type, it.id)
                }
            }
            loaded = true
        }
    }

    val intervalsValid get() = Scheduler.parseIntervals(intervalsText) != null
    var titleError by mutableStateOf(false)

    val hasChanges
        get() = title.isNotBlank() || body.isNotBlank() || keywords.isNotEmpty() ||
            attachments.any { it.existingId == null } || removed.isNotEmpty()

    fun commitKeywordInput() {
        keywordInput.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in keywords }.forEach { keywords += it }
        keywordInput = ""
    }

    fun addAttachment(fileName: String, type: AttachmentType) {
        attachments += DraftAttachment(fileName, type)
    }

    fun removeAttachment(item: DraftAttachment) {
        attachments.remove(item)
        if (item.existingId != null) {
            existingAttachments[item.existingId]?.let { removed += it }
        } else {
            repo.store.delete(item.fileName)
        }
    }

    /**
     * Title is optional: a note only needs some content. A blank title is filled
     * from the notes, the keywords, or the attachment type and date.
     */
    private fun autoTitle(): String {
        RichNotes.plain(body).lineSequence().map { it.trim() }
            .firstOrNull { it.isNotEmpty() }?.let { return it.take(60) }
        if (keywords.isNotEmpty()) return keywords.take(3).joinToString(", ")
        val kind = when (attachments.firstOrNull()?.type) {
            AttachmentType.PHOTO -> "Photo"
            AttachmentType.HANDWRITING -> tr("Likha hua", "Handwriting")
            AttachmentType.AUDIO -> "Voice"
            AttachmentType.VIDEO -> "Video"
            null -> "Note"
        }
        return "$kind · " + LocalDate.now().format(DateTimeFormatter.ofPattern("d MMM"))
    }

    /** Returns false (and flags the field) when the note is empty or the schedule is invalid. */
    fun save(onDone: (Long) -> Unit): Boolean {
        commitKeywordInput()
        titleError = title.isBlank() && body.isBlank() && keywords.isEmpty() && attachments.isEmpty()
        val intervals = Scheduler.parseIntervals(intervalsText)
        if (titleError || intervals == null || saving) return false
        saving = true
        val finalTitle = title.trim().ifEmpty { autoTitle() }
        viewModelScope.launch {
            val id = repo.saveNote(
                existing = existing,
                title = finalTitle,
                body = body.trim(),
                subject = subject.trim(),
                keywords = keywords.toList(),
                intervals = intervals,
                attachments = attachments.toList(),
                removed = removed.toList(),
            )
            saved = true
            onDone(id)
        }
        return true
    }

    private var saving = false

    override fun onCleared() {
        // Discarded draft: drop files captured in this session.
        if (!saved) attachments.filter { it.existingId == null }.forEach { repo.store.delete(it.fileName) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                EditNoteViewModel((this[APPLICATION_KEY] as RemindApp).repository, createSavedStateHandle())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditNoteScreen(onBack: () -> Unit, onSaved: (Long) -> Unit, vm: EditNoteViewModel = viewModel(factory = EditNoteViewModel.Factory)) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = vm.store

    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    var showDraw by rememberSaveable { mutableStateOf(false) }
    var showVoice by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val name = pendingCapture ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        val file = store.file(name)
        if (!ok || !file.exists() || file.length() == 0L) {
            file.delete()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            withContext(Dispatchers.IO) { store.compressPhoto(file) }
            vm.addAttachment(name, AttachmentType.PHOTO)
        }
    }

    val recordVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val name = pendingCapture ?: return@rememberLauncherForActivityResult
        pendingCapture = null
        val file = store.file(name)
        if (ok && file.exists() && file.length() > 0L) vm.addAttachment(name, AttachmentType.VIDEO) else file.delete()
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris: List<Uri> ->
        scope.launch {
            uris.forEach { uri ->
                val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true
                val name = withContext(Dispatchers.IO) {
                    runCatching { if (isVideo) store.importVideo(uri) else store.importImage(uri) }.getOrNull()
                }
                if (name != null) {
                    vm.addAttachment(name, if (isVideo) AttachmentType.VIDEO else AttachmentType.PHOTO)
                } else {
                    Toast.makeText(context, "File import nahi hui", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun launchCapture(extension: String, launch: (Uri) -> Unit) {
        val file = store.newFile(extension)
        pendingCapture = file.name
        try {
            launch(store.uriFor(file))
        } catch (e: ActivityNotFoundException) {
            pendingCapture = null
            file.delete()
            Toast.makeText(context, tr("Camera app nahi mila", "No camera app found"), Toast.LENGTH_SHORT).show()
        }
    }

    fun tryBack() {
        if (vm.hasChanges && !vm.saved) confirmDiscard = true else onBack()
    }

    BackHandler(onBack = ::tryBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) tr("Naya note", "New note") else "Edit note") },
                navigationIcon = {
                    IconButton(onClick = ::tryBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.save(onSaved) }, enabled = vm.loaded) { Text("Save") }
                },
            )
        },
    ) { padding ->
        if (!vm.loaded) return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(scroll)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.title,
                onValueChange = { vm.title = it; if (it.isNotBlank()) vm.titleError = false },
                label = { Text("Title") },
                placeholder = { Text(tr("Kya padha? (optional)", "What did you study? (optional)")) },
                isError = vm.titleError,
                supportingText = if (vm.titleError) ({ Text(tr("Kuch to daalo: title, notes, keyword ya photo", "Add something: a title, notes, a keyword or a photo")) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubjectField(vm, Modifier.weight(1f))
                OutlinedTextField(
                    value = vm.keywordInput,
                    onValueChange = {
                        if (it.endsWith(",")) {
                            vm.keywordInput = it
                            vm.commitKeywordInput()
                        } else {
                            vm.keywordInput = it
                        }
                    },
                    label = { Text("Keywords") },
                    placeholder = { Text(tr("comma se add", "add with comma")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.commitKeywordInput() }),
                    modifier = Modifier.weight(1f),
                )
            }
            if (vm.keywords.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    vm.keywords.toList().forEach { word ->
                        InputChip(
                            selected = false,
                            onClick = { vm.keywords.remove(word) },
                            label = { Text(word) },
                            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove", Modifier.padding(0.dp)) },
                        )
                    }
                }
            }

            NotesEditor(vm)

            Text("Attachments", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { launchCapture("jpg") { takePhoto.launch(it) } },
                    label = { Text("Photo") },
                    leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                )
                AssistChip(
                    onClick = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                    label = { Text("Gallery") },
                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                )
                AssistChip(onClick = { showDraw = true }, label = { Text(tr("Likho", "Draw")) }, leadingIcon = { Icon(Icons.Default.Draw, null) })
                AssistChip(onClick = { showVoice = true }, label = { Text("Voice") }, leadingIcon = { Icon(Icons.Default.Mic, null) })
                AssistChip(
                    onClick = { launchCapture("mp4") { recordVideo.launch(it) } },
                    label = { Text("Video") },
                    leadingIcon = { Icon(Icons.Default.Videocam, null) },
                )
            }
            if (vm.attachments.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    vm.attachments.toList().forEach { item ->
                        AttachmentThumb(
                            file = store.file(item.fileName),
                            type = item.type,
                            size = 96.dp,
                            onClick = { viewing = item.fileName },
                            onRemove = { vm.removeAttachment(item) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = vm.intervalsText,
                onValueChange = { vm.intervalsText = it },
                label = { Text(tr("Revision schedule (din)", "Revision schedule (days)")) },
                supportingText = {
                    Text(if (vm.intervalsValid) tr("Har revision ke baad itne din ka gap", "Days of gap after each revision") else tr("Sirf positive numbers, comma se alag", "Positive numbers only, comma separated"))
                },
                isError = !vm.intervalsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            androidx.compose.material3.Button(
                onClick = {
                    if (!vm.save(onSaved)) scope.launch { scroll.animateScrollTo(0) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) { Text(if (vm.isNew) "Save note" else "Save changes") }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showDraw) {
        HandwritingDialog(store, onDismiss = { showDraw = false }) {
            vm.addAttachment(it, AttachmentType.HANDWRITING)
            showDraw = false
        }
    }
    if (showVoice) {
        VoiceRecorderDialog(store, onDismiss = { showVoice = false }) {
            vm.addAttachment(it, AttachmentType.AUDIO)
            showVoice = false
        }
    }
    viewing?.let { name ->
        val item = vm.attachments.firstOrNull { it.fileName == name }
        if (item == null) {
            LaunchedEffect(name) { viewing = null }
        } else when (item.type) {
            AttachmentType.VIDEO -> VideoPlayerDialog(store.file(name)) { viewing = null }
            AttachmentType.AUDIO -> AlertDialog(
                onDismissRequest = { viewing = null },
                title = { Text("Voice note") },
                text = { AudioPlayer(store.file(name)) },
                confirmButton = { TextButton(onClick = { viewing = null }) { Text("Close") } },
            )
            else -> ImageViewerDialog(store.file(name), whiteBackground = item.type == AttachmentType.HANDWRITING) { viewing = null }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(tr("Changes discard karein?", "Discard changes?")) },
            text = { Text(tr("Save nahi kiya to ye changes chale jayenge.", "These changes will be lost if you don't save.")) },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(tr("Rukko", "Keep editing")) } },
        )
    }
}

/**
 * Notes field with a small formatting bar (bold, italic, underline, bullet and
 * numbered lists) and a handle below it to drag the box taller or shorter.
 */
@Composable
private fun NotesEditor(vm: EditNoteViewModel) {
    val state = rememberRichTextState()
    var ready by remember { mutableStateOf(false) }
    var boxHeight by rememberSaveable { mutableFloatStateOf(140f) }
    val density = LocalDensity.current.density

    LaunchedEffect(Unit) {
        if (vm.body.isNotBlank()) state.setHtml(RichNotes.toHtml(vm.body))
        ready = true
    }
    LaunchedEffect(state.annotatedString, ready) {
        if (ready) vm.body = if (state.annotatedString.text.isBlank()) "" else state.toHtml()
    }

    val span = state.currentSpanStyle
    val bold = span.fontWeight == FontWeight.Bold
    val italic = span.fontStyle == FontStyle.Italic
    val underline = span.textDecoration?.contains(TextDecoration.Underline) == true

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            FormatButton(Icons.Default.FormatBold, "Bold", bold) {
                state.toggleSpanStyle(SpanStyle(fontWeight = FontWeight.Bold))
            }
            FormatButton(Icons.Default.FormatItalic, "Italic", italic) {
                state.toggleSpanStyle(SpanStyle(fontStyle = FontStyle.Italic))
            }
            FormatButton(Icons.Default.FormatUnderlined, "Underline", underline) {
                state.toggleSpanStyle(SpanStyle(textDecoration = TextDecoration.Underline))
            }
            FormatButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullets", state.isUnorderedList) {
                state.toggleUnorderedList()
            }
            FormatButton(Icons.Default.FormatListNumbered, "Numbering", state.isOrderedList) {
                state.toggleOrderedList()
            }
        }
        OutlinedRichTextEditor(
            state = state,
            placeholder = { Text("Short summary, formula, points…") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = boxHeight.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> boxHeight = (boxHeight + delta / density).coerceIn(96f, 900f) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 48.dp, height = 5.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(3.dp)),
            )
        }
    }
}

/** A formatting button that shows whether its style is active at the cursor. */
@Composable
private fun FormatButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = if (active) {
            IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        } else {
            IconButtonDefaults.iconButtonColors()
        },
    ) { Icon(icon, contentDescription = label) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectField(vm: EditNoteViewModel, modifier: Modifier = Modifier) {
    val subjects by vm.subjects.collectAsStateWithLifecycle(emptyList())
    var expanded by remember { mutableStateOf(false) }
    val suggestions = subjects.filter { it.contains(vm.subject, ignoreCase = true) && !it.equals(vm.subject, ignoreCase = true) }

    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded && suggestions.isNotEmpty(),
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = vm.subject,
            onValueChange = { vm.subject = it; expanded = true },
            label = { Text("Deck") },
            placeholder = { Text("Physics, DSA…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded && suggestions.isNotEmpty(),
            onDismissRequest = { expanded = false },
        ) {
            suggestions.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(s) },
                    onClick = { vm.subject = s; expanded = false },
                )
            }
        }
    }
}

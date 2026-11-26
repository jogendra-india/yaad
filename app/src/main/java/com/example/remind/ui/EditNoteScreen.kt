package com.example.remind.ui

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
    val canSave get() = title.isNotBlank() && intervalsValid

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

    fun save(onDone: (Long) -> Unit) {
        commitKeywordInput()
        val intervals = Scheduler.parseIntervals(intervalsText) ?: return
        viewModelScope.launch {
            val id = repo.saveNote(
                existing = existing,
                title = title.trim(),
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
    }

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
            Toast.makeText(context, "Camera app nahi mila", Toast.LENGTH_SHORT).show()
        }
    }

    fun tryBack() {
        if (vm.hasChanges && !vm.saved) confirmDiscard = true else onBack()
    }

    BackHandler(onBack = ::tryBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Naya note" else "Edit note") },
                navigationIcon = {
                    IconButton(onClick = ::tryBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(onClick = { vm.save(onSaved) }, enabled = vm.canSave && vm.loaded) { Text("Save") }
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.title,
                onValueChange = { vm.title = it },
                label = { Text("Title *") },
                placeholder = { Text("Kya padha?") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

            SubjectField(vm)

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
                placeholder = { Text("Likho, comma ya Enter se add") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.commitKeywordInput() }),
                modifier = Modifier.fillMaxWidth(),
            )
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

            OutlinedTextField(
                value = vm.body,
                onValueChange = { vm.body = it },
                label = { Text("Notes") },
                placeholder = { Text("Short summary, formula, points…") },
                minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )

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
                AssistChip(onClick = { showDraw = true }, label = { Text("Likho") }, leadingIcon = { Icon(Icons.Default.Draw, null) })
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
                label = { Text("Revision schedule (din)") },
                supportingText = {
                    Text(if (vm.intervalsValid) "Har revision ke baad itne din ka gap" else "Sirf positive numbers, comma se alag")
                },
                isError = !vm.intervalsValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
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
            title = { Text("Changes discard karein?") },
            text = { Text("Save nahi kiya to ye changes chale jayenge.") },
            confirmButton = { TextButton(onClick = { confirmDiscard = false; onBack() }) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Rukko") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubjectField(vm: EditNoteViewModel) {
    val subjects by vm.subjects.collectAsStateWithLifecycle(emptyList())
    var expanded by remember { mutableStateOf(false) }
    val suggestions = subjects.filter { it.contains(vm.subject, ignoreCase = true) && !it.equals(vm.subject, ignoreCase = true) }

    androidx.compose.material3.ExposedDropdownMenuBox(expanded = expanded && suggestions.isNotEmpty(), onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = vm.subject,
            onValueChange = { vm.subject = it; expanded = true },
            label = { Text("Deck") },
            placeholder = { Text("e.g. Physics, DSA, History") },
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

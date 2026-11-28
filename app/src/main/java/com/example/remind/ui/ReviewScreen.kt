package com.example.remind.ui

import com.example.remind.tr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Style
import androidx.compose.material.icons.filled.ViewCarousel
import com.example.remind.Lang
import com.example.remind.RemindApp
import com.example.remind.data.NoteWithAttachments
import com.example.remind.data.Repository
import com.example.remind.data.ReviewResult
import com.example.remind.data.Scheduler
import com.example.remind.data.today
import com.example.remind.notify.Notifications
import kotlinx.coroutines.launch

/**
 * Modes: due queue (default), a single note, or practice over a deck / starred notes.
 * Practice never changes the schedule.
 *
 * Two ways to go through the queue: flashcard (recall first, then "Show answer") or full view
 * (every note shown in full; swipe between them, rating is optional).
 */
class ReviewViewModel(
    private val repo: Repository,
    private val prefs: SharedPreferences,
    state: SavedStateHandle,
) : ViewModel() {
    private val noteId: Long = state["noteId"] ?: -1L
    private val deck: String? = state["deck"]
    private val starred: Boolean = state["starred"] ?: false
    val practice = deck != null || starred
    val title = when {
        deck != null -> "Practice: ${deckName(deck)}"
        starred -> "Practice: Starred"
        else -> "Revision"
    }

    /** Notes in this session, or null while loading. */
    var notes by mutableStateOf<List<NoteWithAttachments>?>(null)
        private set
    var index by mutableIntStateOf(0)
        private set
    var revealed by mutableStateOf(false)
    val counts = mutableStateMapOf<ReviewResult, Int>()
    /** Rating given to each note this session, by note id. */
    val rated = mutableStateMapOf<Long, ReviewResult>()
    var fullView by mutableStateOf(prefs.getBoolean(KEY_FULL_VIEW, false))
        private set
    private var busy = false

    val store get() = repo.store
    val total get() = notes?.size ?: 0
    val current get() = notes?.getOrNull(index)
    val finished get() = notes?.let { index >= it.size } ?: false

    init {
        viewModelScope.launch {
            val ids = when {
                noteId >= 0 -> listOf(noteId)
                deck != null -> repo.idsInDeck(deck).shuffled()
                starred -> repo.starredIds().shuffled()
                else -> repo.dueIds()
            }
            // Notes deleted meanwhile are dropped.
            notes = ids.mapNotNull { repo.get(it) }
        }
    }

    fun toggleFullView() {
        fullView = !fullView
        prefs.edit().putBoolean(KEY_FULL_VIEW, fullView).apply()
    }

    /** Full view: the pager settled on [page]. */
    fun goTo(page: Int) {
        if (page != index && page in 0 until total) {
            index = page
            revealed = false
        }
    }

    /** First unrated note after the current one (wrapping round), or the end if all are rated. */
    private fun nextUnrated(): Int {
        val list = notes ?: return 0
        val order = (index + 1 until list.size) + (0 until index)
        return order.firstOrNull { list[it].note.id !in rated } ?: list.size
    }

    /** Days until the next review if [result] is picked, or null if the note would be mastered. */
    fun preview(result: ReviewResult): Int? {
        val note = current?.note ?: return null
        val day = today()
        val outcome = Scheduler.review(note.stage, note.intervals, result, day)
        return if (outcome.mastered) null else (outcome.nextDueDay - day).toInt()
    }

    fun rate(result: ReviewResult) {
        val item = current ?: return
        if (busy || item.note.id in rated) return
        busy = true
        viewModelScope.launch {
            if (!practice) repo.review(item.note.id, result)
            counts[result] = (counts[result] ?: 0) + 1
            rated[item.note.id] = result
            revealed = false
            index = nextUnrated()
            busy = false
        }
    }

    /** Note opened in the editor from this session; reloaded when the screen comes back. */
    var editingId: Long? = null

    /** Picks up edits to one note (or drops it if it was deleted), keeping the place in the queue. */
    fun reload(id: Long) {
        viewModelScope.launch {
            val fresh = repo.get(id)
            notes = notes?.let { list ->
                if (fresh == null) list.filterNot { it.note.id == id } else list.map { if (it.note.id == id) fresh else it }
            }
        }
    }

    fun toggleStar(item: NoteWithAttachments) {
        val updated = !item.note.starred
        notes = notes?.map { if (it.note.id == item.note.id) it.copy(note = it.note.copy(starred = updated)) else it }
        viewModelScope.launch { repo.setStarred(item.note.id, updated) }
    }

    companion object {
        private const val KEY_FULL_VIEW = "review_full_view"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as RemindApp
                ReviewViewModel(
                    app.repository,
                    app.getSharedPreferences(Lang.PREFS, Context.MODE_PRIVATE),
                    createSavedStateHandle(),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    onClose: () -> Unit,
    onEdit: (Long) -> Unit,
    vm: ReviewViewModel = viewModel(factory = ReviewViewModel.Factory),
) {
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.editingId?.let {
            vm.editingId = null
            vm.reload(it)
        }
    }
    LaunchedEffect(Unit) { if (!vm.practice) Notifications.cancelAll(context) }

    val total = vm.total
    val progress by animateFloatAsState(
        targetValue = if (total == 0) 0f else vm.rated.size.toFloat() / total,
        animationSpec = tween(400),
        label = "progress",
    )

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(vm.title) },
                    navigationIcon = {
                        IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close") }
                    },
                    actions = {
                        if (total > 0 && !vm.finished) {
                            Text(
                                "${vm.index + 1}/$total",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            vm.current?.let { item ->
                                IconButton(onClick = {
                                    vm.editingId = item.note.id
                                    onEdit(item.note.id)
                                }) {
                                    Icon(Icons.Default.Edit, contentDescription = tr("Is note ko edit karo", "Edit this note"))
                                }
                            }
                            IconButton(onClick = vm::toggleFullView) {
                                Icon(
                                    if (vm.fullView) Icons.Default.Style else Icons.Default.ViewCarousel,
                                    contentDescription = if (vm.fullView) tr("Flashcard mode", "Flashcard mode")
                                    else tr("Poora note dikhao, swipe karke agla", "Show full notes, swipe for next"),
                                )
                            }
                        }
                    },
                )
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    drawStopIndicator = {},
                )
            }
        },
        bottomBar = {
            val item = vm.current
            if (item != null && !vm.finished && vm.fullView) {
                Surface(tonalElevation = 3.dp) {
                    Column(
                        Modifier
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val given = vm.rated[item.note.id]
                        if (given == null) {
                            RatingButtons(vm)
                        } else {
                            val (label, color) = resultStyle(given)
                            Text(tr("Rating: $label", "Rated: $label"), style = MaterialTheme.typography.labelLarge, color = color)
                        }
                        if (total > 1) {
                            Text(
                                tr("← swipe karke pichhla / agla →", "← swipe for previous / next →"),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else if (item != null && !vm.finished) {
                Surface(tonalElevation = 3.dp) {
                    AnimatedContent(
                        targetState = vm.revealed,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                        label = "actions",
                        modifier = Modifier
                            .navigationBarsPadding()
                            .padding(16.dp),
                    ) { revealed ->
                        if (!revealed) {
                            Button(
                                onClick = { vm.revealed = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 56.dp),
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(tr("Jawab dekho", "Show answer"))
                            }
                        } else {
                            RatingButtons(vm)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                vm.notes == null -> Unit
                total == 0 -> NothingToReview(vm.practice, onClose)
                vm.finished -> FinishedView(vm, onClose)
                vm.fullView -> FullViewPager(vm)
                else -> AnimatedContent(
                    targetState = vm.current,
                    contentKey = { it?.note?.id },
                    transitionSpec = {
                        (slideInHorizontally(tween(300)) { it / 2 } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(tween(250)) { -it / 2 } + fadeOut(tween(200)))
                    },
                    label = "card",
                ) { item ->
                    if (item != null) ReviewCard(item, vm, revealed = vm.revealed)
                }
            }
        }
    }
}

/** Full view: every note shown in full, side by side; swipe to move between them. */
@Composable
private fun FullViewPager(vm: ReviewViewModel) {
    val notes = vm.notes ?: return
    val pagerState = rememberPagerState(initialPage = vm.index.coerceIn(0, notes.lastIndex)) { notes.size }
    // Swipe → current note; rating → pager follows to the next unrated note.
    LaunchedEffect(pagerState.settledPage) { vm.goTo(pagerState.settledPage) }
    LaunchedEffect(vm.index) {
        if (vm.index in notes.indices && vm.index != pagerState.settledPage) pagerState.animateScrollToPage(vm.index)
    }
    HorizontalPager(
        state = pagerState,
        key = { notes[it].note.id },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        ReviewCard(notes[page], vm, revealed = true)
    }
}

@Composable
private fun ReviewCard(item: NoteWithAttachments, vm: ReviewViewModel, revealed: Boolean) {
    val note = item.note
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        deckName(note.subject).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.toggleStar(item) }) {
                        AnimatedContent(
                            targetState = note.starred,
                            transitionSpec = { scaleIn(spring(Spring.DampingRatioMediumBouncy)) togetherWith scaleOut() },
                            label = "star",
                        ) { starred ->
                            Icon(
                                if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                                contentDescription = if (starred) "Unstar" else "Star",
                                tint = if (starred) StarColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(note.title, style = MaterialTheme.typography.headlineMedium)

                AnimatedVisibility(
                    visible = !revealed,
                    enter = fadeIn(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        tr("Pehle khud yaad karo: keywords kya the, main points kya the? Phir jawab dekho.", "Recall it first: what were the keywords and main points? Then check the answer."),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                AnimatedVisibility(
                    visible = revealed,
                    enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                    exit = shrinkVertically() + fadeOut(),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        KeywordChips(note.keywords)
                        if (note.body.isNotBlank()) NotesText(note.body, style = MaterialTheme.typography.bodyLarge)
                        if (item.attachments.isNotEmpty()) {
                            AttachmentGallery(item.attachments.map { vm.store.file(it.fileName) to it.type })
                        }
                        if (note.keywords.isEmpty() && note.body.isBlank() && item.attachments.isEmpty()) {
                            Text(
                                tr("Is note me sirf title hai. Edit karke keywords ya photo add karo.", "This note only has a title. Edit it to add keywords or a photo."),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingButtons(vm: ReviewViewModel) {
    val scheme = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        RatingButton(tr("Bhool gaya", "Forgot"), ReviewResult.FORGOT, vm, scheme.errorContainer, scheme.onErrorContainer, Modifier.weight(1f))
        RatingButton(tr("Thoda", "Partly"), ReviewResult.HARD, vm, scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.weight(1f))
        RatingButton(tr("Yaad tha", "Remembered"), ReviewResult.GOOD, vm, scheme.primary, scheme.onPrimary, Modifier.weight(1f))
    }
}

@Composable
private fun RatingButton(
    label: String,
    result: ReviewResult,
    vm: ReviewViewModel,
    container: Color,
    content: Color,
    modifier: Modifier,
) {
    val subtitle = when {
        vm.practice -> "practice"
        else -> vm.preview(result)?.let { if (it == 1) tr("kal", "tomorrow") else tr("$it din", "$it days") } ?: "mastered"
    }
    FilledTonalButton(
        onClick = { vm.rate(result) },
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = container, contentColor = content),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.75f))
        }
    }
}

@Composable
private fun FinishedView(vm: ReviewViewModel, onClose: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        AnimatedVisibility(shown, enter = scaleIn(spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow)) + fadeIn()) {
            Box(
                Modifier
                    .size(112.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(36.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (vm.practice) Icons.Default.EmojiEvents else Icons.Default.TaskAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(56.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(tr("Shabash!", "Well done!"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            if (vm.practice) tr("Practice complete. Schedule pe koi asar nahi pada.", "Practice complete. Your schedule is unchanged.")
            else tr("Aaj ka revision ho gaya. Agla reminder time pe aa jayega.", "Today's revision is done. The next reminder will come on time."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ReviewResult.entries.reversed().forEach { result ->
                val (label, color) = resultStyle(result)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${vm.counts[result] ?: 0}", style = MaterialTheme.typography.headlineSmall, color = color)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Button(onClick = onClose) { Text("Done") }
    }
}

@Composable
private fun NothingToReview(practice: Boolean, onClose: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.TaskAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(16.dp))
        Text(if (practice) tr("Is deck me koi note nahi", "No notes in this deck") else tr("Abhi kuch due nahi hai", "Nothing is due right now"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClose) { Text(tr("Wapas jao", "Go back")) }
    }
}

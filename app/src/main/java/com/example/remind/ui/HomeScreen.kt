package com.example.remind.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.example.remind.RemindApp
import com.example.remind.data.DeckSummary
import com.example.remind.data.NoteWithAttachments
import com.example.remind.data.Repository
import com.example.remind.data.Scheduler
import com.example.remind.data.today
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class HomeState(
    val loaded: Boolean = false,
    val today: Long = 0,
    val due: List<NoteWithAttachments> = emptyList(),
    val upcoming: List<NoteWithAttachments> = emptyList(),
    val decks: List<DeckSummary> = emptyList(),
    val starredCount: Int = 0,
    val streak: Int = 0,
) {
    val isEmpty get() = loaded && decks.isEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(val repo: Repository) : ViewModel() {
    private val day = MutableStateFlow(today())

    fun refresh() {
        day.value = today()
    }

    val state = day.flatMapLatest { d ->
        combine(
            repo.observeDue(d),
            repo.observeUpcoming(d),
            repo.observeDecks(d),
            repo.observeStarredCount(),
            repo.observeReviewDays(),
        ) { due, upcoming, decks, starred, days ->
            HomeState(true, d, due, upcoming, decks, starred, Scheduler.streak(days, d))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    companion object {
        val Factory = viewModelFactory {
            initializer { HomeViewModel((this[APPLICATION_KEY] as RemindApp).repository) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAdd: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onStartReview: () -> Unit,
    onOpenLibrary: (deck: String?, starred: Boolean) -> Unit,
    onSettings: () -> Unit,
    vm: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val listState = rememberLazyListState()
    val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Remind") },
                actions = {
                    IconButton(onClick = { onOpenLibrary(null, false) }) {
                        Icon(Icons.AutoMirrored.Filled.LibraryBooks, contentDescription = "Library")
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                expanded = fabExpanded,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Naya note") },
            )
        },
    ) { padding ->
        if (!state.loaded) return@Scaffold
        if (state.isEmpty) {
            EmptyHome(Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "hero") {
                HeroCard(
                    dueCount = state.due.size,
                    streak = state.streak,
                    nextUp = state.upcoming.firstOrNull()?.let { dueLabel(it.note.nextDueDay, state.today) },
                    onStart = onStartReview,
                )
            }

            item(key = "decks-header") {
                SectionHeader("Decks") {
                    TextButton(onClick = { onOpenLibrary(null, false) }) { Text("Sab notes") }
                }
            }
            item(key = "decks") {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (state.starredCount > 0) {
                        item(key = "starred") {
                            DeckCard(
                                name = "Starred",
                                icon = Icons.Default.Star,
                                iconTint = StarColor,
                                total = state.starredCount,
                                due = 0,
                                mastered = 0,
                                showProgress = false,
                                onClick = { onOpenLibrary(null, true) },
                            )
                        }
                    }
                    items(state.decks, key = { "deck-" + it.subject }) { deck ->
                        DeckCard(
                            name = deckName(deck.subject),
                            icon = Icons.Default.Folder,
                            iconTint = MaterialTheme.colorScheme.primary,
                            total = deck.total,
                            due = deck.due,
                            mastered = deck.mastered,
                            onClick = { onOpenLibrary(deck.subject, false) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }

            if (state.due.isNotEmpty()) {
                item(key = "due-header") { SectionHeader("Aaj revise karo (${state.due.size})") }
                items(state.due, key = { "due-" + it.note.id }) { item ->
                    NoteCard(item, state.today, vm.repo.store, onClick = { onOpenNote(item.note.id) }, modifier = Modifier.animateItem())
                }
            }

            if (state.upcoming.isNotEmpty()) {
                item(key = "up-header") { SectionHeader("Aage aane wale") }
                items(state.upcoming, key = { "up-" + it.note.id }) { item ->
                    NoteCard(item, state.today, vm.repo.store, onClick = { onOpenNote(item.note.id) }, modifier = Modifier.animateItem())
                }
            }
        }
    }
}

@Composable
private fun HeroCard(dueCount: Int, streak: Int, nextUp: String?, onStart: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(listOf(scheme.primaryContainer, scheme.tertiaryContainer))
                )
                .padding(20.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Aaj ka revision",
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    AnimatedVisibility(streak > 0, enter = fadeIn(), exit = fadeOut()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalFireDepartment, contentDescription = null, tint = StarColor, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("$streak din streak", style = MaterialTheme.typography.labelLarge, color = scheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                AnimatedContent(
                    targetState = dueCount,
                    transitionSpec = {
                        (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                    },
                    label = "dueCount",
                ) { count ->
                    if (count > 0) {
                        Text(
                            "$count ${if (count == 1) "note" else "notes"} due",
                            style = MaterialTheme.typography.headlineMedium,
                            color = scheme.onPrimaryContainer,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.TaskAlt, contentDescription = null, tint = scheme.onPrimaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text("Sab revise ho gaya", style = MaterialTheme.typography.headlineSmall, color = scheme.onPrimaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        dueCount > 0 -> "Thoda time nikaalo, yaad pakka ho jayega."
                        nextUp != null -> "Agla revision: $nextUp"
                        else -> "Naya note add karo, reminder hum denge."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onPrimaryContainer.copy(alpha = 0.8f),
                )
                AnimatedVisibility(dueCount > 0) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.padding(top = 16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Revision shuru karo")
                    }
                }
            }
        }
    }
}

@Composable
private fun DeckCard(
    name: String,
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    total: Int,
    due: Int,
    mastered: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.width(156.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
                Spacer(Modifier.weight(1f))
                if (due > 0) Badge(containerColor = MaterialTheme.colorScheme.error) { Text("$due due") }
            }
            Spacer(Modifier.height(10.dp))
            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "$total ${if (total == 1) "note" else "notes"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showProgress) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else mastered.toFloat() / total },
                    modifier = Modifier.fillMaxWidth(),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    drawStopIndicator = {},
                )
            } else {
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun EmptyHome(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(96.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.AutoStories, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Jo padha, wo yaad rahega", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Photo, handwriting, voice ya video ke saath note banao. Remind sahi time pe revision yaad dilayega: 1, 2, 4, 7… din baad.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

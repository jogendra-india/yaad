package com.example.remind.ui

import com.example.remind.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.remind.data.today
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

sealed interface LibraryFilter {
    data object All : LibraryFilter
    data object Starred : LibraryFilter
    data object Due : LibraryFilter
    data object Mastered : LibraryFilter
    data class Deck(val subject: String) : LibraryFilter
}

data class LibraryState(
    val loaded: Boolean = false,
    val notes: List<NoteWithAttachments> = emptyList(),
    val decks: List<String> = emptyList(),
    val filter: LibraryFilter = LibraryFilter.All,
    val query: String = "",
    val today: Long = 0,
)

class LibraryViewModel(val repo: Repository, state: SavedStateHandle) : ViewModel() {
    private val initial: LibraryFilter = run {
        val deck: String? = state["deck"]
        val starred: Boolean = state["starred"] ?: false
        when {
            starred -> LibraryFilter.Starred
            deck != null -> LibraryFilter.Deck(deck)
            else -> LibraryFilter.All
        }
    }
    private val filter = MutableStateFlow(initial)
    private val query = MutableStateFlow("")

    fun setFilter(value: LibraryFilter) {
        filter.value = value
    }

    fun setQuery(value: String) {
        query.value = value
    }

    val state = combine(repo.observeAll(), repo.observeDecks(today()), filter, query) { all, decks, f, q ->
        val day = today()
        val terms = q.trim().lowercase()
        val list = all.filter { item ->
            val n = item.note
            val matchesFilter = when (f) {
                LibraryFilter.All -> true
                LibraryFilter.Starred -> n.starred
                LibraryFilter.Due -> !n.mastered && n.nextDueDay <= day
                LibraryFilter.Mastered -> n.mastered
                is LibraryFilter.Deck -> n.subject == f.subject
            }
            val matchesQuery = terms.isEmpty() ||
                n.title.lowercase().contains(terms) ||
                n.body.lowercase().contains(terms) ||
                n.subject.lowercase().contains(terms) ||
                n.keywords.any { it.lowercase().contains(terms) }
            matchesFilter && matchesQuery
        }
        LibraryState(true, list, decks.map { it.subject }, f, q, day)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState(filter = initial))

    companion object {
        val Factory = viewModelFactory {
            initializer {
                LibraryViewModel((this[APPLICATION_KEY] as RemindApp).repository, createSavedStateHandle())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onPractice: (deck: String?, starred: Boolean) -> Unit,
    vm: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val filter = state.filter

    val title = when (filter) {
        LibraryFilter.All -> "Library"
        LibraryFilter.Starred -> "Starred"
        LibraryFilter.Due -> "Due notes"
        LibraryFilter.Mastered -> "Mastered"
        is LibraryFilter.Deck -> deckName(filter.subject)
    }
    val canPractice = (filter is LibraryFilter.Deck || filter == LibraryFilter.Starred) && state.notes.isNotEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    if (canPractice) {
                        TextButton(onClick = {
                            when (filter) {
                                is LibraryFilter.Deck -> onPractice(filter.subject, false)
                                else -> onPractice(null, true)
                            }
                        }) {
                            Icon(Icons.Default.School, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text("  Practice")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                placeholder = { Text(tr("Title, keyword ya notes me search…", "Search titles, keywords or notes…")) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { FilterOption(tr("Sab", "All"), filter == LibraryFilter.All) { vm.setFilter(LibraryFilter.All) } }
                item {
                    FilterOption("Starred", filter == LibraryFilter.Starred, leading = {
                        Icon(Icons.Default.Star, null, tint = StarColor, modifier = Modifier.size(18.dp))
                    }) { vm.setFilter(LibraryFilter.Starred) }
                }
                item { FilterOption("Due", filter == LibraryFilter.Due) { vm.setFilter(LibraryFilter.Due) } }
                item { FilterOption("Mastered", filter == LibraryFilter.Mastered) { vm.setFilter(LibraryFilter.Mastered) } }
                items(state.decks, key = { "deck-$it" }) { subject ->
                    val selected = filter is LibraryFilter.Deck && filter.subject == subject
                    FilterOption(deckName(subject), selected, leading = {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(18.dp))
                    }) { vm.setFilter(LibraryFilter.Deck(subject)) }
                }
            }

            if (state.loaded && state.notes.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.SearchOff, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(tr("Kuch nahi mila", "Nothing found"), style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (state.query.isNotEmpty()) tr("Dusra keyword try karo", "Try another keyword") else tr("Is filter me abhi koi note nahi hai", "No notes in this filter yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item(key = "count") {
                        Text(
                            "${state.notes.size} ${if (state.notes.size == 1) "note" else "notes"}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(state.notes, key = { it.note.id }) { item ->
                        NoteCard(item, state.today, vm.repo.store, onClick = { onOpenNote(item.note.id) }, modifier = Modifier.animateItem())
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterOption(label: String, selected: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = leading,
        colors = FilterChipDefaults.filterChipColors(),
    )
}

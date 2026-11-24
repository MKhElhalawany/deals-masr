package com.dealsmasr.app.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.dealsmasr.app.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.core.DealSort
import com.dealsmasr.app.data.DealsRepository
import com.dealsmasr.app.data.SearchFilters
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.ui.appViewModel
import com.dealsmasr.app.ui.components.ErrorState
import com.dealsmasr.app.ui.components.MessageState
import com.dealsmasr.app.ui.components.ProductCard
import com.dealsmasr.app.ui.components.userMessage
import com.dealsmasr.app.ui.deals.STORES
import com.dealsmasr.app.ui.deals.SkeletonGrid
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchState(
    val query: String = "",
    val filters: SearchFilters = SearchFilters(),
    val sort: DealSort = DealSort.DISCOUNT,
    val results: List<DealDto> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val categories: List<String> = emptyList(),
    val unavailableStores: List<String> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val searched: Boolean = false,
    /** the server is still checking the stores live for this query (results can still arrive) */
    val checkingStores: Boolean = false,
)

class SearchViewModel(private val repo: DealsRepository) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()
    val favoriteIds = repo.favoriteIds.map { it.toSet() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    private var job: Job? = null

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setFilters(f: SearchFilters) { _state.update { it.copy(filters = f) }; if (_state.value.searched) search() }
    fun setSort(s: DealSort) { _state.update { it.copy(sort = s) }; if (_state.value.searched) search() }
    fun clearFilters() = setFilters(SearchFilters())

    fun search() {
        val s = _state.value
        if (s.query.isBlank()) return
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null, searched = true, checkingStores = false) }
            var live = false
            runCatching { repo.searchApi(s.query, s.filters, s.sort.apiKey, 1, askServer = true) }
                .onSuccess { r ->
                    live = r.live
                    _state.update {
                        it.copy(loading = false, results = r.page.items, total = r.page.total, page = 1, checkingStores = r.live,
                            categories = (it.categories + r.page.categories).distinct(), unavailableStores = emptyList())
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
            if (!live) return@launch
            // the server is checking the stores live — pick up new results as they land (a few seconds)
            for (wait in listOf(4_000L, 5_000L, 7_000L, 10_000L)) {
                kotlinx.coroutines.delay(wait)
                runCatching { repo.searchApi(s.query, s.filters, s.sort.apiKey, 1, askServer = false) }.onSuccess { r ->
                    _state.update { if (r.page.items.size >= it.results.size) it.copy(results = r.page.items, total = r.page.total) else it }
                }
            }
            _state.update { it.copy(checkingStores = false) }
        }
    }

    fun loadMore() {
        val s = _state.value
        if (s.loadingMore || s.results.size >= s.total) return
        viewModelScope.launch {
            _state.update { it.copy(loadingMore = true) }
            runCatching { repo.search(s.query, s.filters, s.sort.apiKey, s.page + 1) }
                .onSuccess { p ->
                    _state.update {
                        val merged = (it.results + p.items).distinctBy { d -> d.id }
                        // Server pages carry the real total; local pages report "+1" while more pages exist.
                        val total = if (p.items.isEmpty()) merged.size else maxOf(it.total, merged.size + if (p.total > p.items.size) 1 else 0)
                        it.copy(loadingMore = false, results = merged, page = p.page, total = total)
                    }
                }
                .onFailure { _state.update { s2 -> s2.copy(loadingMore = false, total = s2.results.size) } }
        }
    }

    fun toggleFavorite(d: DealDto) = viewModelScope.launch { repo.toggleFavorite(d, d.id in favoriteIds.value) }

    fun createAlert(query: String, minDiscount: Int) = viewModelScope.launch {
        repo.addAlert(query, minDiscount, _state.value.filters.stores, _state.value.filters.maxPrice)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(initialQuery: String?, onProduct: (String) -> Unit) {
    val vm = appViewModel { SearchViewModel(it.deals) }
    val s by vm.state.collectAsStateWithLifecycle()
    val favs by vm.favoriteIds.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var showFilters by remember { mutableStateOf(false) }
    var showAlert by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialQuery.isNullOrBlank() && !s.searched) { vm.setQuery(initialQuery); vm.search() }
        else if (!s.searched) runCatching { focus.requestFocus() }
    }

    Scaffold { pad ->
        Column(Modifier.padding(pad)) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(focus),
                placeholder = { Text("مثال: Air Fryer أو قلاية هوائية") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = {
                    Row {
                        if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Clear, "مسح") }
                        IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.FilterList, "فلاتر") }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); vm.search() }),
            )
            // Store selection
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = s.filters.stores.isEmpty(), onClick = { vm.setFilters(s.filters.copy(stores = emptySet())) },
                        label = { Text("كل المتاجر") })
                }
                items(STORES) { (id, name) ->
                    val st = s.filters.stores
                    FilterChip(selected = id in st, onClick = { vm.setFilters(s.filters.copy(stores = if (id in st) st - id else st + id)) },
                        label = { Text(name) })
                }
            }
            // Minimum discount
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DealMath.MIN_DISCOUNT_OPTIONS) { pct ->
                    val selected = (s.filters.minDiscount ?: 0) == pct
                    FilterChip(selected = selected, onClick = { vm.setFilters(s.filters.copy(minDiscount = pct.takeIf { it > 0 })) },
                        label = { Text(if (pct == 0) "أي خصم" else "$pct%+") })
                }
            }
            ActiveFiltersRow(s, onClear = vm::clearFilters, onSort = vm::setSort)

            when {
                s.loading -> SkeletonGrid()
                s.error != null -> ErrorState(s.error!!, vm::search)
                !s.searched -> MessageState(Icons.Outlined.Search, "ابحث في كل المتاجر مرة واحدة",
                    "اكتب اسم المنتج واختار المتاجر ونسبة الخصم اللي تناسبك")
                s.results.isEmpty() && s.checkingStores -> SkeletonGrid()
                s.results.isEmpty() -> MessageState(Icons.Outlined.SearchOff, "مفيش عروض على «${s.query.trim()}» دلوقتي",
                    "أول ما ينزل عليه خصم في أي متجر هنبلغك فوراً.",
                    "ابلغني أول ما يتوفر", { showAlert = true })
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column {
                            unavailableNote(s.unavailableStores)?.let {
                                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                            }
                            Row {
                                Text("${s.total} نتيجة", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                TextButton(onClick = { showAlert = true }) {
                                    Icon(Icons.Outlined.NotificationsActive, null); Text(" تنبيه")
                                }
                            }
                        }
                    }
                    items(s.results, key = { it.id }) { d ->
                        ProductCard(d, d.id in favs, { vm.toggleFavorite(d) }, { onProduct(d.id) })
                    }
                    if (s.results.size < s.total) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            OutlinedButton(onClick = vm::loadMore, enabled = !s.loadingMore) {
                                Text(if (s.loadingMore) "جاري التحميل…" else "تحميل المزيد")
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFilters) {
        ModalBottomSheet(onDismissRequest = { showFilters = false }) {
            FiltersSheet(s, onApply = { vm.setFilters(it); showFilters = false; if (!s.searched) vm.search() })
        }
    }
    if (showAlert) {
        AlertDialogContent(s.query, s.filters.minDiscount ?: 30, onDismiss = { showAlert = false }) { q, pct ->
            vm.createAlert(q, pct); showAlert = false
        }
    }
}

private fun unavailableNote(ids: List<String>): String? {
    if (ids.isEmpty()) return null
    val names = ids.mapNotNull { id -> STORES.firstOrNull { it.first == id }?.second }
    return "تنبيه: ${names.joinToString("، ")} غير متصل بمصدر بيانات حالياً"
}

@Composable
private fun ActiveFiltersRow(s: SearchState, onClear: () -> Unit, onSort: (DealSort) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(DealSort.entries) { o ->
            FilterChip(selected = s.sort == o, onClick = { onSort(o) }, label = { Text(o.labelAr) })
        }
        val f = s.filters
        if (f != SearchFilters()) {
            item { AssistChip(onClick = onClear, label = { Text("مسح الفلاتر") }, leadingIcon = { Icon(Icons.Outlined.Clear, null) }) }
        }
    }
}

@Composable
private fun FiltersSheet(s: SearchState, onApply: (SearchFilters) -> Unit) {
    var minP by remember { mutableStateOf(s.filters.minPrice?.toInt()?.toString().orEmpty()) }
    var maxP by remember { mutableStateOf(s.filters.maxPrice?.toInt()?.toString().orEmpty()) }
    var cat by remember { mutableStateOf(s.filters.category) }
    Column(Modifier.padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("فلاتر البحث", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(minP, { minP = it.filter(Char::isDigit) }, label = { Text("أقل سعر (ج.م)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
            OutlinedTextField(maxP, { maxP = it.filter(Char::isDigit) }, label = { Text("أعلى سعر (ج.م)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
        }
        if (s.categories.isNotEmpty()) {
            Text("القسم", style = MaterialTheme.typography.titleSmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = cat == null, onClick = { cat = null }, label = { Text("الكل") }) }
                items(s.categories) { c -> FilterChip(selected = cat == c, onClick = { cat = c }, label = { Text(c) }) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                onApply(s.filters.copy(minPrice = minP.toDoubleOrNull(), maxPrice = maxP.toDoubleOrNull(), category = cat))
            }, modifier = Modifier.weight(1f)) { Text("تطبيق") }
            OutlinedButton(onClick = { onApply(SearchFilters()) }, modifier = Modifier.weight(1f)) { Text("مسح الكل") }
        }
    }
}

@Composable
private fun AlertDialogContent(query: String, defaultPct: Int, onDismiss: () -> Unit, onCreate: (String, Int) -> Unit) {
    var q by remember { mutableStateOf(query) }
    var pct by remember { mutableStateOf(defaultPct.coerceAtLeast(10)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("تنبيه بالخصم") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("المنتج") }, singleLine = true)
                Text("بلغني لما يتوفر خصم $pct% أو أكثر")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(DealMath.MIN_DISCOUNT_OPTIONS.drop(1)) { p ->
                        FilterChip(selected = pct == p, onClick = { pct = p }, label = { Text("$p%") })
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text("بنراجع العروض تلقائياً كل كام ساعة ونبعتلك إشعار.", Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = { onCreate(q, pct) }, enabled = q.isNotBlank()) { Text("إنشاء التنبيه") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

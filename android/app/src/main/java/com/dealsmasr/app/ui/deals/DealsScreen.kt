package com.dealsmasr.app.ui.deals

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.core.DealSort
import com.dealsmasr.app.ui.appViewModel
import com.dealsmasr.app.ui.components.ErrorState
import com.dealsmasr.app.ui.components.MessageState
import com.dealsmasr.app.ui.components.ProductCard
import com.dealsmasr.app.ui.components.SkeletonCard
import com.dealsmasr.app.ui.home.DealsViewModel
import com.dealsmasr.app.ui.theme.tierColor

val STORES = listOf("amazon" to "أمازون", "noon" to "نون", "townteam" to "تاون تيم", "jumia" to "جوميا")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DealsScreen(initialTier: String?, onProduct: (String) -> Unit, onSettings: () -> Unit) {
    val vm = appViewModel { DealsViewModel(it.deals, it.settings) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshIfStale() }

    val tabs = remember(s.tiers) { listOf<String?>(null) + s.tiers.sortedByDescending { it.minPct }.map { it.key } }
    var tier by rememberSaveable(initialTier) { mutableStateOf(initialTier) }
    var sort by rememberSaveable { mutableStateOf(DealSort.DISCOUNT) }
    var stores by rememberSaveable { mutableStateOf(setOf<String>()) }
    var sortMenu by remember { mutableStateOf(false) }

    val shown = remember(s.deals, tier, sort, stores, s.tiers) {
        val range = tier?.let { DealMath.tierRange(it, s.tiers) }
        DealMath.sort(
            s.deals.filter { d ->
                (stores.isEmpty() || d.marketplace in stores) &&
                    (range == null || (d.discountPct >= range.first && (range.second == null || d.discountPct < range.second!!)))
            },
            sort,
        )
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("العروض") },
            actions = {
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, "ترتيب") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        DealSort.entries.forEach { o ->
                            DropdownMenuItem(
                                text = { Text((if (o == sort) "✓ " else "") + o.labelAr) },
                                onClick = { sort = o; sortMenu = false },
                            )
                        }
                    }
                }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad)) {
            val idx = tabs.indexOf(tier).coerceAtLeast(0)
            ScrollableTabRow(selectedTabIndex = idx, edgePadding = 12.dp) {
                tabs.forEach { key ->
                    val t = s.tiers.firstOrNull { it.key == key }
                    Tab(selected = key == tier, onClick = { tier = key },
                        text = {
                            Text(t?.labelAr ?: "الكل", color = if (key == tier && t != null) tierColor(key) else MaterialTheme.colorScheme.onSurface)
                        })
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = stores.isEmpty(), onClick = { stores = emptySet() }, label = { Text("كل المتاجر") }) }
                items(STORES + ("talabat" to "طلبات")) { (id, name) ->
                    FilterChip(selected = id in stores, onClick = { stores = if (id in stores) stores - id else stores + id },
                        label = { Text(name) })
                }
            }
            PullToRefreshBox(isRefreshing = s.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
                when {
                    s.deals.isEmpty() && s.error != null -> ErrorState(s.error!!, { vm.refresh() })
                    s.deals.isEmpty() && !s.loaded -> SkeletonGrid()
                    shown.isEmpty() -> MessageState(Icons.Outlined.LocalOffer, "لا توجد عروض في هذا القسم الآن",
                        "جرّب قسم أو متجر تاني")
                    else -> LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text("${shown.size} عرض · ${sort.labelAr}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        items(shown, key = { it.id }) { d ->
                            ProductCard(d, d.id in s.favoriteIds, { vm.toggleFavorite(d) }, { onProduct(d.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SkeletonGrid() {
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { repeat(2) { SkeletonCard(Modifier.weight(1f)) } }
        }
    }
}

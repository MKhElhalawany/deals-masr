package com.dealsmasr.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import com.dealsmasr.app.ui.i18n.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import com.dealsmasr.app.ui.appContainer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.core.DealSort
import com.dealsmasr.app.core.DealTier
import com.dealsmasr.app.data.DealsRepository
import com.dealsmasr.app.data.SettingsStore
import com.dealsmasr.app.data.local.toDto
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.ui.appViewModel
import com.dealsmasr.app.ui.components.ErrorState
import com.dealsmasr.app.ui.components.MessageState
import com.dealsmasr.app.ui.components.ProductCard
import com.dealsmasr.app.ui.components.SectionHeader
import com.dealsmasr.app.ui.components.SkeletonCard
import com.dealsmasr.app.ui.components.relativeTime
import com.dealsmasr.app.ui.components.userMessage
import com.dealsmasr.app.ui.theme.tierColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DealsState(
    val deals: List<DealDto> = emptyList(),
    val tiers: List<DealTier> = DealMath.DEFAULT_TIERS,
    val favoriteIds: Set<String> = emptySet(),
    val hasServer: Boolean = true,
    val lastRefresh: Long = 0,
    val loading: Boolean = false,
    val error: String? = null,
    val loaded: Boolean = false,
)

/** Shared by Home and Deals tabs: cached snapshot + background refresh. */
class DealsViewModel(private val repo: DealsRepository, private val settings: SettingsStore) : ViewModel() {
    private val ui = MutableStateFlow(Triple(false, null as String?, false))

    val state: StateFlow<DealsState> = combine(repo.cachedDeals, repo.favoriteIds, settings.settings, ui) { deals, favs, s, u ->
        DealsState(
            deals = deals.map { e -> e.toDto().copy(tier = DealMath.classify(e.discountPct, s.tiers)?.key) },
            tiers = s.tiers, favoriteIds = favs.toSet(), hasServer = s.hasServer, lastRefresh = s.lastRefresh,
            loading = u.first, error = u.second, loaded = u.third || deals.isNotEmpty(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DealsState(loading = true))

    fun refreshIfStale() = viewModelScope.launch {
        val s = settings.current()
        if (System.currentTimeMillis() - s.lastRefresh > 60_000) refresh()
    }

    fun refresh() = viewModelScope.launch {
        ui.value = Triple(true, null, ui.value.third)
        val r = runCatching { repo.refresh() }
        ui.value = Triple(false, r.exceptionOrNull()?.userMessage(), true)
    }

    fun toggleFavorite(d: DealDto) = viewModelScope.launch { repo.toggleFavorite(d, d.id in state.value.favoriteIds) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onOpenTier: (String?) -> Unit,
    onProduct: (String) -> Unit,
    onFavorites: () -> Unit,
    onSettings: () -> Unit,
    onMenu: () -> Unit,
    onCrazy: (Int) -> Unit = {},
) {
    val vm = appViewModel { DealsViewModel(it.deals, it.settings) }
    val s by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshIfStale() }
    val container = com.dealsmasr.app.ui.appContainer()
    val forYou by androidx.compose.runtime.produceState(emptyList<com.dealsmasr.app.data.remote.DealDto>(), s.deals) {
        value = runCatching { container.interests.recommend(s.deals, 15) }.getOrDefault(emptyList())
    }
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(androidx.compose.ui.res.painterResource(com.dealsmasr.app.R.drawable.ic_launcher_foreground), null,
                            tint = androidx.compose.ui.graphics.Color.Unspecified, modifier = Modifier.size(40.dp))
                        Text("عروض مصر", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.secondary.takeIf { androidx.compose.foundation.isSystemInDarkTheme() } ?: MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = { IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "الأقسام") } },
                actions = {
                    IconButton(onClick = onFavorites) { Icon(Icons.Outlined.FavoriteBorder, "المفضلة") }
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "الإعدادات") }
                },
            )
        },
    ) { pad ->
        PullToRefreshBox(isRefreshing = s.loading, onRefresh = { vm.refresh() }, modifier = Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item { SearchEntry(onSearch) }
                when {
                    s.deals.isEmpty() && s.error != null -> item { ErrorState(s.error!!, { vm.refresh() }) }
                    s.deals.isEmpty() && !s.loaded -> item {
                        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            repeat(2) { SkeletonCard(Modifier.weight(1f)) }
                        }
                    }
                    s.deals.isEmpty() -> item {
                        MessageState(Icons.Outlined.LocalOffer, "لا توجد عروض موثقة حالياً",
                            "سيرفرنا بيتابع أمازون ونون وتاون تيم 24 ساعة، والعروض بتظهر هنا أول بأول. اسحب للتحديث.")
                    }
                    else -> {
                        item {
                            Text("آخر تحديث ${relativeTime(s.lastRefresh / 1000)} · ${s.deals.size} عرض",
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp))
                        }
                        if (forYou.isNotEmpty()) {
                            item(key = "h_foryou") { SectionHeader("✨ مختار ليك") }
                            item(key = "r_foryou") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(forYou, key = { "fy_" + it.id }) { d ->
                                        ProductCard(d, d.id in s.favoriteIds, { vm.toggleFavorite(d) }, { onProduct(d.id) }, Modifier.width(160.dp))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        item(key = "crazy") { CrazyGrid(onCrazy) }
                        // Highest tiers first: Mega → Super → Hot are the most prominent.
                        s.tiers.sortedByDescending { it.minPct }.forEach { tier ->
                            val range = DealMath.tierRange(tier.key, s.tiers) ?: return@forEach
                            val list = DealMath.sort(
                                s.deals.filter { it.discountPct >= range.first && (range.second == null || it.discountPct < range.second!!) },
                                DealSort.DISCOUNT,
                            )
                            if (list.isEmpty()) return@forEach
                            val prominent = tier.minPct >= 30
                            item(key = "h_${tier.key}") {
                                if (prominent) TierBanner(tier, list.size) { onOpenTier(tier.key) }
                                else SectionHeader(tier.labelAr, "عرض الكل", { onOpenTier(tier.key) })
                            }
                            item(key = "r_${tier.key}") {
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(list.take(15), key = { it.id }) { d ->
                                        ProductCard(d, d.id in s.favoriteIds, { vm.toggleFavorite(d) }, { onProduct(d.id) },
                                            Modifier.width(if (prominent) 190.dp else 160.dp))
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchEntry(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, null)
            Spacer(Modifier.width(10.dp))
            Text("ابحث عن أي منتج… قلاية، سماعة، iPhone", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CrazyGrid(onOpen: (Int) -> Unit) {
    val tiles = listOf(
        Triple("🔥 عروض مجنونة", "عروض طرش أول بأول", listOf(Color(0xFFB3123A), Color(0xFFE4572E))) to 0,
        Triple("🍔 مطاعم وخروجات", "أكل وكافيهات وخروجات", listOf(Color(0xFFE07B00), Color(0xFFF2A93B))) to 3,
        Triple("🎟️ كوبونات", "أكواد خصم جاهزة", listOf(Color(0xFF0F7B5F), Color(0xFF2BAE87))) to 1,
        Triple("🎁 Buy 1 Get 1", "اشتري واحد والتاني هدية", listOf(Color(0xFF5B2A86), Color(0xFF8E4FC4))) to 2,
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (t, f) ->
                    Box(Modifier.weight(1f).height(86.dp).background(Brush.horizontalGradient(t.third), RoundedCornerShape(16.dp))
                        .clickable { onOpen(f) }.padding(12.dp)) {
                        Column {
                            Text(t.first, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                            Text(t.second, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f), maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TierBanner(tier: DealTier, count: Int, onClick: () -> Unit) {
    val c = tierColor(tier.key)
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            .background(Brush.horizontalGradient(listOf(c, c.copy(alpha = 0.75f))), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(16.dp),
    ) {
        Column {
            Text(tier.labelAr, style = MaterialTheme.typography.titleLarge, color = Color.White)
            Text("خصم ${tier.minPct.toInt()}% أو أكثر · $count عرض · عرض الكل ←", style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f))
        }
    }
}

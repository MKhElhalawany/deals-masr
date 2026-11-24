package com.dealsmasr.app.ui.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.DealsRepository
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.components.ErrorState
import com.dealsmasr.app.ui.components.MessageState
import com.dealsmasr.app.ui.components.NetImage
import com.dealsmasr.app.ui.components.ProductCard
import com.dealsmasr.app.ui.deals.SkeletonGrid
import com.dealsmasr.app.ui.product.openUrl
import kotlinx.coroutines.launch

/** Side-menu categories. Must match the server's audit list (supabase/functions/scan/audit.ts). */
val CATEGORY_MENU: List<Pair<String, List<String>>> = listOf(
    "ملابس" to listOf("رجالي", "حريمي", "أطفال", "أحذية", "شنط واكسسوارات"),
    "إلكترونيات" to listOf("موبايلات وتابلت", "لابتوب وكمبيوتر", "سماعات وصوتيات", "تلفزيونات وشاشات", "ألعاب فيديو", "كاميرات", "اكسسوارات وشواحن", "ساعات ذكية"),
    "أجهزة منزلية" to listOf("كبيرة", "صغيرة"),
    "البيت والمطبخ" to listOf("مطبخ", "ديكور ومفروشات", "أدوات وعدد"),
    "جمال وعناية" to listOf("مكياج", "عطور", "عناية شخصية"),
    "صحة ورياضة" to listOf("رياضة", "صحة ومكملات"),
    "أطفال ولعب" to listOf("لعب", "مستلزمات أطفال"),
    "سوبرماركت" to listOf("أكل ومشروبات", "منظفات"),
    "عربيات" to listOf("اكسسوارات عربيات"),
    "كتب ومكتبة" to listOf("كتب", "أدوات مكتبية"),
    "حيوانات أليفة" to listOf("مستلزمات"),
)

@Composable
fun AppDrawerContent(onCategory: (String) -> Unit, onCrazy: (Int) -> Unit, onFavorites: () -> Unit, onSettings: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Text("الأقسام", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        NavigationDrawerItem(label = { Text("🔥 عروض مجنونة") }, selected = false, onClick = { onCrazy(0) },
            icon = { Icon(Icons.Outlined.Bolt, null) }, modifier = Modifier.padding(horizontal = 12.dp))
        NavigationDrawerItem(label = { Text("🎟️ كوبونات") }, selected = false, onClick = { onCrazy(1) },
            icon = { Icon(Icons.Outlined.LocalOffer, null) }, modifier = Modifier.padding(horizontal = 12.dp))
        NavigationDrawerItem(label = { Text("1+1 اشتري واحد والتاني هدية") }, selected = false, onClick = { onCrazy(2) },
            icon = { Icon(Icons.Outlined.LocalOffer, null) }, modifier = Modifier.padding(horizontal = 12.dp))
        NavigationDrawerItem(label = { Text("🍔 مطاعم وأكل") }, selected = false, onClick = { onCrazy(3) },
            icon = { Icon(Icons.Outlined.LocalOffer, null) }, modifier = Modifier.padding(horizontal = 12.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        CATEGORY_MENU.forEach { (main, subs) ->
            NavigationDrawerItem(
                label = { Text(main, fontWeight = FontWeight.SemiBold) }, selected = open == main,
                onClick = { open = if (open == main) null else main },
                icon = { Icon(Icons.Outlined.LocalOffer, null) },
                badge = { Icon(if (open == main) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            if (open == main) {
                TextButton(onClick = { onCategory(main) }, modifier = Modifier.padding(start = 48.dp)) { Text("كل $main") }
                subs.forEach { sub ->
                    TextButton(onClick = { onCategory("$main/$sub") }, modifier = Modifier.padding(start = 48.dp)) { Text(sub) }
                }
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        NavigationDrawerItem(label = { Text("المفضلة") }, selected = false, onClick = onFavorites,
            icon = { Icon(Icons.Outlined.FavoriteBorder, null) }, modifier = Modifier.padding(horizontal = 12.dp))
        NavigationDrawerItem(label = { Text("الإعدادات") }, selected = false, onClick = onSettings,
            icon = { Icon(Icons.Outlined.Settings, null) }, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(category: String, onBack: () -> Unit, onProduct: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val favs by c.deals.favoriteIds.collectAsState(initial = emptyList())
    val items = remember(category) { mutableStateListOf<DealDto>() }
    var page by remember(category) { mutableStateOf(0) }
    var loading by remember(category) { mutableStateOf(true) }
    var end by remember(category) { mutableStateOf(false) }
    var error by remember(category) { mutableStateOf<String?>(null) }
    suspend fun load() {
        loading = true; error = null
        runCatching { c.deals.categoryDeals(category, page) }
            .onSuccess { items.addAll(it.filter { d -> items.none { x -> x.id == d.id } }); end = it.size < 60; page++ }
            .onFailure { error = "مقدرناش نجيب العروض، اتأكد من النت" }
        loading = false
    }
    LaunchedEffect(category) { load() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(category.replace("/", " · ")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            when {
                items.isEmpty() && loading -> SkeletonGrid()
                items.isEmpty() && error != null -> ErrorState(error!!, { scope.launch { load() } })
                items.isEmpty() -> MessageState(Icons.Outlined.LocalOffer, "مفيش عروض في القسم ده دلوقتي",
                    "الذكاء الاصطناعي بيصنّف العروض أول بأول، ارجع بعد شوية")
                else -> LazyVerticalGrid(GridCells.Adaptive(160.dp), contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(items, key = { it.id }) { d ->
                        ProductCard(d, d.id in favs, { scope.launch { c.deals.toggleFavorite(d, d.id in favs) } }, { onProduct(d.id) })
                    }
                    if (!end) item(span = { GridItemSpan(maxLineSpan) }) {
                        LaunchedEffect(items.size) { if (!loading) load() }
                        Text("بنحمّل كمان…", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Filters over the channel posts (the channel itself is the source; codes can't be test-used without ordering). */
val CRAZY_FILTERS: List<Pair<String, List<String>>> = listOf(
    "الكل" to emptyList(),
    "🎟️ كوبونات" to listOf("كوبون", "كود", "code", "coupon", "promo"),
    "1+1" to listOf("1+1", "١+١", "اشتري 1", "اشتري واحد", "buy 1", "buy one", "الثانية مجان", "التانية مجان", "الثاني مجان"),
    "🍔 مطاعم وأكل" to listOf("مطعم", "طلبات", "talabat", "بريدفاست", "breadfast", "بيتزا", "برجر", "وجبة", "كنتاكي", "ماكدونالدز", "مشويات", "فطار"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrazyDealsScreen(initialFilter: Int = 0, onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(initialFilter.coerceIn(0, CRAZY_FILTERS.size - 1)) }
    val posts = remember(filter) { mutableStateListOf<DealsRepository.TgPost>() }
    var loading by remember(filter) { mutableStateOf(true) }
    var end by remember(filter) { mutableStateOf(false) }
    var error by remember(filter) { mutableStateOf<String?>(null) }
    suspend fun load() {
        loading = true; error = null
        runCatching { c.deals.crazyPosts(posts.lastOrNull()?.id, CRAZY_FILTERS[filter].second) }
            .onSuccess { posts.addAll(it); end = it.size < 40 }
            .onFailure { error = "مقدرناش نجيب العروض، اتأكد من النت" }
        loading = false
    }
    LaunchedEffect(filter) { load() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("🔥 عروض مجنونة") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad0 ->
      Column(Modifier.padding(pad0)) {
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(CRAZY_FILTERS.size) { i ->
                androidx.compose.material3.FilterChip(selected = filter == i, onClick = { filter = i }, label = { Text(CRAZY_FILTERS[i].first) })
            }
        }
        val pad = PaddingValues(0.dp)
        when {
            posts.isEmpty() && loading -> Column(Modifier.padding(pad)) { SkeletonGrid() }
            posts.isEmpty() && error != null -> ErrorState(error!!, { scope.launch { load() } }, Modifier.padding(pad))
            posts.isEmpty() -> MessageState(Icons.Outlined.Bolt, "لسه مفيش عروض هنا", "السيرفر بيجيب عروض القناة كل 3 دقايق")
            else -> LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(posts, key = { it.id }) { p ->
                    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = p.url != null) { p.url?.let { openUrl(ctx, it) } }) {
                        p.imageUrl?.let { NetImage(it, Modifier.fillMaxWidth().aspectRatio(1.6f), ContentScale.Crop) }
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (p.price != null && p.oldPrice != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(Money.egp(p.price), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.padding(4.dp))
                                Text(Money.egp(p.oldPrice), textDecoration = TextDecoration.LineThrough,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Spacer(Modifier.padding(4.dp))
                                Text("-${(100 * (p.oldPrice - p.price) / p.oldPrice).toInt()}%", fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Text(p.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                            if (p.url != null) Text("افتح العرض ←", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                if (!end) item {
                    LaunchedEffect(posts.size) { if (!loading) load() }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
      }
    }
}

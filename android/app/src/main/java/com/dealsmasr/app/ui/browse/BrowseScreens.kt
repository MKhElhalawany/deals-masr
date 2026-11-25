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
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.LocalLaundryService
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Toys
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
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
import com.dealsmasr.app.ui.i18n.Text
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

internal val CATEGORY_ICONS: Map<String, androidx.compose.ui.graphics.vector.ImageVector> = mapOf(
    "ملابس" to Icons.Outlined.Checkroom, "إلكترونيات" to Icons.Outlined.Devices, "أجهزة منزلية" to Icons.Outlined.LocalLaundryService,
    "البيت والمطبخ" to Icons.Outlined.Kitchen, "جمال وعناية" to Icons.Outlined.Face, "صحة ورياضة" to Icons.Outlined.FitnessCenter,
    "أطفال ولعب" to Icons.Outlined.Toys, "سوبرماركت" to Icons.Outlined.ShoppingCart, "عربيات" to Icons.Outlined.DirectionsCar,
    "كتب ومكتبة" to Icons.AutoMirrored.Outlined.MenuBook, "حيوانات أليفة" to Icons.Outlined.Pets,
)
internal val CATEGORY_COLORS: Map<String, Color> = mapOf(
    "ملابس" to Color(0xFFB0185E), "إلكترونيات" to Color(0xFF1E5BD8), "أجهزة منزلية" to Color(0xFF00897B), "البيت والمطبخ" to Color(0xFFE07B00),
    "جمال وعناية" to Color(0xFFD81B60), "صحة ورياضة" to Color(0xFF2E7D32), "أطفال ولعب" to Color(0xFF8E24AA), "سوبرماركت" to Color(0xFF6D4C41),
    "عربيات" to Color(0xFF37474F), "كتب ومكتبة" to Color(0xFF5D4037), "حيوانات أليفة" to Color(0xFFF4511E),
)

/** Keyword groups for the channel posts (they carry no category). */
val CRAZY_CATS: List<Pair<String, List<String>>> = listOf(
    "ملابس" to listOf("تيشيرت", "قميص", "بنطلون", "فستان", "جاكيت", "هودي", "ملابس", "حذاء", "جزمة", "كوتشي", "shirt", "dress", "jeans", "shoes", "sneaker"),
    "إلكترونيات" to listOf("موبايل", "سماعة", "شاحن", "لابتوب", "شاشة", "ساعة", "iphone", "samsung", "xiaomi", "airpods", "laptop", "tv", "باور بانك"),
    "سوبرماركت" to listOf("زيت", "سكر", "رز", "شاي", "قهوة", "مكرونة", "لبن", "جبنة", "شوكولاتة", "منظف", "مسحوق", "بريدفاست", "سوبرماركت"),
    "جمال وعناية" to listOf("كريم", "شامبو", "برفان", "عطر", "مكياج", "سيروم", "واقي شمس", "perfume", "serum"),
    "البيت والمطبخ" to listOf("طقم", "حلة", "طاسة", "مفرش", "ملاية", "خلاط", "قلاية", "مكنسة", "غسالة", "ثلاجة", "بوتاجاز", "تيفال"),
    "أطفال" to listOf("أطفال", "اطفال", "بيبي", "حفاض", "لعبة", "لعب", "baby", "kids", "toy"),
)

@Composable
fun CategoryChips(names: List<String>, selected: String?, onSelect: (String?) -> Unit, allLabel: String = "الكل") {
    androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { androidx.compose.material3.FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) }) }
        items(names.size) { i ->
            val name = names[i]
            androidx.compose.material3.FilterChip(selected = selected == name, onClick = { onSelect(if (selected == name) null else name) },
                label = { Text(name) },
                leadingIcon = CATEGORY_ICONS[name]?.let { ic -> { Icon(ic, null, Modifier.size(18.dp), tint = CATEGORY_COLORS[name] ?: Color.Unspecified) } })
        }
    }
}

@Composable
fun AppDrawerContent(onCategory: (String) -> Unit, onFavorites: () -> Unit, onSettings: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        Text("الأقسام", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        CATEGORY_MENU.forEach { (main, subs) ->
            NavigationDrawerItem(
                label = { Text(main, fontWeight = FontWeight.SemiBold) }, selected = open == main,
                onClick = { open = if (open == main) null else main },
                icon = {
                    androidx.compose.foundation.layout.Box(Modifier.size(36.dp).background(CATEGORY_COLORS[main] ?: Color(0xFF546E7A), CircleShape),
                        contentAlignment = Alignment.Center) { Icon(CATEGORY_ICONS[main] ?: Icons.Outlined.LocalOffer, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
                },
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
fun CategoryScreen(category: String, onBack: () -> Unit, onProduct: (String) -> Unit, onOpenCategory: (String) -> Unit = {}) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val favs by c.deals.favoriteIds.collectAsState(initial = emptyList())
    var query by rememberSaveable(category) { mutableStateOf("") }
    var applied by rememberSaveable(category) { mutableStateOf("") }
    var stores by remember(category) { mutableStateOf(setOf<String>()) }
    var minPct by rememberSaveable(category) { mutableStateOf(0) }
    var sort by rememberSaveable(category) { mutableStateOf("discount") }
    val key = listOf(category, applied, stores.sorted().joinToString(), minPct, sort).joinToString("|")
    val items = remember(key) { mutableStateListOf<DealDto>() }
    var page by remember(key) { mutableStateOf(0) }
    var loading by remember(key) { mutableStateOf(true) }
    var end by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    suspend fun load() {
        loading = true; error = null
        runCatching { c.deals.categoryDeals(category, page, applied, stores, minPct, sort) }
            .onSuccess { items.addAll(it.filter { d -> items.none { x -> x.id == d.id } }); end = it.size < 60; page++ }
            .onFailure { error = "مقدرناش نجيب العروض، اتأكد من النت" }
        loading = false
    }
    LaunchedEffect(key) { load() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(category.replace("/", " · ")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            CATEGORY_MENU.firstOrNull { it.first == category.substringBefore('/') }?.let { (main, subs) ->
                CategoryChips(subs, category.substringAfter('/', "").ifBlank { null }, { sub -> onOpenCategory(if (sub == null) main else "$main/$sub") }, "كل $main")
            }
            androidx.compose.material3.OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                placeholder = { Text("دوّر في ${category.substringAfter('/')}…") }, singleLine = true, shape = RoundedCornerShape(24.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { applied = query }))
            androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    androidx.compose.material3.FilterChip(selected = sort != "discount", onClick = {
                        sort = when (sort) { "discount" -> "price_asc"; "price_asc" -> "price_desc"; "price_desc" -> "newest"; else -> "discount" }
                    }, label = { Text(when (sort) { "price_asc" -> "الأرخص"; "price_desc" -> "الأغلى"; "newest" -> "الأحدث"; else -> "أعلى خصم" }) })
                }
                items(listOf(30, 50, 70).size) { i ->
                    val p = listOf(30, 50, 70)[i]
                    androidx.compose.material3.FilterChip(selected = minPct == p, onClick = { minPct = if (minPct == p) 0 else p }, label = { Text("+$p%") })
                }
                items(com.dealsmasr.app.ui.deals.STORES.size) { i ->
                    val (id, name) = com.dealsmasr.app.ui.deals.STORES[i]
                    androidx.compose.material3.FilterChip(selected = id in stores, onClick = { stores = if (id in stores) stores - id else stores + id },
                        label = { Text(name) })
                }
            }
            when {
                items.isEmpty() && loading -> SkeletonGrid()
                items.isEmpty() && error != null -> ErrorState(error!!, { scope.launch { load() } })
                items.isEmpty() -> MessageState(Icons.Outlined.LocalOffer, "مفيش عروض في القسم ده دلوقتي",
                    "أول ما ينزل عرض جديد هيظهر هنا")
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
    "🎁 Buy 1 Get 1" to listOf("1+1", "١+١", "اشتري 1", "اشتري واحد", "buy 1", "buy one", "الثانية مجان", "التانية مجان", "الثاني مجان"),
    "🍔 مطاعم وخروجات" to listOf("مطعم", "طلبات", "talabat", "بريدفاست", "breadfast", "بيتزا", "برجر", "وجبة", "كنتاكي", "ماكدونالدز", "مشويات", "فطار",
        "خروجة", "خروجات", "سينما", "كافيه", "ملاهي", "تذاكر", "رحلة"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrazyDealsScreen(initialFilter: Int = 0, onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val filter = initialFilter.coerceIn(0, CRAZY_FILTERS.size - 1)
    var grid by rememberSaveable { mutableStateOf(true) }   // grid (3–4 per row) or one under the other
    var cat by rememberSaveable { mutableStateOf<String?>(null) }
    val k = "$filter|$cat"
    val posts = remember(k) { mutableStateListOf<DealsRepository.TgPost>() }
    var loading by remember(k) { mutableStateOf(true) }
    var end by remember(k) { mutableStateOf(false) }
    var error by remember(k) { mutableStateOf<String?>(null) }
    suspend fun load() {
        loading = true; error = null
        runCatching { c.deals.crazyPosts(posts.lastOrNull()?.postedIso, CRAZY_FILTERS[filter].second, CRAZY_CATS.firstOrNull { it.first == cat }?.second.orEmpty()) }
            .onSuccess { new -> posts.addAll(new.filter { n -> posts.none { it.key == n.key } }); end = new.size < 40 }
            .onFailure { error = "مقدرناش نجيب العروض، اتأكد من النت" }
        loading = false
    }
    LaunchedEffect(k) { load() }
    Scaffold(topBar = {
        TopAppBar(title = { Text(if (filter == 0) "🔥 عروض مطرقعة" else CRAZY_FILTERS[filter].first) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
            actions = {
                IconButton(onClick = { grid = !grid }) {
                    Icon(if (grid) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.GridView, if (grid) "تحت بعض" else "شبكة")
                }
            })
    }) { pad0 ->
      Column(Modifier.padding(pad0)) {
        CategoryChips(CRAZY_CATS.map { it.first }, cat, { cat = it })
        val pad = PaddingValues(0.dp)
        when {
            posts.isEmpty() && loading -> Column(Modifier.padding(pad)) { SkeletonGrid() }
            posts.isEmpty() && error != null -> ErrorState(error!!, { scope.launch { load() } }, Modifier.padding(pad))
            posts.isEmpty() -> MessageState(Icons.Outlined.Bolt, "لسه مفيش عروض هنا", "أول ما ينزل عرض جديد هيظهر هنا")
            else -> LazyVerticalGrid(
                columns = if (grid) GridCells.Adaptive(112.dp) else GridCells.Fixed(1),
                modifier = Modifier.padding(pad), contentPadding = PaddingValues(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(posts, key = { it.key }) { p -> CrazyCard(p, compact = grid) { p.url?.let { openUrl(ctx, it) } } }
                if (!end) item(span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(posts.size) { if (!loading) load() }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
      }
}

@Composable
private fun CrazyCard(p: DealsRepository.TgPost, compact: Boolean, onOpen: () -> Unit) {
    Card(shape = RoundedCornerShape(if (compact) 12.dp else 16.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = p.url != null, onClick = onOpen)) {
        p.imageUrl?.let { NetImage(it, Modifier.fillMaxWidth().aspectRatio(if (compact) 1f else 1.6f), ContentScale.Crop) }
        Column(Modifier.padding(if (compact) 6.dp else 12.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
            if (p.price != null && p.oldPrice != null) {
                Text(Money.egp(p.price), fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary,
                    style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleMedium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Money.egp(p.oldPrice), textDecoration = TextDecoration.LineThrough, maxLines = 1,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.padding(2.dp))
                    Text("-${(100 * (p.oldPrice - p.price) / p.oldPrice).toInt()}%", fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(p.text, style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodyMedium,
                maxLines = if (compact) 3 else 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (p.url != null && !compact) Text("افتح العرض ←", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
        }
    }
}

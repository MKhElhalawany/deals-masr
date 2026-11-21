package com.dealsmasr.app.ui.product

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.data.remote.PricePointDto
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.components.DiscountBadge
import com.dealsmasr.app.ui.components.ErrorState
import com.dealsmasr.app.ui.components.MessageState
import com.dealsmasr.app.ui.components.NetImage
import com.dealsmasr.app.ui.components.PriceBlock
import com.dealsmasr.app.ui.components.Shimmer
import com.dealsmasr.app.ui.components.StoreLabel
import com.dealsmasr.app.ui.components.relativeTime
import com.dealsmasr.app.ui.components.userMessage
import kotlinx.coroutines.launch

fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    ctx.startActivity(Intent.createChooser(i, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductScreen(id: String, onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var deal by remember { mutableStateOf<DealDto?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val favIds by c.deals.favoriteIds.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)

    LaunchedEffect(id, reload) {
        error = null
        runCatching { c.deals.product(id) }.onSuccess { deal = it }.onFailure { error = it.userMessage() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("تفاصيل العرض") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } },
            actions = {
                deal?.let { d ->
                    IconButton(onClick = { shareText(ctx, "${d.title}\n${Money.egp(d.currentPrice)} بدل ${Money.egp(d.originalPrice)} (-${d.discountPct.toInt()}%)\n${d.url}") }) {
                        Icon(Icons.Outlined.Share, "مشاركة")
                    }
                    val fav = d.id in favIds
                    IconButton(onClick = { scope.launch { c.deals.toggleFavorite(d, fav) } }) {
                        Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "مفضلة",
                            tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    }
                }
            },
        )
    }) { pad ->
        val d = deal
        when {
            d == null && error != null -> ErrorState(error!!, { reload++ }, Modifier.padding(pad))
            d == null -> Column(Modifier.padding(pad).padding(16.dp)) {
                Shimmer(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
            }
            else -> Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val tier = DealMath.classify(d.discountPct, settings?.tiers ?: DealMath.DEFAULT_TIERS)?.key
                Card(shape = RoundedCornerShape(20.dp)) {
                    NetImage(d.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f), ContentScale.Fit)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StoreLabel(d.marketplaceName)
                    DiscountBadge(d.discountPct, tier, large = true)
                    if (!d.active) Text("انتهى العرض", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
                Text(d.title, style = MaterialTheme.typography.titleMedium)
                PriceBlock(d.currentPrice, d.originalPrice, d.savings)
                d.rating?.let { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = Color(0xFFF5A623), modifier = Modifier.size(18.dp))
                        Text(" ${"%.1f".format(r)}" + (d.ratingCount?.let { " ($it تقييم)" } ?: ""))
                    }
                }
                Text("آخر تحقق من السعر: ${relativeTime(d.lastChecked)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { openUrl(ctx, d.url) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null)
                    Spacer(Modifier.width(8.dp))
                    Text("اذهب للعرض على ${d.marketplaceName}")
                }
                if (d.history.size >= 2) {
                    Text("تاريخ السعر", style = MaterialTheme.typography.titleMedium)
                    PriceHistoryChart(d.history, Modifier.fillMaxWidth().height(140.dp))
                    val low = d.history.minOf { it.currentPrice }
                    Text("أقل سعر مسجل: ${Money.egp(low)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("الأسعار من المتجر مباشرة وقد تتغير في أي وقت. تأكد من السعر النهائي على صفحة المتجر.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PriceHistoryChart(points: List<PricePointDto>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Canvas(modifier) {
        val prices = points.map { it.currentPrice }
        val min = prices.min(); val max = prices.max()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val t0 = points.first().checkedAt; val t1 = points.last().checkedAt
        val tSpan = (t1 - t0).takeIf { it > 0 } ?: 1L
        fun x(t: Long) = ((t - t0).toFloat() / tSpan) * size.width
        fun y(p: Double) = size.height - ((p - min) / span).toFloat() * (size.height * 0.85f) - size.height * 0.075f
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height))
        val path = Path()
        points.forEachIndexed { i, p ->
            // step line: price holds until the next change
            if (i == 0) path.moveTo(x(p.checkedAt), y(p.currentPrice))
            else { path.lineTo(x(p.checkedAt), y(points[i - 1].currentPrice)); path.lineTo(x(p.checkedAt), y(p.currentPrice)) }
        }
        path.lineTo(size.width, y(points.last().currentPrice))
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(onBack: () -> Unit, onProduct: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val favs by c.deals.favorites.collectAsStateWithLifecycle(emptyList())
    Scaffold(topBar = {
        TopAppBar(title = { Text("المفضلة") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        if (favs.isEmpty()) {
            MessageState(Icons.Outlined.FavoriteBorder, "مفيش منتجات في المفضلة",
                "اضغط على القلب في أي عرض علشان نتابع سعره ونبلغك لو نزل", modifier = Modifier.padding(pad))
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(favs, key = { it.productId }) { f ->
                    Card(Modifier.fillMaxWidth().clickable { onProduct(f.productId) }) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            NetImage(f.imageUrl, Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)), ContentScale.Fit)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(f.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(Money.egp(f.currentPrice), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(6.dp))
                                    Text(Money.egp(f.originalPrice), textDecoration = TextDecoration.LineThrough,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                                Text(if (f.active) "${f.marketplaceName} · -${f.discountPct.toInt()}% · ${relativeTime(f.lastChecked)}"
                                    else "انتهى العرض", style = MaterialTheme.typography.labelSmall,
                                    color = if (f.active) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            }
                            IconButton(onClick = { scope.launch { c.deals.removeFavorite(f.productId) } }) { Icon(Icons.Outlined.Delete, "حذف") }
                        }
                    }
                }
            }
        }
    }
}

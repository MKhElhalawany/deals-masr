package com.dealsmasr.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import com.dealsmasr.app.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.ui.theme.tierColor

fun relativeTime(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochSeconds <= 0) return "—"
    val mins = (nowMillis / 1000 - epochSeconds) / 60
    return when {
        mins < 1 -> "الآن"
        mins < 60 -> "منذ $mins دقيقة"
        mins < 60 * 24 -> "منذ ${mins / 60} ساعة"
        else -> "منذ ${mins / (60 * 24)} يوم"
    }
}

@Composable
fun NetImage(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    SubcomposeAsyncImage(
        model = url,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
        loading = { Shimmer(Modifier.fillMaxSize()) },
        error = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ImageNotSupported, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
    )
}

@Composable
fun DiscountBadge(pct: Double, tier: String?, modifier: Modifier = Modifier, large: Boolean = false, atLeast: Boolean = false) {
    Surface(color = tierColor(tier), shape = RoundedCornerShape(8.dp), modifier = modifier) {
        Text(
            text = "-${pct.toInt()}%" + (if (atLeast) "+" else "") + if (tier == "hot" || tier == "super" || tier == "mega") " 🔥" else "",
            color = Color.White,
            style = if (large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun PriceBlock(current: Double, original: Double, savings: Double, compact: Boolean = false, minPct: Double? = null) {
    Column {
        Text(Money.egp(current), style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        if (original <= current) {   // store shows a discount but no crossed-out price (Amazon)
            Text("خصم ${(minPct ?: 10.0).toInt()}% أو أكتر", style = MaterialTheme.typography.labelSmall,
                color = com.dealsmasr.app.ui.theme.Success, fontWeight = FontWeight.SemiBold)
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Money.egp(original), style = MaterialTheme.typography.bodySmall, textDecoration = TextDecoration.LineThrough,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text("وفّر ${Money.plain(savings)}", style = MaterialTheme.typography.labelSmall, color = com.dealsmasr.app.ui.theme.Success,
                fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun ProductCard(
    deal: DealDto,
    isFavorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box {
            NetImage(deal.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                contentScale = ContentScale.Fit)
            DiscountBadge(deal.discountPct, deal.tier, Modifier.align(Alignment.TopStart).padding(8.dp), atLeast = !deal.hasOriginal)
            IconButton(onClick = onFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) {
                    Icon(if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, contentDescription = "مفضلة",
                        tint = if (isFavorite) MaterialTheme.colorScheme.primary else Color.DarkGray, modifier = Modifier.size(18.dp))
                }
            }
        }
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StoreLabel(deal.marketplaceName)
            Text(deal.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(40.dp))
            PriceBlock(deal.currentPrice, deal.originalPrice, deal.savings, compact = true, minPct = deal.discountPct)
            Row(verticalAlignment = Alignment.CenterVertically) {
                deal.rating?.let {
                    Icon(Icons.Filled.Star, null, tint = Color(0xFFF5A623), modifier = Modifier.size(14.dp))
                    Text(" ${"%.1f".format(it)}", style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                }
                Text("تحقق ${relativeTime(deal.lastChecked)}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
fun StoreLabel(name: String) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
        Text(name, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun Shimmer(modifier: Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val a by t.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a")
    Box(modifier.alpha(a).background(MaterialTheme.colorScheme.surfaceVariant))
}

@Composable
fun SkeletonCard(modifier: Modifier = Modifier) {
    Card(modifier, shape = RoundedCornerShape(16.dp)) {
        Shimmer(Modifier.fillMaxWidth().aspectRatio(1f))
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Shimmer(Modifier.fillMaxWidth(0.4f).height(12.dp).clip(RoundedCornerShape(4.dp)))
            Shimmer(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)))
            Shimmer(Modifier.fillMaxWidth(0.6f).height(18.dp).clip(RoundedCornerShape(4.dp)))
        }
    }
}

@Composable
fun MessageState(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(4.dp))
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) =
    MessageState(Icons.Outlined.ErrorOutline, "حصلت مشكلة", message, "حاول تاني", onRetry, modifier)

@Composable
fun NoServerState(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) =
    MessageState(Icons.Outlined.CloudOff, "لم يتم ربط التطبيق بخادم العروض",
        "العروض تأتي فقط من مصادر رسمية عبر خادم التطبيق. أضف عنوان الخادم من الإعدادات.",
        "الإعدادات", onOpenSettings, modifier)

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null, color: Color = MaterialTheme.colorScheme.onBackground) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = color, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
    }
}

/** Human-readable error for UI. */
fun Throwable.userMessage(): String = when (this) {
    is com.dealsmasr.app.data.remote.NoServerException -> message ?: "لم يتم ضبط الخادم"
    is java.net.UnknownHostException, is java.net.ConnectException -> "تعذر الاتصال بالخادم. تأكد من الإنترنت."
    is java.net.SocketTimeoutException -> "الخادم لم يستجب. حاول مرة أخرى."
    is retrofit2.HttpException -> "خطأ من الخادم (${code()})"
    else -> message ?: "خطأ غير متوقع"
}

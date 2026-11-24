package com.dealsmasr.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dealsmasr.app.DealsApp
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.ui.components.NetImage
import com.dealsmasr.app.ui.components.userMessage
import kotlinx.coroutines.launch

private data class Msg(val mine: Boolean, val text: String, val products: List<DealDto> = emptyList(),
    val live: List<com.dealsmasr.app.data.DealsRepository.LiveOffer> = emptyList())

private val SUGGESTIONS = listOf(
    "رشحلي موبايل كويس تحت 15000",
    "عايز سماعة بلوتوث تحت 1000",
    "أحسن قلاية هوائية للعيلة؟",
    "الفرق بين OLED و QLED إيه؟",
)

/** Shopping assistant: answers in Egyptian Arabic using our real deals (Gemini on the server). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(onProduct: (String) -> Unit) {
    val c = (LocalContext.current.applicationContext as DealsApp).container
    val scope = rememberCoroutineScope()
    val msgs = remember { mutableStateListOf(Msg(false, "أهلاً! أنا مساعد عروض مصر 👋\nقولي بتدور على إيه وميزانيتك كام، وأنا أرشحلك من العروض الحقيقية على أمازون ونون وتاون تيم، وأجاوبك على أي سؤال عن المنتجات والمقاسات والمواصفات.")) }
    var input by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(msgs.size) { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        msgs.add(Msg(true, t)); input = ""; busy = true
        scope.launch {
            runCatching { c.deals.chat(msgs.drop(1).map { it.mine to it.text }) }
                .onSuccess { msgs.add(Msg(false, it.text, it.products, it.live)) }
                .onFailure { msgs.add(Msg(false, "معلش، حصلت مشكلة: ${it.userMessage()}")) }
            busy = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("المساعد الذكي ✨", fontWeight = FontWeight.Bold) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(msgs) { m -> Bubble(m, onProduct) }
                if (busy) item { Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("بيفكر…")
                } }
                if (msgs.size == 1) item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { send(s) }, label = { Text(s) }) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("اسأل عن أي منتج…") }, shape = RoundedCornerShape(24.dp), maxLines = 4)
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { send(input) }, enabled = !busy && input.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, "إرسال")
                }
            }
        }
    }
}

@Composable
private fun Bubble(m: Msg, onProduct: (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (m.mine) Alignment.Start else Alignment.End) {
        Surface(
            color = if (m.mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(m.text, Modifier.padding(12.dp), color = if (m.mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
        }
        if (m.products.isNotEmpty()) {
            Spacer(Modifier.padding(3.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(m.products.size) { i ->
                    val d = m.products[i]
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
                        modifier = Modifier.width(150.dp).clickable { onProduct(d.id) }) {
                        Column(Modifier.padding(8.dp)) {
                            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
                                NetImage(d.imageUrl, Modifier.fillMaxWidth().size(110.dp))
                            }
                            Text("[${i + 1}] ${d.title}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Text(Money.egp(d.currentPrice), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("${d.marketplaceName} · -${d.discountPct.toInt()}%", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (m.live.isNotEmpty()) {
            val ctx = LocalContext.current
            Spacer(Modifier.padding(3.dp))
            Text("أسعار لحظية من المتاجر:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(m.live.size) { i ->
                    val o = m.live[i]
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp,
                        modifier = Modifier.width(150.dp).clickable { com.dealsmasr.app.ui.product.openUrl(ctx, o.url) }) {
                        Column(Modifier.padding(8.dp)) {
                            NetImage(o.imageUrl, Modifier.fillMaxWidth().size(90.dp))
                            Text("[${o.label}] ${o.title}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                            Text(Money.egp(o.price), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text(o.store, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

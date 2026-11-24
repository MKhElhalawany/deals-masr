package com.dealsmasr.app.ui.orders

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.dealsmasr.app.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.random.Random

private val CONFETTI = listOf(Color(0xFFE4572E), Color(0xFFFFC857), Color(0xFF2EC4B6), Color(0xFF6A4C93), Color(0xFF25D366))

/** Full-screen "order confirmed" moment: check mark draws itself, confetti falls, the parcel sets off. */
@Composable
fun ConfirmedCelebration(minDays: Int, maxDays: Int, onDone: () -> Unit) {
    val circle = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val fall = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { fall.animateTo(1f, tween(2600, easing = LinearEasing)) }
        circle.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        check.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    val pieces = remember {
        List(60) { Triple(Random.nextFloat(), Random.nextFloat() * 0.6f + 0.4f, Random.nextInt(CONFETTI.size)) to Random.nextFloat() * 360f }
    }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDone() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            pieces.forEach { (p, angle) ->
                val (x, speed, c) = p
                val y = (fall.value * speed * 1.4f - 0.2f) * size.height
                rotate(angle + fall.value * 540f, Offset(x * size.width, y)) {
                    drawRect(CONFETTI[c], topLeft = Offset(x * size.width, y), size = androidx.compose.ui.geometry.Size(14f, 24f))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.padding(28.dp)) {
            Box(Modifier.size(140.dp).scale(circle.value), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(140.dp)) {
                    drawCircle(Color(0xFF25D366))
                    val path = Path().apply {
                        moveTo(size.width * 0.28f, size.height * 0.52f)
                        lineTo(size.width * 0.44f, size.height * 0.67f)
                        lineTo(size.width * 0.73f, size.height * 0.36f)
                    }
                    val measure = PathMeasure().apply { setPath(path, false) }
                    val partial = Path()
                    measure.getSegment(0f, measure.length * check.value, partial, true)
                    drawPath(partial, Color.White, style = Stroke(width = 14f, cap = StrokeCap.Round))
                }
            }
            Text("تم تأكيد طلبك 🎉", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Black)
            Text("طلبنا منتجاتك من SHEIN، وهتوصلك لحد باب البيت خلال $minDays–$maxDays يوم",
                color = Color.White.copy(alpha = 0.9f), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            Button(onClick = onDone) { Text("تابع طلبي") }
        }
    }
}

/** Parcel journey: warehouse → plane → truck → home, with the parcel moving to the current progress. */
@Composable
fun ShipmentJourney(progress: Float, modifier: Modifier = Modifier) {
    val bob by rememberInfiniteTransition(label = "bob").animateFloat(
        -3f, 3f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "y")
    val animated = remember { Animatable(0f) }
    LaunchedEffect(progress) { animated.animateTo(progress.coerceIn(0f, 1f), tween(1400, easing = FastOutSlowInEasing)) }
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(56.dp)) {
            val w = maxWidth
            Canvas(Modifier.fillMaxWidth().height(56.dp)) {
                val y = size.height * 0.75f
                drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = 10f, cap = StrokeCap.Round)
                // RTL: journey runs from right (warehouse) to left (home)
                drawLine(primary, Offset(size.width, y), Offset(size.width * (1 - animated.value), y), strokeWidth = 10f, cap = StrokeCap.Round)
            }
            Box(
                Modifier.absoluteOffset(x = (w - 34.dp) * (1 - animated.value), y = (8 + bob).dp)
                    .size(34.dp).background(primary, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.LocalShipping, null, tint = Color.White, modifier = Modifier.size(22.dp)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(Icons.Outlined.Inventory2 to "SHEIN", Icons.Outlined.Flight to "شحن دولي", Icons.Outlined.LocalShipping to "في مصر",
                Icons.Outlined.Home to "بيتك").forEach { (icon, label) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

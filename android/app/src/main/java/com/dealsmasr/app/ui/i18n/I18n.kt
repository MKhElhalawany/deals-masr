package com.dealsmasr.app.ui.i18n

import android.content.Context
import android.util.Log
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/** "ar" or "en" — chosen in Settings. */
val LocalLang = staticCompositionLocalOf { "ar" }

private val ARABIC = Regex("[\\u0600-\\u06FF]")

/** Hand-written English for the main screens; everything else goes through the on-device translator. */
private val EN = mapOf(
    "الرئيسية" to "Home", "العروض" to "Deals", "البحث" to "Search", "المساعد" to "Assistant", "الإعدادات" to "Settings",
    "المفضلة" to "Favorites", "الأقسام" to "Categories", "رجوع" to "Back", "الكل" to "All", "كل المتاجر" to "All stores",
    "عروض مصر" to "Deals Masr", "🔥 عروض مطرقعة" to "🔥 Hot drops", "🎟️ كوبونات" to "🎟️ Coupons",
    "1+1 اشتري واحد والتاني هدية" to "Buy 1 get 1", "🍔 مطاعم وأكل" to "🍔 Restaurants & food",
    "ملابس" to "Clothing", "رجالي" to "Men", "حريمي" to "Women", "أطفال" to "Kids", "أحذية" to "Shoes", "شنط واكسسوارات" to "Bags & accessories",
    "إلكترونيات" to "Electronics", "موبايلات وتابلت" to "Phones & tablets", "لابتوب وكمبيوتر" to "Laptops & computers",
    "سماعات وصوتيات" to "Headphones & audio", "تلفزيونات وشاشات" to "TVs & monitors", "ألعاب فيديو" to "Video games",
    "كاميرات" to "Cameras", "اكسسوارات وشواحن" to "Accessories & chargers", "ساعات ذكية" to "Smart watches",
    "أجهزة منزلية" to "Home appliances", "كبيرة" to "Large", "صغيرة" to "Small", "البيت والمطبخ" to "Home & kitchen",
    "مطبخ" to "Kitchen", "ديكور ومفروشات" to "Decor & furnishing", "أدوات وعدد" to "Tools", "جمال وعناية" to "Beauty & care",
    "مكياج" to "Makeup", "عطور" to "Perfumes", "عناية شخصية" to "Personal care", "صحة ورياضة" to "Health & sports",
    "رياضة" to "Sports", "صحة ومكملات" to "Health & supplements", "أطفال ولعب" to "Kids & toys", "لعب" to "Toys",
    "مستلزمات أطفال" to "Baby supplies", "سوبرماركت" to "Supermarket", "أكل ومشروبات" to "Food & drinks", "منظفات" to "Cleaning",
    "عربيات" to "Cars", "اكسسوارات عربيات" to "Car accessories", "كتب ومكتبة" to "Books & stationery", "كتب" to "Books",
    "أدوات مكتبية" to "Stationery", "حيوانات أليفة" to "Pets", "مستلزمات" to "Supplies",
    "ميجا ديلز" to "Mega deals", "سوبر ديلز" to "Super deals", "عروض ساخنة 🔥" to "Hot deals 🔥", "عروض" to "Deals",
    "أعلى خصم" to "Biggest discount", "الأحدث" to "Newest", "الأقل سعراً" to "Lowest price", "الأعلى سعراً" to "Highest price",
    "الأكثر شعبية" to "Most popular", "انتهى العرض" to "Deal ended", "تفاصيل العرض" to "Deal details", "تاريخ السعر" to "Price history",
    "🔍 يستاهل ولا لأ؟" to "🔍 Is it worth it?", "بنراجع السعر…" to "Checking the price…", "افتح العرض ←" to "Open deal →",
    "بنحمّل كمان…" to "Loading more…", "المساعد الذكي ✨" to "AI assistant ✨", "اسأل عن أي منتج…" to "Ask about any product…",
    "بيفكر…" to "Thinking…", "أسعار لحظية من المتاجر:" to "Live store prices:",
    "ابحث عن أي منتج… قلاية، سماعة، iPhone" to "Search any product… air fryer, headphones, iPhone",
    "فلاتر البحث" to "Search filters", "مسح الفلاتر" to "Clear filters", "مسح الكل" to "Clear all", "أقل سعر (ج.م)" to "Min price (EGP)",
    "أعلى سعر (ج.م)" to "Max price (EGP)", "إلغاء" to "Cancel", "تنبيه بالخصم" to "Discount alert", "إنشاء التنبيه" to "Create alert",
    "تسجيل الخروج" to "Sign out", "الإيميل" to "Email", "كلمة السر" to "Password", "الاسم" to "Name", "رقم الموبايل" to "Phone number",
    "نسيت كلمة السر؟" to "Forgot password?", "عندك حساب؟ سجّل دخول" to "Have an account? Sign in",
    "معندكش حساب؟ اعمل حساب جديد" to "No account? Create one", "6 حروف على الأقل" to "At least 6 characters",
    "ابعتلي إشعار من خصم:" to "Notify me from discount:", "تحديث الحالة" to "Refresh status",
    "مفيش عروض في القسم ده دلوقتي" to "No deals in this category right now", "لسه مفيش عروض هنا" to "No deals here yet",
    "مقدرناش نجيب العروض، اتأكد من النت" to "Couldn't load deals, check your connection",
    "✨ مختار ليك (بيتعلم من اهتماماتك)" to "✨ Picked for you (learns from your interests)",
    "اللغة" to "Language", "العربية" to "العربية", "English" to "English",
)

/** On-device Arabic → English translation (Google ML Kit, free, works offline after a one-time ~30 MB download). */
object MlTranslate {
    private const val TAG = "MlTranslate"
    private val cache = ConcurrentHashMap<String, String>()
    private val lock = Mutex()
    private var translator: Translator? = null
    private var file: File? = null
    private var ready = false

    fun init(ctx: Context) {
        if (file != null) return
        file = File(ctx.filesDir, "translations_en.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            o.keys().forEach { k -> cache[k] = o.getString(k) }
        }
    }

    fun cached(text: String): String? = EN[text] ?: cache[text]

    private suspend fun ensure(): Translator? = lock.withLock {
        translator?.let { if (ready) return it }
        val t = translator ?: Translation.getClient(TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ARABIC).setTargetLanguage(TranslateLanguage.ENGLISH).build()).also { translator = it }
        ready = suspendCancellableCoroutine { c ->
            t.downloadModelIfNeeded(DownloadConditions.Builder().build())
                .addOnSuccessListener { c.resume(true) }.addOnFailureListener { Log.w(TAG, "model: ${it.message}"); c.resume(false) }
        }
        if (ready) t else null
    }

    /** Prepare the model as soon as English is chosen. */
    suspend fun warmUp() { ensure() }

    suspend fun translate(text: String): String? {
        cached(text)?.let { return it }
        val t = ensure() ?: return null
        val out = suspendCancellableCoroutine<String?> { c ->
            t.translate(text).addOnSuccessListener { c.resume(it) }.addOnFailureListener { c.resume(null) }
        } ?: return null
        cache[text] = out
        if (text.length <= 120 && !text.any(Char::isDigit)) persist()   // keep stable UI phrases, not prices/counts
        return out
    }

    private suspend fun persist() = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject()
            cache.entries.filter { it.key.length <= 120 && !it.key.any(Char::isDigit) }.take(3000).forEach { o.put(it.key, it.value) }
            file?.writeText(o.toString())
        }
    }
}

/** The text in the user's language. Arabic text is shown first and replaced as soon as its translation is ready. */
@Composable
fun tr(text: String): String {
    if (LocalLang.current != "en" || !ARABIC.containsMatchIn(text)) return text
    MlTranslate.cached(text)?.let { return it }
    return produceState(text, text) { MlTranslate.translate(text)?.let { value = it } }.value
}

/** Drop-in replacement of material3 Text that follows the app language. */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(text = tr(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style)

@Composable
fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
    fontWeight = fontWeight, fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
    lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout, style = style)

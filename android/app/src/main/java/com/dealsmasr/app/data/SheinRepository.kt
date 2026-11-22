package com.dealsmasr.app.data

import android.content.Context
import android.net.Uri
import com.dealsmasr.app.core.OrderBreakdown
import com.dealsmasr.app.core.OrderSummary
import com.dealsmasr.app.core.SheinLink
import com.dealsmasr.app.core.SheinPricing
import com.dealsmasr.app.core.SheinPricingConfig
import com.dealsmasr.app.core.SummaryItem
import com.dealsmasr.app.core.SummaryOrder
import com.dealsmasr.app.data.local.CartDao
import com.dealsmasr.app.data.local.CartItemEntity
import com.dealsmasr.app.data.local.OrderDao
import com.dealsmasr.app.data.local.OrderEntity
import com.dealsmasr.app.data.local.displayTitle
import com.dealsmasr.app.data.local.isPriced
import com.dealsmasr.app.data.remote.ApiProvider
import com.dealsmasr.app.data.remote.CandidateDto
import com.dealsmasr.app.data.remote.ChooseIn
import com.dealsmasr.app.data.remote.OrderIn
import com.dealsmasr.app.data.remote.OrderItemIn
import com.dealsmasr.app.data.remote.QuoteDto
import com.dealsmasr.app.data.remote.ResolveIn
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.math.BigDecimal
import java.util.UUID

@Serializable
data class OrderItemSnapshot(
    val title: String,
    val productUrl: String? = null,
    val imageUrl: String? = null,
    val localImagePath: String? = null,
    val size: String = "",
    val color: String = "",
    val notes: String = "",
    val quantity: Int,
    val unitSar: String,
    val lineTotalEgp: String,
    val quoteId: String? = null,
)

@Serializable
data class PricingSnapshot(val sarToEgp: String, val multiplier: String, val deliveryFeeEgp: String)

sealed interface AddResult {
    data object Added : AddResult
    data object AddedPriced : AddResult
    data class Error(val message: String) : AddResult
}

class SheinRepository(
    private val context: Context,
    private val supabase: com.dealsmasr.app.data.auth.Supabase,
    private val apiProvider: ApiProvider,
    private val settings: SettingsStore,
    private val cartDao: CartDao,
    private val orderDao: OrderDao,
) {
    val cart: Flow<List<CartItemEntity>> = cartDao.observeAll()
    val orders: Flow<List<OrderEntity>> = orderDao.observeAll()
    fun order(id: String): Flow<OrderEntity?> = orderDao.observe(id)

    private val json get() = apiProvider.json
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ───────── server-side SHEIN pricing: the phone only queues a request; the 24/7 server opens SHEIN Saudi.
    private fun cloud() = com.dealsmasr.app.data.auth.SupabaseConfig.configured && supabase.session.value != null

    private suspend fun requestQuote(url: String, kind: String): String {
        val text = supabase.insert("shein_quotes", kotlinx.serialization.json.buildJsonObject {
            put("url", url); put("kind", kind)
        })
        return json.parseToJsonElement(text).jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content
    }

    private suspend fun autoPriceUrl(url: String, kind: String = "product"): AddResult {
        if (!cloud()) return AddResult.Error("سجّل دخول الأول علشان نسعّر المنتج")
        return runCatching {
            val qid = requestQuote(url, kind)
            cartDao.insert(CartItemEntity(inputType = if (kind == "bag") "bag" else "url", inputValue = url, productUrl = url,
                quoteId = "s:$qid", quoteStatus = "pricing", title = if (kind == "bag") "باج SHEIN" else null, addedAt = now()))
            AddResult.Added
        }.getOrElse { AddResult.Error(it.message ?: "تعذر إرسال الطلب") }
    }

    /** Shared bag link: the server reads every product in it. */
    suspend fun addBag(link: String): AddResult {
        val url = SheinLink.firstUrl(link) ?: return AddResult.Error("الصق لينك الباج")
        return autoPriceUrl(url, "bag")
    }

    /** Ask the server to try again for an item that failed. */
    fun retry(item: CartItemEntity) {
        bg.launch {
            runCatching {
                val qid = requestQuote(item.productUrl ?: item.inputValue, if (item.inputType == "bag") "bag" else "product")
                cartDao.update(item.copy(quoteId = "s:$qid", quoteStatus = "pricing", adminNote = null))
            }
        }
    }

    /** Applies server results to cart items that are waiting ("s:<id>" quotes). */
    private suspend fun refreshServerQuotes() {
        val waiting = cartDao.all().filter { it.quoteStatus == "pricing" && it.quoteId?.startsWith("s:") == true }
        if (waiting.isEmpty() || !cloud()) return
        val ids = waiting.joinToString(",") { it.quoteId!!.removePrefix("s:") }
        val rows = json.parseToJsonElement(supabase.select("shein_quotes", "select=id,status,title,image_url,retail_sar,items,error&id=in.($ids)"))
            .jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        for (item in waiting) {
            val r = rows[item.quoteId!!.removePrefix("s:")]?.jsonObject ?: continue
            fun s(k: String) = (r[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            when (s("status")) {
                "priced" -> {
                    val bagItems = (r["items"] as? kotlinx.serialization.json.JsonArray)
                    if (bagItems != null && bagItems.isNotEmpty()) {
                        cartDao.delete(item.id)
                        bagItems.forEach { b ->
                            val o = b.jsonObject
                            fun bs(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                            cartDao.insert(CartItemEntity(inputType = "bag", inputValue = item.inputValue, quoteStatus = "quoted",
                                title = bs("title"), imageUrl = bs("image_url"), productUrl = bs("url"), saUrl = bs("url"),
                                priceSar = bs("retail_sar")?.toDoubleOrNull(), priceSource = "auto", addedAt = now()))
                        }
                    } else {
                        cartDao.update(item.copy(quoteStatus = "quoted", title = s("title") ?: item.title, imageUrl = s("image_url"),
                            priceSar = s("retail_sar")?.toDoubleOrNull(), priceSource = "auto", adminNote = s("error")))
                    }
                }
                "failed" -> cartDao.update(item.copy(quoteStatus = "auto_failed", adminNote = s("error") ?: "تعذر التسعير"))
                else -> Unit
            }
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun applyQuote(item: CartItemEntity, q: QuoteDto): CartItemEntity {
        val quoted = q.status == "quoted" && q.originalPriceSar != null
        return item.copy(
            quoteId = q.id,
            quoteStatus = q.status,
            saUrl = q.saUrl ?: item.saUrl,
            title = q.title ?: item.title,
            imageUrl = q.imageUrl ?: item.imageUrl,
            productUrl = q.productUrl ?: q.saUrl ?: item.productUrl,
            adminNote = q.adminNote,
            candidatesJson = if (q.candidates.isEmpty()) null
                else json.encodeToString(ListSerializer(CandidateDto.serializer()), q.candidates),
            // A real quote always replaces a customer-entered estimate.
            priceSar = if (quoted) q.originalPriceSar else item.priceSar,
            priceSource = if (quoted) "quoted" else item.priceSource,
        )
    }

    /** Adds a product from a pasted link / shared text. */
    suspend fun addByUrl(input: String, manualPriceSar: Double?): AddResult {
        val url = SheinLink.firstUrl(input) ?: return AddResult.Error("الرابط غير صحيح")
        if (!SheinLink.isSheinUrl(url)) return AddResult.Error("ده مش رابط SHEIN")
        val base = CartItemEntity(
            inputType = "url", inputValue = url, productUrl = url, addedAt = now(),
            priceSar = manualPriceSar, priceSource = manualPriceSar?.let { "manual" },
        )
        val s = settings.current()
        if (!s.hasServer) {
            if (manualPriceSar != null) { cartDao.insert(base); return AddResult.AddedPriced }
            return autoPriceUrl(url)
        }
        return runCatching {
            val api = apiProvider.get(s.serverUrl)
            val resolved = api.resolve(ResolveIn(url))
            val q = resolved.quote ?: api.createQuote(
                "url".toRequestBody(), url.toRequestBody(), null
            )
            val item = applyQuote(base.copy(saUrl = resolved.saUrl), q)
            cartDao.insert(item)
            if (item.isPriced()) AddResult.AddedPriced else AddResult.Added
        }.getOrElse { fallback(base, manualPriceSar, it) }
    }

    suspend fun addByText(text: String, manualPriceSar: Double?): AddResult {
        if (text.isBlank()) return AddResult.Error("اكتب اسم المنتج")
        val base = CartItemEntity(inputType = "text", inputValue = text.trim(), title = text.trim(), addedAt = now(),
            priceSar = manualPriceSar, priceSource = manualPriceSar?.let { "manual" })
        val s = settings.current()
        if (!s.hasServer) {
            if (manualPriceSar == null) { cartDao.insert(base.copy(quoteStatus = "team")); return AddResult.Added }
            cartDao.insert(base); return AddResult.AddedPriced
        }
        return runCatching {
            val q = apiProvider.get(s.serverUrl).createQuote("text".toRequestBody(), text.trim().toRequestBody(), null)
            cartDao.insert(applyQuote(base, q)); AddResult.Added
        }.getOrElse { fallback(base, manualPriceSar, it) }
    }

    suspend fun addByImage(uri: Uri, manualPriceSar: Double?): AddResult {
        val file = copyImage(uri) ?: return AddResult.Error("تعذر قراءة الصورة")
        val base = CartItemEntity(inputType = "image", inputValue = "", localImagePath = file.absolutePath,
            addedAt = now(), priceSar = manualPriceSar, priceSource = manualPriceSar?.let { "manual" })
        val s = settings.current()
        if (!s.hasServer) {
            if (manualPriceSar == null) { cartDao.insert(base.copy(quoteStatus = "team")); return AddResult.Added }
            cartDao.insert(base); return AddResult.AddedPriced
        }
        return runCatching {
            val part = MultipartBody.Part.createFormData("image", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            val q = apiProvider.get(s.serverUrl).createQuote("image".toRequestBody(), "".toRequestBody(), part)
            cartDao.insert(applyQuote(base, q)); AddResult.Added
        }.getOrElse { fallback(base, manualPriceSar, it) }
    }

    private suspend fun fallback(base: CartItemEntity, manual: Double?, e: Throwable): AddResult {
        if (manual != null) {
            cartDao.insert(base); return AddResult.AddedPriced
        }
        return AddResult.Error("تعذر الاتصال بالخادم. أدخل السعر يدوياً أو حاول لاحقاً (${e.message ?: "خطأ"})")
    }

    private suspend fun copyImage(uri: Uri): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.filesDir, "shein").apply { mkdirs() }
            val out = File(dir, "${UUID.randomUUID()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
                ?: return@runCatching null
            if (out.length() > 8 * 1024 * 1024) { out.delete(); null } else out
        }.getOrNull()
    }

    /** Polls the backend for quote updates of items still waiting. */
    suspend fun refreshQuotes() {
        refreshServerQuotes()
        val s = settings.current()
        if (!s.hasServer) return
        val items = cartDao.all().filter { it.quoteId != null && it.quoteStatus in setOf("pending", "needs_choice") }
        if (items.isEmpty()) return
        val quotes = apiProvider.get(s.serverUrl).quotes(items.joinToString(",") { it.quoteId!! }).associateBy { it.id }
        items.forEach { i -> quotes[i.quoteId]?.let { cartDao.update(applyQuote(i, it)) } }
    }

    fun candidates(item: CartItemEntity): List<CandidateDto> = item.candidatesJson?.let {
        runCatching { json.decodeFromString(ListSerializer(CandidateDto.serializer()), it) }.getOrNull()
    } ?: emptyList()

    suspend fun choose(item: CartItemEntity, index: Int) {
        val s = settings.current()
        val q = apiProvider.get(s.serverUrl).choose(item.quoteId ?: return, ChooseIn(index))
        cartDao.update(applyQuote(item, q))
    }

    suspend fun update(item: CartItemEntity) = cartDao.update(item)
    suspend fun remove(item: CartItemEntity) {
        cartDao.delete(item.id)
        val path = item.localImagePath ?: return
        if (orderDao.all().none { it.itemsJson.contains(path) }) File(path).delete()
    }

    fun breakdown(items: List<CartItemEntity>, cfg: SheinPricingConfig): OrderBreakdown? {
        val priced = items.filter { it.isPriced() }
        if (priced.isEmpty() || priced.size != items.size) return null
        return SheinPricing.order(priced.map { BigDecimal.valueOf(it.priceSar!!) to it.quantity }, cfg)
    }

    /** Creates the order locally, submits it to the backend if configured, clears the cart. */
    suspend fun submitOrder(name: String, phone: String, address: String, notes: String = ""): OrderEntity {
        val s = settings.current()
        val items = cartDao.all()
        require(items.isNotEmpty()) { "السلة فاضية" }
        val cfg = s.pricing
        val cloud = cloudEnabled()
        val b = breakdown(items, cfg) ?: throw IllegalStateException("بعض المنتجات لم يتم تسعيرها بعد")
        val id = UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        val createdAt = now()
        val snaps = items.zip(b.lines).map { (i, l) ->
            OrderItemSnapshot(i.displayTitle(), i.productUrl ?: i.saUrl, i.imageUrl, i.localImagePath, i.size, i.color,
                i.notes, i.quantity, l.sarUnit.toPlainString(), l.lineTotalEgp.toPlainString(), i.quoteId)
        }
        val summary = OrderSummary.whatsappText(SummaryOrder(id, name, phone, address.ifBlank { null },
            items.zip(b.lines).map { (i, l) ->
                SummaryItem(i.displayTitle(), i.productUrl ?: i.saUrl, i.imageUrl, i.size, i.color, i.notes, i.quantity, l)
            }, b, createdAt, notes.ifBlank { null }))
        var order = OrderEntity(
            id = id, customerName = name, customerPhone = phone, address = address.ifBlank { null },
            itemsJson = json.encodeToString(ListSerializer(OrderItemSnapshot.serializer()), snaps),
            sarTotal = b.sarTotal.toPlainString(), convertedEgp = b.convertedEgp.toPlainString(),
            extraEgp = b.extraEgp.toPlainString(), subtotalEgp = b.subtotalEgp.toPlainString(),
            deliveryFeeEgp = b.deliveryFeeEgp.toPlainString(), totalEgp = b.totalEgp.toPlainString(),
            pricingJson = json.encodeToString(PricingSnapshot.serializer(),
                PricingSnapshot(cfg.sarToEgp.toPlainString(), cfg.multiplier.toPlainString(), cfg.deliveryFeeEgp.toPlainString())),
            // With a backend (Supabase or API) the shop confirms the order; without one, sending confirms it.
            status = if (s.hasServer || cloud) "submitted" else "confirmed",
            createdAt = createdAt,
            confirmedAt = if (s.hasServer || cloud) null else createdAt,
            countdownDays = s.countdownDays,
            synced = false,
            whatsappText = summary,
            notes = notes.ifBlank { null },
        )
        orderDao.upsert(order)
        settings.saveCustomer(name, phone, address)
        if (cloud) order = tryCloud(order) else if (s.hasServer) order = trySync(order, s.serverUrl)
        cartDao.clear()
        return order
    }

    private suspend fun trySync(order: OrderEntity, serverUrl: String): OrderEntity = runCatching {
        val snaps = json.decodeFromString(ListSerializer(OrderItemSnapshot.serializer()), order.itemsJson)
        apiProvider.get(serverUrl).createOrder(OrderIn(order.id, order.customerName, order.customerPhone, order.address,
            snaps.map { OrderItemIn(it.quoteId, it.title, it.productUrl, it.imageUrl, it.size.ifBlank { null },
                it.color.ifBlank { null }, it.notes.ifBlank { null }, it.quantity, it.unitSar.toDouble()) }))
        order.copy(synced = true).also { orderDao.upsert(it) }
    }.getOrDefault(order)

    private fun cloudEnabled() =
        com.dealsmasr.app.data.auth.SupabaseConfig.configured && supabase.session.value != null

    /** Saves the order in Supabase (RLS: only as "submitted", only for the signed-in user). */
    private suspend fun tryCloud(order: OrderEntity): OrderEntity = runCatching {
        val items = json.parseToJsonElement(order.itemsJson)
        supabase.insert("orders", kotlinx.serialization.json.buildJsonObject {
            put("id", kotlinx.serialization.json.JsonPrimitive(order.id))
            put("customer_name", kotlinx.serialization.json.JsonPrimitive(order.customerName))
            put("customer_phone", kotlinx.serialization.json.JsonPrimitive(order.customerPhone.filter { it.isDigit() || it == '+' }))
            order.address?.let { put("address", kotlinx.serialization.json.JsonPrimitive(it)) }
            order.notes?.let { put("notes", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("items", items)
            put("subtotal_egp", kotlinx.serialization.json.JsonPrimitive(BigDecimal(order.subtotalEgp)))
            put("delivery_fee_egp", kotlinx.serialization.json.JsonPrimitive(BigDecimal(order.deliveryFeeEgp)))
            put("total_egp", kotlinx.serialization.json.JsonPrimitive(BigDecimal(order.totalEgp)))
            put("pricing", json.parseToJsonElement(order.pricingJson))
        })
        order.copy(synced = true).also { orderDao.upsert(it) }
    }.getOrElse { e ->
        // duplicate key = already uploaded on an earlier attempt
        if ((e as? com.dealsmasr.app.data.auth.SupabaseException)?.status == 409) order.copy(synced = true).also { orderDao.upsert(it) } else order
    }

    /** Pulls status/confirmation of my orders from Supabase. Returns orders that just got confirmed. */
    private suspend fun syncCloud(): List<OrderEntity> {
        val all = orderDao.all()
        all.filter { !it.synced }.forEach { tryCloud(it) }
        val uid = supabase.session.value?.userId ?: return emptyList()
        val rows = json.parseToJsonElement(supabase.select("orders", "select=id,status,confirmed_at&user_id=eq.$uid&order=created_at.desc&limit=100"))
            as kotlinx.serialization.json.JsonArray
        val byId = orderDao.all().associateBy { it.id }
        val newlyConfirmed = mutableListOf<OrderEntity>()
        rows.forEach { r ->
            val o = r as kotlinx.serialization.json.JsonObject
            val local = byId[(o["id"] as kotlinx.serialization.json.JsonPrimitive).content] ?: return@forEach
            val status = (o["status"] as kotlinx.serialization.json.JsonPrimitive).content
            val confirmedAt = (o["confirmed_at"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
                ?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
            if (status != local.status || confirmedAt != local.confirmedAt) {
                val updated = local.copy(status = status, confirmedAt = confirmedAt ?: local.confirmedAt)
                orderDao.upsert(updated)
                if (local.confirmedAt == null && updated.confirmedAt != null) newlyConfirmed += updated
            }
        }
        return newlyConfirmed
    }

    /** Retries unsynced orders and pulls status/confirmation time from the backend. */
    suspend fun syncOrders(): List<OrderEntity> {
        if (cloudEnabled()) return syncCloud()
        syncLegacy(); return emptyList()
    }

    private suspend fun syncLegacy() {
        val s = settings.current()
        if (!s.hasServer) return
        val all = orderDao.all()
        all.filter { !it.synced }.forEach { trySync(it, s.serverUrl) }
        all.filter { it.synced && it.status !in setOf("delivered", "cancelled") }
            .groupBy { it.customerPhone }
            .forEach { (phone, list) ->
                val remote = runCatching {
                    apiProvider.get(s.serverUrl).orderStatus(list.joinToString(",") { it.id }, phone)
                }.getOrNull() ?: return@forEach
                val byId = remote.associateBy { it.id }
                list.forEach { o ->
                    val r = byId[o.id] ?: return@forEach
                    val confirmed = o.confirmedAt ?: r.confirmedAt?.times(1000)
                    if (r.status != o.status || confirmed != o.confirmedAt) {
                        orderDao.upsert(o.copy(status = r.status, confirmedAt = confirmed))
                    }
                }
            }
    }

    fun itemsOf(order: OrderEntity): List<OrderItemSnapshot> =
        runCatching { json.decodeFromString(ListSerializer(OrderItemSnapshot.serializer()), order.itemsJson) }.getOrDefault(emptyList())

    suspend fun saveOrder(o: OrderEntity) = orderDao.upsert(o)
    suspend fun allOrders() = orderDao.all()
}

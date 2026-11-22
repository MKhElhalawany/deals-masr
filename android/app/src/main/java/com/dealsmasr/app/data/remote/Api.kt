package com.dealsmasr.app.data.remote

import com.dealsmasr.app.core.Sortable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@Serializable
data class TierDto(val key: String, @SerialName("label_ar") val labelAr: String = key, @SerialName("min_pct") val minPct: Double)

@Serializable
data class SheinPricingDto(
    @SerialName("sar_to_egp") val sarToEgp: String,
    val multiplier: String,
    @SerialName("delivery_fee_egp") val deliveryFeeEgp: String,
)

@Serializable
data class ConfigDto(
    val tiers: List<TierDto>,
    @SerialName("min_discount_pct") val minDiscountPct: Double,
    @SerialName("shein_pricing") val sheinPricing: SheinPricingDto,
    @SerialName("order_countdown_days") val orderCountdownDays: Int = 35,
    @SerialName("whatsapp_number") val whatsappNumber: String = "",
)

@Serializable
data class MarketplaceDto(
    val id: String,
    @SerialName("name_ar") val nameAr: String,
    val enabled: Boolean,
    val configured: Boolean,
    val status: String,
    @SerialName("last_run_at") val lastRunAt: Long? = null,
)

@Serializable
data class PricePointDto(
    @SerialName("original_price") val originalPrice: Double,
    @SerialName("current_price") val currentPrice: Double,
    @SerialName("checked_at") val checkedAt: Long,
)

@Serializable
data class DealDto(
    val id: String,
    val marketplace: String,
    @SerialName("marketplace_name") val marketplaceName: String,
    val title: String,
    @SerialName("image_url") val imageUrl: String? = null,
    val url: String,
    val category: String? = null,
    override val rating: Double? = null,
    @SerialName("rating_count") override val ratingCount: Int? = null,
    @SerialName("original_price") val originalPrice: Double,
    @SerialName("current_price") override val currentPrice: Double,
    @SerialName("discount_pct") override val discountPct: Double,
    val savings: Double,
    val tier: String? = null,
    @SerialName("first_seen") val firstSeen: Long = 0,
    @SerialName("deal_since") override val dealSince: Long = 0,
    @SerialName("last_checked") val lastChecked: Long,
    val active: Boolean = true,
    val history: List<PricePointDto> = emptyList(),
    /** lower price with coupons / bank offers shown by the store (a note; the deal price is currentPrice) */
    @SerialName("best_price") val bestPrice: Double? = null,
) : Sortable {
    /** true when the store showed a crossed-out price; false = "X% off or more" without an old price (Amazon) */
    val hasOriginal: Boolean get() = originalPrice > currentPrice
}

@Serializable
data class DealPage(
    val total: Int,
    val page: Int,
    @SerialName("page_size") val pageSize: Int,
    val items: List<DealDto>,
    val categories: List<String> = emptyList(),
    @SerialName("stores_unavailable") val storesUnavailable: List<String> = emptyList(),
)

@Serializable
data class NotificationDto(
    val id: Long, val kind: String, @SerialName("product_id") val productId: String? = null,
    val title: String, val body: String, @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class CandidateDto(
    val title: String,
    @SerialName("original_price_sar") val originalPriceSar: Double,
    @SerialName("product_url") val productUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
)

@Serializable
data class QuoteDto(
    val id: String,
    @SerialName("input_type") val inputType: String,
    @SerialName("input_value") val inputValue: String? = null,
    @SerialName("goods_id") val goodsId: String? = null,
    @SerialName("sa_url") val saUrl: String? = null,
    val status: String,
    val title: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("original_price_sar") val originalPriceSar: Double? = null,
    @SerialName("product_url") val productUrl: String? = null,
    val candidates: List<CandidateDto> = emptyList(),
    @SerialName("admin_note") val adminNote: String? = null,
)

@Serializable
data class ResolveIn(val input: String)

@Serializable
data class ResolveOut(@SerialName("goods_id") val goodsId: String? = null, @SerialName("sa_url") val saUrl: String? = null, val quote: QuoteDto? = null)

@Serializable
data class ChooseIn(val index: Int)

@Serializable
data class OrderItemIn(
    @SerialName("quote_id") val quoteId: String? = null,
    val title: String,
    @SerialName("product_url") val productUrl: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    val size: String? = null,
    val color: String? = null,
    val notes: String? = null,
    val quantity: Int,
    @SerialName("original_price_sar") val originalPriceSar: Double,
)

@Serializable
data class OrderIn(
    @SerialName("client_order_id") val clientOrderId: String,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String,
    val address: String? = null,
    val items: List<OrderItemIn>,
)

@Serializable
data class OrderOut(val id: String, val status: String, @SerialName("total_egp") val totalEgp: Double)

@Serializable
data class OrderStatusDto(
    val id: String, val status: String,
    @SerialName("confirmed_at") val confirmedAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long,
)

interface DealsApi {
    @GET("api/config") suspend fun config(): ConfigDto
    @GET("api/marketplaces") suspend fun marketplaces(): List<MarketplaceDto>

    @GET("api/deals")
    suspend fun deals(
        @Query("tier") tier: String? = null,
        @Query("stores") stores: String? = null,
        @Query("sort") sort: String = "discount",
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 50,
    ): DealPage

    @GET("api/search")
    suspend fun search(
        @Query("q") q: String,
        @Query("stores") stores: String?,
        @Query("min_discount") minDiscount: Int?,
        @Query("min_price") minPrice: Double?,
        @Query("max_price") maxPrice: Double?,
        @Query("category") category: String?,
        @Query("sort") sort: String,
        @Query("page") page: Int = 1,
        @Query("page_size") pageSize: Int = 30,
    ): DealPage

    @GET("api/products/{id}") suspend fun product(@Path("id") id: String): DealDto
    @GET("api/products") suspend fun products(@Query("ids") ids: String): List<DealDto>
    @GET("api/notifications") suspend fun notifications(@Query("since") since: Long): List<NotificationDto>

    @POST("api/shein/resolve") suspend fun resolve(@Body body: ResolveIn): ResolveOut

    @Multipart
    @POST("api/shein/quotes")
    suspend fun createQuote(
        @Part("input_type") inputType: RequestBody,
        @Part("input_value") inputValue: RequestBody,
        @Part image: MultipartBody.Part?,
    ): QuoteDto

    @GET("api/shein/quotes") suspend fun quotes(@Query("ids") ids: String): List<QuoteDto>
    @POST("api/shein/quotes/{id}/choose") suspend fun choose(@Path("id") id: String, @Body body: ChooseIn): QuoteDto

    @POST("api/orders") suspend fun createOrder(@Body body: OrderIn): OrderOut
    @GET("api/orders") suspend fun orderStatus(@Query("ids") ids: String, @Query("phone") phone: String): List<OrderStatusDto>
}

/** Builds (and caches) a Retrofit client for the user-configurable backend URL. */
class ApiProvider {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()
    @Volatile private var cached: Pair<String, DealsApi>? = null

    fun get(baseUrl: String): DealsApi {
        val url = normalize(baseUrl) ?: throw NoServerException()
        cached?.let { if (it.first == url) return it.second }
        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(DealsApi::class.java)
        cached = url to api
        return api
    }

    companion object {
        fun normalize(raw: String): String? {
            val t = raw.trim()
            if (t.isEmpty()) return null
            val withScheme = if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
            return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
        }
    }
}

class NoServerException : IllegalStateException("لم يتم ضبط عنوان الخادم")

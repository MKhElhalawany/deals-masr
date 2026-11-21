package com.dealsmasr.app.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.URLEncoder

@Serializable
data class CloudOrderItem(
    val title: String,
    val productUrl: String? = null,
    val imageUrl: String? = null,
    val size: String = "",
    val color: String = "",
    val notes: String = "",
    val quantity: Int = 1,
    val unitSar: String = "",
    val lineTotalEgp: String = "",
)

@Serializable
data class CloudOrder(
    val id: String,
    @SerialName("customer_name") val customerName: String,
    @SerialName("customer_phone") val customerPhone: String,
    val address: String? = null,
    val notes: String? = null,
    val items: List<CloudOrderItem> = emptyList(),
    @SerialName("subtotal_egp") val subtotalEgp: Double,
    @SerialName("delivery_fee_egp") val deliveryFeeEgp: Double,
    @SerialName("total_egp") val totalEgp: Double,
    val status: String,
    @SerialName("confirmed_at") val confirmedAt: String? = null,
    @SerialName("created_at") val createdAt: String,
)

/** Admin-only operations. RLS enforces this on the server; the UI just hides it from normal users. */
class AdminRepository(private val supabase: Supabase, private val json: Json) {
    private val list = ListSerializer(CloudOrder.serializer())

    suspend fun orders(status: String? = null): List<CloudOrder> {
        val filter = status?.let { "&status=eq.$it" }.orEmpty()
        return json.decodeFromString(list, supabase.select("orders", "select=*&order=created_at.desc&limit=200$filter"))
    }

    suspend fun setStatus(id: String, status: String): CloudOrder? =
        json.decodeFromString(list, supabase.update("orders", "id=eq.$id", buildJsonObject { put("status", status) })).firstOrNull()

    /** New orders since [sinceIso] (for the admin's "new order" notification). */
    suspend fun submittedSince(sinceIso: String): List<CloudOrder> = json.decodeFromString(list,
        supabase.select("orders", "select=*&status=eq.submitted&order=created_at.asc&created_at=gt.${URLEncoder.encode(sinceIso, "UTF-8")}"))

    fun isEmptyArray(text: String) = (json.parseToJsonElement(text) as? JsonArray)?.isEmpty() ?: true
}

package com.dealsmasr.app.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.dealsmasr.app.core.Sortable
import com.dealsmasr.app.data.remote.DealDto
import kotlinx.coroutines.flow.Flow

/** Offline cache of the latest verified deal snapshot from the backend. */
@Entity(tableName = "deals")
data class DealEntity(
    @PrimaryKey val id: String,
    val marketplace: String,
    val marketplaceName: String,
    val title: String,
    val imageUrl: String?,
    val url: String,
    val category: String?,
    override val rating: Double?,
    override val ratingCount: Int?,
    val originalPrice: Double,
    override val currentPrice: Double,
    override val discountPct: Double,
    val savings: Double,
    override val dealSince: Long,
    val lastChecked: Long,
) : Sortable

fun DealDto.toEntity() = DealEntity(
    id, marketplace, marketplaceName, title, imageUrl, url, category, rating, ratingCount,
    originalPrice, currentPrice, discountPct, savings, dealSince, lastChecked,
)

fun DealEntity.toDto() = DealDto(
    id = id, marketplace = marketplace, marketplaceName = marketplaceName, title = title, imageUrl = imageUrl,
    url = url, category = category, rating = rating, ratingCount = ratingCount, originalPrice = originalPrice,
    currentPrice = currentPrice, discountPct = discountPct, savings = savings, dealSince = dealSince,
    lastChecked = lastChecked,
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val productId: String,
    val marketplaceName: String,
    val title: String,
    val imageUrl: String?,
    val url: String,
    val originalPrice: Double,
    val currentPrice: Double,
    val discountPct: Double,
    val lastChecked: Long,
    val active: Boolean,
    val addedAt: Long,
)

fun DealDto.toFavorite(addedAt: Long) = FavoriteEntity(
    id, marketplaceName, title, imageUrl, url, originalPrice, currentPrice, discountPct, lastChecked, active, addedAt,
)

@Entity(tableName = "alerts")
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val query: String,
    val minDiscount: Int,
    val stores: String?,          // comma separated, null = all
    val maxPrice: Double?,
    val createdAt: Long,
    val notifiedIds: String = "", // product ids already notified
)

@Entity(tableName = "cart_items")
data class CartItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inputType: String,              // url | image | text
    val inputValue: String,
    val localImagePath: String? = null,
    val quoteId: String? = null,
    val quoteStatus: String = STATUS_LOCAL, // local | pending | quoted | needs_choice | not_found
    val title: String? = null,
    val imageUrl: String? = null,
    val productUrl: String? = null,
    val saUrl: String? = null,
    val priceSar: Double? = null,
    val priceSource: String? = null,    // quoted | manual
    val adminNote: String? = null,
    val candidatesJson: String? = null,
    val size: String = "",
    val color: String = "",
    val notes: String = "",
    val quantity: Int = 1,
    val addedAt: Long,
) {
    companion object {
        const val STATUS_LOCAL = "local"
    }
}

fun CartItemEntity.isPriced(): Boolean = (priceSar ?: 0.0) > 0.0 && quoteStatus != "not_found"
fun CartItemEntity.displayTitle(): String = title ?: inputValue.ifBlank { "منتج من صورة" }

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey val id: String,
    val customerName: String,
    val customerPhone: String,
    val address: String?,
    val itemsJson: String,          // List<OrderItemSnapshot>
    val sarTotal: String,
    val convertedEgp: String,
    val extraEgp: String,
    val subtotalEgp: String,
    val deliveryFeeEgp: String,
    val totalEgp: String,
    val pricingJson: String,        // rate / multiplier / fee used
    val status: String,             // submitted | confirmed | purchased | shipped | delivered | cancelled
    val createdAt: Long,
    val confirmedAt: Long?,         // countdown anchor (epoch millis)
    val countdownDays: Int,
    val synced: Boolean,
    val notifiedMilestones: String = "",
    val whatsappText: String,
)

@Dao
abstract class DealDao {
    @Query("SELECT * FROM deals") abstract fun observeAll(): Flow<List<DealEntity>>
    @Query("DELETE FROM deals") abstract suspend fun clear()
    @Insert(onConflict = OnConflictStrategy.REPLACE) abstract suspend fun insertAll(items: List<DealEntity>)

    @Transaction
    open suspend fun replaceAll(items: List<DealEntity>) {
        clear(); insertAll(items)
    }
}

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC") fun observeAll(): Flow<List<FavoriteEntity>>
    @Query("SELECT * FROM favorites") suspend fun all(): List<FavoriteEntity>
    @Query("SELECT productId FROM favorites") fun observeIds(): Flow<List<String>>
    @Upsert suspend fun upsert(f: FavoriteEntity)
    @Query("DELETE FROM favorites WHERE productId = :id") suspend fun delete(id: String)
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC") fun observeAll(): Flow<List<AlertEntity>>
    @Query("SELECT * FROM alerts") suspend fun all(): List<AlertEntity>
    @Insert suspend fun insert(a: AlertEntity): Long
    @Update suspend fun update(a: AlertEntity)
    @Delete suspend fun delete(a: AlertEntity)
}

@Dao
interface CartDao {
    @Query("SELECT * FROM cart_items ORDER BY addedAt") fun observeAll(): Flow<List<CartItemEntity>>
    @Query("SELECT * FROM cart_items ORDER BY addedAt") suspend fun all(): List<CartItemEntity>
    @Insert suspend fun insert(i: CartItemEntity): Long
    @Update suspend fun update(i: CartItemEntity)
    @Query("DELETE FROM cart_items WHERE id = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM cart_items") suspend fun clear()
}

@Dao
interface OrderDao {
    @Query("SELECT * FROM orders ORDER BY createdAt DESC") fun observeAll(): Flow<List<OrderEntity>>
    @Query("SELECT * FROM orders WHERE id = :id") fun observe(id: String): Flow<OrderEntity?>
    @Query("SELECT * FROM orders") suspend fun all(): List<OrderEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(o: OrderEntity)
}

@Database(
    entities = [DealEntity::class, FavoriteEntity::class, AlertEntity::class, CartItemEntity::class, OrderEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deals(): DealDao
    abstract fun favorites(): FavoriteDao
    abstract fun alerts(): AlertDao
    abstract fun cart(): CartDao
    abstract fun orders(): OrderDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "deals.db").build()
    }
}

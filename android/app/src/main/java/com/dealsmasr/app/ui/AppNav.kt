package com.dealsmasr.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dealsmasr.app.ui.deals.DealsScreen
import com.dealsmasr.app.ui.home.HomeScreen
import com.dealsmasr.app.ui.orders.OrderDetailScreen
import com.dealsmasr.app.ui.orders.OrdersScreen
import com.dealsmasr.app.ui.product.FavoritesScreen
import com.dealsmasr.app.ui.product.ProductScreen
import com.dealsmasr.app.ui.search.SearchScreen
import com.dealsmasr.app.ui.settings.SettingsScreen
import com.dealsmasr.app.ui.shein.CheckoutScreen
import com.dealsmasr.app.ui.shein.SheinScreen
import java.net.URLEncoder

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val TABS = listOf(
    Tab("home", "الرئيسية", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("deals", "العروض", Icons.Outlined.LocalOffer, Icons.Filled.LocalOffer),
    Tab("search", "البحث", Icons.Outlined.Search, Icons.Filled.Search),
    Tab("shein", "SHEIN", Icons.Outlined.ShoppingBag, Icons.Filled.ShoppingBag),
    Tab("orders", "طلباتي", Icons.Outlined.ReceiptLong, Icons.Filled.ReceiptLong),
)

/** Navigation requests coming from outside Compose (share sheet, notification taps). */
data class ExternalNav(val route: String? = null, val sharedText: String? = null)

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
fun AppNav(external: ExternalNav, onExternalConsumed: () -> Unit, sharedText: String?, onSharedConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route?.substringBefore("?")

    LaunchedEffect(external) {
        when {
            external.sharedText != null -> nav.switchTab("shein")
            external.route != null -> nav.navigate(external.route)
        }
        if (external.route != null || external.sharedText != null) onExternalConsumed()
    }

    Scaffold(
        bottomBar = {
            if (current in TABS.map { it.route }) {
                NavigationBar {
                    TABS.forEach { t ->
                        val selected = current == t.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { if (!selected) nav.switchTab(t.route) },
                            icon = { Icon(if (selected) t.selectedIcon else t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") {
                HomeScreen(
                    onSearch = { nav.switchTab("search") },
                    onOpenTier = { tier -> nav.navigate("deals?tier=${tier.orEmpty()}") { launchSingleTop = true } },
                    onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") },
                    onFavorites = { nav.navigate("favorites") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("deals?tier={tier}", arguments = listOf(navArgument("tier") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                DealsScreen(
                    initialTier = e.arguments?.getString("tier")?.ifBlank { null },
                    onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("search?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                SearchScreen(e.arguments?.getString("q"), onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") })
            }
            composable("shein") {
                SheinScreen(sharedText, onSharedConsumed, onCheckout = { nav.navigate("checkout") })
            }
            composable("orders") { OrdersScreen(onOrder = { nav.navigate("order/$it") }) }
            composable("product/{id}") { e ->
                ProductScreen(java.net.URLDecoder.decode(e.arguments?.getString("id").orEmpty(), "UTF-8"), onBack = { nav.popBackStack() })
            }
            composable("order/{id}") { e -> OrderDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
            composable("favorites") {
                FavoritesScreen(onBack = { nav.popBackStack() }, onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable("checkout") {
                CheckoutScreen(onBack = { nav.popBackStack() }, onPlaced = { id ->
                    nav.navigate("order/$id") { popUpTo("shein") }
                })
            }
        }
    }
}

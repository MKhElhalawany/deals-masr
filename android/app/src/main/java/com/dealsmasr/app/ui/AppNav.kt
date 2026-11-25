package com.dealsmasr.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import com.dealsmasr.app.ui.i18n.Text
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
    Tab("shein", "شي إن", Icons.Outlined.ShoppingBag, Icons.Filled.ShoppingBag),
    Tab("assistant", "المساعد", Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
)

/** Navigation requests coming from outside Compose (share sheet, notification taps). */
data class ExternalNav(val route: String? = null, val sharedText: String? = null)

private fun NavHostController.switchTab(route: String) {
    val start = graph.findStartDestination().id
    // Home is the start screen: just drop everything above it (restoring a saved state could bring the old tab back)
    if (route == "home") { if (!popBackStack(start, inclusive = false)) navigate("home") { launchSingleTop = true }; return }
    navigate(route) {
        popUpTo(start) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppNav(external: ExternalNav, onExternalConsumed: () -> Unit, sharedText: String?, onSharedConsumed: () -> Unit) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route?.substringBefore("?")

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    fun fromDrawer(route: String) { scope.launch { drawer.close() }; nav.navigate(route) { launchSingleTop = true } }

    // open SHEIN quietly in the background shortly after start, so the SHEIN tab shows instantly
    val appCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(2500); runCatching { com.dealsmasr.app.ui.shein.SheinWeb.get(appCtx) } }
    LaunchedEffect(external) {
        when {
            external.sharedText != null -> nav.navigate("shein_cart") { launchSingleTop = true }
            external.route != null -> nav.navigate(external.route)
        }
        if (external.route != null || external.sharedText != null) onExternalConsumed()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen || current == "home",
        drawerContent = {
            ModalDrawerSheet {
                com.dealsmasr.app.ui.browse.AppDrawerContent(
                    onCategory = { fromDrawer("category/${URLEncoder.encode(it, "UTF-8")}") },
                    onFavorites = { fromDrawer("favorites") },
                    onSettings = { fromDrawer("settings") },
                )
            }
        },
    ) {
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
                    onMenu = { scope.launch { drawer.open() } },
                    onCrazy = { f -> nav.navigate("crazy?f=$f") },
                    onCategory = { nav.navigate("category/${URLEncoder.encode(it, "UTF-8")}") },
                )
            }
            composable("category/{c}") { e ->
                com.dealsmasr.app.ui.browse.CategoryScreen(java.net.URLDecoder.decode(e.arguments?.getString("c").orEmpty(), "UTF-8"),
                    onBack = { nav.popBackStack() }, onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") },
                    onOpenCategory = { nav.navigate("category/${URLEncoder.encode(it, "UTF-8")}") { popUpTo("category/{c}") { inclusive = true } } })
            }
            composable("crazy?f={f}", arguments = listOf(navArgument("f") { type = NavType.IntType; defaultValue = 0 })) { e ->
                com.dealsmasr.app.ui.browse.CrazyDealsScreen(e.arguments?.getInt("f") ?: 0, onBack = { nav.popBackStack() })
            }
            composable("deals?tier={tier}", arguments = listOf(navArgument("tier") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                DealsScreen(
                    onCrazy = { nav.navigate("crazy?f=0") },
                    initialTier = e.arguments?.getString("tier")?.ifBlank { null },
                    onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") },
                    onSettings = { nav.navigate("settings") },
                )
            }
            composable("search?q={q}", arguments = listOf(navArgument("q") { type = NavType.StringType; nullable = true; defaultValue = null })) { e ->
                SearchScreen(e.arguments?.getString("q"), onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") })
            }
            composable("shein") {
                com.dealsmasr.app.ui.shein.SheinStoreScreen(onCart = { nav.navigate("shein_cart") }, onCheckout = { nav.navigate("checkout") })
            }
            composable("shein_cart") {
                SheinScreen(sharedText, onSharedConsumed, onCheckout = { nav.navigate("checkout") }, onOrders = { nav.navigate("orders") })
            }
            composable("orders") { OrdersScreen(onOrder = { nav.navigate("order/$it") }) }
            composable("assistant") {
                com.dealsmasr.app.ui.assistant.AssistantScreen(onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") })
            }
            composable("product/{id}") { e ->
                ProductScreen(java.net.URLDecoder.decode(e.arguments?.getString("id").orEmpty(), "UTF-8"), onBack = { nav.popBackStack() })
            }
            composable("order/{id}") { e -> OrderDetailScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() }) }
            composable("favorites") {
                FavoritesScreen(onBack = { nav.popBackStack() }, onProduct = { nav.navigate("product/${URLEncoder.encode(it, "UTF-8")}") })
            }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }, onAdminOrders = { nav.navigate("admin_orders") }) }
            composable("admin_orders") { com.dealsmasr.app.ui.admin.AdminOrdersScreen(onBack = { nav.popBackStack() }) }
            composable("checkout") {
                CheckoutScreen(onBack = { nav.popBackStack() }, onPlaced = { id ->
                    nav.navigate("order/$id") { popUpTo("shein") }
                })
            }
        }
    }
    }
}

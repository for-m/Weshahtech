package com.weshah.networkmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.weshah.ui.alerts.AlertsScreen
import com.weshah.ui.common.theme.WeshahTheme
import com.weshah.ui.dashboard.DashboardScreen
import com.weshah.ui.devices.DevicesScreen
import com.weshah.ui.devices.DeviceDetailScreen
import com.weshah.ui.dhcp.DhcpManagerScreen
import com.weshah.ui.health.NetworkHealthScreen
import com.weshah.ui.multiwan.MultiWanScreen
import com.weshah.ui.ports.CableDiagnosticsScreen
import com.weshah.ui.ports.PortManagerScreen
import com.weshah.ui.router.RouterConnectScreen
import com.weshah.ui.subscribers.SubscribersScreen
import com.weshah.ui.tools.ToolsScreen
import com.weshah.ui.settings.SettingsScreen
import com.weshah.ui.vlan.VlanManagerScreen
import com.weshah.ui.wifi.WifiAnalyzerScreen
import dagger.hilt.android.AndroidEntryPoint

// ─── Navigation Routes ────────────────────────────────────────────────────────

private object Routes {
    const val DASHBOARD = "dashboard"
    const val DEVICES = "devices"
    const val DEVICE_DETAIL = "device_detail/{macAddress}"
    const val ROUTER_CONNECT = "router_connect"
    const val SUBSCRIBERS = "subscribers"
    const val TOOLS = "tools"
    const val SETTINGS = "settings"
    const val ALERTS = "alerts"
    const val PORT_MANAGER = "port_manager"
    const val CABLE_DIAGNOSTICS = "cable_diagnostics/{portId}"
    const val NETWORK_HEALTH = "network_health"
    const val WIFI_ANALYZER = "wifi_analyzer"
    const val VLAN_MANAGER = "vlan_manager"
    const val DHCP_MANAGER = "dhcp_manager"
    const val MULTI_WAN = "multi_wan"

    fun deviceDetail(mac: String) = "device_detail/$mac"
    fun cableDiagnostics(portId: String) = "cable_diagnostics/$portId"
}

private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.DASHBOARD, "الرئيسية", Icons.Default.Dashboard),
    BottomNavItem(Routes.DEVICES, "الأجهزة", Icons.Default.DevicesOther, Icons.Default.DevicesOther),
    BottomNavItem(Routes.SUBSCRIBERS, "المشتركون", Icons.Default.People),
    BottomNavItem(Routes.TOOLS, "الأدوات", Icons.Default.Build),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeshahTheme {
                WeshahNavHost()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeshahNavHost() {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStack?.destination?.route

    val showBottomBar = currentRoute in bottomNavItems.map { it.route }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    val hierarchy = navBackStack?.destination?.hierarchy
                    bottomNavItems.forEach { item ->
                        val selected = hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            icon = { Icon(if (selected) item.selectedIcon else item.icon, item.label) },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.DASHBOARD,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNavigateToDevices = { navController.navigate(Routes.DEVICES) },
                    onNavigateToRouter = { navController.navigate(Routes.ROUTER_CONNECT) },
                    onNavigateToSubscribers = { navController.navigate(Routes.SUBSCRIBERS) },
                    onNavigateToAlerts = { navController.navigate(Routes.ALERTS) },
                    onNavigateToHealth = { navController.navigate(Routes.NETWORK_HEALTH) }
                )
            }
            composable(Routes.DEVICES) {
                DevicesScreen(
                    onDeviceClick = { mac -> navController.navigate(Routes.deviceDetail(mac)) }
                )
            }
            composable(
                route = Routes.DEVICE_DETAIL,
                arguments = listOf(navArgument("macAddress") { type = NavType.StringType })
            ) {
                DeviceDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ROUTER_CONNECT) {
                RouterConnectScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SUBSCRIBERS) {
                SubscribersScreen(onSubscriberClick = { /* TODO: subscriber detail */ })
            }
            composable(Routes.TOOLS) {
                ToolsScreen(
                    onNavigateToPortManager = { navController.navigate(Routes.PORT_MANAGER) },
                    onNavigateToHealth = { navController.navigate(Routes.NETWORK_HEALTH) }
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.ALERTS) {
                AlertsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.PORT_MANAGER) {
                PortManagerScreen(
                    onBack = { navController.popBackStack() },
                    onRunDiagnostics = { portId -> navController.navigate(Routes.cableDiagnostics(portId)) }
                )
            }
            composable(
                route = Routes.CABLE_DIAGNOSTICS,
                arguments = listOf(navArgument("portId") { type = NavType.StringType })
            ) {
                CableDiagnosticsScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.NETWORK_HEALTH) {
                NetworkHealthScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.WIFI_ANALYZER) {
                WifiAnalyzerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.VLAN_MANAGER) {
                VlanManagerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.DHCP_MANAGER) {
                DhcpManagerScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.MULTI_WAN) {
                MultiWanScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}

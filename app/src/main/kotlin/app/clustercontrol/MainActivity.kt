package app.clustercontrol

import android.Manifest
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.clustercontrol.ui.AppTheme
import app.clustercontrol.ui.AutomationsScreen
import app.clustercontrol.ui.C
import app.clustercontrol.ui.DashboardScreen
import app.clustercontrol.ui.LogScreen
import app.clustercontrol.ui.MoreScreen
import app.clustercontrol.ui.QueueScreen

enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    QUEUE("Coda", Icons.AutoMirrored.Filled.List),
    LOG("Log", Icons.Default.Info),
    AUTOMATIONS("Automazioni", Icons.Default.Refresh),
    MORE("Altro", Icons.Default.MoreVert),
}

@Composable
fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Column(Modifier.background(C.Card.copy(alpha = .94f))) {
        HorizontalDivider(color = Hairline, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp)) {
            Tab.entries.forEach {
                val on = it == tab
                Column(
                    Modifier.weight(1f).clickable { onSelect(it) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(it.icon, null, Modifier.size(24.dp), tint = if (on) C.Accent else C.Faint)
                    Text(it.label, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = if (on) C.Accent else C.Faint, maxLines = 1)
                }
            }
        }
    }
}

private val Hairline = androidx.compose.ui.graphics.Color(0xFFDCDCE2)

@Composable
fun AppRoot(ui: UiState, actions: Actions, snackbar: SnackbarHostState) {
    var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
    var moreSub by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = tab == Tab.MORE && moreSub != null) { moreSub = null }
    Scaffold(
        containerColor = C.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { BottomBar(tab) { tab = it; moreSub = null } },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            Tab.DASHBOARD -> DashboardScreen(ui, actions, m) { tab = Tab.MORE; moreSub = "Sincronizza" }
            Tab.QUEUE -> QueueScreen(ui, actions, snackbar, m)
            Tab.LOG -> LogScreen(ui, m)
            Tab.AUTOMATIONS -> AutomationsScreen(ui, actions, m)
            Tab.MORE -> MoreScreen(ui, actions, moreSub, { moreSub = it }, m)
        }
    }
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // light UI only: dark status and navigation bar icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
        )
        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            AppTheme {
                val ui by vm.ui.collectAsState()
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }
                AppRoot(ui, vm, snackbar)
            }
        }
    }
}

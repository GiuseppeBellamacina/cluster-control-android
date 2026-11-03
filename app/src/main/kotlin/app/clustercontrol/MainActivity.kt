package app.clustercontrol

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.clustercontrol.ui.DashboardScreen
import app.clustercontrol.ui.MonitorScreen
import app.clustercontrol.ui.MoreScreen
import app.clustercontrol.ui.QueueScreen
import app.clustercontrol.ui.ScheduleScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Dashboard", Icons.Default.Home),
    QUEUE("Coda", Icons.AutoMirrored.Filled.List),
    MONITOR("Monitor", Icons.Default.Info),
    SCHEDULE("Schedule", Icons.Default.DateRange),
    MORE("Altro", Icons.Default.MoreVert),
}

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val ui by vm.ui.collectAsState()
                var tab by rememberSaveable { mutableStateOf(Tab.DASHBOARD) }
                var moreSub by rememberSaveable { mutableStateOf<String?>(null) }
                val snackbar = remember { SnackbarHostState() }
                LaunchedEffect(ui.message) {
                    ui.message?.let {
                        snackbar.showSnackbar(it)
                        vm.messageShown()
                    }
                }
                BackHandler(enabled = tab == Tab.MORE && moreSub != null) { moreSub = null }
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach {
                                NavigationBarItem(
                                    selected = tab == it,
                                    onClick = { tab = it; moreSub = null },
                                    icon = { Icon(it.icon, it.label) },
                                    label = { Text(it.label) },
                                )
                            }
                        }
                    },
                ) { pad ->
                    val m = Modifier.padding(pad)
                    when (tab) {
                        Tab.DASHBOARD -> DashboardScreen(ui, vm, m) { tab = Tab.MORE; moreSub = "Script" }
                        Tab.QUEUE -> QueueScreen(ui, vm, m)
                        Tab.MONITOR -> MonitorScreen(ui, vm, m)
                        Tab.SCHEDULE -> ScheduleScreen(ui, vm, m)
                        Tab.MORE -> MoreScreen(ui, vm, moreSub, { moreSub = it }, m)
                    }
                }
            }
        }
    }
}

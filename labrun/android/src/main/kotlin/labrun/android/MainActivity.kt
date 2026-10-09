package labrun.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import labrun.android.ui.HomeScreen
import labrun.android.ui.LabTheme
import labrun.android.ui.PreviewScreen
import labrun.android.ui.RunDetailScreen
import labrun.android.ui.RunScreen

sealed interface Screen {
    data object Home : Screen
    class Preview(val bytes: ByteArray, val fromLibrary: Boolean) : Screen
    data object Run : Screen
    data class Detail(val runId: String) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = LabApp.repo(this)
        setContent {
            LabTheme {
                var screen by remember { mutableStateOf<Screen>(if (repo.state != null) Screen.Run else Screen.Home) }
                val snack = remember { SnackbarHostState() }
                LaunchedEffect(repo.message) {
                    repo.message?.let { snack.showSnackbar(it); repo.message = null }
                }
                BackHandler(enabled = screen != Screen.Home) { screen = Screen.Home }
                Scaffold(snackbarHost = { SnackbarHost(snack) }) { pad ->
                    val m = Modifier.fillMaxSize().padding(pad)
                    when (val s = screen) {
                        Screen.Home -> HomeScreen(repo, m, onPreview = { b, lib -> screen = Screen.Preview(b, lib) },
                            onOpenRun = { screen = Screen.Run }, onOpenDetail = { screen = Screen.Detail(it) })
                        is Screen.Preview -> PreviewScreen(repo, s.bytes, s.fromLibrary, m, onBack = { screen = Screen.Home },
                            onStarted = { screen = Screen.Run })
                        Screen.Run -> RunScreen(repo, m, onBack = { screen = Screen.Home },
                            onEnded = { id -> screen = Screen.Detail(id) })
                        is Screen.Detail -> RunDetailScreen(repo, s.runId, m, onBack = { screen = Screen.Home })
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 只重挂闹钟；“提醒触发”只由真实闹钟记录，保证误差统计不被前台补记污染
        Reminders.reschedule(this, LabApp.repo(this))
    }
}

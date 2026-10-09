package labrun.android.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import labrun.android.Reminders
import labrun.android.Repository
import labrun.core.TimeFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(
    repo: Repository, modifier: Modifier,
    onPreview: (ByteArray, Boolean) -> Unit, onOpenRun: () -> Unit, onOpenDetail: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val c = LocalLab.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            onPreview(bytes, false)
        } catch (e: Exception) { repo.message = "读取文件失败：${e.message}" }
    }
    var resumeTick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) { resumeTick++; onPauseOrDispose { } }
    var notifOk by remember { mutableStateOf(true) }
    var exactOk by remember { mutableStateOf(true) }
    LaunchedEffect(resumeTick) { notifOk = Reminders.notificationsAllowed(ctx); exactOk = Reminders.exactAllowed(ctx) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifOk = Reminders.notificationsAllowed(ctx) }

    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        Text("实验记录", style = LabType.title.copy(fontSize = 26.sp), modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))

        repo.recoveryNote?.let { Banner(it, c.uncertain, "知道了") { repo.recoveryNote = null } }
        if (!notifOk) Banner("通知未开启：到点不会提醒采样", c.overdue, "开启") {
            if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
            else ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        if (!exactOk) Banner("精确闹钟未允许：提醒可能延迟数分钟", c.overdue, "允许") {
            if (Build.VERSION.SDK_INT >= 31) ctx.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${ctx.packageName}")))
        }

        repo.state?.let { s ->
            val nowT = rememberNowTRun(repo)
            Card(Modifier.padding(bottom = 8.dp), borderColor = c.primary) {
                Text("进行中", style = LabType.label, color = c.primary)
                Text(s.protocol.title, style = LabType.title)
                Text(TimeFormat.elapsed(nowT), style = LabType.display, color = c.primary)
                Text("当前：" + (s.currentStep?.def?.title ?: "步骤已全部处理，待结束"), style = LabType.body, color = c.muted)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onOpenRun, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("回到实验") }
            }
        }

        Button(onClick = { picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 0.dp)) { Text("导入步骤文件") }

        val lib = remember(repo.libraryVersion) { repo.storage.listProtocols() }
        SectionTitle("步骤文件（${lib.size}）")
        if (lib.isEmpty()) Text("还没有步骤文件。用 ChatGPT 按提示词生成 JSON 后导入。", style = LabType.body, color = c.muted)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            lib.forEach { p ->
                Card(Modifier.clickableRow { onPreview(p.bytes, true) }) {
                    Text(p.title, style = LabType.body)
                    Text("${p.id} · 版本 ${p.version}", style = LabType.caption, color = c.muted)
                }
            }
        }

        val all = remember(repo.libraryVersion) { repo.runs() }
        val runs = all.filter { !it.archived }
        val archived = all.filter { it.archived }
        SectionTitle("历史实验（${runs.size}）")
        if (runs.isEmpty()) Text("暂无", style = LabType.body, color = c.muted)
        RunList(runs, onOpenRun, onOpenDetail)
        if (archived.isNotEmpty()) {
            var showArchived by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickableRow { showArchived = !showArchived }.padding(vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("已归档（${archived.size}）", style = LabType.label, color = c.muted, modifier = Modifier.weight(1f))
                Text(if (showArchived) "收起 ▴" else "展开 ▾", style = LabType.label, color = c.primary)
            }
            if (showArchived) RunList(archived, onOpenRun, onOpenDetail)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RunList(runs: List<Repository.RunSummary>, onOpenRun: () -> Unit, onOpenDetail: (String) -> Unit) {
    val c = LocalLab.current
    val fmt = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        runs.forEach { r ->
            val s = r.state
            Card(Modifier.clickableRow { if (s?.isEnded == false) onOpenRun() else onOpenDetail(r.runId) }) {
                if (s == null) Text("无法读取：${r.error}", color = c.overdue)
                else Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.protocol.title, style = LabType.body)
                        Text(fmt.format(Date(s.start.wallMs)) + " · " + r.runId.take(8), style = LabType.caption, color = c.muted)
                    }
                    if (s.isEnded) Chip("已结束 ${TimeFormat.elapsed(s.ended!!.tRunMs)}", c.muted) else Chip("进行中", c.primary)
                }
            }
        }
    }
}

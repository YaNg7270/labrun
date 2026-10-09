package labrun.android.ui

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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import labrun.android.Repository
import labrun.core.Measurement
import labrun.core.Observation
import labrun.core.PointStatus
import labrun.core.StepStatus
import labrun.core.TimeFormat

@Composable
fun RunDetailScreen(repo: Repository, runId: String, modifier: Modifier, onBack: () -> Unit) {
    val c = LocalLab.current
    val s = remember(runId, repo.libraryVersion) { repo.loadState(runId) }
    var tab by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var editMeasurement by remember { mutableStateOf<Measurement?>(null) }
    var editObs by remember { mutableStateOf<Observation?>(null) }
    var anchorFix by remember { mutableStateOf<String?>(null) }

    Column(modifier.padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("‹ 首页") }
        if (s == null) { Text("无法读取该实验", color = c.overdue); return@Column }
        Text(s.protocol.title, style = LabType.title)
        val ended = s.ended
        Text(
            "开始 ${TimeFormat.iso(s.start.wallMs, s.start.tzOffsetS).take(19).replace('T', ' ')}" +
                (ended?.let { " · 时长 ${TimeFormat.elapsed(it.tRunMs)}" } ?: " · 未结束"),
            style = LabType.caption, color = c.muted,
        )
        val rec = s.points.count { it.status == PointStatus.RECORDED }
        val miss = s.points.count { it.status == PointStatus.MISSED }
        Text("步骤 ${s.steps.count { it.status == StepStatus.CONFIRMED }}/${s.steps.size} 确认 · 采样 $rec 录入 / $miss 漏测" +
            (if (s.extraMeasurements.isNotEmpty()) " · 计划外记录 ${s.extraMeasurements.size}" else "") + " · 现象 ${s.observations.size}" +
            if (s.correctionCount > 0) " · 更正 ${s.correctionCount}" else "",
            style = LabType.body, modifier = Modifier.padding(vertical = 8.dp))
        if (s.timeUncertain) Banner("出现过重启或系统时间变化，部分时间为估算", c.uncertain)
        Button(onClick = { exporting = true }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("导出（数据包 / HTML 报告 / Excel）") }
        Spacer(Modifier.height(12.dp))
        Tabs(listOf("时间线", "测量表", "现象", "更正"), tab) { tab = it }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            when (tab) {
                0 -> TimelineList(s)
                1 -> {
                    Text("点已录入的数值可更正或作废；原值始终保留。", style = LabType.caption, color = c.muted)
                    MeasurementTable(s, null, onPoint = { p -> p.measurement?.let { editMeasurement = it } }, onMeasurement = { editMeasurement = it })
                }
                2 -> ObservationsPanel(repo, s) { editObs = it }
                else -> {
                    SectionTitle("计时起点")
                    s.anchors.values.filter { it.def.id != s.protocol.runStartAnchorId }.forEach { a ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(a.def.label, style = LabType.body)
                                Text(a.tRunMs?.let { s.moment(it, a.atWallMs!!) + qualityMark(a.atQuality!!) } ?: "未建立", style = LabType.caption, color = c.muted)
                            }
                            if (a.established != null) TextButton(onClick = { anchorFix = a.def.id }) { Text("更正时刻") }
                        }
                    }
                    SectionTitle("更正记录")
                    CorrectionsPanel(s)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (s != null) {
        if (exporting) ExportDialog(repo, runId) { exporting = false }
        editMeasurement?.let { m -> CorrectMeasurementDialog(repo, s, m) { editMeasurement = null } }
        editObs?.let { o -> CorrectObservationDialog(repo, s, o) { editObs = null } }
        anchorFix?.let { id -> CorrectAnchorDialog(repo, s, id) { anchorFix = null } }
    }
}

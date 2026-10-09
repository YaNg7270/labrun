package labrun.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import labrun.android.Repository
import labrun.core.DesignTokens
import labrun.core.PointStatus
import labrun.core.RunState
import labrun.core.RunViews
import labrun.core.SamplePoint
import labrun.core.TimeFormat
import labrun.core.TimeQuality
import labrun.core.TimelineKind
import labrun.core.TimelineRow

/** 运行页的实时“现在”（运行时间毫秒），每 250ms 刷新。 */
@Composable
fun rememberNowTRun(repo: Repository): Long {
    var t by remember { mutableLongStateOf(repo.engine?.nowTRun()?.first ?: 0L) }
    LaunchedEffect(Unit) {
        while (true) { repo.engine?.nowTRun()?.first?.let { t = it }; delay(250) }
    }
    return t
}

@Composable
fun Chip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = LabType.caption.copy(fontWeight = FontWeight.Medium), color = color)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = LabType.label, color = LocalLab.current.muted, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, borderColor: Color? = null, content: @Composable () -> Unit) {
    val c = LocalLab.current
    Column(
        modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor ?: c.outline, RoundedCornerShape(12.dp)).padding(16.dp)
    ) { content() }
}

@Composable
fun Banner(text: String, color: Color, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp).border(1.dp, color, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = LabType.label, color = color, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

fun qualityMark(q: TimeQuality) = when (q) {
    TimeQuality.MONOTONIC -> ""
    TimeQuality.WALL_ESTIMATE -> "（估算）"
    TimeQuality.USER_ENTERED -> "（补填）"
}

/** 规范 §4：墙上时间 · 全局 · 相对起点。 */
fun RunState.moment(tRunMs: Long, wallMs: Long, relAnchor: String? = null): String {
    val parts = mutableListOf(TimeFormat.clock(wallMs, start.tzOffsetS), TimeFormat.elapsed(tRunMs))
    relAnchor?.let { a -> parts += "${anchorDef(a).label} ${TimeFormat.elapsed(relativeTo(a, tRunMs))}" }
    return parts.joinToString(" · ")
}

data class StatusLook(val text: String, val color: Color)

/** 规范 §5。 */
@Composable
fun pointLook(p: SamplePoint, nowT: Long, s: RunState): StatusLook {
    val c = LocalLab.current
    return when (p.status) {
        PointStatus.RECORDED -> {
            val m = p.measurement!!
            val v = p.plan.fields.joinToString(" ") { f -> (m.values[f.id]?.value ?: "") + (f.unit?.let { " $it" } ?: "") }
            val dev = (m.measuredTRunMs - p.plannedTRunMs) / 1000
            StatusLook("$v（偏差 ${if (dev >= 0) "+" else ""}${dev}s）", c.done)
        }
        PointStatus.MISSED -> StatusLook("漏测" + (p.missedReason?.let { "（${labrun.core.missedReasonText(it)}）" } ?: ""), c.missed)
        PointStatus.OPEN -> when {
            nowT < p.plannedTRunMs -> StatusLook("还有 ${TimeFormat.elapsed(p.plannedTRunMs - nowT).drop(1)}", c.muted)
            nowT < p.plannedTRunMs + DesignTokens.DUE_WINDOW_MS -> StatusLook("到期", c.due)
            else -> StatusLook("逾期 ${TimeFormat.elapsed(nowT - p.plannedTRunMs).drop(1)}", c.overdue)
        }
    }
}

@Composable
fun TimelineList(s: RunState, modifier: Modifier = Modifier) {
    val c = LocalLab.current
    val rows = remember(s) { RunViews(s).timeline().filter { it.kind != TimelineKind.REMINDER } }
    val locals = s.anchors.values.filter { it.established != null && it.def.id != s.protocol.runStartAnchorId && !s.isAdhocAnchor(it.def.id) }
    Column(modifier) {
        rows.forEach { r -> TimelineItem(s, r, locals.map { it.def.id }) }
        if (rows.isEmpty()) Text("暂无记录", color = c.muted)
        val reminders = s.reminders
        if (reminders.isNotEmpty()) {
            val delays = reminders.map { (r, e) -> (e.tRunMs - r.plannedTRunMs) / 1000.0 }
            Text("提醒 ${reminders.size} 次，触发延迟 ${"%.1f".format(delays.min())}–${"%.1f".format(delays.max())} 秒",
                style = LabType.caption, color = c.muted, modifier = Modifier.padding(top = 12.dp))
        }
    }
}

@Composable
private fun TimelineItem(s: RunState, r: TimelineRow, localAnchors: List<String>) {
    val c = LocalLab.current
    val color = when (r.kind) {
        TimelineKind.MISSED, TimelineKind.STEP_SKIPPED -> c.missed
        TimelineKind.MEASUREMENT -> c.done
        TimelineKind.ANCHOR, TimelineKind.RUN_STARTED, TimelineKind.RUN_ENDED -> c.primary
        TimelineKind.CLOCK, TimelineKind.CORRECTION -> c.uncertain
        TimelineKind.OBSERVATION -> c.due
        else -> c.text
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.width(92.dp)) {
            Text(TimeFormat.clock(r.wallMs, s.start.tzOffsetS), style = LabType.labelNum)
            Text(TimeFormat.elapsed(r.tRunMs) + qualityMark(r.quality), style = LabType.caption,
                color = if (r.quality == TimeQuality.MONOTONIC) c.muted else c.uncertain)
        }
        Column(Modifier.weight(1f)) {
            Text(r.title, style = LabType.body, color = color)
            r.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = LabType.caption, color = c.muted) }
            val rel = localAnchors.mapNotNull { a -> s.relativeAfter(a, r.tRunMs)?.let { "${s.anchorDef(a).label} ${TimeFormat.elapsed(it)}" } }
            if (rel.isNotEmpty()) Text(rel.joinToString(" · "), style = LabType.caption, color = c.muted)
        }
    }
    HorizontalDivider(color = c.outline)
}

@Composable
fun MeasurementTable(s: RunState, nowT: Long?, modifier: Modifier = Modifier, onPoint: ((SamplePoint) -> Unit)? = null,
                     onMeasurement: ((labrun.core.Measurement) -> Unit)? = null,
                     onExtra: ((labrun.core.SamplingPlanDef) -> Unit)? = null,
                     onNewTable: (() -> Unit)? = null,
                     onStopTable: ((labrun.core.AdhocTable) -> Unit)? = null) {
    val c = LocalLab.current
    Column(modifier) {
        if (onNewTable != null && !s.isEnded)
            androidx.compose.material3.OutlinedButton(onClick = onNewTable, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text("＋新建计划外测量表（自定义列，手动或定时记录）", style = LabType.label)
            }
        s.allPlans.forEach { plan ->
            val pts = s.points.filter { it.plan.id == plan.id }
            val extras = s.extraMeasurementsAll.filter { it.body.planId == plan.id }
            val table = s.adhoc(plan.id)
            val anchorLabel = s.anchorDef(plan.anchorId).label
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.label, style = LabType.label)
                    Text(
                        if (table == null) "以“$anchorLabel”为起点"
                        else "计划外测量表 · " + (table.created.intervalS?.let { iv -> "每 ${TimeFormat.elapsed(iv * 1000).drop(1)}，共 ${table.created.count} 次" + if (table.stopped != null) "（已停止）" else "" } ?: "手动记录") +
                            " · 列：" + plan.fields.joinToString("、") { f -> f.label + (f.unit?.let { "（$it）" } ?: "") },
                        style = LabType.caption, color = c.muted)
                }
                val canAdd = onExtra != null && s.anchorTRun(plan.anchorId) != null && !s.isEnded
                if (table != null && table.isTimed && table.stopped == null && onStopTable != null && !s.isEnded)
                    TextButton(onClick = { onStopTable(table) }) { Text("停止", color = c.overdue) }
                if (canAdd) androidx.compose.material3.OutlinedButton(onClick = { onExtra!!(plan) }) {
                    Text(if (table != null && !table.isTimed) "＋记录一行" else "＋计划外", style = LabType.label)
                }
            }
            if (pts.isEmpty() && table == null)
                Text("“$anchorLabel”未建立，采样计划尚未生成：" + plan.offsetsSeconds.joinToString("、") { TimeFormat.elapsed(it * 1000) },
                    style = LabType.caption, color = c.muted)
            if (pts.isEmpty() && extras.isEmpty() && table != null)
                Text("还没有记录。点“＋记录一行”，时间自动记下。", style = LabType.caption, color = c.muted)
            pts.forEach { p ->
                val look = pointLook(p, nowT ?: Long.MAX_VALUE, s)
                val m = p.measurement
                Row(
                    Modifier.fillMaxWidth()
                        .then(if (onPoint != null) Modifier.clickableRow { onPoint(p) } else Modifier)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("${p.index + 1}", style = LabType.title, color = c.muted, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text("计划 " + s.moment(p.plannedTRunMs, s.wallAt(p.plannedTRunMs), plan.anchorId), style = LabType.labelNum)
                        if (m != null) {
                            Text("实测 " + s.moment(m.measuredTRunMs, m.measuredWallMs, plan.anchorId) + qualityMark(m.measuredQuality),
                                style = LabType.caption, color = if (m.measuredQuality == TimeQuality.MONOTONIC) c.muted else c.uncertain)
                            if (m.measuredQuality == TimeQuality.USER_ENTERED)
                                Text("录入 " + s.moment(m.enteredTRunMs, m.enteredWallMs), style = LabType.caption, color = c.muted)
                            if (m.isCorrected) Text("已更正 ${m.corrections.size} 次，原值 " + plan.fields.joinToString(" ") { f -> m.body.values[f.id]?.value ?: "" },
                                style = LabType.caption, color = c.uncertain)
                        }
                        if (p.voided.isNotEmpty()) Text("作废 ${p.voided.size} 条：" + p.voided.joinToString("；") { v -> plan.fields.joinToString(" ") { f -> v.values[f.id]?.value ?: "" } + "（${v.voided!!.first.reason}）" },
                            style = LabType.caption, color = c.missed)
                    }
                    Chip(look.text, look.color)
                }
                HorizontalDivider(color = c.outline)
            }
            // 计划外记录：逐行显示在所属计划/表下面，点一行可更正或作废
            extras.sortedBy { it.measuredTRunMs }.forEachIndexed { i, m ->
                Row(
                    Modifier.fillMaxWidth().then(if (onMeasurement != null && !m.isVoided) Modifier.clickableRow { onMeasurement(m) } else Modifier).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (table != null && !table.isTimed) "${i + 1}" else "外", style = LabType.title, color = c.muted, modifier = Modifier.width(24.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.moment(m.measuredTRunMs, m.measuredWallMs, plan.anchorId) + qualityMark(m.measuredQuality), style = LabType.labelNum,
                            color = if (m.isVoided) c.muted else c.text)
                        if (m.measuredQuality == TimeQuality.USER_ENTERED) Text("录入 " + s.moment(m.enteredTRunMs, m.enteredWallMs), style = LabType.caption, color = c.muted)
                        if (m.isCorrected) Text("已更正 ${m.corrections.size} 次，原值 " + plan.fields.joinToString(" ") { f -> m.body.values[f.id]?.value ?: "" },
                            style = LabType.caption, color = c.uncertain)
                        m.voided?.let { (v, _) -> Text("已作废：${v.reason}", style = LabType.caption, color = c.missed) }
                    }
                    Chip(plan.fields.joinToString(" ") { f -> (m.values[f.id]?.value ?: "") + (f.unit?.let { " $it" } ?: "") }, if (m.isVoided) c.missed else c.done)
                }
                HorizontalDivider(color = c.outline)
            }
        }
    }
}

@Composable
fun Tabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalLab.current
    Row(Modifier.fillMaxWidth().background(c.surfaceAlt, RoundedCornerShape(20.dp)).padding(4.dp)) {
        options.forEachIndexed { i, o ->
            val sel = i == selected
            Box(
                Modifier.weight(1f).background(if (sel) c.surface else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickableRow { onSelect(i) }.padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) { Text(o, style = LabType.label, color = if (sel) c.primary else c.muted) }
        }
    }
}

fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)

package labrun.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import labrun.android.Repository
import labrun.core.DesignTokens
import labrun.core.AnchorTrigger
import labrun.core.FieldType
import labrun.core.PointStatus
import labrun.core.RunState
import labrun.core.SamplePoint
import labrun.core.StepStatus
import labrun.core.TimeFormat
import kotlinx.coroutines.delay
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun newAction() = UUID.randomUUID().toString()

/** “HH:mm[:ss]” → 不晚于现在的最近一次该时刻（墙上毫秒）。解析失败返回 null。 */
fun parseClock(text: String): Long? {
    val t = try { LocalTime.parse(text.trim(), DateTimeFormatter.ofPattern(if (text.trim().length <= 5) "H:mm" else "H:mm:ss")) } catch (_: Exception) { return null }
    val zone = ZoneId.systemDefault()
    var dt = LocalDate.now(zone).atTime(t).atZone(zone)
    if (dt.toInstant().toEpochMilli() > System.currentTimeMillis() + 1000) dt = dt.minusDays(1)
    return dt.toInstant().toEpochMilli()
}

@Composable
fun RunScreen(repo: Repository, modifier: Modifier, onBack: () -> Unit, onEnded: (String) -> Unit) {
    val s = repo.state
    if (s == null) {
        // 刚结束：跳到详情
        repo.lastEndedRunId?.let { id -> LaunchedEffect(id) { onEnded(id) } }
        Column(modifier.padding(16.dp)) { Text("没有进行中的实验"); TextButton(onClick = onBack) { Text("‹ 返回") } }
        return
    }
    val c = LocalLab.current
    val nowT = rememberNowTRun(repo)
    var tab by remember { mutableIntStateOf(0) }
    var recordPoint by remember { mutableStateOf<SamplePoint?>(null) }
    var showObservation by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var showSkip by remember { mutableStateOf(false) }
    var anchorDialog by remember { mutableStateOf<String?>(null) }
    var anchorFix by remember { mutableStateOf<String?>(null) }
    var editMeasurement by remember { mutableStateOf<labrun.core.Measurement?>(null) }
    var revokePoint by remember { mutableStateOf<SamplePoint?>(null) }
    var revertStep by remember { mutableStateOf<labrun.core.StepState?>(null) }
    var editObs by remember { mutableStateOf<labrun.core.Observation?>(null) }
    var extraPlan by remember { mutableStateOf<labrun.core.SamplingPlanDef?>(null) }
    var newTable by remember { mutableStateOf(false) }
    var stopTable by remember { mutableStateOf<labrun.core.AdhocTable?>(null) }
    val onPoint: (SamplePoint) -> Unit = { p ->
        when (p.status) {
            PointStatus.OPEN -> recordPoint = p
            PointStatus.RECORDED -> editMeasurement = p.measurement
            PointStatus.MISSED -> revokePoint = p
        }
    }

    Column(modifier) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("‹ 首页") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showEnd = true }) { Text("结束实验", color = c.overdue) }
            }
            Text(s.protocol.title, style = LabType.label, color = c.muted)
            Text(TimeFormat.elapsed(nowT), style = LabType.display, color = c.primary)
            Text("开始于 ${TimeFormat.clock(s.start.wallMs, s.start.tzOffsetS)}", style = LabType.caption, color = c.muted)
            // 局部计时起点
            s.anchors.values.filter { it.def.id != s.protocol.runStartAnchorId && !s.isAdhocAnchor(it.def.id) }.forEach { a ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.def.label, style = LabType.label, modifier = Modifier.weight(1f))
                    val rel = s.relativeTo(a.def.id, nowT)
                    Text(TimeFormat.elapsed(rel), style = LabType.labelNum, color = if (rel == null) c.muted else c.text)
                    if (rel == null && a.def.createOn == AnchorTrigger.Manual)
                        TextButton(onClick = { anchorDialog = a.def.id }) { Text("建立") }
                    if (rel != null) TextButton(onClick = { anchorFix = a.def.id }) { Text(if (a.isCorrected) "已更正" else "更正", style = LabType.caption) }
                }
            }
            if (s.timeUncertain) Banner("本次实验出现过重启或系统时间变化，部分时间为估算", c.uncertain)
            Spacer(Modifier.height(8.dp))
            Tabs(listOf("操作", "采样", "现象", "时间线"), tab) { tab = it }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            when (tab) {
                0 -> StepsPanel(repo, s, nowT, onSkip = { showSkip = true }, onPoint = onPoint, onRevert = { revertStep = it }, onRecordRow = { extraPlan = it })
                1 -> MeasurementTable(s, nowT, onPoint = onPoint, onMeasurement = { editMeasurement = it }, onExtra = { extraPlan = it },
                    onNewTable = { newTable = true }, onStopTable = { stopTable = it })
                2 -> ObservationsPanel(repo, s) { editObs = it }
                else -> {
                    TimelineList(s)
                    SectionTitle("更正记录")
                    CorrectionsPanel(s)
                }
            }
            Spacer(Modifier.height(80.dp))
        }

        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showObservation = true }, modifier = Modifier.weight(1f).height(52.dp)) { Text("记录现象") }
        }
    }

    recordPoint?.let { p -> RecordDialog(repo, s, p.plan, p) { recordPoint = null } }
    extraPlan?.let { pl -> RecordDialog(repo, s, pl, null) { extraPlan = null } }
    if (newTable) NewTableDialog(repo) { newTable = false }
    stopTable?.let { t -> StopTableDialog(repo, s, t) { stopTable = null } }
    if (showObservation) ObservationDialog(repo, s) { showObservation = false }
    if (showEnd) EndDialog(repo, s, nowT, onDone = { ok -> showEnd = false; if (ok) repo.lastEndedRunId?.let(onEnded) })
    if (showSkip) SkipDialog(repo, s) { showSkip = false }
    anchorDialog?.let { id -> AnchorDialog(repo, s, id) { anchorDialog = null } }
    anchorFix?.let { id -> CorrectAnchorDialog(repo, s, id) { anchorFix = null } }
    editMeasurement?.let { m -> CorrectMeasurementDialog(repo, s, m) { editMeasurement = null } }
    revokePoint?.let { p -> RevokeMissedDialog(repo, s, p.plan.id, p.index) { revokePoint = null } }
    revertStep?.let { st -> RevertStepDialog(repo, s, st) { revertStep = null } }
    editObs?.let { o -> CorrectObservationDialog(repo, s, o) { editObs = null } }
}

@Composable
private fun StepsPanel(repo: Repository, s: RunState, nowT: Long, onSkip: () -> Unit, onPoint: (SamplePoint) -> Unit, onRevert: (labrun.core.StepState) -> Unit,
                       onRecordRow: (labrun.core.SamplingPlanDef) -> Unit) {
    val c = LocalLab.current
    val cur = s.currentStep
    if (cur == null) {
        Card(borderColor = c.done) {
            Text("所有步骤已处理", style = LabType.title)
            Text("核对采样后点右上角“结束实验”。", style = LabType.body, color = c.muted)
        }
    } else {
        val idx = s.steps.indexOf(cur)
        val actionId = remember(cur.def.id) { newAction() }
        // 防误触：上一步刚确认、按钮原位换成下一步时，连点会把下一步也确认掉
        var armed by remember(cur.def.id) { mutableStateOf(false) }
        LaunchedEffect(cur.def.id) { delay(DesignTokens.CONFIRM_ARM_MS); armed = true }
        Card(borderColor = c.primary) {
            Text("第 ${idx + 1} / ${s.steps.size} 步", style = LabType.label, color = c.primary)
            Text(cur.def.title, style = LabType.title, modifier = Modifier.padding(vertical = 4.dp))
            cur.def.instruction?.let { Text(it, style = LabType.body) }
            s.stepNotBeforeTRun(cur.def)?.let { nb ->
                val left = nb - nowT
                Text(if (left > 0) "计划时间未到，还有 ${TimeFormat.elapsed(left).drop(1)}" else "计划时间已到",
                    style = LabType.label, color = if (left > 0) c.due else c.done, modifier = Modifier.padding(top = 8.dp))
            } ?: cur.def.notBefore?.let { Text("计划起点“${s.anchorDef(it.anchorId).label}”未建立", style = LabType.label, color = c.muted) }
            s.protocol.anchorCreatedBy(cur.def.id)?.let {
                Text("确认后建立计时起点“${it.label}”", style = LabType.label, color = c.primary, modifier = Modifier.padding(top = 8.dp))
            }
            val overdue = s.points.filter { it.status == PointStatus.OPEN && it.plannedTRunMs + DesignTokens.DUE_WINDOW_MS <= nowT && it.plan.id in cur.def.samplingPlanIds }
            if (overdue.isNotEmpty()) Text("有 ${overdue.size} 个采样逾期未测；确认步骤不会关闭它们。", style = LabType.label, color = c.overdue, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { repo.confirmStep(cur.def.id, actionId) }, enabled = armed, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("确认完成", style = LabType.title) }
            TextButton(onClick = onSkip) { Text("跳过此步…", color = c.muted) }
        }
    }

    // 当前需要关注的采样点：到期、逾期，以及下一个
    val open = s.points.filter { it.status == PointStatus.OPEN }
    val focus = open.filter { it.plannedTRunMs <= nowT } + open.filter { it.plannedTRunMs > nowT }.take(1)
    if (focus.isNotEmpty()) {
        SectionTitle("采样")
        focus.forEach { p ->
            val look = pointLook(p, nowT, s)
            Card(Modifier.padding(bottom = 8.dp).clickableRow { onPoint(p) }, borderColor = look.color) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${p.plan.label} 第 ${p.index + 1} 次", style = LabType.body)
                        Text("计划 " + s.moment(p.plannedTRunMs, s.wallAt(p.plannedTRunMs), p.plan.anchorId), style = LabType.caption, color = c.muted)
                    }
                    Chip(look.text, look.color)
                }
            }
        }
    }

    // 手动计划外测量表：在操作页也能一键记一行
    val manualTables = s.adhocTables.filter { !it.isTimed }
    if (manualTables.isNotEmpty()) {
        SectionTitle("计划外测量表")
        manualTables.forEach { t ->
            val n = s.extraMeasurements.count { it.body.planId == t.id }
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.created.title, style = LabType.body)
                    Text("已记 $n 行 · 列：" + t.plan.fields.joinToString("、") { it.label }, style = LabType.caption, color = c.muted)
                }
                OutlinedButton(onClick = { onRecordRow(t.plan) }) { Text("＋记录一行") }
            }
        }
    }
    SectionTitle("全部步骤（点最近确认的一步可撤销）")
    val lastDone = s.lastDoneStep
    s.steps.forEachIndexed { i, st ->
        val (txt, col) = when (st.status) {
            StepStatus.CONFIRMED -> "已确认 " + TimeFormat.elapsed(st.doneEvent!!.tRunMs) to c.done
            StepStatus.SKIPPED -> "已跳过" to c.missed
            StepStatus.CURRENT -> "进行中" to c.primary
            StepStatus.PENDING -> "未开始" to c.muted
            StepStatus.NOT_COMPLETED -> "未完成" to c.missed
        }
        Row(Modifier.fillMaxWidth().then(if (st == lastDone) Modifier.clickableRow { onRevert(st) } else Modifier).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. ${st.def.title}", style = LabType.body, modifier = Modifier.weight(1f))
            if (st.reverts.isNotEmpty()) Chip("撤销过 ${st.reverts.size} 次", c.uncertain, Modifier.padding(end = 4.dp))
            Chip(txt, col)
        }
    }
}

@Composable
private fun TimeChoice(label: String, useNow: Boolean, onUseNow: (Boolean) -> Unit, text: String, onText: (String) -> Unit) {
    Text(label, style = LabType.label, modifier = Modifier.padding(top = 12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 整个“圆点+文字”都可点（实测只有圆点可点时容易点空）
        Row(Modifier.selectable(useNow, role = Role.RadioButton) { onUseNow(true) }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = useNow, onClick = null, modifier = Modifier.padding(12.dp)); Text("现在")
        }
        Row(Modifier.selectable(!useNow, role = Role.RadioButton) { onUseNow(false) }.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = !useNow, onClick = null, modifier = Modifier.padding(12.dp)); Text("更早")
        }
    }
    if (!useNow) OutlinedTextField(text, onText, singleLine = true, label = { Text("时:分:秒") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), isError = parseClock(text) == null)
}

/** 录入：[p] 为计划采样点；为空时是 [plan] 的计划外追加测量。 */
@Composable
private fun RecordDialog(repo: Repository, s: RunState, plan: labrun.core.SamplingPlanDef, p: SamplePoint?, onClose: () -> Unit) {
    val c = LocalLab.current
    val actionId = remember { newAction() }
    val values = remember { mutableStateOf(plan.fields.associate { it.id to "" }) }
    var useNow by remember { mutableStateOf(true) }
    var clockText by remember { mutableStateOf(TimeFormat.clock(System.currentTimeMillis(), s.start.tzOffsetS)) }
    var confirmMissed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text(if (p == null) "${plan.label}：计划外测量" else "${plan.label} 第 ${p.index + 1} 次"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (p != null) Text("计划 " + s.moment(p.plannedTRunMs, s.wallAt(p.plannedTRunMs), plan.anchorId), style = LabType.caption, color = c.muted)
                else Text("不占用计划采样点，单独列在测量表“计划外测量”中；时间以“${s.anchorDef(plan.anchorId).label}”为相对起点。", style = LabType.caption, color = c.muted)
                plan.fields.forEach { f ->
                    OutlinedTextField(values.value[f.id].orEmpty(), { v -> values.value = values.value + (f.id to v) },
                        label = { Text(f.label + (f.unit?.let { "（$it）" } ?: "")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = when (f.type) {
                            FieldType.DECIMAL -> KeyboardType.Decimal; FieldType.INTEGER -> KeyboardType.Number; FieldType.TEXT -> KeyboardType.Text }),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                TimeChoice("实际测量时刻", useNow, { useNow = it }, clockText) { clockText = it }
                if (confirmMissed) Text("再点一次“标记漏测”确认：该点将保持空值。", style = LabType.label, color = c.overdue, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                val at = if (useNow) null else parseClock(clockText) ?: run { repo.reject("时刻格式应为 时:分:秒"); return@Button }
                if (repo.record(plan.id, p?.index, values.value, at, actionId)) onClose()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (p != null) TextButton(onClick = {
                    if (!confirmMissed) confirmMissed = true
                    else if (repo.markMissed(plan.id, p.index, "manual", actionId + ":missed")) onClose()
                }) { Text("标记漏测", color = c.missed) }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ObservationDialog(repo: Repository, s: RunState, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalLab.current
    val actionId = remember { newAction() }
    var text by remember { mutableStateOf("") }
    var useNow by remember { mutableStateOf(true) }
    var clockText by remember { mutableStateOf(TimeFormat.clock(System.currentTimeMillis(), s.start.tzOffsetS)) }
    var linkStep by remember { mutableStateOf(false) }
    val photos = remember { mutableStateListOf<File>() }
    var pending by remember { mutableStateOf<File?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending; pending = null
        if (f != null) { if (ok && f.length() > 0) photos += f else f.delete() }
    }
    fun discard() { photos.forEach { it.delete() }; onClose() }
    AlertDialog(
        onDismissRequest = ::discard,
        title = { Column { Text("记录现象"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(text, { text = it }, label = { Text("看到了什么") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val f = repo.newPhotoFile(); pending = f
                        camera.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f))
                    }) { Text("拍照") }
                    Text("  ${photos.size} 张", style = LabType.caption, color = c.muted)
                }
                Row { photos.forEach { f ->
                    val bmp = remember(f) { BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap() }
                    bmp?.let { Image(it, null, Modifier.size(64.dp).padding(end = 4.dp), contentScale = ContentScale.Crop) }
                } }
                TimeChoice("发生时间", useNow, { useNow = it }, clockText) { clockText = it }
                s.currentStep?.let { cur ->
                    Row(Modifier.selectable(linkStep, role = Role.Checkbox) { linkStep = !linkStep }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(linkStep, null, Modifier.padding(12.dp)); Text("关联当前步骤“${cur.def.title}”", style = LabType.label)
                    }
                }
                Text("记录现象不会推进步骤。", style = LabType.caption, color = c.muted)
            }
        },
        confirmButton = {
            Button(onClick = {
                val at = if (useNow) null else parseClock(clockText) ?: run { repo.reject("时刻格式应为 时:分:秒"); return@Button }
                if (repo.addObservation(text.ifBlank { null }, photos.toList(), at, if (linkStep) s.currentStep?.def?.id else null, actionId)) onClose()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = ::discard) { Text("取消") } },
    )
}

@Composable
private fun EndDialog(repo: Repository, s: RunState, nowT: Long, onDone: (Boolean) -> Unit) {
    val c = LocalLab.current
    val actionId = remember { newAction() }
    val open = remember(s) { s.openPoints }
    val unfinished = s.steps.filter { it.status == StepStatus.CURRENT || it.status == StepStatus.PENDING }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Column { Text("结束实验？"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("结束于 ${TimeFormat.elapsed(nowT)}。结束后不能再新增记录或确认步骤；录错的数值、现象和起点时刻仍可更正（原值保留）。", style = LabType.body)
                if (open.isNotEmpty()) {
                    Text("以下 ${open.size} 个采样将记为漏测（值为空）：", style = LabType.label, color = c.overdue, modifier = Modifier.padding(top = 12.dp))
                    open.forEach { p -> Text("· ${p.plan.label} 第 ${p.index + 1} 次（计划 ${TimeFormat.elapsed(p.plannedTRunMs)}）", style = LabType.caption) }
                }
                if (unfinished.isNotEmpty()) {
                    Text("以下 ${unfinished.size} 个步骤将记为未完成：", style = LabType.label, color = c.missed, modifier = Modifier.padding(top = 12.dp))
                    unfinished.forEach { Text("· ${it.def.title}", style = LabType.caption) }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onDone(repo.endRun(open.map { it.key }.toSet(), actionId)) },
                colors = ButtonDefaults.buttonColors(containerColor = c.overdue)) { Text("结束实验") }
        },
        dismissButton = { TextButton(onClick = { onDone(false) }) { Text("继续实验") } },
    )
}

@Composable
private fun SkipDialog(repo: Repository, s: RunState, onClose: () -> Unit) {
    val cur = s.currentStep ?: return onClose()
    val actionId = remember { newAction() }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("跳过“${cur.def.title}”"); RejectionText(repo) } },
        text = { OutlinedTextField(reason, { reason = it }, label = { Text("理由（必填）") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { if (repo.skipStep(cur.def.id, reason, actionId)) onClose() }, enabled = reason.isNotBlank()) { Text("跳过") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
private fun AnchorDialog(repo: Repository, s: RunState, anchorId: String, onClose: () -> Unit) {
    val actionId = remember { newAction() }
    var useNow by remember { mutableStateOf(true) }
    var clockText by remember { mutableStateOf(TimeFormat.clock(System.currentTimeMillis(), s.start.tzOffsetS)) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("建立计时起点“${s.anchorDef(anchorId).label}”"); RejectionText(repo) } },
        text = { Column { Text("以它为起点的采样会立即生成。时刻如有误，之后可用“更正”修改（需填理由，原记录保留）。", style = LabType.body); TimeChoice("起点时刻", useNow, { useNow = it }, clockText) { clockText = it } } },
        confirmButton = {
            Button(onClick = {
                val at = if (useNow) null else parseClock(clockText) ?: run { repo.reject("时刻格式应为 时:分:秒"); return@Button }
                if (repo.establishAnchor(anchorId, at, actionId)) onClose()
            }) { Text("建立") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

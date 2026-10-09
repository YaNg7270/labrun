package labrun.android.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import labrun.android.Repository
import labrun.core.Attachment
import labrun.core.FieldType
import labrun.core.Measurement
import labrun.core.Observation
import labrun.core.RunState
import labrun.core.StepState
import labrun.core.TimeFormat
import labrun.core.TimeQuality
import java.io.File
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun newAction() = UUID.randomUUID().toString()

// ------------------------------------------------------------------ 照片

/** 按目标尺寸采样解码，避免大图占满内存。 */
fun loadBitmap(file: File, maxDim: Int): ImageBitmap? {
    if (!file.exists()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
    return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}

@Composable
fun Thumb(file: File, sizeDp: Int, onClick: () -> Unit) {
    val c = LocalLab.current
    val bmp = remember(file.path) { loadBitmap(file, sizeDp * 3) }
    if (bmp != null) Image(bmp, "照片", Modifier.size(sizeDp.dp).clickableRow(onClick), contentScale = ContentScale.Crop)
    else Box(Modifier.size(sizeDp.dp).background(c.surfaceAlt), contentAlignment = Alignment.Center) { Text("缺失", style = LabType.caption, color = c.overdue) }
}

/** 全屏看照片；点任意处关闭。 */
@Composable
fun PhotoViewer(file: File, caption: String, onClose: () -> Unit) {
    val bmp = remember(file.path) { loadBitmap(file, 2048) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black).clickableRow(onClose), contentAlignment = Alignment.Center) {
            if (bmp != null) Image(bmp, caption, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit)
            else Text("照片文件缺失：${file.name}", color = Color.White)
            Text(caption + "　（点任意处关闭）", color = Color.White, style = LabType.caption,
                modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp))
        }
    }
}

/** 现象列表：文字、照片缩略图（点开全屏）、更正/作废标记；点卡片可更正。 */
@Composable
fun ObservationsPanel(repo: Repository, s: RunState, onEdit: (Observation) -> Unit) {
    val c = LocalLab.current
    var viewing by remember { mutableStateOf<Pair<File, String>?>(null) }
    val list = s.observationsAll.sortedBy { it.occurredTRunMs }
    if (list.isEmpty()) Text("还没有现象记录。底部“记录现象”可随时添加文字和照片。", style = LabType.body, color = c.muted)
    list.forEach { o ->
        val t = s.moment(o.occurredTRunMs, o.occurredWallMs) + qualityMark(o.occurredQuality)
        Card(Modifier.padding(bottom = 8.dp).clickableRow { if (!o.isVoided) onEdit(o) }, borderColor = if (o.isVoided) c.outline else null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t, style = LabType.caption, color = if (o.occurredQuality == TimeQuality.MONOTONIC) c.muted else c.uncertain, modifier = Modifier.weight(1f))
                if (o.isVoided) Chip("已作废", c.missed) else if (o.isCorrected) Chip("已更正", c.uncertain)
            }
            o.body.stepId?.let { Text("步骤：${s.protocol.step(it).title}", style = LabType.caption, color = c.muted) }
            o.text?.let {
                Text(it, style = LabType.body, color = if (o.isVoided) c.muted else c.text,
                    textDecoration = if (o.isVoided) TextDecoration.LineThrough else null, modifier = Modifier.padding(top = 4.dp))
            }
            if (o.isCorrected && o.text != o.body.text) Text("原内容：${o.body.text ?: "（无文字）"}", style = LabType.caption, color = c.muted)
            o.voided?.let { (v, _) -> Text("作废理由：${v.reason}", style = LabType.caption, color = c.missed) }
            if (o.body.attachments.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                o.body.attachments.forEach { a ->
                    val f = repo.attachmentFile(s.runId, a)
                    Thumb(f, 96) { viewing = f to t }
                }
            }
        }
    }
    viewing?.let { (f, cap) -> PhotoViewer(f, cap) { viewing = null } }
}

// ------------------------------------------------------------------ 时间输入

/** “时:分[:秒]” → 墙上毫秒；日期取参考时刻（按实验时区）所在日，相差超过 12 小时则跨日校正。 */
fun parseClockNear(text: String, refWallMs: Long, tzOffsetS: Int): Long? {
    val t = try { LocalTime.parse(text.trim(), DateTimeFormatter.ofPattern(if (text.trim().length <= 5) "H:mm" else "H:mm:ss")) } catch (_: Exception) { return null }
    val off = ZoneOffset.ofTotalSeconds(tzOffsetS)
    val refDate = Instant.ofEpochMilli(refWallMs).atOffset(off).toLocalDate()
    var ms = refDate.atTime(t).atOffset(off).toInstant().toEpochMilli()
    if (ms - refWallMs > 12 * 3600_000L) ms -= 86_400_000L
    if (refWallMs - ms > 12 * 3600_000L) ms += 86_400_000L
    return ms
}

@Composable
private fun OptionalTime(label: String, enabled: Boolean, onEnabled: (Boolean) -> Unit, text: String, onText: (String) -> Unit, valid: Boolean) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp).selectable(enabled, role = Role.Checkbox) { onEnabled(!enabled) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(enabled, null, Modifier.padding(12.dp)); Text(label, style = LabType.label)
    }
    if (enabled) OutlinedTextField(text, onText, singleLine = true, label = { Text("时:分:秒（实验时区）") }, isError = !valid, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReasonField(reason: String, onReason: (String) -> Unit, required: Boolean) {
    OutlinedTextField(reason, onReason, label = { Text(if (required) "理由（必填）" else "理由（建议填写）") },
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
}

// ------------------------------------------------------------------ 更正对话框

@Composable
fun CorrectMeasurementDialog(repo: Repository, s: RunState, m: Measurement, onClose: () -> Unit) {
    val c = LocalLab.current
    val plan = s.plan(m.body.planId)
    val actionId = remember { newAction() }
    val voidAction = remember { newAction() }
    var values by remember { mutableStateOf(plan.fields.associate { it.id to (m.values[it.id]?.value ?: "") }) }
    var changeTime by remember { mutableStateOf(false) }
    var clockText by remember { mutableStateOf(TimeFormat.clock(m.measuredWallMs, s.start.tzOffsetS)) }
    var reason by remember { mutableStateOf("") }
    var confirmVoid by remember { mutableStateOf(false) }
    val parsed = parseClockNear(clockText, m.measuredWallMs, s.start.tzOffsetS)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("更正：${plan.label} ${m.body.pointIndex?.let { "第 ${it + 1} 次" } ?: if (s.adhoc(plan.id)?.isTimed == false) "记录" else "计划外"}"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("原始记录：" + plan.fields.joinToString(" ") { f -> (m.body.values[f.id]?.value ?: "") + (f.unit?.let { " $it" } ?: "") } +
                    " · 实测 " + TimeFormat.clock(m.body.measuredWallMs, s.start.tzOffsetS) + " · 录入 " + TimeFormat.clock(m.enteredWallMs, s.start.tzOffsetS),
                    style = LabType.caption, color = c.muted)
                m.corrections.forEachIndexed { i, (b, e) ->
                    Text("第 ${i + 1} 次更正（${TimeFormat.clock(e.wallMs, e.tzOffsetS)}）：" + plan.fields.joinToString(" ") { f -> b.values[f.id]?.value ?: "" } +
                        (b.reason?.let { "；$it" } ?: ""), style = LabType.caption, color = c.uncertain)
                }
                plan.fields.forEach { f ->
                    OutlinedTextField(values[f.id].orEmpty(), { v -> values = values + (f.id to v) },
                        label = { Text(f.label + (f.unit?.let { "（$it）" } ?: "")) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = when (f.type) {
                            FieldType.DECIMAL -> KeyboardType.Decimal; FieldType.INTEGER -> KeyboardType.Number; FieldType.TEXT -> KeyboardType.Text }),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                OptionalTime("同时更正实测时刻", changeTime, { changeTime = it }, clockText, { clockText = it }, parsed != null)
                ReasonField(reason, { reason = it }, required = confirmVoid)
                Text("更正不会删除原值：表格显示更正后的值，报告附完整更正记录。", style = LabType.caption, color = c.muted, modifier = Modifier.padding(top = 8.dp))
                if (confirmVoid) Text("作废后该点${if (s.isEnded) "记为漏测" else "恢复为待测，可以重新录入"}。填写理由后再点一次“作废”。", style = LabType.label, color = c.overdue)
            }
        },
        confirmButton = {
            Button(onClick = {
                val raw = values.takeIf { v -> v != plan.fields.associate { it.id to (m.values[it.id]?.value ?: "") } }
                val at = if (changeTime) parsed ?: run { repo.reject("时刻格式应为 时:分:秒"); return@Button } else null
                if (repo.correctMeasurement(s.runId, m.id, raw, at, reason, actionId)) onClose()
            }) { Text("保存更正") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    if (!confirmVoid) confirmVoid = true
                    else if (repo.voidMeasurement(s.runId, m.id, reason, voidAction)) onClose()
                }) { Text("作废", color = c.missed) }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
}

@Composable
fun RevokeMissedDialog(repo: Repository, s: RunState, planId: String, index: Int, onClose: () -> Unit) {
    val actionId = remember { newAction() }
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("撤销漏测：${s.plan(planId).label} 第 ${index + 1} 次"); RejectionText(repo) } },
        text = { Column { Text("撤销后该点恢复为待测，可以录入。原“漏测”记录会保留在更正记录里。", style = LabType.body); ReasonField(reason, { reason = it }, true) } },
        confirmButton = { Button(onClick = { if (repo.revokeMissed(planId, index, reason, actionId)) onClose() }, enabled = reason.isNotBlank()) { Text("撤销漏测") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
fun RevertStepDialog(repo: Repository, s: RunState, step: StepState, onClose: () -> Unit) {
    val c = LocalLab.current
    val actionId = remember { newAction() }
    var reason by remember { mutableStateOf("") }
    val anchor = s.protocol.anchorCreatedBy(step.def.id)?.takeIf { s.anchors[it.id]?.established != null }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("撤销确认：${step.def.title}"); RejectionText(repo) } },
        text = {
            Column {
                Text("撤销后回到这一步，可以重新确认。原确认记录会保留在更正记录里。", style = LabType.body)
                anchor?.let { Text("这一步建立的计时起点“${it.label}”会一并撤销（仅当它下面还没有任何采样记录）。", style = LabType.label, color = c.due, modifier = Modifier.padding(top = 8.dp)) }
                ReasonField(reason, { reason = it }, true)
            }
        },
        confirmButton = { Button(onClick = { if (repo.revertStep(step.def.id, reason, actionId)) onClose() }, enabled = reason.isNotBlank()) { Text("撤销确认") } },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
fun CorrectAnchorDialog(repo: Repository, s: RunState, anchorId: String, onClose: () -> Unit) {
    val c = LocalLab.current
    val a = s.anchors.getValue(anchorId)
    val actionId = remember { newAction() }
    var clockText by remember { mutableStateOf(TimeFormat.clock(a.atWallMs!!, s.start.tzOffsetS)) }
    var reason by remember { mutableStateOf("") }
    val parsed = parseClockNear(clockText, a.atWallMs!!, s.start.tzOffsetS)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("更正起点时刻：${a.def.label}"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("当前：" + s.moment(a.tRunMs!!, a.atWallMs!!) + qualityMark(a.atQuality!!), style = LabType.body)
                if (a.isCorrected) Text("原始：" + s.moment(a.established!!.atTRunMs, a.established!!.atWallMs), style = LabType.caption, color = c.muted)
                OutlinedTextField(clockText, { clockText = it }, singleLine = true, label = { Text("实际时刻 时:分:秒") }, isError = parsed == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                ReasonField(reason, { reason = it }, true)
                Text("以它为起点的所有计划采样时刻会随之移动；已录入的测量值和实测时刻不变，偏差会重新计算。", style = LabType.caption, color = c.due, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            Button(onClick = {
                val at = parsed ?: run { repo.reject("时刻格式应为 时:分:秒"); return@Button }
                if (repo.correctAnchor(s.runId, anchorId, at, reason, actionId)) onClose()
            }, enabled = reason.isNotBlank()) { Text("更正") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
fun CorrectObservationDialog(repo: Repository, s: RunState, o: Observation, onClose: () -> Unit) {
    val c = LocalLab.current
    val actionId = remember { newAction() }
    val voidAction = remember { newAction() }
    var text by remember { mutableStateOf(o.text.orEmpty()) }
    var changeTime by remember { mutableStateOf(false) }
    var clockText by remember { mutableStateOf(TimeFormat.clock(o.occurredWallMs, s.start.tzOffsetS)) }
    var reason by remember { mutableStateOf("") }
    var confirmVoid by remember { mutableStateOf(false) }
    val parsed = parseClockNear(clockText, o.occurredWallMs, s.start.tzOffsetS)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("更正现象"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("原始：${o.body.text ?: "（无文字）"} · ${TimeFormat.clock(o.body.occurredWallMs, s.start.tzOffsetS)}", style = LabType.caption, color = c.muted)
                OutlinedTextField(text, { text = it }, label = { Text("内容") }, minLines = 3, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                OptionalTime("同时更正发生时间", changeTime, { changeTime = it }, clockText, { clockText = it }, parsed != null)
                ReasonField(reason, { reason = it }, required = confirmVoid)
                if (o.body.attachments.isNotEmpty()) Text("照片不能修改；如整条记录有误请作废。", style = LabType.caption, color = c.muted)
                if (confirmVoid) Text("填写理由后再点一次“作废”。", style = LabType.label, color = c.overdue)
            }
        },
        confirmButton = {
            Button(onClick = {
                val at = if (changeTime) parsed ?: run { repo.reject("时刻格式应为 时:分:秒"); return@Button } else null
                if (repo.correctObservation(s.runId, o.id, text, at, reason, actionId)) onClose()
            }) { Text("保存更正") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { if (!confirmVoid) confirmVoid = true else if (repo.voidObservation(s.runId, o.id, reason, voidAction)) onClose() }) {
                    Text("作废", color = c.missed)
                }
                TextButton(onClick = onClose) { Text("取消") }
            }
        },
    )
}

/** 更正记录列表（时间线中更正类事件的汇总）。 */
@Composable
fun CorrectionsPanel(s: RunState) {
    val c = LocalLab.current
    val rows = remember(s) { labrun.core.RunViews(s).corrections() }
    if (rows.isEmpty()) Text("没有更正。点测量表里已录入的数值、现象卡片或起点时刻即可更正；原记录始终保留。", style = LabType.body, color = c.muted)
    rows.forEach { r ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(r.title, style = LabType.body, color = c.uncertain)
            Text("更正于 ${TimeFormat.clock(r.wallMs, s.start.tzOffsetS)} · ${TimeFormat.elapsed(r.tRunMs)}", style = LabType.caption, color = c.muted)
            r.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = LabType.caption) }
        }
        androidx.compose.material3.HorizontalDivider(color = c.outline)
    }
}

// ------------------------------------------------------------------ 导出

@Composable
fun ExportDialog(repo: Repository, runId: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val c = LocalLab.current
    var kind by remember { mutableStateOf(Repository.ExportKind.HTML) }
    var file by remember { mutableStateOf<File?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val f = file ?: return@rememberLauncherForActivityResult
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            repo.message = "已保存 ${f.name}"; onClose()
        } catch (e: Exception) { repo.message = "保存失败：${e.message}" }
    }
    fun make(): File? = try { repo.export(runId, kind).also { file = it } } catch (e: Exception) { repo.message = "生成失败：${e.message}"; null }
    fun uri(f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("导出"); RejectionText(repo) } },
        text = {
            Column {
                Repository.ExportKind.entries.forEach { k ->
                    Row(Modifier.fillMaxWidth().selectable(k == kind, role = Role.RadioButton) { kind = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(k == kind, null, Modifier.padding(12.dp))
                        Column {
                            Text(k.label, style = LabType.body)
                            Text(when (k) {
                                Repository.ExportKind.PACKAGE -> "完整原始记录（含照片），传到电脑端导入"
                                Repository.ExportKind.HTML -> "可读报告，含照片；浏览器打开后可打印为 PDF"
                                Repository.ExportKind.XLSX -> "测量表、时间线、现象、步骤、更正记录分工作表"
                            }, style = LabType.caption, color = c.muted)
                        }
                    }
                }
                if (kind == Repository.ExportKind.HTML) OutlinedButton(onClick = {
                    val f = make() ?: return@OutlinedButton
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri(f), "text/html").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    } catch (_: Exception) { repo.message = "没有可以打开 HTML 的应用，请用“发送”或“保存”" }
                }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("在浏览器中打开") }
            }
        },
        confirmButton = {
            Button(onClick = {
                val f = make() ?: return@Button
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(kind.mime)
                    .putExtra(Intent.EXTRA_STREAM, uri(f)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "发送 ${kind.label}"))
            }) { Text("发送") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { make()?.let { saver.launch(it.name) } }) { Text("保存到文件") }
                TextButton(onClick = onClose) { Text("关闭") }
            }
        },
    )
}

/** 对话框内显示被拒绝的原因；对话框打开时清掉旧的。 */
@Composable
fun RejectionText(repo: Repository) {
    androidx.compose.runtime.LaunchedEffect(Unit) { repo.rejection = null }
    repo.rejection?.let { Text(it, style = LabType.label, color = LocalLab.current.overdue, modifier = Modifier.padding(top = 8.dp)) }
}

// ------------------------------------------------------------------ 计划外测量表

private class FieldDraft(label: String = "", type: FieldType = FieldType.DECIMAL, unit: String = "") {
    var label by mutableStateOf(label)
    var type by mutableStateOf(type)
    var unit by mutableStateOf(unit)
}

/** 新建计划外测量表：表名、自定义列（名称/类型/单位）、手动或定时（间隔 + 次数）。 */
@Composable
fun NewTableDialog(repo: Repository, onClose: () -> Unit) {
    val c = LocalLab.current
    val actionId = remember { newAction() }
    var title by remember { mutableStateOf("") }
    val fields = remember { androidx.compose.runtime.mutableStateListOf(FieldDraft()) }
    var timed by remember { mutableStateOf(false) }
    var interval by remember { mutableStateOf("30") }
    var minutes by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf("10") }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("新建计划外测量表"); RejectionText(repo) } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text("表名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("列（每行记录时填写；时间自动记下）", style = LabType.label, modifier = Modifier.padding(top = 12.dp))
                fields.forEachIndexed { i, f ->
                    Column(Modifier.fillMaxWidth().padding(top = 8.dp).background(c.surfaceAlt, androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(f.label, { f.label = it }, label = { Text("第 ${i + 1} 列名称") }, singleLine = true, modifier = Modifier.weight(1f))
                            if (fields.size > 1) TextButton(onClick = { fields.removeAt(i) }) { Text("删", color = c.overdue) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            listOf(FieldType.DECIMAL to "小数", FieldType.INTEGER to "整数", FieldType.TEXT to "文字").forEach { (t, l) ->
                                Row(Modifier.selectable(f.type == t, role = Role.RadioButton) { f.type = t }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(f.type == t, null, Modifier.padding(8.dp)); Text(l, style = LabType.label)
                                }
                            }
                        }
                        if (f.type != FieldType.TEXT) OutlinedTextField(f.unit, { f.unit = it }, label = { Text("单位（可空）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                }
                if (fields.size < 10) TextButton(onClick = { fields += FieldDraft() }) { Text("＋添加一列") }
                Text("记录方式", style = LabType.label, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.fillMaxWidth().selectable(!timed, role = Role.RadioButton) { timed = false }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(!timed, null, Modifier.padding(12.dp))
                    Column { Text("手动", style = LabType.body); Text("想记就点“记录一行”", style = LabType.caption, color = c.muted) }
                }
                Row(Modifier.fillMaxWidth().selectable(timed, role = Role.RadioButton) { timed = true }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(timed, null, Modifier.padding(12.dp))
                    Column { Text("定时", style = LabType.body); Text("从现在起每隔一段时间提醒一次（现在就是第 1 次），可中途停止", style = LabType.caption, color = c.muted) }
                }
                if (timed) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(5) }, label = { Text("间隔") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        Row(Modifier.selectable(!minutes, role = Role.RadioButton) { minutes = false }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(!minutes, null, Modifier.padding(8.dp)); Text("秒")
                        }
                        Row(Modifier.selectable(minutes, role = Role.RadioButton) { minutes = true }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(minutes, null, Modifier.padding(8.dp)); Text("分")
                        }
                    }
                    OutlinedTextField(count, { count = it.filter(Char::isDigit).take(3) }, label = { Text("次数（最多 500）") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    val iv = interval.toLongOrNull()?.let { if (minutes) it * 60 else it }
                    val n = count.toIntOrNull()
                    if (iv != null && n != null && n > 0) Text("共 $n 次，最后一次在 ${TimeFormat.elapsed((n - 1) * iv * 1000)} 后", style = LabType.caption, color = c.muted)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val iv = if (timed) interval.toLongOrNull()?.let { if (minutes) it * 60 else it } ?: run { repo.reject("请填写间隔"); return@Button } else null
                val n = if (timed) count.toIntOrNull() ?: run { repo.reject("请填写次数"); return@Button } else null
                val fs = fields.map { labrun.core.RunEngine.NewField(it.label, it.type, if (it.type != FieldType.TEXT) it.unit else null) }
                if (repo.createTable(title, fs, iv, n, actionId)) onClose()
            }) { Text("新建") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

@Composable
fun StopTableDialog(repo: Repository, s: RunState, table: labrun.core.AdhocTable, onClose: () -> Unit) {
    val actionId = remember { newAction() }
    val recorded = s.points.count { it.plan.id == table.id && it.status == labrun.core.PointStatus.RECORDED }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Column { Text("停止“${table.created.title}”的定时测量？"); RejectionText(repo) } },
        text = { Text("尚未到点的计划时刻会取消（不算漏测）；已到点还没测的保留，可以继续录入。已记录 $recorded 次。之后仍可用“＋计划外”手动补记。", style = LabType.body) },
        confirmButton = { Button(onClick = { if (repo.stopTable(table.id, actionId)) onClose() }) { Text("停止") } },
        dismissButton = { TextButton(onClick = onClose) { Text("继续定时") } },
    )
}

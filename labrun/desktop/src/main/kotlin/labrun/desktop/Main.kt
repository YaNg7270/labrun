package labrun.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import labrun.core.DesignTokens
import labrun.core.FieldType
import labrun.core.ImportDecision
import labrun.core.PointStatus
import labrun.core.RunReport
import labrun.core.RunState
import labrun.core.RunViews
import labrun.core.StepStatus
import labrun.core.TimeFormat
import labrun.core.TimeQuality
import labrun.core.TimelineKind
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ------------------------------------------------------------------ 主题（同 docs/03_设计规范.md）

class LabColors(p: DesignTokens.Palette) {
    val primary = Color(p.primary); val onPrimary = Color(p.onPrimary); val bg = Color(p.bg); val surface = Color(p.surface)
    val surfaceAlt = Color(p.surfaceAlt); val text = Color(p.text); val muted = Color(p.muted); val outline = Color(p.outline)
    val due = Color(p.due); val overdue = Color(p.overdue); val done = Color(p.done); val missed = Color(p.missed); val uncertain = Color(p.uncertain)
}
val LocalLab = staticCompositionLocalOf { LabColors(DesignTokens.light) }

object T {
    val title = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    val body = TextStyle(fontSize = 15.sp)
    val num = TextStyle(fontSize = 14.sp, fontFeatureSettings = "tnum")
    val label = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val caption = TextStyle(fontSize = 12.sp, fontFeatureSettings = "tnum")
}

@Composable
fun LabTheme(content: @Composable () -> Unit) {
    val dark = LocalForceDark.current ?: isSystemInDarkTheme()
    val c = LabColors(if (dark) DesignTokens.dark else DesignTokens.light)
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.primary, onPrimary = c.onPrimary, background = c.bg, surface = c.surface, onSurface = c.text,
        onBackground = c.text, onSurfaceVariant = c.muted, outline = c.outline, error = c.overdue, surfaceVariant = c.surfaceAlt,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalLab provides c, androidx.compose.material3.LocalContentColor provides c.text, content = content)
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(Modifier.border(1.dp, color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = T.caption.copy(fontWeight = FontWeight.Medium), color = color, maxLines = 1)
    }
}

// ------------------------------------------------------------------ 应用

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--snapshot") return snapshot(args.drop(1))
    app(args)
}

/**
 * 离屏渲染真实界面为 PNG，用于界面回归检查（不截屏、不需要窗口）。
 * 用法：--snapshot <输出目录> [要先导入的包…]；库位置同正常启动（可用 LABRUN_LIBRARY 指定）。
 */
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
private fun snapshot(args: List<String>) {
    val out = File(args.firstOrNull() ?: "snapshots").apply { mkdirs() }
    val lib = Library.default().apply { reload() }
    args.drop(1).forEach { println("导入 $it → ${lib.import(File(it))}") }
    val entry = lib.entries.firstOrNull()
    for (dark in listOf(false, true)) for (tab in 0..3) {
        if (entry == null && tab > 0) continue
        val scene = androidx.compose.ui.ImageComposeScene(1280, 820, density = androidx.compose.ui.unit.Density(1f)) {
            CompositionLocalProvider(LocalForceDark provides dark) {
                LabTheme {
                    val c = LocalLab.current
                    Row(Modifier.fillMaxSize().background(c.bg)) {
                        Sidebar(lib, 0, entry?.runId, remember { FocusRequester() }, onSelect = {}, onImport = {})
                        Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
                        Column(Modifier.weight(1f).fillMaxHeight()) { if (entry == null) Empty(lib) else Detail(entry, {}, initialTab = tab) }
                    }
                }
            }
        }
        scene.render(0); val img = scene.render(1_000_000_000L)
        val png = img.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes
        File(out, "desktop_${if (dark) "dark" else "light"}_tab$tab.png").writeBytes(png)
        scene.close()
    }
    println("快照已写入 ${out.absolutePath}")
}

const val APP_VERSION = "desktop-1.1.2"

val LocalForceDark = staticCompositionLocalOf<Boolean?> { null }

private fun app(args: Array<String>) = application {
    val lib = remember { Library.default().apply { reload() } }
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    val search = remember { FocusRequester() }

    fun importFiles(files: List<File>) {
        val msgs = files.map { f ->
            when (val r = lib.import(f)) {
                is Library.ImportResult.Failed -> "导入失败 · ${r.message}"
                is Library.ImportResult.Imported -> {
                    selected = r.runId
                    when (r.decision) {
                        ImportDecision.NEW -> "已导入 ${f.name}"
                        ImportDecision.DUPLICATE -> "已导入过，跳过：${f.name}"
                        ImportDecision.REVISION -> "同一实验的新修订已保存（未覆盖旧的）：${f.name}"
                    }
                }
            }
        }
        version++
        message = msgs.joinToString("\n")
    }
    remember { if (args.isNotEmpty()) importFiles(args.map(::File)); 0 }

    Window(
        onCloseRequest = ::exitApplication, title = "实验记录 · 电脑端 " + APP_VERSION.removePrefix("desktop-"),
        state = rememberWindowState(width = 1280.dp, height = 820.dp),
        onPreviewKeyEvent = { e ->
            when {
                e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.O -> { pickFiles(null)?.let(::importFiles); true }
                e.type == KeyEventType.KeyDown && e.isCtrlPressed && e.key == Key.F -> { runCatching { search.requestFocus() }; true }
                else -> false
            }
        },
    ) {
        LabTheme {
            val c = LocalLab.current
            Row(Modifier.fillMaxSize().background(c.bg)) {
                Sidebar(lib, version, selected, search, onSelect = { selected = it }, onImport = { pickFiles(window)?.let(::importFiles) })
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    message?.let { m ->
                        Row(Modifier.fillMaxWidth().background(c.surfaceAlt).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m, style = T.label, color = if (m.startsWith("导入失败") || m.startsWith("删除失败")) c.overdue else c.text, modifier = Modifier.weight(1f))
                            TextButton(onClick = { message = null }) { Text("关闭") }
                        }
                    }
                    val entry = remember(version, selected) { lib.entries.firstOrNull { it.runId == selected } }
                    if (entry == null) Empty(lib) else Detail(entry, onMessage = { message = it }, onDelete = { rev ->
                        val ok = try { if (rev == null) lib.deleteRun(entry.runId) else lib.deleteRevision(rev) } catch (e: Exception) { false }
                        if (lib.entries.none { it.runId == entry.runId }) selected = null
                        version++
                        message = if (ok) (if (rev == null) "已从库中删除：${entry.latest.content.state.protocol.title}" else "已删除该修订") else "删除失败 · 请检查库文件是否被占用"
                    })
                }
            }
        }
    }
}

private fun pickFiles(owner: Frame?): List<File>? {
    val d = FileDialog(owner, "选择导出包（.labrun.zip）", FileDialog.LOAD).apply {
        isMultipleMode = true; file = "*.zip"
        setFilenameFilter { _, name -> name.endsWith(".zip") }
        isVisible = true
    }
    return d.files.toList().takeIf { it.isNotEmpty() }
}

private fun saveFile(owner: Frame?, suggested: String): File? {
    val d = FileDialog(owner, "保存", FileDialog.SAVE).apply { file = suggested; isVisible = true }
    return d.file?.let { File(d.directory, it) }
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

/** 实验的开始时刻一律按手机记录时的时区显示（规范 §4），不随电脑时区变化。 */
private fun startText(s: RunState) = TimeFormat.iso(s.start.wallMs, s.start.tzOffsetS).take(16).replace('T', ' ')

/** 检索：标题、步骤文件 ID、运行 ID、现象文字、步骤标题。 */
private fun matches(s: RunState, q: String): Boolean {
    if (q.isBlank()) return true
    val hay = buildList {
        add(s.protocol.title); add(s.protocol.protocolId); add(s.runId)
        s.observations.forEach { add(it.text.orEmpty()) }
        s.steps.forEach { add(it.def.title) }
    }
    return q.trim().split(Regex("\\s+")).all { w -> hay.any { it.contains(w, ignoreCase = true) } }
}

@Composable
private fun Sidebar(lib: Library, version: Int, selected: String?, search: FocusRequester, onSelect: (String) -> Unit, onImport: () -> Unit) {
    val c = LocalLab.current
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableIntStateOf(0) }
    Column(Modifier.width(340.dp).fillMaxHeight().padding(16.dp)) {
        Text("实验记录", style = T.title.copy(fontSize = 24.sp))
        Spacer(Modifier.height(12.dp))
        Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(44.dp)) { Text("导入导出包…  Ctrl+O") }
        OutlinedTextField(q, { q = it }, placeholder = { Text("检索标题、现象、步骤…  Ctrl+F") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp).focusRequester(search))
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("全部", "已结束", "未结束快照").forEachIndexed { i, s ->
                val sel = i == filter
                Box(Modifier.background(if (sel) c.primary else c.surfaceAlt, RoundedCornerShape(16.dp)).clickable { filter = i }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(s, style = T.label, color = if (sel) c.onPrimary else c.text)
                }
            }
        }
        val list = remember(version, q, filter) {
            lib.entries.filter { e ->
                val s = e.latest.content.state
                matches(s, q) && when (filter) { 1 -> s.isEnded; 2 -> !s.isEnded; else -> true }
            }
        }
        if (lib.problems.isNotEmpty()) Text("${lib.problems.size} 个库文件无法读取：${lib.problems.first().message}", style = T.caption, color = c.overdue)
        Text("${list.size} 个实验", style = T.caption, color = c.muted, modifier = Modifier.padding(bottom = 4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.runId }) { e ->
                val s = e.latest.content.state
                val sel = e.runId == selected
                Column(
                    Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp))
                        .border(if (sel) 2.dp else 1.dp, if (sel) c.primary else c.outline, RoundedCornerShape(12.dp))
                        .clickable { onSelect(e.runId) }.padding(12.dp)
                ) {
                    Text(s.protocol.title, style = T.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(startText(s) + " · " + e.runId.take(8), style = T.caption, color = c.muted, modifier = Modifier.weight(1f))
                        if (s.isEnded) Chip("已结束", c.muted) else Chip("未结束快照", c.due)
                    }
                    if (e.revisions.size > 1) Text("${e.revisions.size} 个修订", style = T.caption, color = c.uncertain)
                }
            }
        }
    }
}

@Composable
private fun Empty(lib: Library) {
    val c = LocalLab.current
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (lib.entries.isEmpty()) "还没有实验" else "从左侧选择一个实验", style = T.title)
        Text("在手机上结束实验后“导出并发送”或“保存到文件”，把 .labrun.zip 传到电脑，再点“导入导出包”。", style = T.body, color = c.muted,
            modifier = Modifier.padding(top = 8.dp))
        Text("库位置：${lib.root}", style = T.caption, color = c.muted, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun Detail(entry: Library.Entry, onMessage: (String) -> Unit, onDelete: (Library.Revision?) -> Unit = {}, initialTab: Int = 0) {
    val c = LocalLab.current
    var revIdx by remember(entry.runId) { mutableIntStateOf(entry.revisions.indexOf(entry.latest)) }
    val rev = entry.revisions[revIdx.coerceIn(0, entry.revisions.lastIndex)]
    val s = rev.content.state
    var tab by remember(entry.runId) { mutableIntStateOf(initialTab) }
    var deleting by remember(entry.runId) { mutableStateOf(false) }
    val baseName = remember(s) { startText(s).replace(Regex("[- :]"), "") + "_" + s.protocol.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40) }

    fun export(ext: String, write: (File) -> Unit) {
        val f = saveFile(null, "$baseName.$ext") ?: return
        try { write(f); onMessage("已导出：${f.absolutePath}") } catch (e: Exception) { onMessage("导入失败 · 导出出错：${e.message}") }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.protocol.title, style = T.title.copy(fontSize = 24.sp))
                Text("${s.protocol.protocolId} v${s.protocol.protocolVersion} · 运行 ${s.runId} · 开始 ${TimeFormat.iso(s.start.wallMs, s.start.tzOffsetS).take(19).replace('T', ' ')}" +
                    (s.ended?.let { " · 时长 ${TimeFormat.elapsed(it.tRunMs)}" } ?: " · 未结束快照（导出时仍在进行）"), style = T.caption, color = c.muted)
                val rec = s.points.count { it.status == PointStatus.RECORDED }; val miss = s.points.count { it.status == PointStatus.MISSED }
                val open = s.points.count { it.status == PointStatus.OPEN }
                Text("步骤 ${s.steps.count { it.status == StepStatus.CONFIRMED }}/${s.steps.size} 确认 · 采样 $rec 录入 / $miss 漏测" +
                    (if (open > 0) " / $open 未处理" else "") +
                    (if (s.extraMeasurements.isNotEmpty()) " · 计划外记录 ${s.extraMeasurements.size}" else "") + " · 现象 ${s.observations.size}", style = T.body, modifier = Modifier.padding(top = 4.dp))
                if (s.timeUncertain) Text("出现过重启或系统时间变化，部分时间为估算", style = T.label, color = c.uncertain)
            }
            Column(horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { export("html") { it.writeText(RunReport(s, APP_VERSION).html(rev.content.attachments)) } }) { Text("HTML 报告") }
                    OutlinedButton(onClick = { export("xlsx") { f -> f.outputStream().use { RunReport(s, APP_VERSION).writeXlsx(it) } } }) { Text("Excel") }
                    OutlinedButton(onClick = { export("measurements.csv") { it.writeText(RunViews(s).measurementsCsv()) } }) { Text("测量表 CSV") }
                    OutlinedButton(onClick = { export("timeline.csv") { it.writeText(RunViews(s).timelineCsv()) } }) { Text("时间线 CSV") }
                }
                Row {
                    TextButton(onClick = { runCatching { Desktop.getDesktop().open(rev.file.parentFile) } }) { Text("打开原始包所在文件夹", style = T.caption) }
                    TextButton(onClick = { deleting = true }) { Text("从库中删除…", style = T.caption, color = c.overdue) }
                }
            }
        }
        if (entry.revisions.size > 1) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("修订：", style = T.label, color = c.uncertain)
                entry.revisions.sortedBy { it.importedAt }.forEach { r ->
                    val i = entry.revisions.indexOf(r)
                    val label = "${r.content.state.events.size} 条事件 · " + (if (r.content.state.isEnded) "已结束" else "未结束") + " · 导入于 " + dateFmt.format(Date(r.importedAt))
                    Box(Modifier.background(if (i == revIdx) c.uncertain else c.surfaceAlt, RoundedCornerShape(16.dp)).clickable { revIdx = i }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                        Text(label, style = T.caption, color = if (i == revIdx) c.onPrimary else c.text)
                    }
                }
            }
        }
        if (deleting) DeleteDialog(entry, rev, onDismiss = { deleting = false }) { which -> deleting = false; onDelete(which) }
        Row(Modifier.padding(vertical = 12.dp).background(c.surfaceAlt, RoundedCornerShape(20.dp)).padding(4.dp)) {
            listOf("测量表", "时间线", "现象", "步骤、起点与更正").forEachIndexed { i, t ->
                Box(Modifier.background(if (i == tab) c.surface else Color.Transparent, RoundedCornerShape(16.dp)).clickable { tab = i }.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Text(t, style = T.label, color = if (i == tab) c.primary else c.muted)
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> Measurements(s)
                1 -> Timeline(s)
                2 -> Observations(s, rev.content.attachments)
                else -> StepsAndAnchors(s)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** 删除确认：多修订时可只删当前修订；[onConfirm] 传 null 表示删除整个实验。 */
@Composable
private fun DeleteDialog(entry: Library.Entry, rev: Library.Revision, onDismiss: () -> Unit, onConfirm: (Library.Revision?) -> Unit) {
    val c = LocalLab.current
    val multi = entry.revisions.size > 1
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("从库中删除“${rev.content.state.protocol.title}”？") },
        text = {
            Text((if (multi) "这个实验有 ${entry.revisions.size} 个修订。可以只删除当前查看的修订，或删除整个实验。\n\n" else "") +
                "删除的是电脑端实验库里保存的导出包副本，无法在程序里恢复；手机上的记录和你当初导入的原始文件不受影响，需要时可以重新导入。",
                style = T.body)
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (multi) OutlinedButton(onClick = { onConfirm(rev) }) { Text("只删当前修订") }
                Button(onClick = { onConfirm(null) }, colors = ButtonDefaults.buttonColors(containerColor = c.overdue)) {
                    Text(if (multi) "删除整个实验" else "删除")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RowScopeCells(cells: List<Pair<String, Float>>, colors: List<Color?> = emptyList(), header: Boolean = false) {
    val c = LocalLab.current
    Row(Modifier.fillMaxWidth().background(if (header) c.surfaceAlt else c.surface).padding(horizontal = 12.dp, vertical = 8.dp)) {
        cells.forEachIndexed { i, (t, w) ->
            Text(t, style = if (header) T.label else T.num, color = colors.getOrNull(i) ?: if (header) c.muted else c.text,
                modifier = Modifier.weight(w).padding(end = 8.dp))
        }
    }
    HorizontalDivider(color = c.outline)
}

private fun RunState.moment(tRunMs: Long, wallMs: Long, anchor: String?): String =
    listOfNotNull(TimeFormat.clock(wallMs, start.tzOffsetS), TimeFormat.elapsed(tRunMs), anchor?.let { "${anchorDef(it).label} ${TimeFormat.elapsed(relativeTo(it, tRunMs))}" }).joinToString(" · ")

@Composable
private fun Measurements(s: RunState) {
    val c = LocalLab.current
    s.allPlans.forEach { plan ->
        // 计划外测量表：直接显示时间表（每行一个时刻，列为自定义字段）
        s.adhoc(plan.id)?.let { t ->
            val cr = t.created
            Text("${plan.label}  计划外测量表 · 建于 ${s.moment(cr.atTRunMs, cr.atWallMs, null)} · " +
                (cr.intervalS?.let { iv -> "每 ${TimeFormat.elapsed(iv * 1000).drop(1)} 一次，共 ${cr.count} 次" + (t.stopped?.let { st -> "，已于 ${TimeFormat.elapsed(st.tRunMs)} 停止" } ?: "") } ?: "手动记录"),
                style = T.label, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            val tt = RunViews(s).timeTable(plan)
            if (tt.rows.isEmpty()) { Text("无记录", style = T.body, color = c.muted); return@forEach }
            val widths = tt.header.mapIndexed { i, _ -> when (i) { 0 -> .4f; 1 -> .6f; 2 -> .9f; 3 -> 2.2f; 4, 5 -> .8f; 6 -> 2.2f; else -> 1f } }
            Column(Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
                RowScopeCells(tt.header.zip(widths), header = true)
                tt.rows.forEach { r ->
                    RowScopeCells(r.map { cell -> if (cell.length > 20 && cell.contains('T')) cell.substring(11, 19) else cell }.zip(widths),
                        colors = r.mapIndexed { i, cell -> if (i == r.lastIndex) when {
                            cell.startsWith("漏测") -> c.missed; cell.contains("更正") -> c.uncertain; cell == "待测" -> c.due; else -> c.done } else null })
                }
            }
            return@forEach
        }
        val anchor = s.anchorDef(plan.anchorId).label
        Text("${plan.label}  以“$anchor”为起点", style = T.label, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        val pts = s.points.filter { it.plan.id == plan.id }
        if (pts.isEmpty()) { Text("“$anchor”未建立，未生成采样点。", style = T.body, color = c.muted); return@forEach }
        val head = listOf("次" to .4f, "计划（墙上 · 全局 · 相对）" to 2.4f, "实测（墙上 · 全局 · 相对）" to 2.6f, "录入" to 1.1f) +
            plan.fields.map { (it.label + (it.unit?.let { u -> "（$u）" } ?: "")) to 1f } + listOf("偏差" to .7f, "状态" to 1.3f)
        Column(Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
            RowScopeCells(head, header = true)
            pts.forEach { p ->
                val m = p.measurement
                val b = m
                val (st, col) = when (p.status) {
                    PointStatus.RECORDED -> (if (m!!.isCorrected) "已录入 · 更正 ${m.corrections.size} 次" else "已录入") to (if (m.isCorrected) c.uncertain else c.done)
                    PointStatus.MISSED -> "漏测（${labrun.core.missedReasonText(p.missedReason)}）" to c.missed
                    PointStatus.OPEN -> "未处理" to c.due
                }
                val q = b?.measuredQuality
                RowScopeCells(
                    listOf((p.index + 1).toString() to .4f,
                        s.moment(p.plannedTRunMs, s.wallAt(p.plannedTRunMs), plan.anchorId) to 2.4f,
                        (b?.let { s.moment(it.measuredTRunMs, it.measuredWallMs, plan.anchorId) + if (q == TimeQuality.USER_ENTERED) "（补填）" else "" } ?: "—") to 2.6f,
                        (m?.let { TimeFormat.clock(it.enteredWallMs, s.start.tzOffsetS) } ?: "—") to 1.1f) +
                        plan.fields.map { f -> (b?.values?.get(f.id)?.value ?: "—") to 1f } +
                        listOf((b?.let { "%+d s".format((it.measuredTRunMs - p.plannedTRunMs) / 1000) } ?: "") to .7f, st to 1.3f),
                    colors = listOf(null, null, if (q != null && q != TimeQuality.MONOTONIC) c.uncertain else null) + List(plan.fields.size + 2) { null } + col,
                )
            }
        }
        val values = pts.mapNotNull { p -> plan.fields.firstOrNull { it.type != FieldType.TEXT }?.let { f -> p.measurement?.let { b -> b.values[f.id]?.value?.toDoubleOrNull()?.let { b.measuredTRunMs to it } } } }
        if (values.size >= 2) Sparkline(s, values.map { it.first - s.anchorTRun(plan.anchorId)!! to it.second }, plan.fields.first { it.type != FieldType.TEXT }.label)
    }
    val protocolExtras = s.extraMeasurements.filter { s.adhoc(it.body.planId) == null }
    if (protocolExtras.isNotEmpty()) {
        Text("计划外测量", style = T.label, modifier = Modifier.padding(top = 16.dp))
        protocolExtras.forEach { m ->
            val plan = s.plan(m.body.planId)
            Text("${plan.label}：" + plan.fields.joinToString(" ") { f -> (m.values[f.id]?.value ?: "") + (f.unit?.let { " $it" } ?: "") } +
                " · " + s.moment(m.measuredTRunMs, m.measuredWallMs, plan.anchorId), style = T.num)
        }
    }
}

/** 测量值随相对时间的折线（只画已录入点；漏测不连线、不补值）。 */
@Composable
private fun Sparkline(s: RunState, pts: List<Pair<Long, Double>>, label: String) {
    val c = LocalLab.current
    val minV = pts.minOf { it.second }; val maxV = pts.maxOf { it.second }
    val maxT = pts.maxOf { it.first }.coerceAtLeast(1)
    Column(Modifier.padding(top = 12.dp)) {
        Text("$label 变化（横轴：实测时刻相对起点，只画已录入点）  最小 $minV · 最大 $maxV", style = T.caption, color = c.muted)
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(120.dp).padding(vertical = 8.dp).border(1.dp, c.outline)) {
            val range = (maxV - minV).takeIf { it > 0 } ?: 1.0
            val xy = pts.map { (t, v) -> androidx.compose.ui.geometry.Offset(12f + (size.width - 24f) * t / maxT, size.height - 12f - ((v - minV) / range).toFloat() * (size.height - 24f)) }
            xy.zipWithNext().forEach { (a, b) -> drawLine(c.primary, a, b, strokeWidth = 2f) }
            xy.forEach { drawCircle(c.primary, 4f, it) }
        }
    }
}

@Composable
private fun Timeline(s: RunState) {
    val c = LocalLab.current
    val rows = remember(s) { RunViews(s).timeline().filter { it.kind != TimelineKind.REMINDER } }
    val locals = s.anchors.values.filter { it.established != null && it.def.id != s.protocol.runStartAnchorId && !s.isAdhocAnchor(it.def.id) }
    Column(Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
        RowScopeCells(listOf("墙上" to .8f, "全局" to 1f, "事件" to 2.2f, "详情" to 3f, "相对" to 1.6f), header = true)
        rows.forEach { r ->
            val col = when (r.kind) {
                TimelineKind.MISSED, TimelineKind.STEP_SKIPPED -> c.missed; TimelineKind.MEASUREMENT -> c.done
                TimelineKind.ANCHOR, TimelineKind.RUN_STARTED, TimelineKind.RUN_ENDED -> c.primary; TimelineKind.CLOCK, TimelineKind.CORRECTION -> c.uncertain
                TimelineKind.OBSERVATION -> c.due; else -> null
            }
            RowScopeCells(listOf(
                TimeFormat.clock(r.wallMs, s.start.tzOffsetS) to .8f,
                TimeFormat.elapsed(r.tRunMs) + when (r.quality) { TimeQuality.MONOTONIC -> ""; TimeQuality.USER_ENTERED -> "（补填）"; TimeQuality.WALL_ESTIMATE -> "（估算）" } to 1f,
                r.title to 2.2f, r.detail.orEmpty() to 3f,
                locals.mapNotNull { a -> s.relativeAfter(a.def.id, r.tRunMs)?.let { "${a.def.label} ${TimeFormat.elapsed(it)}" } }.joinToString(" · ") to 1.6f,
            ), colors = listOf(null, if (r.quality != TimeQuality.MONOTONIC) c.uncertain else null, col))
        }
    }
    if (s.reminders.isNotEmpty()) {
        val d = s.reminders.map { (r, e) -> (e.tRunMs - r.plannedTRunMs) / 1000.0 }
        Text("手机提醒 ${d.size} 次，触发延迟 %.3f – %.3f 秒".format(Locale.ROOT, d.min(), d.max()), style = T.caption, color = c.muted, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun Observations(s: RunState, attachments: Map<String, ByteArray>) {
    val c = LocalLab.current
    if (s.observations.isEmpty()) Text("没有现象记录", style = T.body, color = c.muted)
    s.observations.sortedBy { it.occurredTRunMs }.forEach { o ->
        val b = o
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).background(c.surface, RoundedCornerShape(12.dp)).border(1.dp, c.outline, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Text(s.moment(b.occurredTRunMs, b.occurredWallMs, null) + (if (b.occurredQuality == TimeQuality.USER_ENTERED) "（补填，记录于 ${TimeFormat.clock(o.event.wallMs, s.start.tzOffsetS)}）" else "") +
                (o.body.stepId?.let { " · ${s.protocol.step(it).title}" } ?: ""), style = T.caption, color = c.muted)
            b.text?.let { Text(it, style = T.body, modifier = Modifier.padding(top = 4.dp)) }
            if (o.isCorrected) Text("已更正，原内容：${o.body.text ?: "（无文字）"}", style = T.caption, color = c.uncertain)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                o.body.attachments.forEach { a ->
                    val bmp = remember(a.path) { attachments[a.path]?.let { runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull() } }
                    if (bmp != null) Image(bmp, a.path, Modifier.size(200.dp), contentScale = ContentScale.Crop) else Text("照片无法显示：${a.path}", color = c.overdue)
                }
            }
        }
    }
}

@Composable
private fun StepsAndAnchors(s: RunState) {
    val c = LocalLab.current
    Column(Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
        RowScopeCells(listOf("#" to .3f, "步骤" to 2f, "状态" to 1f, "完成" to 2f, "说明" to 2f), header = true)
        s.steps.forEachIndexed { i, st ->
            val (txt, col) = when (st.status) {
                StepStatus.CONFIRMED -> "已确认" to c.done; StepStatus.SKIPPED -> "已跳过" to c.missed; StepStatus.NOT_COMPLETED -> "未完成" to c.missed
                StepStatus.CURRENT -> "进行中" to c.primary; StepStatus.PENDING -> "未开始" to c.muted
            }
            RowScopeCells(listOf((i + 1).toString() to .3f, st.def.title to 2f, txt to 1f,
                (st.doneEvent?.let { s.moment(it.tRunMs, it.wallMs, null) } ?: "—") to 2f, st.skipReason.orEmpty() to 2f), colors = listOf(null, null, col))
        }
    }
    Text("更正记录（原始记录均保留）", style = T.label, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    val fixes = remember(s) { RunViews(s).corrections() }
    if (fixes.isEmpty()) Text("无", style = T.body, color = c.muted)
    else Column(Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
        RowScopeCells(listOf("更正时刻" to 1.2f, "更正" to 2f, "内容" to 4f), header = true)
        fixes.forEach { r -> RowScopeCells(listOf(s.moment(r.tRunMs, r.wallMs, null) to 1.2f, r.title to 2f, r.detail.orEmpty() to 4f), colors = listOf(null, c.uncertain)) }
    }
    Text("计时起点", style = T.label, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
    Column(Modifier.border(1.dp, c.outline, RoundedCornerShape(8.dp))) {
        RowScopeCells(listOf("起点" to 2f, "建立于" to 2f, "来源" to 2f), header = true)
        s.anchors.values.forEach { a ->
            val b = a.established
            RowScopeCells(listOf(a.def.label to 2f, (b?.let { s.moment(a.tRunMs!!, a.atWallMs!!, null) + if (a.atQuality == TimeQuality.MONOTONIC) "" else "（补填/估算）" } ?: "未建立") to 2f,
                ((b?.source?.name?.lowercase() ?: "—") + if (a.isCorrected) "（已更正，原 ${TimeFormat.elapsed(b!!.atTRunMs)}）" else "") to 2f), colors = listOf(null, if (b == null) c.muted else null))
        }
    }
}

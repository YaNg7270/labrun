package labrun.core

import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 最小 xlsx 写入器（SpreadsheetML，内联字符串），不依赖第三方库。 */
object XlsxWriter {
    private val NUM = Regex("^-?\\d+(\\.\\d+)?$")
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    private fun col(i: Int): String { var n = i + 1; val sb = StringBuilder(); while (n > 0) { val r = (n - 1) % 26; sb.insert(0, 'A' + r); n = (n - 1) / 26 }; return sb.toString() }

    fun write(out: OutputStream, sheets: List<Pair<String, Table>>) {
        ZipOutputStream(out).use { z ->
            fun put(name: String, xml: String) { z.putNextEntry(ZipEntry(name)); z.write(xml.toByteArray()); z.closeEntry() }
            put("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            put("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            put("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                sheets.mapIndexed { i, (n, _) -> """<sheet name="${esc(n.take(31))}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            put("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            put("xl/styles.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="2"><xf fontId="0" xfId="0"/><xf fontId="1" xfId="0" applyFont="1"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
            sheets.forEachIndexed { i, (_, t) ->
                val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><sheetData>""")
                (listOf(t.header) + t.rows).forEachIndexed { r, row ->
                    sb.append("<row r=\"${r + 1}\">")
                    row.forEachIndexed { c, v ->
                        val ref = "${col(c)}${r + 1}"
                        when {
                            v.isEmpty() -> Unit
                            r > 0 && c in t.numericCols && NUM.matches(v) -> sb.append("<c r=\"$ref\"><v>$v</v></c>")
                            else -> sb.append("<c r=\"$ref\" t=\"inlineStr\"${if (r == 0) " s=\"1\"" else ""}><is><t xml:space=\"preserve\">${esc(v)}</t></is></c>")
                        }
                    }
                    sb.append("</row>")
                }
                sb.append("</sheetData></worksheet>")
                put("xl/worksheets/sheet${i + 1}.xml", sb.toString())
            }
        }
    }
}

/** 一次运行的全部报告表格（Excel 各工作表、HTML 各节共用同一份数据）。 */
class RunReport(val s: RunState, val appVersion: String) {
    private val v = RunViews(s)
    private val tz get() = s.start.tzOffsetS

    fun summary(): Table {
        val rs = s.start.body as RunStarted
        val rows = mutableListOf(
            listOf("实验", s.protocol.title),
            listOf("步骤文件", "${s.protocol.protocolId} 版本 ${s.protocol.protocolVersion}"),
            listOf("步骤文件 SHA-256", rs.protocolSha256),
            listOf("运行 ID", s.runId),
            listOf("设备", rs.device.orEmpty()),
            listOf("开始", TimeFormat.iso(s.start.wallMs, tz)),
            listOf("结束", s.ended?.let { TimeFormat.iso(it.wallMs, it.tzOffsetS) } ?: "未结束"),
            listOf("时长", s.ended?.let { TimeFormat.elapsed(it.tRunMs) } ?: ""),
            listOf("步骤", "${s.steps.count { it.status == StepStatus.CONFIRMED }} 确认 / ${s.steps.count { it.status == StepStatus.SKIPPED }} 跳过 / ${s.steps.count { it.status == StepStatus.NOT_COMPLETED }} 未完成，共 ${s.steps.size}"),
            listOf("采样", "${s.points.count { it.status == PointStatus.RECORDED }} 录入 / ${s.points.count { it.status == PointStatus.MISSED }} 漏测 / ${s.points.count { it.status == PointStatus.OPEN }} 未处理，计划外 ${s.extraMeasurements.size}"),
            listOf("现象", "${s.observations.size} 条，照片 ${s.observations.sumOf { it.body.attachments.size }} 张"),
            listOf("更正", if (s.correctionCount == 0) "无" else "${s.correctionCount} 条（原始记录均保留，见“更正记录”）"),
            listOf("计时可靠性", if (s.timeUncertain) "出现过重启或系统时间变化，部分时间为估算" else "全程单调时钟"),
        )
        if (s.reminders.isNotEmpty()) {
            val d = s.reminders.map { (r, e) -> (e.tRunMs - r.plannedTRunMs) / 1000.0 }
            rows += listOf("提醒延迟（秒）", "共 ${d.size} 次，最小 ${"%.3f".format(java.util.Locale.ROOT, d.min())}，最大 ${"%.3f".format(java.util.Locale.ROOT, d.max())}")
        }
        rows += listOf("生成", "由 run.json 重算（$appVersion）")
        return Table(listOf("项目", "内容"), rows)
    }

    fun steps(): Table = Table(listOf("序号", "步骤", "状态", "完成时刻", "全局", "说明"), s.steps.mapIndexed { i, st ->
        val e = st.doneEvent
        listOf((i + 1).toString(), st.def.title, when (st.status) {
            StepStatus.CONFIRMED -> "已确认"; StepStatus.SKIPPED -> "已跳过"; StepStatus.NOT_COMPLETED -> "未完成"
            StepStatus.CURRENT -> "进行中"; StepStatus.PENDING -> "未开始" },
            e?.let { TimeFormat.iso(it.wallMs, it.tzOffsetS) }.orEmpty(), e?.let { TimeFormat.elapsed(it.tRunMs) }.orEmpty(), st.skipReason.orEmpty())
    }, setOf(0))

    fun anchors(): Table = Table(listOf("计时起点", "ID", "状态", "时刻", "全局", "来源"), s.anchors.values.map { a ->
        val b = a.established
        listOf(a.def.label, a.def.id, if (b == null) "未建立" else if (a.isCorrected) "已建立，已更正" else "已建立", a.atWallMs?.let { TimeFormat.iso(it, tz) }.orEmpty(),
            a.tRunMs?.let { t -> TimeFormat.elapsed(t) + when (a.atQuality) { TimeQuality.USER_ENTERED -> "（补填）"; TimeQuality.WALL_ESTIMATE -> "（估算）"; else -> "" } }.orEmpty(),
            b?.source?.name?.lowercase().orEmpty() + if (a.isCorrected) "；原 ${TimeFormat.elapsed(b!!.atTRunMs)}" else "")
    })

    fun observations(): Table = Table(listOf("发生时刻", "全局", "记录时刻", "内容", "照片", "关联步骤"), s.observations.sortedBy { it.occurredTRunMs }.map { o ->
        val b = o
        listOf(TimeFormat.iso(b.occurredWallMs, tz), TimeFormat.elapsed(b.occurredTRunMs) + if (b.occurredQuality == TimeQuality.USER_ENTERED) "（补填）" else "",
            TimeFormat.iso(o.event.wallMs, o.event.tzOffsetS), b.text.orEmpty(), o.body.attachments.joinToString(" ") { it.path },
            o.body.stepId?.let { s.protocol.step(it).title }.orEmpty())
    })

    /** 更正记录：何时做的更正、更正了什么、理由。 */
    fun corrections(): Table = Table(listOf("更正时刻", "全局", "更正", "内容"), v.corrections().map { r ->
        listOf(TimeFormat.iso(r.wallMs, tz), TimeFormat.elapsed(r.tRunMs), r.title, r.detail.orEmpty())
    })

    fun writeXlsx(out: OutputStream) {
        // 每个采样计划 / 计划外测量表各一张“时间表”工作表；表名去掉 Excel 不允许的字符并保证唯一
        val used = mutableSetOf("概要", "测量表", "时间线", "现象", "步骤", "计时起点", "更正记录")
        val perPlan = s.allPlans.map { p ->
            var name = ("表·" + p.label).map { if (it in "[]:*?/\\") '_' else it }.joinToString("").take(28)
            var n = 2
            while (name in used) name = name.take(26) + "_" + n++
            used += name
            name to v.timeTable(p)
        }
        XlsxWriter.write(out, listOf("概要" to summary(), "测量表" to v.measurementsTable()) + perPlan + listOf(
            "时间线" to v.timelineTable(), "现象" to observations(), "步骤" to steps(), "计时起点" to anchors(), "更正记录" to corrections()))
    }

    /** 单文件 HTML（照片内嵌），浏览器打开后可直接“打印 → 另存为 PDF”。 */
    fun html(attachments: Map<String, ByteArray>): String {
        fun e(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        fun table(t: Table, cls: String = "") = buildString {
            append("<div class=\"scroll\"><table class=\"$cls\"><thead><tr>"); t.header.forEach { append("<th>${e(it)}</th>") }; append("</tr></thead><tbody>")
            t.rows.forEach { r -> append("<tr>"); r.forEachIndexed { i, c -> append("<td${if (i in t.numericCols) " class=\"n\"" else ""}>${e(c)}</td>") }; append("</tr>") }
            append("</tbody></table></div>")
        }
        // 测量表的阅读版：每个计划一张表，列用中文
        val meas = buildString {
            s.allPlans.forEach { plan ->
                // 计划外测量表：直接给时间表
                s.adhoc(plan.id)?.let { t ->
                    val c = t.created
                    append("<h3>${e(plan.label)} <small>计划外测量表 · 建于 ${TimeFormat.clock(c.atWallMs, tz)}（${TimeFormat.elapsed(c.atTRunMs)}）· " +
                        (if (c.intervalS != null) "每 ${TimeFormat.elapsed(c.intervalS * 1000).drop(1)} 一次，共 ${c.count} 次" + (if (t.stopped != null) "，已于 ${TimeFormat.elapsed(t.stopped.tRunMs)} 停止" else "") else "手动记录") +
                        "</small></h3>")
                    val tt = v.timeTable(plan)
                    if (tt.rows.isEmpty()) append("<p class=\"muted\">无记录</p>") else append(table(tt))
                    return@forEach
                }
                val anchor = s.anchorDef(plan.anchorId).label
                append("<h3>${e(plan.label)} <small>以“${e(anchor)}”为起点</small></h3>")
                val rows = s.points.filter { it.plan.id == plan.id }.map { p ->
                    val m = p.measurement
                    listOf((p.index + 1).toString(),
                        TimeFormat.clock(s.wallAt(p.plannedTRunMs), tz) + " · " + TimeFormat.elapsed(p.plannedTRunMs) + " · " + TimeFormat.elapsed(p.offsetS * 1000),
                        m?.let { TimeFormat.clock(it.measuredWallMs, tz) + " · " + TimeFormat.elapsed(it.measuredTRunMs) + " · " + TimeFormat.elapsed(s.relativeTo(plan.anchorId, it.measuredTRunMs)) +
                            if (it.measuredQuality == TimeQuality.USER_ENTERED) "（补填，录入 ${TimeFormat.clock(p.measurement!!.enteredWallMs, tz)}）" else "" }.orEmpty()) +
                        plan.fields.map { f -> m?.values?.get(f.id)?.value.orEmpty() + if (m?.isCorrected == true && m.values[f.id]?.value != m.body.values[f.id]?.value) "（已更正，原 ${m.body.values[f.id]?.value}）" else "" } +
                        listOf(when (p.status) { PointStatus.RECORDED -> "已录入"; PointStatus.MISSED -> "漏测（${missedReasonText(p.missedReason)}）"; PointStatus.OPEN -> "未处理" },
                            if (m != null) "%+d".format((m.measuredTRunMs - p.plannedTRunMs) / 1000) else "")
                }
                if (rows.isEmpty()) append("<p class=\"muted\">“${e(anchor)}”未建立，未生成采样点。</p>")
                else append(table(Table(listOf("次", "计划（墙上 · 全局 · 相对）", "实测（墙上 · 全局 · 相对）") +
                    plan.fields.map { it.label + (it.unit?.let { u -> "（$u）" } ?: "") } + listOf("状态", "偏差 s"), rows,
                    setOf(0, 4 + plan.fields.size) + plan.fields.indices.filter { plan.fields[it].type != FieldType.TEXT }.map { it + 3 })))
                val extras = s.extraMeasurements.filter { it.body.planId == plan.id }
                if (extras.isNotEmpty()) {
                    append("<p class=\"muted\">计划外测量</p>")
                    append(table(Table(listOf("实测（墙上 · 全局 · 相对）", "录入") + plan.fields.map { it.label + (it.unit?.let { u -> "（$u）" } ?: "") },
                        extras.map { m ->
                            listOf(TimeFormat.clock(m.measuredWallMs, tz) + " · " + TimeFormat.elapsed(m.measuredTRunMs) + " · " + TimeFormat.elapsed(s.relativeTo(plan.anchorId, m.measuredTRunMs)) +
                                if (m.measuredQuality == TimeQuality.USER_ENTERED) "（补填）" else "",
                                TimeFormat.clock(m.enteredWallMs, tz)) +
                                plan.fields.map { f -> m.values[f.id]?.value.orEmpty() + if (m.isCorrected && m.values[f.id]?.value != m.body.values[f.id]?.value) "（已更正，原 ${m.body.values[f.id]?.value}）" else "" }
                        }, plan.fields.indices.filter { plan.fields[it].type != FieldType.TEXT }.map { it + 2 }.toSet())))
                }
            }
        }
        val obs = buildString {
            if (s.observations.isEmpty()) append("<p class=\"muted\">无</p>")
            s.observations.sortedBy { it.occurredTRunMs }.forEach { o ->
                val b = o
                append("<div class=\"obs\"><div class=\"t\">${TimeFormat.clock(b.occurredWallMs, tz)} · ${TimeFormat.elapsed(b.occurredTRunMs)}")
                if (b.occurredQuality == TimeQuality.USER_ENTERED) append("（补填）")
                o.body.stepId?.let { append(" · ${e(s.protocol.step(it).title)}") }
                append("</div>")
                b.text?.let { append("<p>${e(it)}</p>") }
                o.body.attachments.forEach { a -> attachments[a.path]?.let { bytes -> append("<img src=\"data:${a.mime};base64,${Base64.getEncoder().encodeToString(bytes)}\" alt=\"${e(a.path)}\">") } }
                append("</div>")
            }
        }
        val tl = Table(listOf("墙上", "全局", "事件", "详情", "相对时间"), v.timeline().filter { it.kind != TimelineKind.REMINDER }.map { r ->
            listOf(TimeFormat.clock(r.wallMs, tz), TimeFormat.elapsed(r.tRunMs) + when (r.quality) { TimeQuality.MONOTONIC -> ""; TimeQuality.USER_ENTERED -> "（补填）"; TimeQuality.WALL_ESTIMATE -> "（估算）" },
                r.title, r.detail.orEmpty(),
                s.anchors.values.filter { it.def.id != s.protocol.runStartAnchorId && !s.isAdhocAnchor(it.def.id) }.mapNotNull { a -> s.relativeAfter(a.def.id, r.tRunMs)?.let { "${a.def.label} ${TimeFormat.elapsed(it)}" } }.joinToString(" · "))
        })
        return """<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${e(s.protocol.title)} · 实验报告</title><style>
:root{--primary:#0F6E6E;--bg:#F6F7F6;--surface:#fff;--alt:#EDF1F0;--text:#1A1F1E;--muted:#5B6563;--outline:#D3DAD8;--done:#2E6B30;--missed:#7A4A2A;--uncertain:#5B45A8}
body{margin:0;background:var(--bg);color:var(--text);font:15px/1.55 "Microsoft YaHei","PingFang SC","Noto Sans SC",system-ui,sans-serif}
main{max-width:1000px;margin:0 auto;padding:24px 16px 48px}h1{font-size:26px;margin:0 0 4px}h2{font-size:18px;margin:32px 0 8px;color:var(--primary)}h3{font-size:16px;margin:20px 0 6px}
small,.muted{color:var(--muted);font-weight:400}table{border-collapse:collapse;width:100%;background:var(--surface);font-variant-numeric:tabular-nums;margin:4px 0}
th,td{border:1px solid var(--outline);padding:6px 8px;text-align:left;vertical-align:top}th{background:var(--alt);font-weight:600;font-size:13px}td.n{text-align:right}
.kv td:first-child{width:9em;white-space:nowrap;color:var(--muted)}.kv td{overflow-wrap:anywhere}.scroll{overflow-x:auto}.obs{background:var(--surface);border:1px solid var(--outline);border-radius:12px;padding:10px 14px;margin:8px 0}
.obs .t{font-size:13px;color:var(--muted);font-variant-numeric:tabular-nums}.obs p{margin:4px 0}.obs img{max-width:240px;max-height:240px;margin:6px 6px 0 0;border-radius:8px}
@media print{body{background:#fff}main{padding:0}h2{break-after:avoid}tr,.obs{break-inside:avoid}}
</style></head><body><main>
<h1>${e(s.protocol.title)}</h1><div class="muted">实验报告 · ${TimeFormat.iso(s.start.wallMs, tz).take(19).replace('T', ' ')}</div>
<h2>概要</h2>${table(summary().copy(header = emptyList()), "kv").replace("<thead><tr></tr></thead>", "")}
<h2>步骤</h2>${table(steps())}
<h2>计时起点</h2>${table(anchors())}
<h2>测量</h2>$meas
<h2>现象</h2>$obs
<h2>更正记录</h2>${if (s.correctionCount == 0) "<p class=\"muted\">无</p>" else table(corrections())}
<h2>时间线</h2>${table(tl)}
<p class="muted" style="margin-top:32px">时间格式：墙上时间 · 全局经过时间 · 相对计时起点。“补填”= 用户事后输入的时刻；“估算”= 跨重启由墙上时间推算。本报告由导出包中的 run.json 重算生成。</p>
</main></body></html>"""
    }
}

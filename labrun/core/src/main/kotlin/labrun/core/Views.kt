package labrun.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 漏测原因的显示文字（内部代码不直接给用户看）。 */
fun missedReasonText(reason: String?): String = when (reason) {
    null -> ""
    "run_ended" -> "结束时未测"
    "manual" -> "手动标记"
    "voided" -> "测量已作废"
    else -> reason
}

/** 两端共用的时间显示规则（契约 §2）。 */
object TimeFormat {
    fun elapsed(ms: Long?): String {
        if (ms == null) return "未建立"
        val sign = if (ms < 0) "−" else "+"
        val total = kotlin.math.abs(ms) / 1000
        val h = total / 3600; val m = total % 3600 / 60; val s = total % 60
        return if (h > 0) "%s%d:%02d:%02d".format(sign, h, m, s) else "%s%02d:%02d".format(sign, m, s)
    }

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

    fun iso(wallMs: Long, tzOffsetS: Int): String =
        ISO.format(Instant.ofEpochMilli(wallMs).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetS)))

    fun clock(wallMs: Long, tzOffsetS: Int): String =
        DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(wallMs).atOffset(ZoneOffset.ofTotalSeconds(tzOffsetS)))
}

enum class TimelineKind { RUN_STARTED, STEP_CONFIRMED, STEP_SKIPPED, ANCHOR, MEASUREMENT, MISSED, OBSERVATION, REMINDER, CLOCK, RUN_ENDED, CORRECTION, TABLE }

data class TimelineRow(
    val tRunMs: Long,
    val wallMs: Long,
    val quality: TimeQuality,
    val kind: TimelineKind,
    val title: String,
    val detail: String?,
    val seq: Long,
)

/** 运行的派生视图：时间线、测量表、CSV。电脑端以 run.json 为准用同一套代码重算。 */
class RunViews(val s: RunState) {
    private val tz get() = s.start.tzOffsetS

    fun timeline(): List<TimelineRow> {
        val rows = mutableListOf<TimelineRow>()
        for (e in s.events) {
            val b = e.body
            fun row(kind: TimelineKind, title: String, detail: String? = null, t: Long = e.tRunMs, w: Long = e.wallMs, q: TimeQuality = e.timeQuality) =
                rows.add(TimelineRow(t, w, q, kind, title, detail, e.seq))
            when (b) {
                is RunStarted -> row(TimelineKind.RUN_STARTED, "开始实验", "${b.title}（${b.protocolId} v${b.protocolVersion}）")
                is StepConfirmed -> row(TimelineKind.STEP_CONFIRMED, "确认：${s.protocol.step(b.stepId).title}")
                is StepSkipped -> row(TimelineKind.STEP_SKIPPED, "跳过：${s.protocol.step(b.stepId).title}", b.reason)
                is AnchorEstablished -> if (b.source != AnchorSource.RUN_STARTED) {
                    // 起点被撤销（随步骤撤销）后不再出现在时间线上；被更正时按生效时刻显示
                    val a = s.anchors.getValue(b.anchorId)
                    if (a.event?.seq == e.seq)
                        row(TimelineKind.ANCHOR, "建立起点：${a.def.label}" + if (a.isCorrected) "（已更正）" else "", sourceText(b), a.tRunMs!!, a.atWallMs!!, a.atQuality!!)
                    else row(TimelineKind.ANCHOR, "（已撤销）建立起点：${a.def.label}", sourceText(b), b.atTRunMs, b.atWallMs, b.atQuality)
                }
                is MeasurementRecorded -> {
                    val plan = s.plan(b.planId)
                    val m = s.allMeasurements.getValue(b.measurementId)
                    val which = b.pointIndex?.let { "第 ${it + 1} 次" } ?: "计划外"
                    val late = if (m.measuredQuality == TimeQuality.USER_ENTERED) "，录入于 ${TimeFormat.clock(e.wallMs, e.tzOffsetS)}" else ""
                    val prefix = if (m.isVoided) "（已作废）" else ""
                    val suffix = if (m.isCorrected && !m.isVoided) "（已更正）" else ""
                    row(TimelineKind.MEASUREMENT, "${prefix}测量：${plan.label} $which$suffix", valuesText(plan, m.values) + late, m.measuredTRunMs, m.measuredWallMs, m.measuredQuality)
                }
                is SampleMissed -> {
                    val p = s.points.first { it.plan.id == b.planId && it.index == b.pointIndex }
                    row(TimelineKind.MISSED, "漏测：${p.plan.label} 第 ${b.pointIndex + 1} 次",
                        "计划 ${TimeFormat.elapsed(p.plannedTRunMs)}；" + missedReasonText(b.reason))
                }
                is ObservationAdded -> {
                    val o = s.observationsAll.first { it.id == b.observationId }
                    val title = (if (o.isVoided) "（已作废）" else "") + "现象" + if (o.isCorrected && !o.isVoided) "（已更正）" else ""
                    row(TimelineKind.OBSERVATION, title, listOfNotNull(o.text, b.attachments.takeIf { it.isNotEmpty() }?.let { "照片 ${it.size} 张" }).joinToString("；"),
                        o.occurredTRunMs, o.occurredWallMs, o.occurredQuality)
                }
                is MeasurementCorrected -> {
                    val m = s.allMeasurements.getValue(b.measurementId)
                    val plan = s.plan(m.body.planId)
                    val before = m.corrections.indexOfFirst { it.second.seq == e.seq }.let { i -> if (i <= 0) m.body.values to m.body.measuredTRunMs else m.corrections[i - 1].first.values to m.corrections[i - 1].first.measuredTRunMs }
                    val parts = mutableListOf<String>()
                    if (before.first.mapValues { it.value.value } != b.values.mapValues { it.value.value }) parts += "${valuesText(plan, before.first)} → ${valuesText(plan, b.values)}"
                    if (before.second != b.measuredTRunMs) parts += "测量时刻 ${TimeFormat.elapsed(before.second)} → ${TimeFormat.elapsed(b.measuredTRunMs)}"
                    b.reason?.let { parts += "理由：$it" }
                    row(TimelineKind.CORRECTION, "更正测量：${plan.label} ${pointText(m)}", parts.joinToString("；"))
                }
                is MeasurementVoided -> {
                    val m = s.allMeasurements.getValue(b.measurementId)
                    row(TimelineKind.CORRECTION, "作废测量：${s.plan(m.body.planId).label} ${pointText(m)}", "原值 ${valuesText(s.plan(m.body.planId), m.values)}；理由：${b.reason}")
                }
                is SampleMissedRevoked -> row(TimelineKind.CORRECTION, "撤销漏测：${s.plan(b.planId).label} 第 ${b.pointIndex + 1} 次", "理由：${b.reason}")
                is StepReverted -> row(TimelineKind.CORRECTION, "撤销确认：${s.protocol.step(b.stepId).title}", "理由：${b.reason}")
                is AnchorCorrected -> {
                    val a = s.anchors.getValue(b.anchorId)
                    val i = a.corrections.indexOfFirst { it.second.seq == e.seq }
                    val before = if (i <= 0) a.established!!.atTRunMs else a.corrections[i - 1].first.atTRunMs
                    row(TimelineKind.CORRECTION, "更正起点：${a.def.label}", "${TimeFormat.elapsed(before)} → ${TimeFormat.elapsed(b.atTRunMs)}（以它为基准的计划采样时刻随之移动）；理由：${b.reason}")
                }
                is ObservationCorrected -> row(TimelineKind.CORRECTION, "更正现象", listOfNotNull(b.text?.let { "内容：$it" }, b.reason?.let { "理由：$it" }).joinToString("；"))
                is ObservationVoided -> row(TimelineKind.CORRECTION, "作废现象", "理由：${b.reason}")
                is AdhocTableCreated -> row(TimelineKind.TABLE, "新建计划外测量表：${b.title}",
                    (if (b.intervalS != null) "定时：每 ${TimeFormat.elapsed(b.intervalS * 1000).drop(1)}，共 ${b.count} 次" else "手动记录") +
                        "；字段：" + b.fields.joinToString("、") { f -> f.label + (f.unit?.let { "（$it）" } ?: "") }, b.atTRunMs, b.atWallMs)
                is AdhocTableStopped -> row(TimelineKind.TABLE, "停止定时测量：${s.adhoc(b.tableId)?.created?.title ?: b.tableId}", "未到点的计划时刻已取消")
                is ReminderFired -> row(TimelineKind.REMINDER, "提醒触发", "计划 ${TimeFormat.elapsed(b.plannedTRunMs)}，延迟 ${(e.tRunMs - b.plannedTRunMs) / 1000.0} 秒")
                is ClockDiscontinuity -> row(TimelineKind.CLOCK, "系统时间变化", "偏差 ${(b.skewMs - b.previousSkewMs) / 1000.0} 秒")
                is RunEnded -> row(TimelineKind.RUN_ENDED, "结束实验", b.reason)
            }
        }
        return rows.sortedWith(compareBy({ it.tRunMs }, { it.seq }))
    }

    private fun valuesText(plan: SamplingPlanDef, v: Map<String, FieldValue>) =
        plan.fields.joinToString("，") { f -> "${f.label} ${v[f.id]?.value}${f.unit?.let { " $it" } ?: ""}" }
    private fun pointText(m: Measurement) = m.body.pointIndex?.let { "第 ${it + 1} 次" } ?: "计划外"

    /** 全部更正事件（报告“更正记录”一节用）。 */
    fun corrections(): List<TimelineRow> = timeline().filter { it.kind == TimelineKind.CORRECTION }

    private fun sourceText(b: AnchorEstablished) = when (b.source) {
        AnchorSource.RUN_STARTED -> "开始"
        AnchorSource.STEP_CONFIRMED -> "确认步骤“${s.protocol.step(b.sourceStepId!!).title}”时建立"
        AnchorSource.MANUAL -> "手动建立"
        AnchorSource.MANUAL_BACKDATED -> "手动回溯建立"
    }

    fun timelineTable(): Table {
        val header = listOf("t_run_s", "wall_time", "time_quality", "kind", "title", "detail", "seq") + s.protocol.anchors.map { "rel_${it.id}_s" }
        val rows = timeline().map { r ->
            listOf(sec(r.tRunMs), TimeFormat.iso(r.wallMs, tz), r.quality.wire(), r.kind.name.lowercase(), r.title, r.detail.orEmpty(), r.seq.toString()) +
                s.protocol.anchors.map { a -> s.relativeAfter(a.id, r.tRunMs)?.let(::sec).orEmpty() }
        }
        return Table(header, rows, setOf(0, 6) + s.protocol.anchors.indices.map { it + 7 })
    }

    fun timelineCsv(): String = csv(timelineTable())

    /** 测量表：每个计划点一行（含漏测/未测），再加计划外测量。 */
    fun measurementsTable(): Table {
        val fieldCols = s.allPlans.flatMap { p -> p.fields.map { p to it } }
        val header = listOf("plan_id", "point", "status", "anchor_id", "offset_s", "planned_wall", "planned_t_run_s", "planned_rel_s",
            "measured_wall", "measured_t_run_s", "measured_rel_s", "measured_time_quality", "entered_wall", "deviation_s", "missed_reason") +
            fieldCols.flatMap { (p, f) -> listOf("${p.id}.${f.id}${f.unit?.let { "[$it]" } ?: ""}", "${p.id}.${f.id}.raw") } +
            listOf("corrections", "original_values", "voided_count")
        val rows = mutableListOf<List<String>>()
        fun line(plan: SamplingPlanDef, point: SamplePoint?, m: Measurement?) {
            val a = plan.anchorId
            rows += listOf(plan.id, point?.let { (it.index + 1).toString() } ?: "extra", point?.status?.name?.lowercase() ?: "recorded", a,
                point?.offsetS?.toString().orEmpty(),
                point?.let { TimeFormat.iso(s.wallAt(it.plannedTRunMs), tz) }.orEmpty(),
                point?.let { sec(it.plannedTRunMs) }.orEmpty(),
                point?.let { sec(it.offsetS * 1000) }.orEmpty(),
                m?.let { TimeFormat.iso(it.measuredWallMs, tz) }.orEmpty(),
                m?.let { sec(it.measuredTRunMs) }.orEmpty(),
                m?.let { s.relativeTo(a, it.measuredTRunMs)?.let(::sec) }.orEmpty(),
                m?.measuredQuality?.wire().orEmpty(),
                m?.let { TimeFormat.iso(it.enteredWallMs, it.event.tzOffsetS) }.orEmpty(),
                if (point != null && m != null) sec(m.measuredTRunMs - point.plannedTRunMs) else "",
                point?.missedReason.orEmpty()) +
                fieldCols.flatMap { (p, f) -> if (p.id == plan.id && m != null) listOf(m.values[f.id]?.value.orEmpty(), m.values[f.id]?.raw.orEmpty()) else listOf("", "") } +
                listOf(m?.corrections?.size?.takeIf { it > 0 }?.toString().orEmpty(),
                    m?.takeIf { it.isCorrected }?.let { valuesText(plan, it.body.values) }.orEmpty(),
                    point?.voided?.size?.takeIf { it > 0 }?.toString().orEmpty())
        }
        s.points.forEach { line(it.plan, it, it.measurement) }
        s.extraMeasurements.forEach { line(s.plan(it.body.planId), null, it) }
        val numeric = setOf(1, 4, 6, 7, 9, 10, 13) + fieldCols.withIndex().filter { it.value.second.type != FieldType.TEXT }.map { 15 + it.index * 2 } +
            setOf(15 + fieldCols.size * 2, 17 + fieldCols.size * 2)
        return Table(header, rows, numeric)
    }

    fun measurementsCsv(): String = csv(measurementsTable())

    /**
     * 某个采样计划/计划外测量表的“时间表”：每行一个时刻（计划点与计划外记录按时间排序），列为各字段。
     * 计划点按计划时刻排序（不论是否已测，保证 1、2、3… 顺序不乱）；计划外记录按实测时刻插入；作废的记录不进表。
     */
    fun timeTable(plan: SamplingPlanDef): Table {
        val a = plan.anchorId
        data class R(val sort: Long, val cells: List<String>)
        val rows = mutableListOf<R>()
        fun cells(no: String, kind: String, planned: Long?, m: Measurement?, status: String) = listOf(no, kind,
            planned?.let { TimeFormat.elapsed(it - (s.anchorTRun(a) ?: 0)) }.orEmpty(),
            m?.let { TimeFormat.iso(it.measuredWallMs, tz) }.orEmpty(),
            m?.let { sec(it.measuredTRunMs) }.orEmpty(),
            m?.let { s.relativeTo(a, it.measuredTRunMs)?.let(::sec) }.orEmpty(),
            m?.let { TimeFormat.iso(it.enteredWallMs, it.event.tzOffsetS) }.orEmpty()) +
            plan.fields.map { f -> m?.values?.get(f.id)?.value.orEmpty() } + status
        s.points.filter { it.plan.id == plan.id }.forEach { p ->
            val m = p.measurement
            val st = when (p.status) {
                PointStatus.RECORDED -> if (m!!.isCorrected) "已录入（已更正）" else "已录入"
                PointStatus.MISSED -> "漏测（${missedReasonText(p.missedReason)}）"
                PointStatus.OPEN -> "待测"
            }
            rows += R(p.plannedTRunMs, cells("${p.index + 1}", "计划", p.plannedTRunMs, m, st))
        }
        s.extraMeasurements.filter { it.body.planId == plan.id }.forEach { m ->
            rows += R(m.measuredTRunMs, cells("", if (s.adhoc(plan.id)?.isTimed == false) "记录" else "计划外", null, m, if (m.isCorrected) "已录入（已更正）" else "已录入"))
        }
        val sorted = rows.sortedBy { it.sort }.mapIndexed { i, r -> if (r.cells[0].isEmpty()) listOf("${i + 1}") + r.cells.drop(1) else r.cells }
        val header = listOf("序号", "类型", "计划（相对起点）", "测量时刻", "全局_s", "相对起点_s", "录入时刻") +
            plan.fields.map { it.label + (it.unit?.let { u -> "（$u）" } ?: "") } + "状态"
        return Table(header, sorted, setOf(0, 4, 5) + plan.fields.indices.filter { plan.fields[it].type != FieldType.TEXT }.map { it + 7 })
    }

    companion object {
        fun sec(ms: Long): String = if (ms % 1000 == 0L) (ms / 1000).toString() else String.format(java.util.Locale.ROOT, "%.3f", ms / 1000.0)
        fun TimeQuality.wire() = name.lowercase()
        /** RFC 4180 + UTF-8 BOM，便于 Excel 直接打开中文。 */
        fun csv(t: Table) = csv(t.header, t.rows)
        fun csv(header: List<String>, rows: List<List<String>>): String = buildString {
            append('﻿')
            (listOf(header) + rows).forEach { r -> append(r.joinToString(",") { c -> if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + c.replace("\"", "\"\"") + "\"" else c }).append("\r\n") }
        }
    }
}

/** 表格数据：CSV、Excel、HTML 共用。[numericCols] 中能解析为数字的单元格在 Excel 里存为数值。 */
data class Table(val header: List<String>, val rows: List<List<String>>, val numericCols: Set<Int> = emptySet())

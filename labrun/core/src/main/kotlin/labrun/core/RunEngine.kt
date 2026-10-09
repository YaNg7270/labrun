package labrun.core

import java.math.BigDecimal
import java.util.UUID

sealed interface CommandResult {
    data class Accepted(val events: List<Event>) : CommandResult
    /** 同一动作 ID 已处理过：不产生新事件。 */
    data object Duplicate : CommandResult
    data class Rejected(val reason: String) : CommandResult
}

/** 事件先交给 sink 持久化成功，再更新内存状态；sink 抛异常则状态不变。 */
fun interface EventSink { fun append(events: List<Event>) }

/**
 * 运行命令处理器。只做两件事：校验命令是否允许，生成事件。
 * 一切状态都通过 [RunState.replay] 的同一套规则推导，恢复与电脑端复核走同一份代码。
 */
class RunEngine private constructor(
    val protocol: Protocol,
    val protocolBytes: ByteArray,
    private val clock: Clock,
    private val sink: EventSink,
    private val newId: () -> String,
    events: List<Event>,
) {
    var state: RunState = RunState.replay(protocol, events)
        private set

    companion object {
        const val SKEW_THRESHOLD_MS = 2_000L
        const val ENTRY_SLACK_MS = 1_000L

        fun start(
            protocolBytes: ByteArray, clock: Clock, sink: EventSink, actionId: String,
            device: String? = null, newId: () -> String = { UUID.randomUUID().toString() },
        ): RunEngine {
            val parsed = ProtocolParser.parse(protocolBytes)
            val p = parsed.protocol
            require(parsed.ok && p != null) { "步骤文件有错误，不能开始：" + parsed.errors.joinToString("；") }
            val now = clock.now()
            fun ev(seq: Long, body: EventBody) = Event(seq, newId(), actionId, now.wallMs, now.tzOffsetS, now.monoMs, now.bootCount, 0, TimeQuality.MONOTONIC, body)
            val events = listOf(
                ev(1, RunStarted(newId(), p.protocolId, p.protocolVersion, parsed.sha256, p.title, device)),
                ev(2, AnchorEstablished(p.runStartAnchorId, AnchorSource.RUN_STARTED, null, now.wallMs, 0, TimeQuality.MONOTONIC)),
            )
            sink.append(events)
            return RunEngine(p, protocolBytes, clock, sink, newId, events)
        }

        /** 从持久化的快照与事件恢复（进程被杀、手机重启后）。 */
        fun restore(protocolBytes: ByteArray, events: List<Event>, clock: Clock, sink: EventSink,
                    newId: () -> String = { UUID.randomUUID().toString() }): RunEngine {
            val parsed = ProtocolParser.parse(protocolBytes)
            val p = parsed.protocol ?: error("快照无法解析：" + parsed.errors.joinToString("；"))
            val rs = events.firstOrNull()?.body as? RunStarted ?: error("缺少 run_started")
            check(rs.protocolSha256 == parsed.sha256) { "快照哈希与运行记录不一致" }
            return RunEngine(p, protocolBytes, clock, sink, newId, events)
        }
    }

    // ---------------------------------------------------------------- 时间

    private data class Stamp(val now: Now, val tRunMs: Long, val quality: TimeQuality)

    private fun stamp(): Stamp {
        val now = clock.now()
        val s = state.start
        // 读不到开机序号时（-1）用“单调时钟倒退”兜底判定重启
        val sameBoot = now.bootCount == s.bootCount && now.monoMs >= s.monoMs
        return if (sameBoot) Stamp(now, now.monoMs - s.monoMs, TimeQuality.MONOTONIC)
        else Stamp(now, now.wallMs - s.wallMs - state.lastSkewMs, TimeQuality.WALL_ESTIMATE)
    }

    /** 现在的运行时间（界面计时、提醒调度用）。 */
    fun nowTRun(): Pair<Long, TimeQuality> = stamp().let { it.tRunMs to it.quality }

    /** 运行时间 → 应该在何时（墙上时间）触发，供 AlarmManager 使用。 */
    fun wallFor(tRunMs: Long): Long = stamp().let { it.now.wallMs + (tRunMs - it.tRunMs) }

    /** 用户输入的墙上时刻 → 运行时间。 */
    private fun userTRun(wallMs: Long) = wallMs - state.start.wallMs - state.lastSkewMs

    // ---------------------------------------------------------------- 命令

    private fun run(actionId: String, afterEnd: Boolean = false, build: (Stamp) -> Any): CommandResult {
        if (actionId in state.actionIds) return CommandResult.Duplicate
        if (state.isEnded && !afterEnd) return CommandResult.Rejected("实验已结束")
        val st = stamp()
        @Suppress("UNCHECKED_CAST")
        val bodies = when (val r = build(st)) {
            is String -> return CommandResult.Rejected(r)
            is List<*> -> r as List<EventBody>
            else -> listOf(r as EventBody)
        }
        val prefix = mutableListOf<EventBody>()
        if (st.quality == TimeQuality.MONOTONIC) {
            val skew = (st.now.wallMs - state.start.wallMs) - st.tRunMs
            if (kotlin.math.abs(skew - state.lastSkewMs) > SKEW_THRESHOLD_MS) prefix += ClockDiscontinuity(skew, state.lastSkewMs)
        }
        var seq = state.events.size.toLong()
        val events = (prefix + bodies).map { b ->
            Event(++seq, newId(), actionId, st.now.wallMs, st.now.tzOffsetS, st.now.monoMs, st.now.bootCount, st.tRunMs, st.quality, b)
        }
        val next = RunState.replay(protocol, state.events + events) // 用同一套规则再验一遍
        sink.append(events)
        state = next
        return CommandResult.Accepted(events)
    }

    fun confirmStep(stepId: String, actionId: String): CommandResult = run(actionId) { st ->
        val cur = state.currentStep ?: return@run "没有待确认的步骤"
        if (cur.def.id != stepId) return@run "只能确认当前步骤“${cur.def.title}”"
        val out = mutableListOf<EventBody>(StepConfirmed(stepId))
        protocol.anchorCreatedBy(stepId)?.let { a ->
            if (state.anchors[a.id]?.established == null)
                out += AnchorEstablished(a.id, AnchorSource.STEP_CONFIRMED, stepId, st.now.wallMs, st.tRunMs, st.quality)
        }
        out
    }

    fun skipStep(stepId: String, reason: String, actionId: String): CommandResult = run(actionId) { _ ->
        val cur = state.currentStep ?: return@run "没有待确认的步骤"
        if (cur.def.id != stepId) return@run "只能跳过当前步骤“${cur.def.title}”"
        if (reason.isBlank()) return@run "跳过必须填写理由"
        if (protocol.anchorCreatedBy(stepId) != null) return@run "该步骤负责建立锚点，不能跳过；请确认，或改用手动锚点"
        StepSkipped(stepId, reason.trim())
    }

    /** 建立 manual 锚点；[atWallMs] 为空表示“现在”，否则为回溯时刻。 */
    fun establishManualAnchor(anchorId: String, atWallMs: Long?, actionId: String): CommandResult = run(actionId) { st ->
        val a = state.anchors[anchorId] ?: return@run "未知锚点 $anchorId"
        if (a.def.createOn != AnchorTrigger.Manual) return@run "锚点“${a.def.label}”不是手动锚点"
        if (a.established != null) return@run "锚点“${a.def.label}”已建立"
        if (atWallMs == null) AnchorEstablished(anchorId, AnchorSource.MANUAL, null, st.now.wallMs, st.tRunMs, st.quality)
        else {
            val t = userTRun(atWallMs)
            if (t < 0) return@run "回溯时刻不能早于实验开始"
            if (atWallMs > st.now.wallMs + ENTRY_SLACK_MS) return@run "回溯时刻不能晚于现在"
            AnchorEstablished(anchorId, AnchorSource.MANUAL_BACKDATED, null, atWallMs, t, TimeQuality.USER_ENTERED)
        }
    }

    /**
     * 录入测量。[pointIndex] 为空表示计划外追加；[measuredWallMs] 为空表示“现在测的”。
     * [raw] 为 字段 ID → 用户输入原文。
     */
    fun recordMeasurement(planId: String, pointIndex: Int?, raw: Map<String, String>, measuredWallMs: Long?, actionId: String): CommandResult =
        run(actionId) { st ->
            val plan = state.allPlans.firstOrNull { it.id == planId } ?: return@run "未知采样计划 $planId"
            val anchorT = state.anchorTRun(plan.anchorId) ?: return@run "锚点“${state.anchorDef(plan.anchorId).label}”未建立，不能录入"
            if (pointIndex != null) {
                val p = state.points.firstOrNull { it.plan.id == planId && it.index == pointIndex } ?: return@run "没有第 ${pointIndex + 1} 个采样点"
                if (p.status != PointStatus.OPEN) return@run "第 ${pointIndex + 1} 个采样点已${if (p.status == PointStatus.RECORDED) "录入" else "标记漏测"}"
            }
            @Suppress("UNCHECKED_CAST")
            val values = parseValues(plan, raw).let { it as? Map<String, FieldValue> ?: return@run it as String }
            val (mWall, mT, mQ) = if (measuredWallMs == null) Triple(st.now.wallMs, st.tRunMs, st.quality) else {
                val t = userTRun(measuredWallMs)
                if (t < anchorT - ENTRY_SLACK_MS) return@run "测量时刻不能早于锚点“${state.anchorDef(plan.anchorId).label}”"
                if (measuredWallMs > st.now.wallMs + ENTRY_SLACK_MS) return@run "测量时刻不能晚于现在"
                Triple(measuredWallMs, t, TimeQuality.USER_ENTERED)
            }
            MeasurementRecorded(newId(), planId, pointIndex, mWall, mT, mQ, values)
        }

    /** 返回 字段 ID → 取值；有错误时返回错误文案。 */
    private fun parseValues(plan: SamplingPlanDef, raw: Map<String, String>): Any {
        val values = linkedMapOf<String, FieldValue>()
        for (f in plan.fields) {
            val r = raw[f.id]?.trim().orEmpty()
            if (r.isEmpty()) return "“${f.label}”未填写"
            val v = when (f.type) {
                FieldType.DECIMAL -> r.replace('，', '.').replace('－', '-').toBigDecimalOrNull()?.toPlainString() ?: return "“${f.label}”不是数字：$r"
                FieldType.INTEGER -> r.toLongOrNull()?.toString() ?: return "“${f.label}”不是整数：$r"
                FieldType.TEXT -> r
            }
            values[f.id] = FieldValue(raw[f.id]!!, v, f.unit)
        }
        (raw.keys - plan.fields.map { it.id }.toSet()).firstOrNull()?.let { return "未知字段 $it" }
        return values
    }

    fun markMissed(planId: String, pointIndex: Int, reason: String, actionId: String): CommandResult = run(actionId) { _ ->
        val p = state.points.firstOrNull { it.plan.id == planId && it.index == pointIndex } ?: return@run "没有该采样点"
        if (p.status != PointStatus.OPEN) return@run "该采样点已处理"
        SampleMissed(planId, pointIndex, reason.trim().ifEmpty { "manual" })
    }

    fun addObservation(text: String?, attachments: List<Attachment>, occurredWallMs: Long?, stepId: String?, anchorId: String?, actionId: String): CommandResult =
        run(actionId) { st ->
            if (!protocol.freeObservationsEnabled) return@run "该步骤文件关闭了自由现象记录"
            if (text.isNullOrBlank() && attachments.isEmpty()) return@run "现象记录需要文字或照片"
            if (stepId != null && protocol.steps.none { it.id == stepId }) return@run "未知步骤 $stepId"
            if (anchorId != null && anchorId !in state.anchors) return@run "未知锚点 $anchorId"
            attachments.forEach { if (!PackagePaths.isSafeAttachment(it.path)) return@run "附件路径不合规：${it.path}" }
            val (w, t, q) = if (occurredWallMs == null) Triple(st.now.wallMs, st.tRunMs, st.quality) else {
                val t = userTRun(occurredWallMs)
                if (t < 0) return@run "发生时间不能早于实验开始"
                if (occurredWallMs > st.now.wallMs + ENTRY_SLACK_MS) return@run "发生时间不能晚于现在"
                Triple(occurredWallMs, t, TimeQuality.USER_ENTERED)
            }
            ObservationAdded(newId(), text?.trim(), attachments, w, t, q, stepId, anchorId)
        }

    // ---------------------------------------------------------------- 计划外测量表

    data class NewField(val label: String, val type: FieldType, val unit: String?)

    /**
     * 运行中新建计划外测量表（不在步骤文件里）。[intervalS] 与 [count] 都给出为定时表，都为空为手动表。
     * 以建表时刻为该表的起点；定时表从建表时刻起每隔 [intervalS] 秒提醒一次（含建表时刻那一次），共 [count] 次。
     */
    fun createAdhocTable(title: String, fields: List<NewField>, intervalS: Long?, count: Int?, actionId: String): CommandResult = run(actionId) { st ->
        val t = title.trim()
        if (t.isEmpty()) return@run "请填写表名"
        if (t.length > 40) return@run "表名最多 40 字"
        if (state.allPlans.any { it.label == t }) return@run "已有同名的测量表或采样计划“$t”"
        if (fields.isEmpty()) return@run "至少一个字段"
        if (fields.size > 10) return@run "最多 10 个字段"
        val labels = fields.map { it.label.trim() }
        if (labels.any { it.isEmpty() }) return@run "字段名不能为空"
        if (labels.toSet().size != labels.size) return@run "字段名重复"
        if (fields.any { (it.unit?.trim()?.length ?: 0) > 32 }) return@run "单位最多 32 字"
        if ((intervalS == null) != (count == null)) return@run "定时表需要同时填写间隔和次数"
        if (intervalS != null && intervalS !in 5..86_400) return@run "间隔应在 5 秒到 24 小时之间"
        if (count != null && count !in 1..500) return@run "次数应在 1 到 500 之间"
        AdhocTableCreated(newId().take(8), t, fields.mapIndexed { i, f -> AdhocField("f${i + 1}", labels[i], f.type.wire, f.unit?.trim()?.ifEmpty { null }) },
            intervalS, count, st.now.wallMs, st.tRunMs)
    }

    fun stopAdhocTable(tableId: String, actionId: String): CommandResult = run(actionId) { _ ->
        val t = state.adhocTables.firstOrNull { it.id == tableId } ?: return@run "找不到这张测量表"
        if (!t.isTimed) return@run "手动表不需要停止"
        if (t.stopped != null) return@run "已经停止"
        AdhocTableStopped(tableId)
    }

    // ---------------------------------------------------------------- 更正（只追加）

    /**
     * 更正一条测量。[raw] 为空表示取值不变；[measuredWallMs] 为空表示测量时刻不变。
     * 实验结束后仍可更正；原始记录与每次更正都保留。
     */
    fun correctMeasurement(measurementId: String, raw: Map<String, String>?, measuredWallMs: Long?, reason: String?, actionId: String): CommandResult =
        run(actionId, afterEnd = true) { st ->
            val m = state.allMeasurements[measurementId] ?: return@run "找不到这条测量"
            if (m.isVoided) return@run "这条测量已作废"
            val plan = state.plan(m.body.planId)
            @Suppress("UNCHECKED_CAST")
            val values = if (raw == null) m.values else parseValues(plan, raw).let { it as? Map<String, FieldValue> ?: return@run it as String }
            val (w, t, q) = if (measuredWallMs == null) Triple(m.measuredWallMs, m.measuredTRunMs, m.measuredQuality) else {
                val t = userTRun(measuredWallMs)
                val anchorT = state.anchorTRun(plan.anchorId) ?: 0
                if (t < anchorT - ENTRY_SLACK_MS) return@run "测量时刻不能早于起点“${state.anchorDef(plan.anchorId).label}”"
                if (measuredWallMs > st.now.wallMs + ENTRY_SLACK_MS) return@run "测量时刻不能晚于现在"
                Triple(measuredWallMs, t, TimeQuality.USER_ENTERED)
            }
            if (values.mapValues { it.value.value } == m.values.mapValues { it.value.value } && t == m.measuredTRunMs) return@run "没有改动"
            MeasurementCorrected(measurementId, values, w, t, q, reason?.trim()?.ifEmpty { null })
        }

    /** 作废一条测量（录错了点、根本不该记）。运行中该点恢复待测；结束后该点记为漏测。 */
    fun voidMeasurement(measurementId: String, reason: String, actionId: String): CommandResult = run(actionId, afterEnd = true) { _ ->
        val m = state.allMeasurements[measurementId] ?: return@run "找不到这条测量"
        if (m.isVoided) return@run "这条测量已作废"
        if (reason.isBlank()) return@run "作废必须填写理由"
        MeasurementVoided(measurementId, reason.trim())
    }

    fun revokeMissed(planId: String, pointIndex: Int, reason: String, actionId: String): CommandResult = run(actionId) { _ ->
        val p = state.points.firstOrNull { it.plan.id == planId && it.index == pointIndex } ?: return@run "没有该采样点"
        if (p.status != PointStatus.MISSED) return@run "该采样点不是漏测"
        if (reason.isBlank()) return@run "撤销漏测必须填写理由"
        SampleMissedRevoked(planId, pointIndex, reason.trim())
    }

    /** 撤销最近一次步骤确认/跳过，回到该步骤。 */
    fun revertStep(stepId: String, reason: String, actionId: String): CommandResult = run(actionId) { _ ->
        val last = state.lastDoneStep ?: return@run "没有可撤销的步骤"
        if (last.def.id != stepId) return@run "只能撤销最近一次确认的步骤“${last.def.title}”"
        if (reason.isBlank()) return@run "撤销必须填写理由"
        protocol.anchorCreatedBy(stepId)?.let { a ->
            val pts = state.points.filter { it.plan.anchorId == a.id }
            val hasRecords = pts.any { it.status != PointStatus.OPEN || it.voided.isNotEmpty() } ||
                state.extraMeasurementsAll.any { state.plan(it.body.planId).anchorId == a.id }
            if (hasRecords) return@run "该步骤建立的起点“${a.label}”下已有采样记录，不能撤销；如果是起点时刻不对，请用“更正起点时刻”"
        }
        StepReverted(stepId, reason.trim())
    }

    /** 更正局部起点时刻；全局起点不可更正。以它为基准的计划采样时刻随之移动。 */
    fun correctAnchor(anchorId: String, atWallMs: Long, reason: String, actionId: String): CommandResult = run(actionId, afterEnd = true) { st ->
        val a = state.anchors[anchorId] ?: return@run "未知起点"
        if (anchorId == protocol.runStartAnchorId) return@run "全局起点（实验开始）不能更正"
        if (a.established == null) return@run "起点“${a.def.label}”尚未建立"
        if (reason.isBlank()) return@run "更正起点必须填写理由"
        val t = userTRun(atWallMs)
        if (t < 0) return@run "起点不能早于实验开始"
        if (atWallMs > st.now.wallMs + ENTRY_SLACK_MS) return@run "起点不能晚于现在"
        val earliest = state.allMeasurements.values.filter { !it.isVoided && state.plan(it.body.planId).anchorId == anchorId }.minOfOrNull { it.measuredTRunMs }
        if (earliest != null && t > earliest + ENTRY_SLACK_MS) return@run "起点不能晚于以它为基准的最早一次测量"
        if (t == a.tRunMs) return@run "没有改动"
        AnchorCorrected(anchorId, atWallMs, t, reason.trim())
    }

    fun correctObservation(observationId: String, text: String?, occurredWallMs: Long?, reason: String?, actionId: String): CommandResult =
        run(actionId, afterEnd = true) { st ->
            val o = state.observationsAll.firstOrNull { it.id == observationId } ?: return@run "找不到这条现象"
            if (o.isVoided) return@run "这条现象已作废"
            val newText = text?.trim()?.ifEmpty { null }
            if (newText == null && o.body.attachments.isEmpty()) return@run "现象记录需要文字或照片"
            val (w, t, q) = if (occurredWallMs == null) Triple(o.occurredWallMs, o.occurredTRunMs, o.occurredQuality) else {
                val t = userTRun(occurredWallMs)
                if (t < 0) return@run "发生时间不能早于实验开始"
                if (occurredWallMs > st.now.wallMs + ENTRY_SLACK_MS) return@run "发生时间不能晚于现在"
                Triple(occurredWallMs, t, TimeQuality.USER_ENTERED)
            }
            if (newText == o.text && t == o.occurredTRunMs) return@run "没有改动"
            ObservationCorrected(observationId, newText, w, t, q, reason?.trim()?.ifEmpty { null })
        }

    fun voidObservation(observationId: String, reason: String, actionId: String): CommandResult = run(actionId, afterEnd = true) { _ ->
        val o = state.observationsAll.firstOrNull { it.id == observationId } ?: return@run "找不到这条现象"
        if (o.isVoided) return@run "这条现象已作废"
        if (reason.isBlank()) return@run "作废必须填写理由"
        ObservationVoided(observationId, reason.trim())
    }

    fun reminderFired(kind: ReminderKind, refId: String, pointIndex: Int?, plannedTRunMs: Long, actionId: String): CommandResult =
        run(actionId) { _ -> ReminderFired(kind, refId, pointIndex, plannedTRunMs) }

    /**
     * 结束运行。[acknowledgedOpenPoints] 必须与当前未处理采样点完全一致（用户在确认框里看到的那份清单），
     * 防止界面陈旧时把新出现的待采样点静默处置。
     */
    fun end(acknowledgedOpenPoints: Set<String>, actionId: String, reason: String? = null): CommandResult = run(actionId) { _ ->
        val open = state.openPoints
        if (open.map { it.key }.toSet() != acknowledgedOpenPoints) return@run "待处置采样点清单已变化，请重新确认"
        open.map { SampleMissed(it.plan.id, it.index, "run_ended") } + RunEnded(reason)
    }
}

private fun String.toBigDecimalOrNull(): BigDecimal? = try { BigDecimal(this) } catch (_: NumberFormatException) { null }

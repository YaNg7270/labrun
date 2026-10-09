package labrun.core

enum class StepStatus { PENDING, CURRENT, CONFIRMED, SKIPPED, NOT_COMPLETED }

data class StepState(
    val def: StepDef,
    val status: StepStatus,
    val doneEvent: Event? = null,
    val skipReason: String? = null,
    /** 被撤销过的确认/跳过（只读历史）。 */
    val reverts: List<Pair<StepReverted, Event>> = emptyList(),
)

data class AnchorState(
    val def: AnchorDef,
    val established: AnchorEstablished?,
    val event: Event?,
    val corrections: List<Pair<AnchorCorrected, Event>> = emptyList(),
) {
    private val lastFix get() = corrections.lastOrNull()?.first
    /** 生效时刻（运行时间）；有更正时取最后一次更正。 */
    val tRunMs get() = established?.let { lastFix?.atTRunMs ?: it.atTRunMs }
    val atWallMs get() = established?.let { lastFix?.atWallMs ?: it.atWallMs }
    val atQuality get() = established?.let { if (lastFix != null) TimeQuality.USER_ENTERED else it.atQuality }
    val isCorrected get() = corrections.isNotEmpty()
}

enum class PointStatus { OPEN, RECORDED, MISSED }

data class SamplePoint(
    val plan: SamplingPlanDef,
    val index: Int,
    val offsetS: Long,
    val plannedTRunMs: Long,
    val status: PointStatus,
    /** 当前有效的测量（作废的不在这里）。 */
    val measurement: Measurement? = null,
    val missedReason: String? = null,
    /** 该点上被作废的测量（只读历史）。 */
    val voided: List<Measurement> = emptyList(),
) {
    val key get() = "${plan.id}#$index"
    /** 计划只有一个点时不写“第 1 次”。 */
    val title get() = if (plan.offsetsSeconds.size > 1) "${plan.label} 第 ${index + 1} 次" else plan.label
}

/** 一条测量及其全部更正历史；取值/时刻属性返回生效值，[body] 永远是原始记录。 */
data class Measurement(
    val body: MeasurementRecorded,
    val event: Event,
    val corrections: List<Pair<MeasurementCorrected, Event>> = emptyList(),
    val voided: Pair<MeasurementVoided, Event>? = null,
) {
    private val lastFix get() = corrections.lastOrNull()?.first
    val id get() = body.measurementId
    val values: Map<String, FieldValue> get() = lastFix?.values ?: body.values
    val measuredWallMs get() = lastFix?.measuredWallMs ?: body.measuredWallMs
    val measuredTRunMs get() = lastFix?.measuredTRunMs ?: body.measuredTRunMs
    val measuredQuality get() = lastFix?.measuredQuality ?: body.measuredQuality
    val enteredTRunMs get() = event.tRunMs
    val enteredWallMs get() = event.wallMs
    val isCorrected get() = corrections.isNotEmpty()
    val isVoided get() = voided != null
}

/** 运行中临时新建的计划外测量表；对外表现为一个动态采样计划 + 一个以建表时刻为 T0 的起点。 */
data class AdhocTable(
    val created: AdhocTableCreated,
    val event: Event,
    val plan: SamplingPlanDef,
    val anchor: AnchorDef,
    val stopped: Event? = null,
) {
    val id get() = created.tableId
    val isTimed get() = created.intervalS != null
}

data class Observation(
    val body: ObservationAdded,
    val event: Event,
    val corrections: List<Pair<ObservationCorrected, Event>> = emptyList(),
    val voided: Pair<ObservationVoided, Event>? = null,
) {
    private val lastFix get() = corrections.lastOrNull()?.first
    val id get() = body.observationId
    val text get() = if (lastFix != null) lastFix!!.text else body.text
    val occurredWallMs get() = lastFix?.occurredWallMs ?: body.occurredWallMs
    val occurredTRunMs get() = lastFix?.occurredTRunMs ?: body.occurredTRunMs
    val occurredQuality get() = lastFix?.occurredQuality ?: body.occurredQuality
    val isCorrected get() = corrections.isNotEmpty()
    val isVoided get() = voided != null
}

/** 由事件重放得到的运行状态；纯函数，不读时钟。 */
data class RunState(
    val protocol: Protocol,
    val events: List<Event>,
    val runId: String,
    val start: Event,
    val steps: List<StepState>,
    val anchors: Map<String, AnchorState>,
    val points: List<SamplePoint>,
    /** 计划外测量（含已作废的，展示时按 isVoided 区分）。 */
    val extraMeasurementsAll: List<Measurement>,
    /** 全部现象（含已作废的）。 */
    val observationsAll: List<Observation>,
    val reminders: List<Pair<ReminderFired, Event>>,
    val clockIssues: List<Event>,
    val ended: Event?,
    val actionIds: Set<String>,
    val adhocTables: List<AdhocTable> = emptyList(),
) {
    /** 步骤文件里的采样计划 + 运行中新建的计划外测量表。 */
    val allPlans: List<SamplingPlanDef> get() = protocol.samplingPlans + adhocTables.map { it.plan }
    fun plan(id: String): SamplingPlanDef = allPlans.first { it.id == id }
    fun anchorDef(id: String): AnchorDef = anchors.getValue(id).def
    fun adhoc(planId: String): AdhocTable? = adhocTables.firstOrNull { it.plan.id == planId }
    /** 计划外测量表自带的起点（不在运行页顶部和时间线相对列里显示，避免刷屏）。 */
    fun isAdhocAnchor(id: String) = adhocTables.any { it.anchor.id == id }

    val extraMeasurements get() = extraMeasurementsAll.filter { !it.isVoided }
    val observations get() = observationsAll.filter { !it.isVoided }
    /** 全部测量（含作废），按 ID 查找用。 */
    val allMeasurements: Map<String, Measurement> get() =
        (points.flatMap { listOfNotNull(it.measurement) + it.voided } + extraMeasurementsAll).associateBy { it.id }
    val correctionCount get() = events.count { it.body.isCorrection() }

    val isEnded get() = ended != null
    val currentStep get() = steps.firstOrNull { it.status == StepStatus.CURRENT }
    /** 最近一次已确认/跳过、可以撤销的步骤（运行中才有意义）。 */
    val lastDoneStep get() = steps.lastOrNull { it.status == StepStatus.CONFIRMED || it.status == StepStatus.SKIPPED }
    val openPoints get() = points.filter { it.status == PointStatus.OPEN }
    val lastSkewMs get() = (clockIssues.lastOrNull()?.body as? ClockDiscontinuity)?.skewMs ?: 0L
    /** 运行中是否出现过跨重启或系统时间被改。 */
    val timeUncertain get() = clockIssues.isNotEmpty() || events.any { it.timeQuality == TimeQuality.WALL_ESTIMATE }

    fun anchorTRun(anchorId: String): Long? = anchors[anchorId]?.tRunMs
    /** 相对某锚点的时间；锚点未建立时为 null（界面显示“未建立”）。 */
    fun relativeTo(anchorId: String, tRunMs: Long): Long? = anchorTRun(anchorId)?.let { tRunMs - it }
    /** 时间线用：只给锚点之后（含）的事件标相对时间，之前的显示为空，避免“−00:12”被误读。 */
    fun relativeAfter(anchorId: String, tRunMs: Long): Long? = relativeTo(anchorId, tRunMs)?.takeIf { it >= 0 }
    /** 把运行时间换算回墙上时间（以开始时刻为基准，扣除已知的系统时间偏移）。 */
    fun wallAt(tRunMs: Long): Long = start.wallMs + tRunMs + lastSkewMs
    fun stepNotBeforeTRun(step: StepDef): Long? = step.notBefore?.let { nb -> anchorTRun(nb.anchorId)?.plus(nb.offsetSeconds * 1000) }

    companion object {
        /** 结束后仍允许追加的事件类型。 */
        fun allowedAfterEnd(b: EventBody) = b is MeasurementCorrected || b is MeasurementVoided || b is AnchorCorrected ||
            b is ObservationCorrected || b is ObservationVoided

        fun replay(protocol: Protocol, events: List<Event>): RunState {
            require(events.isNotEmpty()) { "空事件流" }
            val first = events.first()
            val rs = first.body as? RunStarted ?: error("第一个事件必须是 run_started")
            val steps = protocol.steps.mapIndexed { i, s -> StepState(s, if (i == 0) StepStatus.CURRENT else StepStatus.PENDING) }.toMutableList()
            val anchors = protocol.anchors.associate { it.id to AnchorState(it, null, null) }.toMutableMap()
            val plans = protocol.samplingPlans.toMutableList()
            val adhoc = linkedMapOf<String, AdhocTable>()

            // 采样点：键 plan#index → 可变记录；测量按 ID 存放，最后组装
            class P(val plan: SamplingPlanDef, val index: Int, var planned: Long, var status: PointStatus = PointStatus.OPEN,
                    var mId: String? = null, var missed: String? = null, val voided: MutableList<String> = mutableListOf())
            val points = linkedMapOf<String, P>()
            val ms = linkedMapOf<String, Measurement>()
            val extras = mutableListOf<String>()
            val obs = linkedMapOf<String, Observation>()
            val reminders = mutableListOf<Pair<ReminderFired, Event>>()
            val clock = mutableListOf<Event>()
            val actions = mutableSetOf<String>()
            var ended: Event? = null

            fun pointOf(planId: String, idx: Int) = points["$planId#$idx"]
            fun regenerate(anchorId: String, at: Long) = plans.filter { it.anchorId == anchorId }.forEach { plan ->
                plan.offsetsSeconds.forEachIndexed { idx, off ->
                    val k = "${plan.id}#$idx"
                    points[k]?.let { it.planned = at + off * 1000 } ?: run { points[k] = P(plan, idx, at + off * 1000) }
                }
            }

            events.forEachIndexed { i, e ->
                val b = e.body
                check(e.seq == i + 1L) { "事件序号不连续：期望 ${i + 1}，实际 ${e.seq}" }
                check(ended == null || allowedAfterEnd(b)) { "运行结束后不允许 ${b::class.simpleName}（seq ${e.seq}）" }
                actions += e.actionId
                when (b) {
                    is RunStarted -> check(i == 0) { "run_started 只能出现一次" }
                    is AnchorEstablished -> {
                        val cur = anchors[b.anchorId] ?: error("未知锚点 ${b.anchorId}")
                        check(cur.established == null) { "锚点 ${b.anchorId} 重复建立" }
                        anchors[b.anchorId] = cur.copy(established = b, event = e, corrections = emptyList())
                        regenerate(b.anchorId, b.atTRunMs)
                    }
                    is AnchorCorrected -> {
                        val cur = anchors[b.anchorId] ?: error("未知锚点 ${b.anchorId}")
                        check(cur.established != null && b.anchorId != protocol.runStartAnchorId) { "锚点 ${b.anchorId} 不可更正" }
                        anchors[b.anchorId] = cur.copy(corrections = cur.corrections + (b to e))
                        regenerate(b.anchorId, b.atTRunMs)
                    }
                    is StepConfirmed, is StepSkipped -> {
                        val sid = if (b is StepConfirmed) b.stepId else (b as StepSkipped).stepId
                        val idx = steps.indexOfFirst { it.def.id == sid }
                        check(idx >= 0 && steps[idx].status == StepStatus.CURRENT) { "步骤 $sid 不是当前步骤" }
                        steps[idx] = if (b is StepConfirmed) steps[idx].copy(status = StepStatus.CONFIRMED, doneEvent = e, skipReason = null)
                        else steps[idx].copy(status = StepStatus.SKIPPED, doneEvent = e, skipReason = (b as StepSkipped).reason)
                        if (idx + 1 < steps.size && steps[idx + 1].status == StepStatus.PENDING) steps[idx + 1] = steps[idx + 1].copy(status = StepStatus.CURRENT)
                    }
                    is StepReverted -> {
                        val idx = steps.indexOfFirst { it.def.id == b.stepId }
                        val last = steps.indexOfLast { it.status == StepStatus.CONFIRMED || it.status == StepStatus.SKIPPED }
                        check(idx >= 0 && idx == last) { "只能撤销最近一次确认的步骤" }
                        if (idx + 1 < steps.size) { check(steps[idx + 1].status == StepStatus.CURRENT); steps[idx + 1] = steps[idx + 1].copy(status = StepStatus.PENDING) }
                        steps[idx] = steps[idx].copy(status = StepStatus.CURRENT, doneEvent = null, skipReason = null, reverts = steps[idx].reverts + (b to e))
                        // 该步骤建立的起点：仅当其下没有任何采样记录时一并撤销
                        protocol.anchorCreatedBy(b.stepId)?.let { a ->
                            val st = anchors.getValue(a.id)
                            if (st.established != null) {
                                val keys = points.filterValues { it.plan.anchorId == a.id }
                                check(keys.values.all { it.status == PointStatus.OPEN && it.voided.isEmpty() } &&
                                    extras.none { ms.getValue(it).body.planId in keys.values.map { p -> p.plan.id } }) { "起点 ${a.id} 下已有采样记录，不能随步骤撤销" }
                                keys.keys.forEach { points.remove(it) }
                                anchors[a.id] = AnchorState(st.def, null, null)
                            }
                        }
                    }
                    is MeasurementRecorded -> {
                        ms[b.measurementId] = Measurement(b, e)
                        if (b.pointIndex == null) extras += b.measurementId
                        else {
                            val p = pointOf(b.planId, b.pointIndex)
                            check(p != null && p.status == PointStatus.OPEN) { "采样点 ${b.planId}#${b.pointIndex} 不可录入" }
                            p.status = PointStatus.RECORDED; p.mId = b.measurementId
                        }
                    }
                    is MeasurementCorrected -> {
                        val m = ms[b.measurementId]
                        check(m != null && !m.isVoided) { "测量 ${b.measurementId} 不存在或已作废" }
                        ms[b.measurementId] = m.copy(corrections = m.corrections + (b to e))
                    }
                    is MeasurementVoided -> {
                        val m = ms[b.measurementId]
                        check(m != null && !m.isVoided) { "测量 ${b.measurementId} 不存在或已作废" }
                        ms[b.measurementId] = m.copy(voided = b to e)
                        m.body.pointIndex?.let { idx ->
                            val p = pointOf(m.body.planId, idx)!!
                            p.voided += b.measurementId; p.mId = null
                            if (ended == null) { p.status = PointStatus.OPEN; p.missed = null }
                            else { p.status = PointStatus.MISSED; p.missed = "voided" }
                        }
                    }
                    is SampleMissed -> {
                        val p = pointOf(b.planId, b.pointIndex)
                        check(p != null && p.status == PointStatus.OPEN) { "采样点 ${b.planId}#${b.pointIndex} 不可标记漏测" }
                        p.status = PointStatus.MISSED; p.missed = b.reason
                    }
                    is SampleMissedRevoked -> {
                        val p = pointOf(b.planId, b.pointIndex)
                        check(p != null && p.status == PointStatus.MISSED) { "采样点 ${b.planId}#${b.pointIndex} 不是漏测" }
                        p.status = PointStatus.OPEN; p.missed = null
                    }
                    is ObservationAdded -> obs[b.observationId] = Observation(b, e)
                    is ObservationCorrected -> {
                        val o = obs[b.observationId]
                        check(o != null && !o.isVoided) { "现象 ${b.observationId} 不存在或已作废" }
                        obs[b.observationId] = o.copy(corrections = o.corrections + (b to e))
                    }
                    is ObservationVoided -> {
                        val o = obs[b.observationId]
                        check(o != null && !o.isVoided) { "现象 ${b.observationId} 不存在或已作废" }
                        obs[b.observationId] = o.copy(voided = b to e)
                    }
                    is AdhocTableCreated -> {
                        check(b.tableId !in adhoc) { "测量表 ${b.tableId} 重复建立" }
                        val anchorId = "t0-${b.tableId}"
                        val plan = SamplingPlanDef(b.tableId, b.title, anchorId,
                            if (b.intervalS != null && b.count != null) (0 until b.count).map { it * b.intervalS } else emptyList(),
                            b.fields.map { f -> FieldDef(f.id, f.label, FieldType.entries.first { it.wire == f.type }, f.unit) })
                        val anchor = AnchorDef(anchorId, "${b.title} 开始", AnchorTrigger.Manual)
                        plans += plan
                        adhoc[b.tableId] = AdhocTable(b, e, plan, anchor)
                        anchors[anchorId] = AnchorState(anchor, AnchorEstablished(anchorId, AnchorSource.MANUAL, null, b.atWallMs, b.atTRunMs, e.timeQuality), e)
                        regenerate(anchorId, b.atTRunMs)
                    }
                    is AdhocTableStopped -> {
                        val t = adhoc[b.tableId]
                        check(t != null && t.isTimed && t.stopped == null) { "测量表 ${b.tableId} 不可停止" }
                        adhoc[b.tableId] = t.copy(stopped = e)
                        // 未到点的计划时刻取消；已到点的保留（仍可录入或结束时记为漏测）
                        points.entries.removeIf { (_, p) -> p.plan.id == b.tableId && p.status == PointStatus.OPEN && p.voided.isEmpty() && p.planned > e.tRunMs }
                    }
                    is ReminderFired -> reminders += b to e
                    is ClockDiscontinuity -> clock += e
                    is RunEnded -> {
                        ended = e
                        steps.replaceAll { if (it.status == StepStatus.CURRENT || it.status == StepStatus.PENDING) it.copy(status = StepStatus.NOT_COMPLETED) else it }
                    }
                }
            }
            val built = points.values.map { p ->
                SamplePoint(p.plan, p.index, p.plan.offsetsSeconds[p.index], p.planned, p.status, p.mId?.let { ms.getValue(it) }, p.missed, p.voided.map { ms.getValue(it) })
            }.sortedWith(compareBy({ it.plannedTRunMs }, { it.plan.id }, { it.index }))
            return RunState(protocol, events, rs.runId, first, steps.toList(), anchors.toMap(), built,
                extras.map { ms.getValue(it) }, obs.values.toList(), reminders, clock, ended, actions, adhoc.values.toList())
        }
    }
}

fun EventBody.isCorrection() = this is MeasurementCorrected || this is MeasurementVoided || this is SampleMissedRevoked ||
    this is StepReverted || this is AnchorCorrected || this is ObservationCorrected || this is ObservationVoided

package labrun.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 一个时刻的完整读数：墙上时间 + 时区 + 单调时钟（含休眠）+ 开机序号。 */
data class Now(val wallMs: Long, val tzOffsetS: Int, val monoMs: Long, val bootCount: Int)

fun interface Clock { fun now(): Now }

@Serializable
enum class TimeQuality {
    /** 与开始同一次开机，由单调时钟求差。 */
    @SerialName("monotonic") MONOTONIC,
    /** 跨重启，只能由墙上时间估算。 */
    @SerialName("wall_estimate") WALL_ESTIMATE,
    /** 用户补填的时刻（改早的测量时间、回溯的锚点、自由现象发生时间）。 */
    @SerialName("user_entered") USER_ENTERED,
}

/** 只追加的事件。运行的一切状态都由事件按 seq 重放得到。 */
@Serializable
data class Event(
    val seq: Long,
    @SerialName("event_id") val eventId: String,
    /** 客户端动作 ID：同一次点击重复提交不会产生第二组事件。 */
    @SerialName("action_id") val actionId: String,
    @SerialName("wall_ms") val wallMs: Long,
    @SerialName("tz_offset_s") val tzOffsetS: Int,
    @SerialName("mono_ms") val monoMs: Long,
    @SerialName("boot_count") val bootCount: Int,
    @SerialName("t_run_ms") val tRunMs: Long,
    @SerialName("time_quality") val timeQuality: TimeQuality,
    val body: EventBody,
)

@Serializable
sealed interface EventBody

@Serializable @SerialName("run_started")
data class RunStarted(
    @SerialName("run_id") val runId: String,
    @SerialName("protocol_id") val protocolId: String,
    @SerialName("protocol_version") val protocolVersion: String,
    @SerialName("protocol_sha256") val protocolSha256: String,
    val title: String,
    val device: String? = null,
) : EventBody

@Serializable
enum class AnchorSource {
    @SerialName("run_started") RUN_STARTED,
    @SerialName("step_confirmed") STEP_CONFIRMED,
    @SerialName("manual") MANUAL,
    @SerialName("manual_backdated") MANUAL_BACKDATED,
}

@Serializable @SerialName("anchor_established")
data class AnchorEstablished(
    @SerialName("anchor_id") val anchorId: String,
    val source: AnchorSource,
    @SerialName("source_step_id") val sourceStepId: String? = null,
    /** 锚点代表的时刻；回溯锚点时早于事件本身的时间。 */
    @SerialName("at_wall_ms") val atWallMs: Long,
    @SerialName("at_t_run_ms") val atTRunMs: Long,
    @SerialName("at_quality") val atQuality: TimeQuality,
) : EventBody

@Serializable @SerialName("step_confirmed")
data class StepConfirmed(@SerialName("step_id") val stepId: String) : EventBody

@Serializable @SerialName("step_skipped")
data class StepSkipped(@SerialName("step_id") val stepId: String, val reason: String) : EventBody

@Serializable
data class FieldValue(
    /** 用户输入的原文。 */
    val raw: String,
    /** 规范化后的值（数字为十进制字符串，文本为原文去首尾空白）。 */
    val value: String,
    val unit: String? = null,
)

@Serializable @SerialName("measurement_recorded")
data class MeasurementRecorded(
    @SerialName("measurement_id") val measurementId: String,
    @SerialName("plan_id") val planId: String,
    /** 为空表示计划外的追加测量。 */
    @SerialName("point_index") val pointIndex: Int? = null,
    @SerialName("measured_wall_ms") val measuredWallMs: Long,
    @SerialName("measured_t_run_ms") val measuredTRunMs: Long,
    @SerialName("measured_quality") val measuredQuality: TimeQuality,
    val values: Map<String, FieldValue>,
) : EventBody

@Serializable @SerialName("sample_missed")
data class SampleMissed(
    @SerialName("plan_id") val planId: String,
    @SerialName("point_index") val pointIndex: Int,
    val reason: String,
) : EventBody

@Serializable
data class Attachment(
    val id: String,
    /** 导出包内的相对路径，固定为 attachments/<id>.<ext>。 */
    val path: String,
    val mime: String,
    val sha256: String,
    val bytes: Long,
)

@Serializable @SerialName("observation_added")
data class ObservationAdded(
    @SerialName("observation_id") val observationId: String,
    val text: String? = null,
    val attachments: List<Attachment> = emptyList(),
    @SerialName("occurred_wall_ms") val occurredWallMs: Long,
    @SerialName("occurred_t_run_ms") val occurredTRunMs: Long,
    @SerialName("occurred_quality") val occurredQuality: TimeQuality,
    @SerialName("step_id") val stepId: String? = null,
    @SerialName("anchor_id") val anchorId: String? = null,
) : EventBody

// ---------------------------------------------------------------- 计划外测量表（运行中临时新建，不在步骤文件里）

@Serializable
data class AdhocField(val id: String, val label: String, val type: String, val unit: String? = null)

/**
 * 新建一张计划外测量表。以建表时刻为该表的起点（T0）。
 * [intervalS] 与 [count] 同时给出为定时表：在 0、间隔、2×间隔…共 count 个时刻提醒；都为空为手动表。
 */
@Serializable @SerialName("adhoc_table_created")
data class AdhocTableCreated(
    @SerialName("table_id") val tableId: String,
    val title: String,
    val fields: List<AdhocField>,
    @SerialName("interval_s") val intervalS: Long? = null,
    val count: Int? = null,
    @SerialName("at_wall_ms") val atWallMs: Long,
    @SerialName("at_t_run_ms") val atTRunMs: Long,
) : EventBody

/** 停止定时表：尚未到点的计划时刻取消（不算漏测）；已到点未测的保留待处理。 */
@Serializable @SerialName("adhoc_table_stopped")
data class AdhocTableStopped(@SerialName("table_id") val tableId: String) : EventBody

// ---------------------------------------------------------------- 更正（只追加；原记录永远保留）
// 允许在结束后追加的只有：测量更正/作废、起点时刻更正、现象更正/作废。

@Serializable @SerialName("measurement_corrected")
data class MeasurementCorrected(
    @SerialName("measurement_id") val measurementId: String,
    /** 更正后的完整取值与测量时刻（不是差量）。 */
    val values: Map<String, FieldValue>,
    @SerialName("measured_wall_ms") val measuredWallMs: Long,
    @SerialName("measured_t_run_ms") val measuredTRunMs: Long,
    @SerialName("measured_quality") val measuredQuality: TimeQuality,
    val reason: String? = null,
) : EventBody

/** 作废一条测量：运行中该采样点恢复为待测；结束后该点记为漏测。 */
@Serializable @SerialName("measurement_voided")
data class MeasurementVoided(@SerialName("measurement_id") val measurementId: String, val reason: String) : EventBody

/** 撤销一次“标记漏测”（仅运行中）。 */
@Serializable @SerialName("sample_missed_revoked")
data class SampleMissedRevoked(@SerialName("plan_id") val planId: String, @SerialName("point_index") val pointIndex: Int, val reason: String) : EventBody

/** 撤销最近一次步骤确认/跳过（仅运行中）；若该步骤建立了起点且起点下尚无任何采样记录，起点一并撤销。 */
@Serializable @SerialName("step_reverted")
data class StepReverted(@SerialName("step_id") val stepId: String, val reason: String) : EventBody

/** 更正局部起点的时刻；以它为基准的计划采样时刻随之移动。全局起点不可更正。 */
@Serializable @SerialName("anchor_corrected")
data class AnchorCorrected(
    @SerialName("anchor_id") val anchorId: String,
    @SerialName("at_wall_ms") val atWallMs: Long,
    @SerialName("at_t_run_ms") val atTRunMs: Long,
    val reason: String,
) : EventBody

@Serializable @SerialName("observation_corrected")
data class ObservationCorrected(
    @SerialName("observation_id") val observationId: String,
    val text: String? = null,
    @SerialName("occurred_wall_ms") val occurredWallMs: Long,
    @SerialName("occurred_t_run_ms") val occurredTRunMs: Long,
    @SerialName("occurred_quality") val occurredQuality: TimeQuality,
    val reason: String? = null,
) : EventBody

@Serializable @SerialName("observation_voided")
data class ObservationVoided(@SerialName("observation_id") val observationId: String, val reason: String) : EventBody

@Serializable
enum class ReminderKind { @SerialName("sample") SAMPLE, @SerialName("step") STEP }

@Serializable @SerialName("reminder_fired")
data class ReminderFired(
    val kind: ReminderKind,
    @SerialName("ref_id") val refId: String,
    @SerialName("point_index") val pointIndex: Int? = null,
    @SerialName("planned_t_run_ms") val plannedTRunMs: Long,
) : EventBody

@Serializable @SerialName("clock_discontinuity")
data class ClockDiscontinuity(
    /** 墙上经过时间 − 单调经过时间，当前值与上一次记录值。 */
    @SerialName("skew_ms") val skewMs: Long,
    @SerialName("previous_skew_ms") val previousSkewMs: Long,
) : EventBody

@Serializable @SerialName("run_ended")
data class RunEnded(val reason: String? = null) : EventBody

val LabJson = Json {
    classDiscriminator = "type"
    encodeDefaults = false
    explicitNulls = false
    ignoreUnknownKeys = false
    prettyPrint = false
}

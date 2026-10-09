package labrun.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import java.security.MessageDigest

/** 步骤文件 lab-protocol-draft 0.1 的内存模型。规则见 docs/01_需求契约.md §3。 */
data class Protocol(
    val protocolId: String,
    val protocolVersion: String,
    val title: String,
    val description: String?,
    val runStartAnchorId: String,
    val anchors: List<AnchorDef>,
    val steps: List<StepDef>,
    val samplingPlans: List<SamplingPlanDef>,
    val freeObservationsEnabled: Boolean,
) {
    fun anchor(id: String) = anchors.first { it.id == id }
    fun step(id: String) = steps.first { it.id == id }
    fun plan(id: String) = samplingPlans.first { it.id == id }
    /** 确认该步骤时建立的锚点（每步最多一个）。 */
    fun anchorCreatedBy(stepId: String) = anchors.firstOrNull { it.createOn == AnchorTrigger.StepConfirmed(stepId) }
}

sealed interface AnchorTrigger {
    data object RunStarted : AnchorTrigger
    data class StepConfirmed(val stepId: String) : AnchorTrigger
    data object Manual : AnchorTrigger
}

data class AnchorDef(val id: String, val label: String, val createOn: AnchorTrigger)

data class StepDef(
    val id: String,
    val title: String,
    val instruction: String?,
    val samplingPlanIds: List<String>,
    val notBefore: Offset?,
)

data class Offset(val anchorId: String, val offsetSeconds: Long)

enum class FieldType(val wire: String) { DECIMAL("decimal"), INTEGER("integer"), TEXT("text") }

data class FieldDef(val id: String, val label: String, val type: FieldType, val unit: String?)

data class SamplingPlanDef(
    val id: String,
    val label: String,
    val anchorId: String,
    val offsetsSeconds: List<Long>,
    val fields: List<FieldDef>,
)

enum class Severity { ERROR, WARNING }

data class Issue(val severity: Severity, val code: String, val path: String, val message: String) {
    override fun toString() = "${severity.name[0]} $code $path：$message"
}

class ParseResult(val protocol: Protocol?, val issues: List<Issue>, val sha256: String) {
    val ok get() = protocol != null && issues.none { it.severity == Severity.ERROR }
    val errors get() = issues.filter { it.severity == Severity.ERROR }
    val warnings get() = issues.filter { it.severity == Severity.WARNING }
}

fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/**
 * 只解析数据，不执行任何表达式。结构规则与 schema/lab-protocol-0.1.schema.json 对齐，
 * 语义规则（引用、唯一、递增）在此额外校验。所有问题一次性列出，带 JSON 路径。
 */
object ProtocolParser {
    const val FORMAT = "lab-protocol-draft"
    val SUPPORTED_VERSIONS = setOf("0.1")
    const val MAX_BYTES = 1_000_000
    private val ID = Regex("^[a-z0-9_-]{1,64}$")
    private val VERSION = Regex("^[0-9A-Za-z.+-]{1,32}$")

    fun parse(bytes: ByteArray): ParseResult {
        val hash = sha256Hex(bytes)
        if (bytes.size > MAX_BYTES) {
            return ParseResult(null, listOf(Issue(Severity.ERROR, "E_SIZE", "$", "文件超过 1 MB")), hash)
        }
        val text = bytes.toString(Charsets.UTF_8).removePrefix("﻿")
        val root = try {
            Json.parseToJsonElement(text)
        } catch (e: Exception) {
            return ParseResult(null, listOf(Issue(Severity.ERROR, "E_JSON", "$", "不是合法 JSON：${e.message?.lineSequence()?.firstOrNull()}")), hash)
        }
        val ctx = Ctx()
        val p = ctx.protocol(root)
        if (p != null) ctx.semantics(p)
        val issues = ctx.issues.toList()
        return ParseResult(if (issues.any { it.severity == Severity.ERROR }) null else p, issues, hash)
    }

    private class Ctx {
        val issues = mutableListOf<Issue>()
        fun err(code: String, path: String, msg: String) { issues += Issue(Severity.ERROR, code, path, msg) }
        fun warn(code: String, path: String, msg: String) { issues += Issue(Severity.WARNING, code, path, msg) }

        fun obj(e: JsonElement?, path: String, allowed: Set<String>): JsonObject? {
            if (e !is JsonObject) { err("E_TYPE", path, "应为对象"); return null }
            (e.keys - allowed).forEach { err("E_UNKNOWN_KEY", "$path.$it", "不认识的字段") }
            return e
        }

        fun str(o: JsonObject, key: String, path: String, required: Boolean, max: Int = 4000): String? {
            val v = o[key]
            if (v == null || v is JsonNull) { if (required) err("E_REQUIRED", "$path.$key", "缺少必填字段"); return null }
            if (v !is JsonPrimitive || !v.isString) { err("E_TYPE", "$path.$key", "应为字符串"); return null }
            if (required && v.content.isEmpty()) { err("E_EMPTY", "$path.$key", "不能为空"); return null }
            if (v.content.length > max) { err("E_LENGTH", "$path.$key", "超过 $max 字符"); return null }
            return v.content
        }

        fun id(o: JsonObject, key: String, path: String): String? {
            val s = str(o, key, path, required = true) ?: return null
            if (!ID.matches(s)) { err("E_ID", "$path.$key", "“$s”不合规：只允许小写字母、数字、_、-，1–64 字符"); return null }
            return s
        }

        fun arr(o: JsonObject, key: String, path: String, required: Boolean): JsonArray? {
            val v = o[key]
            if (v == null) { if (required) err("E_REQUIRED", "$path.$key", "缺少必填字段"); return null }
            if (v !is JsonArray) { err("E_TYPE", "$path.$key", "应为数组"); return null }
            return v
        }

        fun constStr(o: JsonObject, key: String, path: String, expected: String, code: String = "E_VALUE") {
            val s = str(o, key, path, required = true) ?: return
            if (s != expected) err(code, "$path.$key", "应为 “$expected”，实际为 “$s”")
        }

        fun protocol(root: JsonElement): Protocol? {
            val o = obj(root, "$", setOf("format", "format_version", "status", "synthetic", "protocol_id", "protocol_version",
                "title", "description", "time_unit", "run_start_anchor_id", "anchors", "steps", "sampling_plans", "free_observations")) ?: return null
            val format = str(o, "format", "$", true)
            if (format != null && format != FORMAT) { err("E_FORMAT", "$.format", "不支持的格式 “$format”，需要 “$FORMAT”"); return null }
            val fv = str(o, "format_version", "$", true)
            if (fv != null && fv !in SUPPORTED_VERSIONS) { err("E_FORMAT", "$.format_version", "不支持的版本 “$fv”，支持：${SUPPORTED_VERSIONS.joinToString()}"); return null }
            o["status"]?.let { s -> if (s !is JsonPrimitive || s.content !in setOf("draft", "released")) err("E_VALUE", "$.status", "应为 draft 或 released") }
            o["synthetic"]?.let { s -> if (s !is JsonPrimitive || s.booleanOrNull == null) err("E_TYPE", "$.synthetic", "应为布尔值") }
            val pid = id(o, "protocol_id", "$")
            val pver = str(o, "protocol_version", "$", true)
            if (pver != null && !VERSION.matches(pver)) err("E_VALUE", "$.protocol_version", "只允许字母数字和 . + -，≤32 字符")
            val title = str(o, "title", "$", true)
            val desc = str(o, "description", "$", false)
            o["time_unit"]?.let { if (it !is JsonPrimitive || it.content != "seconds") err("E_UNIT", "$.time_unit", "只支持 seconds") }
                ?: err("E_REQUIRED", "$.time_unit", "缺少必填字段")
            val runAnchor = id(o, "run_start_anchor_id", "$")

            val anchors = arr(o, "anchors", "$", true)?.let { a ->
                if (a.isEmpty()) err("E_EMPTY", "$.anchors", "至少一个锚点")
                if (a.size > 50) err("E_LENGTH", "$.anchors", "最多 50 个")
                a.mapIndexedNotNull { i, e -> anchor(e, "$.anchors[$i]") }
            } ?: emptyList()
            val steps = arr(o, "steps", "$", true)?.let { a ->
                if (a.isEmpty()) err("E_EMPTY", "$.steps", "至少一个步骤")
                if (a.size > 500) err("E_LENGTH", "$.steps", "最多 500 个")
                a.mapIndexedNotNull { i, e -> step(e, "$.steps[$i]") }
            } ?: emptyList()
            val plans = arr(o, "sampling_plans", "$", false)?.let { a ->
                if (a.size > 50) err("E_LENGTH", "$.sampling_plans", "最多 50 个")
                a.mapIndexedNotNull { i, e -> plan(e, "$.sampling_plans[$i]") }
            } ?: emptyList()
            var freeEnabled = true
            o["free_observations"]?.let { e ->
                val fo = obj(e, "$.free_observations", setOf("enabled", "step_association", "advances_step")) ?: return@let
                fo["enabled"]?.let { v -> (v as? JsonPrimitive)?.booleanOrNull?.let { freeEnabled = it } ?: err("E_TYPE", "$.free_observations.enabled", "应为布尔值") }
                fo["step_association"]?.let { v -> if ((v as? JsonPrimitive)?.content !in setOf("optional", "none")) err("E_VALUE", "$.free_observations.step_association", "应为 optional 或 none") }
                fo["advances_step"]?.let { v -> if ((v as? JsonPrimitive)?.booleanOrNull != false) err("E_VALUE", "$.free_observations.advances_step", "自由现象不能推进步骤，只能为 false") }
            }
            if (pid == null || pver == null || title == null || runAnchor == null) return null
            return Protocol(pid, pver, title, desc, runAnchor, anchors, steps, plans, freeEnabled)
        }

        fun anchor(e: JsonElement, path: String): AnchorDef? {
            val o = obj(e, path, setOf("id", "label", "create_on")) ?: return null
            val id = id(o, "id", path)
            val label = str(o, "label", path, true)
            val co = o["create_on"]?.let { obj(it, "$path.create_on", setOf("event", "step_id")) }
            if (o["create_on"] == null) err("E_REQUIRED", "$path.create_on", "缺少必填字段")
            val trigger = co?.let {
                when (val ev = str(it, "event", "$path.create_on", true)) {
                    "run_started" -> AnchorTrigger.RunStarted.also { _ -> if ("step_id" in it) err("E_UNKNOWN_KEY", "$path.create_on.step_id", "run_started 不需要 step_id") }
                    "manual" -> AnchorTrigger.Manual.also { _ -> if ("step_id" in it) err("E_UNKNOWN_KEY", "$path.create_on.step_id", "manual 不需要 step_id") }
                    "step_confirmed" -> id(it, "step_id", "$path.create_on")?.let { s -> AnchorTrigger.StepConfirmed(s) }
                    null -> null
                    else -> { err("E_VALUE", "$path.create_on.event", "不支持的事件 “$ev”"); null }
                }
            }
            return if (id != null && label != null && trigger != null) AnchorDef(id, label, trigger) else null
        }

        fun step(e: JsonElement, path: String): StepDef? {
            val o = obj(e, path, setOf("id", "title", "instruction", "completion", "sampling_plan_ids", "not_before")) ?: return null
            val id = id(o, "id", path)
            val title = str(o, "title", path, true)
            val instr = str(o, "instruction", path, false)
            constStr(o, "completion", path, "manual_confirmation")
            val planIds = arr(o, "sampling_plan_ids", path, false)?.mapIndexedNotNull { i, v ->
                (v as? JsonPrimitive)?.takeIf { it.isString && ID.matches(it.content) }?.content
                    ?: run { err("E_ID", "$path.sampling_plan_ids[$i]", "应为合规 ID"); null }
            } ?: emptyList()
            if (planIds.size != planIds.toSet().size) err("E_DUP_ID", "$path.sampling_plan_ids", "有重复")
            val nb = o["not_before"]?.let { nbE ->
                val nbo = obj(nbE, "$path.not_before", setOf("anchor_id", "offset")) ?: return@let null
                val aid = id(nbo, "anchor_id", "$path.not_before")
                val off = nonNegLong(nbo["offset"], "$path.not_before.offset")
                if (aid != null && off != null) Offset(aid, off) else null
            }
            return if (id != null && title != null) StepDef(id, title, instr, planIds, nb) else null
        }

        fun nonNegLong(v: JsonElement?, path: String): Long? {
            if (v == null) { err("E_REQUIRED", path, "缺少必填字段"); return null }
            val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (n == null || n < 0) { err("E_VALUE", path, "应为非负整数秒"); return null }
            return n
        }

        fun plan(e: JsonElement, path: String): SamplingPlanDef? {
            val o = obj(e, path, setOf("id", "label", "anchor_id", "offsets", "capture_mode", "fields")) ?: return null
            val id = id(o, "id", path)
            val label = str(o, "label", path, true)
            val aid = id(o, "anchor_id", path)
            constStr(o, "capture_mode", path, "manual")
            val offsetsArr = arr(o, "offsets", path, true)
            val offsets = offsetsArr?.mapIndexed { i, v -> nonNegLong(v, "$path.offsets[$i]") }
            if (offsetsArr != null) {
                if (offsetsArr.isEmpty()) err("E_OFFSETS", "$path.offsets", "至少一个偏移")
                if (offsetsArr.size > 500) err("E_LENGTH", "$path.offsets", "最多 500 个")
                offsets?.filterNotNull()?.zipWithNext()?.forEachIndexed { i, (a, b) ->
                    if (b <= a) err("E_OFFSETS", "$path.offsets[${i + 1}]", "必须严格递增（$a → $b）")
                }
            }
            val fieldsArr = arr(o, "fields", path, true)
            if (fieldsArr != null && fieldsArr.isEmpty()) err("E_EMPTY", "$path.fields", "至少一个字段")
            if (fieldsArr != null && fieldsArr.size > 20) err("E_LENGTH", "$path.fields", "最多 20 个")
            val fields = fieldsArr?.mapIndexedNotNull { i, fe ->
                val fp = "$path.fields[$i]"
                val fo = obj(fe, fp, setOf("id", "label", "type", "unit")) ?: return@mapIndexedNotNull null
                val fid = id(fo, "id", fp)
                val fl = str(fo, "label", fp, true)
                val ft = str(fo, "type", fp, true)?.let { t -> FieldType.entries.firstOrNull { it.wire == t } ?: run { err("E_VALUE", "$fp.type", "应为 decimal / integer / text"); null } }
                val unit = str(fo, "unit", fp, false, max = 32)?.takeIf { it.isNotBlank() }
                if (fid != null && fl != null && ft != null) FieldDef(fid, fl, ft, unit) else null
            }
            fields?.groupBy { it.id }?.filter { it.value.size > 1 }?.keys?.forEach { err("E_DUP_ID", "$path.fields", "字段 ID “$it” 重复") }
            return if (id != null && label != null && aid != null && offsets != null && offsets.none { it == null } && fields != null)
                SamplingPlanDef(id, label, aid, offsets.filterNotNull(), fields) else null
        }

        fun semantics(p: Protocol) {
            fun dup(kind: String, path: String, ids: List<String>) =
                ids.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { err("E_DUP_ID", path, "$kind ID “$it” 重复") }
            dup("锚点", "$.anchors", p.anchors.map { it.id })
            dup("步骤", "$.steps", p.steps.map { it.id })
            dup("采样计划", "$.sampling_plans", p.samplingPlans.map { it.id })

            val anchorIds = p.anchors.map { it.id }.toSet()
            val stepIndex = p.steps.withIndex().associate { it.value.id to it.index }
            val planIds = p.samplingPlans.map { it.id }.toSet()

            val runStarted = p.anchors.filter { it.createOn == AnchorTrigger.RunStarted }
            if (runStarted.size != 1) err("E_RUN_ANCHOR", "$.anchors", "必须恰好有一个 create_on.event = run_started 的锚点（现有 ${runStarted.size} 个）")
            val ra = p.anchors.firstOrNull { it.id == p.runStartAnchorId }
            if (ra == null) err("E_RUN_ANCHOR", "$.run_start_anchor_id", "引用的锚点 “${p.runStartAnchorId}” 不存在")
            else if (ra.createOn != AnchorTrigger.RunStarted) err("E_RUN_ANCHOR", "$.run_start_anchor_id", "“${ra.id}” 不是 run_started 锚点")

            p.anchors.forEachIndexed { i, a ->
                val t = a.createOn
                if (t is AnchorTrigger.StepConfirmed && t.stepId !in stepIndex) err("E_REF", "$.anchors[$i].create_on.step_id", "步骤 “${t.stepId}” 不存在")
            }
            p.anchors.mapNotNull { (it.createOn as? AnchorTrigger.StepConfirmed)?.stepId }
                .groupBy { it }.filter { it.value.size > 1 }.keys
                .forEach { err("E_ANCHOR_STEP_DUP", "$.anchors", "步骤 “$it” 建立了多个锚点，每步最多一个") }

            val referencedAnchors = mutableSetOf(p.runStartAnchorId)
            val referencedPlans = mutableSetOf<String>()
            p.steps.forEachIndexed { i, s ->
                s.samplingPlanIds.forEachIndexed { j, pid ->
                    referencedPlans += pid
                    if (pid !in planIds) err("E_REF", "$.steps[$i].sampling_plan_ids[$j]", "采样计划 “$pid” 不存在")
                }
                s.notBefore?.let { nb ->
                    referencedAnchors += nb.anchorId
                    if (nb.anchorId !in anchorIds) err("E_REF", "$.steps[$i].not_before.anchor_id", "锚点 “${nb.anchorId}” 不存在")
                }
            }
            p.samplingPlans.forEachIndexed { i, pl ->
                referencedAnchors += pl.anchorId
                if (pl.anchorId !in anchorIds) { err("E_REF", "$.sampling_plans[$i].anchor_id", "锚点 “${pl.anchorId}” 不存在"); return@forEachIndexed }
                val creator = (p.anchor(pl.anchorId).createOn as? AnchorTrigger.StepConfirmed)?.stepId?.let { stepIndex[it] }
                p.steps.withIndex().filter { pl.id in it.value.samplingPlanIds }.forEach { (si, s) ->
                    if (creator != null && creator > si)
                        warn("W_ANCHOR_ORDER", "$.steps[$si].sampling_plan_ids", "“${s.id}” 期间的采样依赖更晚步骤建立的锚点 “${pl.anchorId}”")
                }
            }
            p.anchors.forEachIndexed { i, a ->
                val usedByStep = a.createOn is AnchorTrigger.StepConfirmed
                if (a.id !in referencedAnchors && !usedByStep && a.createOn != AnchorTrigger.Manual)
                    warn("W_UNUSED", "$.anchors[$i]", "锚点 “${a.id}” 未被引用")
            }
            p.samplingPlans.forEachIndexed { i, pl ->
                if (pl.id !in referencedPlans) warn("W_UNUSED", "$.sampling_plans[$i]", "采样计划 “${pl.id}” 没有挂在任何步骤上（仍会按锚点生成采样点）")
            }
        }
    }
}

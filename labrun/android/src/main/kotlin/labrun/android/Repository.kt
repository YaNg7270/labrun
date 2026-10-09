package labrun.android

import android.content.Context
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import labrun.core.Attachment
import labrun.core.CommandResult
import labrun.core.EventSink
import labrun.core.LabPackage
import labrun.core.ParseResult
import labrun.core.ProtocolParser
import labrun.core.ReminderKind
import labrun.core.RunEngine
import labrun.core.RunReport
import labrun.core.RunStarted
import labrun.core.RunState
import labrun.core.sha256Hex
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 应用唯一的数据入口。所有调用都在主线程（界面与广播接收器都在主线程），因此对引擎的访问天然串行。
 */
class Repository(private val ctx: Context) {
    val storage = Storage(ctx.filesDir)
    val clock = AndroidClock(ctx)

    var engine: RunEngine? = null
        private set
    /** 当前运行的状态快照；界面观察它。 */
    var state by mutableStateOf<RunState?>(null)
        private set
    /** 一次性提示（成功/拒绝原因）。 */
    var message by mutableStateOf<String?>(null)
    /** 最近一次被拒绝的原因；对话框内直接显示（提示条会被对话框挡住）。成功后清空。 */
    var rejection by mutableStateOf<String?>(null)

    fun reject(msg: String) { message = msg; rejection = msg }
    /** 恢复时发现的问题（如事件文件尾部损坏）。 */
    var recoveryNote by mutableStateOf<String?>(null)
    /** 刷新步骤文件库与历史运行列表的计数器。 */
    var libraryVersion by mutableStateOf(0)
        private set

    init {
        storage.activeRunId?.let { id ->
            try {
                val loaded = storage.loadRun(id)
                val eng = RunEngine.restore(loaded.protocolBytes, loaded.events, clock, storage.sink(id))
                if (loaded.droppedTail) recoveryNote = "上次写入时中断，最后一条未写完的记录已丢弃（其它记录完整）。"
                if (eng.state.isEnded) storage.activeRunId = null else engine = eng
                state = eng.state.takeIf { !it.isEnded }
            } catch (e: Exception) {
                recoveryNote = "无法恢复进行中的实验：${e.message}"
            }
        }
    }

    // ---------------------------------------------------------------- 导入与开始

    fun parse(bytes: ByteArray): ParseResult = ProtocolParser.parse(bytes)

    fun saveProtocol(bytes: ByteArray): Storage.SaveResult = storage.saveProtocol(bytes).also { libraryVersion++ }
    /** 从步骤文件库删除；已开始的实验各自保存了快照，不受影响。 */
    fun deleteProtocol(bytes: ByteArray) { storage.deleteProtocol(sha256Hex(bytes)); libraryVersion++; message = "已从步骤文件库删除" }

    fun startRun(protocolBytes: ByteArray): Boolean {
        if (engine != null) { message = "已有进行中的实验，请先结束"; return false }
        var runId: String? = null
        val sink = EventSink { evs ->
            if (runId == null) {
                runId = (evs.first().body as RunStarted).runId
                storage.createRun(runId!!, protocolBytes)
            }
            storage.sink(runId!!).append(evs)
        }
        val eng = try {
            RunEngine.start(protocolBytes, clock, sink, "start:" + UUID.randomUUID(), device = "${Build.MANUFACTURER} ${Build.MODEL}")
        } catch (e: Exception) { message = e.message; return false }
        storage.activeRunId = eng.state.runId
        engine = eng
        state = eng.state
        libraryVersion++
        Reminders.reschedule(ctx, this)
        return true
    }

    // ---------------------------------------------------------------- 运行命令

    private fun apply(ok: String?, block: (RunEngine) -> CommandResult): Boolean {
        val eng = engine ?: run { reject("没有进行中的实验"); return false }
        return applyOn(eng, ok, block)
    }

    /**
     * 对指定运行追加更正：进行中的用当前引擎；已结束的从文件恢复一个引擎（只允许结束后可追加的更正）。
     */
    private fun applyTo(runId: String, ok: String, block: (RunEngine) -> CommandResult): Boolean {
        val active = engine?.takeIf { it.state.runId == runId }
        if (active != null) return applyOn(active, ok, block)
        val eng = try {
            val l = storage.loadRun(runId)
            RunEngine.restore(l.protocolBytes, l.events, clock, storage.sink(runId))
        } catch (e: Exception) { reject("无法打开该实验：${e.message}"); return false }
        val r = try { block(eng) } catch (e: Exception) { reject("保存失败：${e.message}"); return false }
        if (r is CommandResult.Rejected) reject(r.reason) else { message = ok; rejection = null }
        libraryVersion++
        return r !is CommandResult.Rejected
    }

    private fun applyOn(eng: RunEngine, ok: String?, block: (RunEngine) -> CommandResult): Boolean {
        val r = try { block(eng) } catch (e: Exception) { reject("保存失败：${e.message}"); return false }
        state = eng.state
        when (r) {
            is CommandResult.Accepted -> { if (ok != null) message = ok; rejection = null }
            CommandResult.Duplicate -> rejection = null
            is CommandResult.Rejected -> reject(r.reason)
        }
        if (eng.state.isEnded) {
            storage.activeRunId = null
            engine = null
            state = null // 已结束的运行不再是“进行中”；详情页按 run_id 从文件读取
            libraryVersion++
        }
        Reminders.reschedule(ctx, this)
        return r !is CommandResult.Rejected
    }

    fun confirmStep(stepId: String, actionId: String) = apply("已确认") { it.confirmStep(stepId, actionId) }
    fun skipStep(stepId: String, reason: String, actionId: String) = apply("已跳过") { it.skipStep(stepId, reason, actionId) }
    fun establishAnchor(anchorId: String, atWallMs: Long?, actionId: String) = apply("计时起点已建立") { it.establishManualAnchor(anchorId, atWallMs, actionId) }
    fun record(planId: String, index: Int?, raw: Map<String, String>, measuredWallMs: Long?, actionId: String) =
        apply("已录入") { it.recordMeasurement(planId, index, raw, measuredWallMs, actionId) }
    fun markMissed(planId: String, index: Int, reason: String, actionId: String) = apply("已标记漏测") { it.markMissed(planId, index, reason, actionId) }
    fun addObservation(text: String?, photos: List<File>, occurredWallMs: Long?, stepId: String?, actionId: String): Boolean {
        val atts = photos.map { f ->
            val bytes = f.readBytes()
            Attachment(f.nameWithoutExtension, "attachments/${f.name}", "image/jpeg", sha256Hex(bytes), bytes.size.toLong())
        }
        return apply("已记录现象") { it.addObservation(text, atts, occurredWallMs, stepId, null, actionId) }
    }
    fun endRun(acknowledged: Set<String>, actionId: String): Boolean {
        val id = engine?.state?.runId
        val ok = apply("实验已结束") { it.end(acknowledged, actionId) }
        if (ok && id != null) lastEndedRunId = id
        return ok
    }
    var lastEndedRunId by mutableStateOf<String?>(null)

    // ---------------------------------------------------------------- 计划外测量表

    fun createTable(title: String, fields: List<RunEngine.NewField>, intervalS: Long?, count: Int?, actionId: String) =
        apply("已新建测量表“${title.trim()}”") { it.createAdhocTable(title, fields, intervalS, count, actionId) }
    fun stopTable(tableId: String, actionId: String) = apply("已停止定时测量") { it.stopAdhocTable(tableId, actionId) }

    // ---------------------------------------------------------------- 更正（只追加，原记录保留）

    fun correctMeasurement(runId: String, id: String, raw: Map<String, String>?, wallMs: Long?, reason: String?, actionId: String) =
        applyTo(runId, "已更正（原值已保留）") { it.correctMeasurement(id, raw, wallMs, reason, actionId) }
    fun voidMeasurement(runId: String, id: String, reason: String, actionId: String) =
        applyTo(runId, "已作废（原记录已保留）") { it.voidMeasurement(id, reason, actionId) }
    fun revokeMissed(planId: String, index: Int, reason: String, actionId: String) =
        apply("已撤销漏测") { it.revokeMissed(planId, index, reason, actionId) }
    fun revertStep(stepId: String, reason: String, actionId: String) = apply("已撤销确认") { it.revertStep(stepId, reason, actionId) }
    fun correctAnchor(runId: String, anchorId: String, wallMs: Long, reason: String, actionId: String) =
        applyTo(runId, "起点时刻已更正") { it.correctAnchor(anchorId, wallMs, reason, actionId) }
    fun correctObservation(runId: String, id: String, text: String?, wallMs: Long?, reason: String?, actionId: String) =
        applyTo(runId, "已更正（原内容已保留）") { it.correctObservation(id, text, wallMs, reason, actionId) }
    fun voidObservation(runId: String, id: String, reason: String, actionId: String) =
        applyTo(runId, "已作废（原记录已保留）") { it.voidObservation(id, reason, actionId) }

    fun attachmentFile(runId: String, a: Attachment) = File(storage.runDir(runId), a.path)

    /** 新照片文件（尚未登记）。拍照取消时调用方删除它。 */
    fun newPhotoFile(): File {
        val id = engine?.state?.runId ?: error("没有进行中的实验")
        return File(storage.attachmentsDir(id), UUID.randomUUID().toString() + ".jpg")
    }

    // ---------------------------------------------------------------- 提醒

    fun onAlarm() {
        Reminders.markFired()
        val eng = engine ?: return Reminders.reschedule(ctx, this)
        val (now, _) = eng.nowTRun()
        val due = Reminders.targets(eng.state).filter { it.plannedTRunMs <= now + 1_000 }
        due.forEach { t -> eng.reminderFired(t.kind, t.refId, t.pointIndex, t.plannedTRunMs, t.actionId) }
        state = eng.state
        Reminders.notify(ctx, due)
        Reminders.reschedule(ctx, this)
    }

    fun upcomingReminderKinds(): List<ReminderKind> = engine?.let { Reminders.targets(it.state).map { t -> t.kind } }.orEmpty()

    // ---------------------------------------------------------------- 历史与导出

    data class RunSummary(val runId: String, val state: RunState?, val error: String?, val archived: Boolean)

    /** 按开始时间新→旧（不按目录修改时间：归档标记会改动目录时间）。 */
    fun runs(): List<RunSummary> = storage.listRunIds().map { id ->
        val archived = storage.isArchived(id)
        try {
            val l = storage.loadRunReadOnly(id)
            RunSummary(id, RunState.replay(ProtocolParser.parse(l.protocolBytes).protocol!!, l.events), null, archived)
        } catch (e: Exception) { RunSummary(id, null, e.message, archived) }
    }.sortedByDescending { it.state?.start?.wallMs ?: Long.MAX_VALUE }

    fun setArchived(runId: String, on: Boolean) {
        if (on && runId == storage.activeRunId) { reject("进行中的实验不能归档，请先结束"); return }
        storage.setArchived(runId, on)
        libraryVersion++
        message = if (on) "已归档" else "已取消归档"
    }

    fun deleteRun(runId: String): Boolean {
        if (runId == storage.activeRunId) { reject("进行中的实验不能删除，请先结束"); return false }
        val ok = try { storage.deleteRun(runId) } catch (e: Exception) { reject("删除失败：${e.message}"); return false }
        // 顺带清掉这次实验之前生成的导出缓存
        File(ctx.cacheDir, "exports").listFiles { f -> f.name.contains("_${runId.take(8)}.") }?.forEach { it.delete() }
        if (lastEndedRunId == runId) lastEndedRunId = null
        libraryVersion++
        if (ok) message = "已删除" else reject("删除未完成，部分文件可能仍在")
        return ok
    }

    fun loadState(runId: String): RunState? = runs().firstOrNull { it.runId == runId }?.state

    enum class ExportKind(val ext: String, val mime: String, val label: String) {
        PACKAGE("labrun.zip", "application/zip", "数据包"),
        HTML("html", "text/html", "HTML 报告"),
        XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel"),
    }

    /** 生成导出文件（数据包 / HTML 报告 / Excel），报告与电脑端用同一份核心代码生成。 */
    fun export(runId: String, kind: ExportKind = ExportKind.PACKAGE): File {
        val l = storage.loadRunReadOnly(runId)
        val st = RunState.replay(ProtocolParser.parse(l.protocolBytes).protocol!!, l.events)
        val day = SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date(st.start.wallMs))
        val safeTitle = st.protocol.title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_").take(40)
        val out = File(File(ctx.cacheDir, "exports").apply { mkdirs() }, "${day}_${safeTitle}_${runId.take(8)}.${kind.ext}")
        val app = "android-${BuildInfo.VERSION}"
        out.outputStream().use { o ->
            when (kind) {
                ExportKind.PACKAGE -> LabPackage.write(o, l.protocolBytes, st, clock.now(), app) { a -> File(storage.runDir(runId), a.path).inputStream() }
                ExportKind.HTML -> {
                    val photos = st.observations.flatMap { it.body.attachments }.associate { a -> a.path to File(storage.runDir(runId), a.path).readBytes() }
                    o.write(RunReport(st, app).html(photos).toByteArray())
                }
                ExportKind.XLSX -> RunReport(st, app).writeXlsx(o)
            }
        }
        return out
    }
}

object BuildInfo { const val VERSION = labrun.android.BuildConfig.VERSION_NAME }

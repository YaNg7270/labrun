package labrun.android

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import labrun.core.Clock
import labrun.core.Event
import labrun.core.EventSink
import labrun.core.LabJson
import labrun.core.Now
import labrun.core.ProtocolParser
import labrun.core.sha256Hex
import java.io.File
import java.io.FileOutputStream
import java.util.TimeZone

class AndroidClock(private val context: Context) : Clock {
    override fun now(): Now {
        val wall = System.currentTimeMillis()
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        return Now(wall, TimeZone.getDefault().getOffset(wall) / 1000, SystemClock.elapsedRealtime(), boot)
    }
}

/**
 * 文件布局（应用私有目录）：
 *   protocols/<sha256>.json          导入过的步骤文件原文
 *   runs/<run_id>/protocol.snapshot.json
 *   runs/<run_id>/events.jsonl       只追加，每行一个事件，写后 fsync
 *   runs/<run_id>/attachments/       照片
 *   runs/<run_id>/archived           存在即“已归档”（仅本机显示用）
 *   active_run                       进行中运行的 run_id
 */
class Storage(private val root: File) {
    private companion object { const val ARCHIVED = "archived" }
    private val protocolsDir = File(root, "protocols").apply { mkdirs() }
    val runsDir = File(root, "runs").apply { mkdirs() }
    private val activeFile = File(root, "active_run")

    // ---------------------------------------------------------------- 步骤文件库

    data class StoredProtocol(val sha256: String, val bytes: ByteArray, val title: String, val id: String, val version: String, val importedAt: Long)

    sealed interface SaveResult {
        data class Saved(val p: StoredProtocol) : SaveResult
        data class AlreadyThere(val p: StoredProtocol) : SaveResult
        data class Conflict(val message: String) : SaveResult
    }

    fun listProtocols(): List<StoredProtocol> = protocolsDir.listFiles { f -> f.extension == "json" }.orEmpty().mapNotNull { f ->
        val bytes = f.readBytes()
        ProtocolParser.parse(bytes).protocol?.let { StoredProtocol(f.nameWithoutExtension, bytes, it.title, it.protocolId, it.protocolVersion, f.lastModified()) }
    }.sortedByDescending { it.importedAt }

    /** 契约 §3：同 id+版本、同内容 → 不重复入库；同 id+版本、内容不同 → 拒绝。只保存通过校验的文件。 */
    fun saveProtocol(bytes: ByteArray): SaveResult {
        val p = requireNotNull(ProtocolParser.parse(bytes).protocol)
        val sha = sha256Hex(bytes)
        val existing = listProtocols()
        existing.firstOrNull { it.sha256 == sha }?.let { return SaveResult.AlreadyThere(it) }
        existing.firstOrNull { it.id == p.protocolId && it.version == p.protocolVersion }?.let {
            return SaveResult.Conflict("已有同一步骤文件 ${p.protocolId} 版本 ${p.protocolVersion}，但内容不同。请在文件里提升 protocol_version 后再导入。")
        }
        atomicWrite(File(protocolsDir, "$sha.json"), bytes)
        return SaveResult.Saved(StoredProtocol(sha, bytes, p.title, p.protocolId, p.protocolVersion, System.currentTimeMillis()))
    }

    fun deleteProtocol(sha: String) = File(protocolsDir, "$sha.json").delete()

    // ---------------------------------------------------------------- 运行

    fun runDir(runId: String) = File(runsDir, runId)
    fun attachmentsDir(runId: String) = File(runDir(runId), "attachments").apply { mkdirs() }

    var activeRunId: String?
        get() = activeFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
        set(v) = if (v == null) { activeFile.delete(); Unit } else atomicWrite(activeFile, v.toByteArray())

    fun createRun(runId: String, protocolBytes: ByteArray) {
        val dir = runDir(runId)
        check(dir.mkdirs()) { "运行目录已存在：$runId" }
        atomicWrite(File(dir, "protocol.snapshot.json"), protocolBytes)
    }

    fun sink(runId: String) = EventSink { events ->
        FileOutputStream(File(runDir(runId), "events.jsonl"), true).use { out ->
            val text = events.joinToString("") { LabJson.encodeToString(Event.serializer(), it) + "\n" }
            out.write(text.toByteArray())
            out.fd.sync()
        }
    }

    class LoadedRun(val runId: String, val protocolBytes: ByteArray, val events: List<Event>, val droppedTail: Boolean)

    /** 读事件文件；最后一行若因断电只写了一半，丢弃并报告，其它行损坏则拒绝加载。 */
    fun loadRun(runId: String): LoadedRun = load(runId, repair = true)
    fun loadRunReadOnly(runId: String): LoadedRun = load(runId, repair = false)

    private fun load(runId: String, repair: Boolean): LoadedRun {
        val dir = runDir(runId)
        val lines = File(dir, "events.jsonl").readText().split("\n")
        val events = mutableListOf<Event>()
        var dropped = false
        lines.forEachIndexed { i, line ->
            if (line.isBlank()) return@forEachIndexed
            try { events += LabJson.decodeFromString(Event.serializer(), line) }
            catch (e: Exception) {
                if (i == lines.lastIndex) dropped = true else throw IllegalStateException("事件文件第 ${i + 1} 行损坏：${e.message}")
            }
        }
        // 截掉半行，否则后续追加会把它夹在中间
        if (dropped && repair) atomicWrite(File(dir, "events.jsonl"), events.joinToString("") { LabJson.encodeToString(Event.serializer(), it) + "\n" }.toByteArray())
        return LoadedRun(runId, File(dir, "protocol.snapshot.json").readBytes(), events, dropped)
    }

    /** 归档只是本机的显示标记（运行目录里的 archived 文件），不属于实验记录，也不进导出包。 */
    fun isArchived(runId: String) = File(runDir(runId), ARCHIVED).exists()

    fun setArchived(runId: String, on: Boolean) {
        val f = File(checkedRunDir(runId), ARCHIVED)
        if (on) atomicWrite(f, System.currentTimeMillis().toString().toByteArray()) else f.delete()
    }

    /** 永久删除一个运行的全部文件（事件、快照、照片）。进行中的运行不能删。 */
    fun deleteRun(runId: String): Boolean {
        check(runId != activeRunId) { "进行中的实验不能删除，请先结束" }
        return checkedRunDir(runId).deleteRecursively()
    }

    private fun checkedRunDir(runId: String): File {
        val dir = runDir(runId)
        require(runId.isNotBlank() && dir.parentFile == runsDir && dir.isDirectory) { "无效的运行：$runId" }
        return dir
    }

    fun listRunIds(): List<String> = runsDir.listFiles { f -> File(f, "events.jsonl").exists() }.orEmpty()
        .sortedByDescending { it.lastModified() }.map { it.name }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        check(tmp.renameTo(target)) { "写入失败：${target.name}" }
    }
}

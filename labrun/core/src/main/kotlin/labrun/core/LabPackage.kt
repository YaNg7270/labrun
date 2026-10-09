package labrun.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object PackagePaths {
    const val MANIFEST = "manifest.json"
    const val PROTOCOL = "protocol.snapshot.json"
    const val RUN = "run.json"
    const val TIMELINE = "derived/timeline.csv"
    const val MEASUREMENTS = "derived/measurements.csv"
    private val ATTACHMENT = Regex("^attachments/[0-9a-zA-Z-]{1,64}\\.(jpg|jpeg|png|webp)$")
    private val DERIVED = Regex("^derived/[a-z0-9_.-]{1,64}$")

    fun isSafeAttachment(p: String) = ATTACHMENT.matches(p)
    /** 只接受白名单形态的相对路径：杜绝 ..、绝对路径、盘符、反斜杠。 */
    fun isAllowed(p: String) = p == MANIFEST || p == PROTOCOL || p == RUN || DERIVED.matches(p) || isSafeAttachment(p)
}

@Serializable
data class ManifestFile(val path: String, val sha256: String, val bytes: Long)

@Serializable
data class Manifest(
    @SerialName("package_format") val packageFormat: String = FORMAT,
    @SerialName("package_version") val packageVersion: String = VERSION,
    @SerialName("run_id") val runId: String,
    @SerialName("run_status") val runStatus: String,
    val title: String,
    @SerialName("exported_at") val exportedAt: String,
    @SerialName("app_version") val appVersion: String,
    val files: List<ManifestFile>,
) {
    companion object {
        const val FORMAT = "labrun-package"
        const val VERSION = "0.1"
    }
}

@Serializable
data class RunFile(
    @SerialName("run_id") val runId: String,
    val events: List<Event>,
)

class PackageContent(
    val manifest: Manifest,
    val protocolBytes: ByteArray,
    val runJsonBytes: ByteArray,
    val state: RunState,
    val attachments: Map<String, ByteArray>,
) {
    /** 重复导入判定用：run.json 的哈希。 */
    val runSha256 get() = sha256Hex(runJsonBytes)
}

class PackageException(message: String) : Exception(message)

object LabPackage {
    private val pretty = Json(LabJson) { prettyPrint = true }
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_ENTRIES = 5000

    fun write(
        out: OutputStream, protocolBytes: ByteArray, state: RunState, exportedAt: Now, appVersion: String,
        openAttachment: (Attachment) -> InputStream,
    ) {
        val views = RunViews(state)
        val files = linkedMapOf<String, ByteArray>()
        files[PackagePaths.PROTOCOL] = protocolBytes
        files[PackagePaths.RUN] = pretty.encodeToString(RunFile.serializer(), RunFile(state.runId, state.events)).toByteArray()
        files[PackagePaths.TIMELINE] = views.timelineCsv().toByteArray()
        files[PackagePaths.MEASUREMENTS] = views.measurementsCsv().toByteArray()
        state.observations.flatMap { it.body.attachments }.forEach { a ->
            val bytes = openAttachment(a).use { it.readBytes() }
            if (sha256Hex(bytes) != a.sha256) throw PackageException("附件 ${a.path} 内容与记录的哈希不一致")
            files[a.path] = bytes
        }
        val manifest = Manifest(runId = state.runId, runStatus = if (state.isEnded) "ended" else "running",
            title = state.protocol.title, exportedAt = TimeFormat.iso(exportedAt.wallMs, exportedAt.tzOffsetS), appVersion = appVersion,
            files = files.map { (p, b) -> ManifestFile(p, sha256Hex(b), b.size.toLong()) })
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry() }
            put(PackagePaths.MANIFEST, pretty.encodeToString(Manifest.serializer(), manifest).toByteArray())
            files.forEach { (p, b) -> put(p, b) }
        }
    }

    /** 完整校验后才返回内容；任何不一致都抛 [PackageException]，不做部分导入。 */
    fun read(input: InputStream): PackageContent {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val name = e.name
                if (!PackagePaths.isAllowed(name)) throw PackageException("包内路径不允许：$name")
                if (name in entries) throw PackageException("包内路径重复：$name")
                if (entries.size >= MAX_ENTRIES) throw PackageException("包内文件过多")
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = zip.read(chunk); if (n < 0) break
                    total += n
                    if (total > MAX_TOTAL_BYTES) throw PackageException("包解压后超过 512 MB")
                    buf.write(chunk, 0, n)
                }
                entries[name] = buf.toByteArray()
            }
        }
        val manifestBytes = entries.remove(PackagePaths.MANIFEST) ?: throw PackageException("缺少 manifest.json")
        val manifest = try { LabJson.decodeFromString(Manifest.serializer(), manifestBytes.decodeToString()) }
        catch (e: Exception) { throw PackageException("manifest.json 无法解析：${e.message}") }
        if (manifest.packageFormat != Manifest.FORMAT) throw PackageException("不是实验记录包（${manifest.packageFormat}）")
        if (manifest.packageVersion != Manifest.VERSION) throw PackageException("不支持的包版本 ${manifest.packageVersion}")
        val listed = manifest.files.associateBy { it.path }
        if (listed.size != manifest.files.size) throw PackageException("清单路径重复")
        (entries.keys - listed.keys).firstOrNull()?.let { throw PackageException("清单外的文件：$it") }
        (listed.keys - entries.keys).firstOrNull()?.let { throw PackageException("清单中的文件缺失：$it") }
        listed.values.forEach { f ->
            val b = entries.getValue(f.path)
            if (b.size.toLong() != f.bytes || sha256Hex(b) != f.sha256) throw PackageException("文件校验失败：${f.path}")
        }
        val protocolBytes = entries[PackagePaths.PROTOCOL] ?: throw PackageException("缺少 ${PackagePaths.PROTOCOL}")
        val runBytes = entries[PackagePaths.RUN] ?: throw PackageException("缺少 ${PackagePaths.RUN}")
        val parsed = ProtocolParser.parse(protocolBytes)
        val protocol = parsed.protocol ?: throw PackageException("步骤快照无效：" + parsed.errors.joinToString("；"))
        val run = try { LabJson.decodeFromString(RunFile.serializer(), runBytes.decodeToString()) }
        catch (e: Exception) { throw PackageException("run.json 无法解析：${e.message}") }
        val state = try { RunState.replay(protocol, run.events) } catch (e: Exception) { throw PackageException("运行记录不一致：${e.message}") }
        val rs = state.start.body as RunStarted
        if (rs.protocolSha256 != parsed.sha256) throw PackageException("步骤快照与运行记录的哈希不一致")
        if (run.runId != rs.runId || manifest.runId != rs.runId) throw PackageException("run_id 不一致")
        val attachments = entries.filterKeys { it.startsWith("attachments/") }
        state.observations.flatMap { it.body.attachments }.forEach { a ->
            val b = attachments[a.path] ?: throw PackageException("附件缺失：${a.path}")
            if (sha256Hex(b) != a.sha256) throw PackageException("附件哈希不符：${a.path}")
        }
        return PackageContent(manifest, protocolBytes, runBytes, state, attachments)
    }
}

enum class ImportDecision { NEW, DUPLICATE, REVISION }

/** 契约 §5：同 run_id 同内容跳过；同 run_id 不同内容存为新修订，绝不覆盖。 */
object ImportRules {
    fun decide(existingRunShas: Collection<String>, incomingRunSha: String): ImportDecision = when {
        existingRunShas.isEmpty() -> ImportDecision.NEW
        incomingRunSha in existingRunShas -> ImportDecision.DUPLICATE
        else -> ImportDecision.REVISION
    }
}

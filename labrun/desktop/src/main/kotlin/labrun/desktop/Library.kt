package labrun.desktop

import labrun.core.ImportDecision
import labrun.core.ImportRules
import labrun.core.LabPackage
import labrun.core.PackageContent
import labrun.core.PackageException
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 电脑端实验库。每个导入的包原样保存，不解压、不改写：
 *   <root>/<run_id>/<run.json 的 sha256>.labrun.zip
 * 同一运行的多个修订并存（契约 §5：绝不覆盖）。启动时逐个完整校验后载入。
 */
class Library(val root: File) {
    init { root.mkdirs() }

    class Revision(val file: File, val content: PackageContent, val importedAt: Long)
    class Entry(val runId: String, val revisions: List<Revision>) {
        /** 默认展示最新导入的修订。 */
        val latest get() = revisions.maxBy { it.importedAt }
    }
    data class Problem(val file: File, val message: String)

    sealed interface ImportResult {
        data class Imported(val runId: String, val decision: ImportDecision) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    var entries: List<Entry> = emptyList(); private set
    var problems: List<Problem> = emptyList(); private set

    fun reload() {
        val probs = mutableListOf<Problem>()
        entries = root.listFiles { f -> f.isDirectory }.orEmpty().mapNotNull { dir ->
            val revs = dir.listFiles { f -> f.name.endsWith(".labrun.zip") }.orEmpty().mapNotNull { f ->
                try { Revision(f, f.inputStream().buffered().use { LabPackage.read(it) }, f.lastModified()) }
                catch (e: Exception) { probs += Problem(f, e.message ?: e.toString()); null }
            }
            if (revs.isEmpty()) null else Entry(dir.name, revs)
        }.sortedByDescending { it.latest.content.state.start.wallMs }
        problems = probs
    }

    /** 先完整校验，再按规则决定新增/跳过/新修订；校验不通过不写入任何文件。 */
    fun import(file: File): ImportResult {
        val content = try { file.inputStream().buffered().use { LabPackage.read(it) } }
        catch (e: PackageException) { return ImportResult.Failed("${file.name}：${e.message}") }
        catch (e: Exception) { return ImportResult.Failed("${file.name}：不是有效的导出包（${e.message}）") }
        val runId = content.state.runId
        val dir = File(root, runId)
        val existing = dir.listFiles { f -> f.name.endsWith(".labrun.zip") }.orEmpty().map { it.name.removeSuffix(".labrun.zip") }
        val decision = ImportRules.decide(existing, content.runSha256)
        if (decision != ImportDecision.DUPLICATE) {
            dir.mkdirs()
            val target = File(dir, content.runSha256 + ".labrun.zip")
            val tmp = File(dir, target.name + ".tmp")
            Files.copy(file.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        reload()
        return ImportResult.Imported(runId, decision)
    }

    /** 从库中删除整个实验（全部修订）。只删库里的副本，不碰用户当初选择的原始文件。 */
    fun deleteRun(runId: String): Boolean {
        val dir = File(root, runId)
        require(runId.isNotBlank() && dir.canonicalFile.parentFile == root.canonicalFile && dir.isDirectory) { "无效的实验：$runId" }
        val ok = dir.deleteRecursively()
        reload()
        return ok
    }

    /** 只删除一个修订；删到最后一个时连同实验目录一起移除。 */
    fun deleteRevision(rev: Revision): Boolean {
        val dir = rev.file.canonicalFile.parentFile
        require(dir.parentFile == root.canonicalFile && rev.file.name.endsWith(".labrun.zip")) { "无效的修订：${rev.file}" }
        val ok = rev.file.delete()
        if (dir.listFiles().isNullOrEmpty()) dir.delete()
        reload()
        return ok
    }

    companion object {
        fun default(): Library {
            System.getenv("LABRUN_LIBRARY")?.takeIf { it.isNotBlank() }?.let { return Library(File(it)) }
            val base = System.getenv("APPDATA")?.let { File(it) } ?: File(System.getProperty("user.home"), ".config")
            return Library(File(base, "LabRun/library"))
        }
    }
}

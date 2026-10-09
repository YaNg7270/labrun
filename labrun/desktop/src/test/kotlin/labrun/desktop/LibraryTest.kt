package labrun.desktop

import labrun.core.Clock
import labrun.core.Event
import labrun.core.EventSink
import labrun.core.ImportDecision
import labrun.core.LabPackage
import labrun.core.Now
import labrun.core.RunEngine
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LibraryTest {
    private val example = File(System.getProperty("labrun.examples"), "演示实验.protocol.json").readBytes()

    private fun pkg(dir: File, name: String, build: (RunEngine) -> Unit, from: List<Event> = emptyList()): Pair<File, List<Event>> {
        var t = 1_791_440_400_000L
        val clock = Clock { Now(t, 28800, t - 1_000_000_000L, 1).also { t += 1000 } }
        val events = from.toMutableList()
        val sink = EventSink { events += it }
        val eng = if (from.isEmpty()) RunEngine.start(example, clock, sink, "s") else RunEngine.restore(example, from, clock, sink)
        build(eng)
        val f = File(dir, name)
        f.outputStream().use { LabPackage.write(it, example, eng.state, clock.now(), "test") { error("无附件") } }
        return f to events
    }

    @Test
    fun importRules() {
        val tmp = Files.createTempDirectory("labrun").toFile()
        val lib = Library(File(tmp, "lib"))
        val (snapshot, events) = pkg(tmp, "a.zip", { it.confirmStep("prepare", "a1") })
        val (final, _) = pkg(tmp, "b.zip", { it.confirmStep("mix_demo", "a2"); it.end(it.state.openPoints.map { p -> p.key }.toSet(), "e") }, events)

        assertEquals(ImportDecision.NEW, (lib.import(snapshot) as Library.ImportResult.Imported).decision)
        assertEquals(ImportDecision.DUPLICATE, (lib.import(snapshot) as Library.ImportResult.Imported).decision)
        assertEquals(ImportDecision.REVISION, (lib.import(final) as Library.ImportResult.Imported).decision)
        assertEquals(1, lib.entries.size)
        assertEquals(2, lib.entries.single().revisions.size)

        // 损坏的包：拒绝，库不变
        val bad = File(tmp, "bad.zip").apply { writeBytes(snapshot.readBytes().copyOf(200)) }
        assertIs<Library.ImportResult.Failed>(lib.import(bad))
        assertEquals(2, lib.entries.single().revisions.size)

        // 重新打开库，内容一致
        val again = Library(File(tmp, "lib")).apply { reload() }
        assertEquals(2, again.entries.single().revisions.size)
        assertTrue(again.problems.isEmpty())
        tmp.deleteRecursively()
    }
}

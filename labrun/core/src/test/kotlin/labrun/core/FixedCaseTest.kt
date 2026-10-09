package labrun.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 可拨动的测试时钟：墙上时间与单调时钟默认同步前进。 */
class FakeClock(startIso: String) : Clock {
    var wallMs = OffsetDateTime.parse(startIso).toInstant().toEpochMilli()
    var monoMs = 5_000_000L
    var bootCount = 7
    val tz = 8 * 3600
    override fun now() = Now(wallMs, tz, monoMs, bootCount)
    fun at(iso: String) { val w = OffsetDateTime.parse(iso).toInstant().toEpochMilli(); monoMs += w - wallMs; wallMs = w }
    fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}

class MemorySink : EventSink {
    val all = mutableListOf<Event>()
    override fun append(events: List<Event>) { all += events }
}

fun exampleBytes(): ByteArray = File(System.getProperty("labrun.examples"), "演示实验.protocol.json").readBytes()

/** 固定案例（examples/演示实验.protocol.json）与契约 §7 预期时间线的逐行断言。 */
class FixedCaseTest {
    private fun d(h: String) = "2026-10-08T$h+08:00"

    @Test
    fun fixedCase() {
        val clock = FakeClock(d("14:00:00"))
        val sink = MemorySink()
        var n = 0
        val eng = RunEngine.start(exampleBytes(), clock, sink, "a-start", newId = { "id-${++n}" })
        var s = eng.state
        assertEquals(0, s.anchorTRun("run_start"))
        assertNull(s.anchorTRun("mix"))
        assertEquals("未建立", TimeFormat.elapsed(s.relativeTo("mix", 0)))
        assertEquals("prepare", s.currentStep!!.def.id)
        assertTrue(s.points.isEmpty())

        clock.at(d("14:01:00"))
        assertIs<CommandResult.Accepted>(eng.confirmStep("prepare", "a1"))
        assertEquals(60_000, eng.state.steps[0].doneEvent!!.tRunMs)

        clock.at(d("14:03:00"))
        assertIs<CommandResult.Accepted>(eng.confirmStep("mix_demo", "a2"))
        assertEquals(CommandResult.Duplicate, eng.confirmStep("mix_demo", "a2"))           // 同一动作重复提交
        assertIs<CommandResult.Rejected>(eng.confirmStep("mix_demo", "a2-again"))         // 新动作但已不是当前步骤
        s = eng.state
        assertEquals(1, s.events.count { it.body is StepConfirmed && (it.body as StepConfirmed).stepId == "mix_demo" })
        assertEquals(1, s.events.count { (it.body as? AnchorEstablished)?.anchorId == "mix" })
        assertEquals(180_000, s.anchorTRun("mix"))
        assertEquals(listOf(240_000L, 300_000L, 360_000L), s.points.map { it.plannedTRunMs })
        assertEquals(listOf(60_000L, 120_000L, 180_000L), s.points.map { s.relativeTo("mix", it.plannedTRunMs) })
        assertEquals("observe", s.currentStep!!.def.id)

        clock.at(d("14:04:00"))
        assertIs<CommandResult.Accepted>(eng.recordMeasurement("reading_series", 0, mapOf("value" to "20.1"), null, "a3"))

        clock.at(d("14:04:30"))
        assertIs<CommandResult.Accepted>(eng.addObservation("模拟观察：颜色变化", emptyList(), null, null, null, "a4"))
        s = eng.state
        assertEquals(270_000, s.observations.single().body.occurredTRunMs)
        assertEquals(90_000, s.relativeTo("mix", 270_000))
        assertEquals("observe", s.currentStep!!.def.id)                                    // 现象不推进步骤

        clock.at(d("14:05:25"))
        assertIs<CommandResult.Accepted>(eng.recordMeasurement("reading_series", 1, mapOf("value" to "20.3"), clock.ms(d("14:05:20")), "a5"))
        val p2 = eng.state.points[1]
        assertEquals(300_000, p2.plannedTRunMs)
        assertEquals(320_000, p2.measurement!!.body.measuredTRunMs)
        assertEquals(325_000, p2.measurement!!.enteredTRunMs)
        assertEquals(140_000, eng.state.relativeTo("mix", p2.measurement!!.body.measuredTRunMs))
        assertEquals(TimeQuality.USER_ENTERED, p2.measurement!!.body.measuredQuality)
        assertEquals("20.3", p2.measurement!!.body.values.getValue("value").value)
        assertEquals("a.u.", p2.measurement!!.body.values.getValue("value").unit)

        clock.at(d("14:06:10"))
        assertIs<CommandResult.Accepted>(eng.confirmStep("observe", "a6"))
        assertEquals(PointStatus.OPEN, eng.state.points[2].status)                         // 确认步骤不关闭采样点
        assertIs<CommandResult.Accepted>(eng.confirmStep("finish", "a7"))
        assertTrue(!eng.state.isEnded)                                                      // 确认最后一步不自动结束
        assertIs<CommandResult.Rejected>(eng.end(emptySet(), "a8-stale"))                 // 清单不符，拒绝
        assertEquals(setOf("reading_series#2"), eng.state.openPoints.map { it.key }.toSet())
        assertIs<CommandResult.Accepted>(eng.end(setOf("reading_series#2"), "a8"))
        s = eng.state
        assertTrue(s.isEnded)
        assertEquals(370_000, s.ended!!.tRunMs)
        assertEquals(PointStatus.MISSED, s.points[2].status)
        assertEquals("run_ended", s.points[2].missedReason)
        assertNull(s.points[2].measurement)
        assertIs<CommandResult.Rejected>(eng.addObservation("迟到的", emptyList(), null, null, null, "a9"))

        // 重放与持久化一致
        assertEquals(s, RunState.replay(eng.protocol, sink.all))

        // 测量表
        val csv = RunViews(s).measurementsCsv().removePrefix("\uFEFF").trim().lines()
        assertEquals(4, csv.size)
        assertTrue(csv[1].startsWith("reading_series,1,recorded,mix,60,2026-10-08T14:04:00.000+08:00,240,60,2026-10-08T14:04:00.000+08:00,240,60,monotonic,2026-10-08T14:04:00.000+08:00,0,,20.1,20.1"), csv[1])
        assertTrue(csv[2].startsWith("reading_series,2,recorded,mix,120,2026-10-08T14:05:00.000+08:00,300,120,2026-10-08T14:05:20.000+08:00,320,140,user_entered,2026-10-08T14:05:25.000+08:00,20,,20.3"), csv[2])
        assertTrue(csv[3].startsWith("reading_series,3,missed,mix,180,2026-10-08T14:06:00.000+08:00,360,180,,,,,,,run_ended,,"), csv[3])

        // 时间线：按运行时间排序，现象位于两次测量之间
        val tl = RunViews(s).timeline()
        assertEquals(listOf(TimelineKind.RUN_STARTED, TimelineKind.STEP_CONFIRMED, TimelineKind.STEP_CONFIRMED, TimelineKind.ANCHOR,
            TimelineKind.MEASUREMENT, TimelineKind.OBSERVATION, TimelineKind.MEASUREMENT, TimelineKind.STEP_CONFIRMED,
            TimelineKind.STEP_CONFIRMED, TimelineKind.MISSED, TimelineKind.RUN_ENDED), tl.map { it.kind })

        // 锚点建立前的事件不标相对 mix 时间
        val tlCsv = RunViews(s).timelineCsv().removePrefix("﻿").lines()
        assertTrue(tlCsv[2].startsWith("60,") && tlCsv[2].trimEnd().endsWith(",60,"), tlCsv[2])
        assertTrue(tlCsv[3].startsWith("180,") && tlCsv[3].trimEnd().endsWith(",180,0"), tlCsv[3])

        // 导出 → 导入两次
        val zip = ByteArrayOutputStream().also { LabPackage.write(it, eng.protocolBytes, s, clock.now(), "test") { error("无附件") } }.toByteArray()
        val c1 = LabPackage.read(ByteArrayInputStream(zip))
        assertEquals(s, c1.state)
        val library = mutableMapOf<String, MutableList<String>>()
        fun import(c: PackageContent): ImportDecision {
            val existing = library.getOrPut(c.state.runId) { mutableListOf() }
            return ImportRules.decide(existing, c.runSha256).also { if (it != ImportDecision.DUPLICATE) existing += c.runSha256 }
        }
        assertEquals(ImportDecision.NEW, import(c1))
        assertEquals(ImportDecision.DUPLICATE, import(LabPackage.read(ByteArrayInputStream(zip))))
        assertEquals(1, library.size)
        assertEquals(1, library.values.single().size)

        // 报告：HTML 与 Excel 由同一份重算数据生成
        val report = RunReport(c1.state, "test")
        val html = report.html(c1.attachments)
        listOf("模拟观察：颜色变化", "漏测（结束时未测）", "补填，录入 14:05:25", "未建立").forEach { assertTrue(it in html || it == "未建立", it) }
        val xlsx = ByteArrayOutputStream().also { report.writeXlsx(it) }.toByteArray()
        val names = java.util.zip.ZipInputStream(ByteArrayInputStream(xlsx)).use { z -> generateSequence { z.nextEntry?.name }.toList() }
        assertTrue("xl/worksheets/sheet6.xml" in names)
        File("build/reports/fixed-case").apply { mkdirs() }.let { d ->
            File(d, "report.html").writeText(html); File(d, "report.xlsx").writeBytes(xlsx); File(d, "run.labrun.zip").writeBytes(zip)
        }
    }

    @Test
    fun restoreAfterProcessDeathAndReboot() {
        val clock = FakeClock(d("14:00:00"))
        val sink = MemorySink()
        val eng = RunEngine.start(exampleBytes(), clock, sink, "s")
        clock.at(d("14:01:00")); eng.confirmStep("prepare", "a1")
        // 进程被杀：从持久化事件恢复
        val e2 = RunEngine.restore(eng.protocolBytes, sink.all.toList(), clock, sink)
        assertEquals(eng.state, e2.state)
        // 手机重启：单调时钟归零、开机序号 +1
        clock.wallMs = clock.ms(d("14:03:00")); clock.monoMs = 30_000; clock.bootCount = 8
        val e3 = RunEngine.restore(eng.protocolBytes, sink.all.toList(), clock, sink)
        e3.confirmStep("mix_demo", "a2")
        val mix = e3.state.anchors.getValue("mix").established!!
        assertEquals(180_000, mix.atTRunMs)
        assertEquals(TimeQuality.WALL_ESTIMATE, mix.atQuality)
        assertTrue(e3.state.timeUncertain)
    }

    @Test
    fun clockChangeIsRecorded() {
        val clock = FakeClock(d("14:00:00"))
        val eng = RunEngine.start(exampleBytes(), clock, MemorySink(), "s")
        clock.monoMs += 60_000; clock.wallMs += 60_000 + 3_600_000   // 用户把系统时间拨快 1 小时
        eng.confirmStep("prepare", "a1")
        val s = eng.state
        assertEquals(60_000, s.steps[0].doneEvent!!.tRunMs)          // 运行时间仍以单调时钟为准
        assertEquals(3_600_000, (s.clockIssues.single().body as ClockDiscontinuity).skewMs)
        assertTrue(s.timeUncertain)
    }

    @Test
    fun manualAnchorBackdatedAndValidation() {
        val text = exampleBytes().decodeToString().replace(
            """{ "id": "mix", "label": "模拟混合完成", "create_on": { "event": "step_confirmed", "step_id": "mix_demo" } }""",
            """{ "id": "mix", "label": "模拟混合完成", "create_on": { "event": "manual" } }""")
        val clock = FakeClock(d("14:00:00"))
        val eng = RunEngine.start(text.toByteArray(), clock, MemorySink(), "s")
        clock.at(d("14:03:00"))
        assertIs<CommandResult.Rejected>(eng.recordMeasurement("reading_series", 0, mapOf("value" to "1"), null, "m0"))  // 锚点未建立
        assertIs<CommandResult.Rejected>(eng.establishManualAnchor("mix", clock.ms(d("13:59:00")), "x"))               // 早于开始
        assertIs<CommandResult.Accepted>(eng.establishManualAnchor("mix", clock.ms(d("14:02:50")), "m1"))
        assertIs<CommandResult.Rejected>(eng.establishManualAnchor("mix", null, "m2"))                                  // 只能建立一次
        assertEquals(170_000, eng.state.anchorTRun("mix"))
        assertEquals(AnchorSource.MANUAL_BACKDATED, eng.state.anchors.getValue("mix").established!!.source)
        assertIs<CommandResult.Rejected>(eng.recordMeasurement("reading_series", 0, mapOf("value" to "abc"), null, "m3"))
        assertIs<CommandResult.Accepted>(eng.recordMeasurement("reading_series", 0, mapOf("value" to " 20，5 "), null, "m4"))
        assertEquals("20.5", eng.state.points[0].measurement!!.body.values.getValue("value").value)
        assertIs<CommandResult.Rejected>(eng.recordMeasurement("reading_series", 0, mapOf("value" to "1"), null, "m5"))   // 已录入
    }
}

class ProtocolParserTest {
    @Test
    fun exampleIsValid() {
        val r = ProtocolParser.parse(exampleBytes())
        assertTrue(r.ok, r.issues.joinToString())
        assertTrue(r.issues.isEmpty(), r.issues.joinToString())
    }

    @Test
    fun fullFeatureExampleIsValid() {
        val r = ProtocolParser.parse(File(System.getProperty("labrun.examples"), "全功能测试.protocol.json").readBytes())
        assertTrue(r.ok && r.issues.isEmpty(), r.issues.joinToString())
        val p = r.protocol!!
        assertEquals(AnchorTrigger.Manual, p.anchor("ppt").createOn)
        assertEquals(Offset("heat", 30), p.step("hold").notBefore)
        assertEquals(listOf("手动标记", "结束时未测", "测量已作废", "其他"), listOf("manual", "run_ended", "voided", "其他").map(::missedReasonText))
    }

    private fun issues(mutate: (String) -> String) = ProtocolParser.parse(mutate(exampleBytes().decodeToString()).toByteArray()).issues.map { it.code }.toSet()

    @Test
    fun semanticErrors() {
        assertEquals(setOf("E_FORMAT"), issues { it.replace("\"format_version\": \"0.1\"", "\"format_version\": \"9.9\"") })
        assertTrue("E_REF" in issues { it.replace("\"anchor_id\": \"mix\"", "\"anchor_id\": \"nope\"") })
        assertTrue("E_OFFSETS" in issues { it.replace("[60, 120, 180]", "[60, 60, 180]") })
        assertTrue("E_REF" in issues { it.replace("\"step_id\": \"mix_demo\"", "\"step_id\": \"ghost\"") })
        assertTrue("E_DUP_ID" in issues { it.replace("\"id\": \"observe\"", "\"id\": \"prepare\"") })
        assertTrue("E_UNKNOWN_KEY" in issues { it.replace("\"synthetic\": true", "\"synthetic\": true, \"script\": \"rm -rf\"") })
        assertTrue("E_JSON" in issues { it.dropLast(3) })
        assertTrue("E_ID" in issues { it.replace("\"id\": \"prepare\"", "\"id\": \"Prepare Step\"") })
        assertTrue("E_RUN_ANCHOR" in issues { it.replace("\"run_start_anchor_id\": \"run_start\"", "\"run_start_anchor_id\": \"mix\"") })
    }

    @Test
    fun packageRejectsPathTraversal() {
        val bad = ByteArrayOutputStream().also { o ->
            java.util.zip.ZipOutputStream(o).use { z -> z.putNextEntry(java.util.zip.ZipEntry("../evil.txt")); z.write(1); z.closeEntry() }
        }.toByteArray()
        val e = assertFailsWith<PackageException> { LabPackage.read(ByteArrayInputStream(bad)) }
        assertTrue("不允许" in e.message!!)
        assertTrue(!PackagePaths.isAllowed("attachments/../x.jpg"))
        assertTrue(!PackagePaths.isAllowed("C:/x/manifest.json"))
        assertTrue(!PackagePaths.isAllowed("attachments\\a.jpg"))
    }
}

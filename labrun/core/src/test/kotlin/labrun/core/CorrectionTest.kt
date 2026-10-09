package labrun.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 更正与撤销：只追加，原始记录永远保留（契约 §1 S15–S18）。 */
class CorrectionTest {
    private fun d(h: String) = "2026-10-08T$h+08:00"
    private val ok = CommandResult.Accepted::class

    private fun started(): Triple<FakeClock, MemorySink, RunEngine> {
        val clock = FakeClock(d("14:00:00")); val sink = MemorySink()
        val eng = RunEngine.start(exampleBytes(), clock, sink, "s")
        clock.at(d("14:01:00")); eng.confirmStep("prepare", "a1")
        clock.at(d("14:03:00")); eng.confirmStep("mix_demo", "a2")
        return Triple(clock, sink, eng)
    }

    @Test
    fun correctMeasurementKeepsOriginal() {
        val (clock, sink, eng) = started()
        clock.at(d("14:04:00")); eng.recordMeasurement("reading_series", 0, mapOf("value" to "21.0"), null, "m1")
        val id = eng.state.points[0].measurement!!.id
        clock.at(d("14:04:20"))
        assertIs<CommandResult.Rejected>(eng.correctMeasurement(id, mapOf("value" to "21.0"), null, null, "c0"))   // 没有改动
        assertIs<CommandResult.Accepted>(eng.correctMeasurement(id, mapOf("value" to "20.1"), clock.ms(d("14:03:58")), "看错小数点", "c1"))
        val m = eng.state.points[0].measurement!!
        assertEquals("20.1", m.values.getValue("value").value)
        assertEquals("21.0", m.body.values.getValue("value").value)                 // 原值保留
        assertEquals(238_000, m.measuredTRunMs)
        assertEquals(240_000, m.enteredTRunMs)                                       // 录入时刻不变
        assertTrue(m.isCorrected)
        assertEquals(PointStatus.RECORDED, eng.state.points[0].status)

        val csv = RunViews(eng.state).measurementsCsv().removePrefix("﻿").lines()
        assertTrue(csv[1].contains(",20.1,20.1,1,模拟读数 21.0 a.u.,"), csv[1])
        val tl = RunViews(eng.state).timeline()
        assertEquals("测量：模拟读数序列 第 1 次（已更正）", tl.first { it.kind == TimelineKind.MEASUREMENT }.title)
        assertTrue(tl.single { it.kind == TimelineKind.CORRECTION }.detail!!.contains("模拟读数 21.0 a.u. → 模拟读数 20.1 a.u."))
        assertEquals(eng.state, RunState.replay(eng.protocol, sink.all))
    }

    @Test
    fun voidDuringRunReopensPointAndAfterEndMarksMissed() {
        val (clock, _, eng) = started()
        clock.at(d("14:04:00")); eng.recordMeasurement("reading_series", 0, mapOf("value" to "1"), null, "m1")
        val wrong = eng.state.points[0].measurement!!.id
        assertIs<CommandResult.Rejected>(eng.voidMeasurement(wrong, " ", "v0"))                                    // 必须有理由
        assertIs<CommandResult.Accepted>(eng.voidMeasurement(wrong, "录到了错误的点", "v1"))
        assertEquals(PointStatus.OPEN, eng.state.points[0].status)
        assertEquals(1, eng.state.points[0].voided.size)
        assertIs<CommandResult.Accepted>(eng.recordMeasurement("reading_series", 0, mapOf("value" to "20.1"), null, "m2"))
        assertEquals("20.1", eng.state.points[0].measurement!!.values.getValue("value").value)

        clock.at(d("14:05:00")); eng.recordMeasurement("reading_series", 1, mapOf("value" to "20.3"), null, "m3")
        clock.at(d("14:06:30")); eng.end(eng.state.openPoints.map { it.key }.toSet(), "end")
        val p2 = eng.state.points[1].measurement!!.id
        assertIs<CommandResult.Accepted>(eng.voidMeasurement(p2, "仪器未校准", "v2"))                            // 结束后仍可作废
        assertEquals(PointStatus.MISSED, eng.state.points[1].status)
        assertEquals("voided", eng.state.points[1].missedReason)
        assertNull(eng.state.points[1].measurement)
    }

    @Test
    fun revertStepRemovesUnusedAnchorAndReconfirm() {
        val (clock, sink, eng) = started()
        assertEquals(180_000, eng.state.anchorTRun("mix"))
        assertIs<CommandResult.Rejected>(eng.revertStep("prepare", "x", "r0"))                                     // 不是最近一步
        clock.at(d("14:03:10"))
        assertIs<CommandResult.Accepted>(eng.revertStep("mix_demo", "混合还没做完就点了", "r1"))
        assertEquals("mix_demo", eng.state.currentStep!!.def.id)
        assertNull(eng.state.anchorTRun("mix"))
        assertTrue(eng.state.points.isEmpty())
        clock.at(d("14:04:00"))
        assertIs<CommandResult.Accepted>(eng.confirmStep("mix_demo", "a3"))
        assertEquals(240_000, eng.state.anchorTRun("mix"))
        assertEquals(listOf(300_000L, 360_000L, 420_000L), eng.state.points.map { it.plannedTRunMs })
        assertEquals(1, eng.state.steps[1].reverts.size)
        assertEquals(eng.state, RunState.replay(eng.protocol, sink.all))

        // 起点下已有记录 → 不能随步骤撤销
        clock.at(d("14:05:00")); eng.recordMeasurement("reading_series", 0, mapOf("value" to "1"), null, "m1")
        assertIs<CommandResult.Rejected>(eng.revertStep("mix_demo", "x", "r2"))
    }

    @Test
    fun correctAnchorShiftsPlan() {
        val (clock, _, eng) = started()
        clock.at(d("14:04:00")); eng.recordMeasurement("reading_series", 0, mapOf("value" to "20.1"), null, "m1")
        assertIs<CommandResult.Rejected>(eng.correctAnchor("run_start", clock.ms(d("14:00:05")), "x", "c0"))
        assertIs<CommandResult.Rejected>(eng.correctAnchor("mix", clock.ms(d("14:04:05")), "x", "c1"))           // 晚于已有测量
        assertIs<CommandResult.Rejected>(eng.correctAnchor("mix", clock.ms(d("14:02:50")), "", "c2"))             // 缺理由
        assertIs<CommandResult.Accepted>(eng.correctAnchor("mix", clock.ms(d("14:02:50")), "实际 14:02:50 混合完成", "c3"))
        val s = eng.state
        assertEquals(170_000, s.anchorTRun("mix"))
        assertEquals(listOf(230_000L, 290_000L, 350_000L), s.points.map { it.plannedTRunMs })
        assertEquals(70_000, s.relativeTo("mix", s.points[0].measurement!!.measuredTRunMs))
        assertEquals(TimeQuality.USER_ENTERED, s.anchors.getValue("mix").atQuality)
        val row = RunReport(s, "t").anchors().rows.single { it[1] == "mix" }
        assertEquals(listOf("已建立，已更正", "2026-10-08T14:02:50.000+08:00", "+02:50（补填）", "step_confirmed；原 +03:00"), row.drop(2))
    }

    @Test
    fun extraMeasurements() {
        val (clock, sink, eng) = started()
        clock.at(d("14:03:30"))
        assertIs<CommandResult.Accepted>(eng.recordMeasurement("reading_series", null, mapOf("value" to "19.8"), null, "x1"))
        assertIs<CommandResult.Accepted>(eng.recordMeasurement("reading_series", null, mapOf("value" to "19.9"), null, "x2"))
        val s = eng.state
        assertEquals(2, s.extraMeasurements.size)
        assertTrue(s.points.all { it.status == PointStatus.OPEN })                                     // 不占用计划点
        assertEquals(30_000, s.relativeTo("mix", s.extraMeasurements[0].measuredTRunMs))
        assertIs<CommandResult.Accepted>(eng.voidMeasurement(s.extraMeasurements[1].id, "重复", "x3"))
        assertEquals(1, eng.state.extraMeasurements.size)
        val csv = RunViews(eng.state).measurementsCsv().removePrefix("﻿").trim().lines()
        assertTrue(csv.any { it.startsWith("reading_series,extra,recorded,mix,") && ",19.8,19.8," in it }, csv.joinToString(" | "))
        val html = RunReport(eng.state, "t").html(emptyMap())
        assertTrue("计划外测量" in html && "19.8" in html)
        // 作废的值不进测量表（只在时间线“已作废”与更正记录里出现）
        val measSection = html.substringAfter("<h2>测量</h2>").substringBefore("<h2>现象</h2>")
        assertTrue("19.8" in measSection && "19.9" !in measSection)
        assertTrue("（已作废）测量" in html)
        assertEquals(eng.state, RunState.replay(eng.protocol, sink.all))
    }

    @Test
    fun observationsAndAfterEndRules() {
        val (clock, sink, eng) = started()
        clock.at(d("14:04:30")); eng.addObservation("颜色变黄", emptyList(), null, null, null, "o1")
        val oid = eng.state.observations.single().id
        clock.at(d("14:05:00")); eng.markMissed("reading_series", 0, "忘了", "mm")
        assertIs<CommandResult.Accepted>(eng.revokeMissed("reading_series", 0, "其实测了", "rv"))
        assertEquals(PointStatus.OPEN, eng.state.points[0].status)
        eng.end(eng.state.openPoints.map { it.key }.toSet(), "end")

        assertIs<CommandResult.Accepted>(eng.correctObservation(oid, "颜色变红", null, "写错了", "oc"))
        assertEquals("颜色变红", eng.state.observations.single().text)
        assertEquals("颜色变黄", eng.state.observations.single().body.text)
        assertIs<CommandResult.Accepted>(eng.voidObservation(oid, "重复记录", "ov"))
        assertTrue(eng.state.observations.isEmpty())
        assertEquals(1, eng.state.observationsAll.size)
        assertIs<CommandResult.Rejected>(eng.revertStep("mix_demo", "x", "rr"))                                     // 结束后不能撤销步骤
        assertIs<CommandResult.Rejected>(eng.addObservation("新的", emptyList(), null, null, null, "o2"))           // 结束后不能新增

        // 结束后的更正随导出包往返
        val zip = ByteArrayOutputStream().also { LabPackage.write(it, eng.protocolBytes, eng.state, clock.now(), "t") { error("") } }.toByteArray()
        val back = LabPackage.read(ByteArrayInputStream(zip)).state
        assertEquals(eng.state, back)
        assertEquals(RunState.replay(eng.protocol, sink.all), back)
        assertTrue(RunReport(back, "t").html(emptyMap()).contains("更正记录"))
    }
}

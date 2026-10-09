package labrun.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** 运行中临时新建的计划外测量表：手动记录 / 定时记录，制成时间表。 */
class AdhocTableTest {
    private fun d(h: String) = "2026-10-08T$h+08:00"
    private val dec = RunEngine.NewField("pH", FieldType.DECIMAL, null)
    private val note = RunEngine.NewField("备注", FieldType.TEXT, null)

    private fun started(): Triple<FakeClock, MemorySink, RunEngine> {
        val clock = FakeClock(d("14:00:00")); val sink = MemorySink()
        var n = 0
        val eng = RunEngine.start(exampleBytes(), clock, sink, "s", newId = { "id%04d".format(++n) })
        return Triple(clock, sink, eng)
    }

    @Test
    fun manualTable() {
        val (clock, sink, eng) = started()
        clock.at(d("14:02:00"))
        assertIs<CommandResult.Accepted>(eng.createAdhocTable("pH 观察", listOf(dec, note), null, null, "t1"))
        val t = eng.state.adhocTables.single()
        assertEquals(listOf("f1", "f2"), t.plan.fields.map { it.id })
        assertTrue(eng.state.points.none { it.plan.id == t.id })                                    // 手动表没有计划点
        assertEquals(120_000, eng.state.anchorTRun(t.anchor.id))
        clock.at(d("14:02:40")); eng.recordMeasurement(t.id, null, mapOf("f1" to "7.1", "f2" to "清"), null, "r1")
        clock.at(d("14:03:30")); eng.recordMeasurement(t.id, null, mapOf("f1" to "6.8", "f2" to "微浑"), clock.ms(d("14:03:10")), "r2")
        val tt = RunViews(eng.state).timeTable(t.plan)
        assertEquals(listOf("序号", "类型", "计划（相对起点）", "测量时刻", "全局_s", "相对起点_s", "录入时刻", "pH", "备注", "状态"), tt.header)
        assertEquals(listOf("1", "记录", "", "2026-10-08T14:02:40.000+08:00", "160", "40", "2026-10-08T14:02:40.000+08:00", "7.1", "清", "已录入"), tt.rows[0])
        assertEquals(listOf("2", "70", "6.8", "微浑"), listOf(tt.rows[1][0], tt.rows[1][5], tt.rows[1][7], tt.rows[1][8]))
        assertEquals(eng.state, RunState.replay(eng.protocol, sink.all))
    }

    @Test
    fun timedTableStopAndEnd() {
        val (clock, _, eng) = started()
        clock.at(d("14:05:00"))
        assertIs<CommandResult.Accepted>(eng.createAdhocTable("温度巡检", listOf(dec), 30, 4, "t1"))
        val id = eng.state.adhocTables.single().id
        assertEquals(listOf(300_000L, 330_000L, 360_000L, 390_000L), eng.state.points.filter { it.plan.id == id }.map { it.plannedTRunMs })
        clock.at(d("14:05:02")); eng.recordMeasurement(id, 0, mapOf("f1" to "25.0"), null, "r1")
        clock.at(d("14:05:45"))
        assertIs<CommandResult.Accepted>(eng.stopAdhocTable(id, "stop"))
        val pts = eng.state.points.filter { it.plan.id == id }
        assertEquals(listOf(0, 1), pts.map { it.index })                                              // 未到点的两个取消，已到点的保留
        assertEquals(PointStatus.OPEN, pts[1].status)
        assertIs<CommandResult.Rejected>(eng.stopAdhocTable(id, "stop2"))
        clock.at(d("14:06:00"))
        eng.end(eng.state.openPoints.map { it.key }.toSet(), "end")
        assertEquals(listOf("1", "2"), RunViews(eng.state).timeTable(eng.state.plan(id)).rows.map { it[0] })
        val after = eng.state.points.filter { it.plan.id == id }
        assertEquals(listOf(PointStatus.RECORDED, PointStatus.MISSED), after.map { it.status })

        val tl = RunViews(eng.state).timeline()
        assertTrue(tl.any { it.kind == TimelineKind.TABLE && it.title == "新建计划外测量表：温度巡检" && it.detail!!.startsWith("定时：每 00:30，共 4 次") })
        assertTrue(tl.any { it.title == "停止定时测量：温度巡检" })

        // 报告与导出：每张表一个“时间表”工作表；导出包往返一致
        val report = RunReport(eng.state, "t")
        val xlsx = ByteArrayOutputStream().also { report.writeXlsx(it) }.toByteArray()
        val wb = ZipInputStream(ByteArrayInputStream(xlsx)).use { z -> generateSequence { z.nextEntry }.associate { it.name to z.readBytes().decodeToString() } }
        assertTrue(wb.getValue("xl/workbook.xml").contains("表·温度巡检") && wb.getValue("xl/workbook.xml").contains("表·模拟读数序列"))
        assertTrue(report.html(emptyMap()).contains("计划外测量表 · 建于 14:05:00"))
        val zip = ByteArrayOutputStream().also { LabPackage.write(it, eng.protocolBytes, eng.state, clock.now(), "t") { error("") } }.toByteArray()
        assertEquals(eng.state, LabPackage.read(ByteArrayInputStream(zip)).state)
    }

    @Test
    fun validationAndCorrections() {
        val (clock, _, eng) = started()
        clock.at(d("14:01:00"))
        assertIs<CommandResult.Rejected>(eng.createAdhocTable(" ", listOf(dec), null, null, "a"))
        assertIs<CommandResult.Rejected>(eng.createAdhocTable("x", emptyList(), null, null, "b"))
        assertIs<CommandResult.Rejected>(eng.createAdhocTable("x", listOf(dec, dec), null, null, "c"))          // 字段名重复
        assertIs<CommandResult.Rejected>(eng.createAdhocTable("x", listOf(dec), 2, 3, "d"))                     // 间隔太短
        assertIs<CommandResult.Rejected>(eng.createAdhocTable("x", listOf(dec), 30, null, "e"))                 // 缺次数
        assertIs<CommandResult.Rejected>(eng.createAdhocTable("模拟读数序列", listOf(dec), null, null, "f"))     // 与步骤文件里的计划重名
        assertIs<CommandResult.Accepted>(eng.createAdhocTable("x", listOf(dec), null, null, "g"))
        val id = eng.state.adhocTables.single().id
        assertIs<CommandResult.Rejected>(eng.recordMeasurement(id, null, mapOf("f1" to "abc"), null, "h"))
        clock.at(d("14:01:10")); eng.recordMeasurement(id, null, mapOf("f1" to "1.0"), null, "i")
        val m = eng.state.extraMeasurements.single()
        assertIs<CommandResult.Accepted>(eng.correctMeasurement(m.id, mapOf("f1" to "1.5"), null, "看错", "j"))
        assertEquals("已录入（已更正）", RunViews(eng.state).timeTable(eng.state.plan(id)).rows.single().last())
    }
}

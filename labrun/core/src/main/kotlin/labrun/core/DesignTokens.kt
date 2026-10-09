package labrun.core

/** docs/03_设计规范.md §1 的颜色令牌（ARGB）。安卓与电脑端主题都从这里取值，保证同源。 */
object DesignTokens {
    class Palette(
        val primary: Long, val onPrimary: Long, val bg: Long, val surface: Long, val surfaceAlt: Long,
        val text: Long, val muted: Long, val outline: Long,
        val due: Long, val overdue: Long, val done: Long, val missed: Long, val uncertain: Long,
    )

    val light = Palette(
        0xFF0F6E6E, 0xFFFFFFFF, 0xFFF6F7F6, 0xFFFFFFFF, 0xFFEDF1F0,
        0xFF1A1F1E, 0xFF5B6563, 0xFFD3DAD8,
        0xFF9A5B00, 0xFFB3261E, 0xFF2E6B30, 0xFF7A4A2A, 0xFF5B45A8,
    )
    val dark = Palette(
        0xFF5DC8C3, 0xFF00201F, 0xFF101413, 0xFF1A201F, 0xFF232A29,
        0xFFE5EBE9, 0xFF9AA6A3, 0xFF2E3735,
        0xFFF2B45A, 0xFFF2B8B5, 0xFF8FD18F, 0xFFD9A88A, 0xFFC3B3FF,
    )

    /** 采样点“到期”窗口：计划时刻后 60 秒内为到期，之后为逾期（规范 §5）。 */
    const val DUE_WINDOW_MS = 60_000L
    /** 新步骤出现后多久才允许确认（规范 §6）。 */
    const val CONFIRM_ARM_MS = 1_000L
}

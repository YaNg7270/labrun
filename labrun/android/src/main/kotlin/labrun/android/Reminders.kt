package labrun.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import labrun.core.PointStatus
import labrun.core.ReminderKind
import labrun.core.RunState
import labrun.core.StepStatus
import labrun.core.TimeFormat

/** 一个需要到点提醒的目标：采样点或带 not_before 的步骤。 */
data class ReminderTarget(val kind: ReminderKind, val refId: String, val pointIndex: Int?, val plannedTRunMs: Long, val text: String) {
    val actionId get() = "reminder:${kind.name.lowercase()}:$refId:${pointIndex ?: "-"}"
}

object Reminders {
    const val CHANNEL = "sampling"

    fun targets(s: RunState): List<ReminderTarget> {
        if (s.isEnded) return emptyList()
        val fired = s.reminders.map { (r, _) -> Triple(r.kind, r.refId, r.pointIndex) }.toSet()
        val points = s.points.filter { it.status == PointStatus.OPEN }.map { p ->
            ReminderTarget(ReminderKind.SAMPLE, p.plan.id, p.index, p.plannedTRunMs, "${p.plan.label} 第 ${p.index + 1} 次采样（${s.anchorDef(p.plan.anchorId).label} ${TimeFormat.elapsed(p.offsetS * 1000)}）")
        }
        val steps = s.steps.filter { it.status == StepStatus.CURRENT || it.status == StepStatus.PENDING }.mapNotNull { st ->
            s.stepNotBeforeTRun(st.def)?.let { ReminderTarget(ReminderKind.STEP, st.def.id, null, it, "可以开始：${st.def.title}") }
        }
        return (points + steps).filter { Triple(it.kind, it.refId, it.pointIndex) !in fired }.sortedBy { it.plannedTRunMs }
    }

    fun exactAllowed(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun notificationsAllowed(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

    private fun alarmIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** 当前已挂闹钟对应的目标；目标不变就不重挂。 */
    private var scheduled: String? = null

    /** 闹钟触发后调用：下一次 reschedule 必须重新挂。 */
    fun markFired() { scheduled = null }

    /**
     * 只挂一个闹钟：下一个未提醒目标。触发后由接收器记录、通知、再挂下一个。
     * 目标没变时不重挂——Android 会把 5 秒内的闹钟推迟到 5 秒后，临近到点时的每次操作若都重挂，提醒会被一再推迟
     * （2026-10-08 实测：到点前几秒反复操作，提醒晚了 5.5 秒）。
     */
    fun reschedule(ctx: Context, repo: Repository) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = alarmIntent(ctx)
        val eng = repo.engine
        val next = eng?.let { targets(it.state).firstOrNull() }
        if (eng == null || next == null) { am.cancel(pi); scheduled = null; return }
        val key = "${eng.state.runId}|${next.actionId}|${next.plannedTRunMs}"
        if (key == scheduled) return
        scheduled = key
        val at = eng.wallFor(next.plannedTRunMs)
        if (exactAllowed(ctx)) {
            val show = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) // 不精确；界面会提示
        }
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "采样与步骤提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "到点提醒人工测量或开始下一步；不会自动记录或确认"
            enableVibration(true)
        })
    }

    fun notify(ctx: Context, due: List<ReminderTarget>) {
        if (due.isEmpty() || !notificationsAllowed(ctx)) return
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val title = if (due.size == 1) due[0].text else "${due.size} 项到点"
        val body = due.joinToString("；") { it.text } + if (due.any { it.kind == ReminderKind.SAMPLE }) "。请测量后在 App 中录入。" else "。完成后在 App 中确认。"
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(due.first().actionId.hashCode(), n) } catch (_: SecurityException) { }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LabApp.repo(context).onAlarm()
    }
}

/** 重启、改时间、改时区、升级后闹钟会丢：统一重挂。 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LabApp.repo(context).onAlarm()
    }
}

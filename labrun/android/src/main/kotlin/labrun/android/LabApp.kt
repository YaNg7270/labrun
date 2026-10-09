package labrun.android

import android.app.Application
import android.content.Context

class LabApp : Application() {
    val repo by lazy { Repository(this) }

    override fun onCreate() {
        super.onCreate()
        Reminders.ensureChannel(this)
        Reminders.reschedule(this, repo)
    }

    companion object {
        fun repo(ctx: Context) = (ctx.applicationContext as LabApp).repo
    }
}

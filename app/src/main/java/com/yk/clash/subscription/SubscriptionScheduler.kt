package com.yk.clash.subscription

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.yk.clash.ProfileStore
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object SubscriptionScheduler {
    private const val JOB_ID = 12081
    fun reconcile(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val enabled = ProfileStore(context).list().any { it.url.isNotBlank() && it.autoUpdate }
        if (!enabled) { scheduler.cancel(JOB_ID); return }
        if (scheduler.getPendingJob(JOB_ID) != null) return
        scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, SubscriptionJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15 * 60_000L).setPersisted(true).build())
    }
}

class SubscriptionJobService : JobService() {
    private var cancelled = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()
    override fun onStartJob(params: JobParameters): Boolean {
        val stop = AtomicBoolean(false); cancelled = stop
        worker.execute {
            runCatching { SubscriptionRepository.updateAll(this, dueOnly = true, cancelled = { stop.get() }) }
            if (!stop.get()) jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { cancelled.set(true); return true }
    override fun onDestroy() { cancelled.set(true); worker.shutdown(); super.onDestroy() }
}

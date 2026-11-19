package com.mycalender.sync

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.mycalender.MyCalenderApp
import com.mycalender.tasks.Changes
import com.mycalender.tasks.actionError
import com.mycalender.widget.ScheduleWidget
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class RefreshJobService : JobService() {
    private val running = ConcurrentHashMap.newKeySet<JobParameters>()

    override fun onStartJob(params: JobParameters): Boolean {
        running.add(params)
        val app = application as MyCalenderApp
        app.operations.execute {
            try {
                if (params.jobId == COMPLETE_JOB) {
                    while (running.contains(params)) {
                        val work = params.dequeueWork() ?: break
                        try {
                            val source = work.intent.getStringExtra(SOURCE)
                            val taskId = work.intent.getStringExtra(TASK)
                            if (source != null && taskId != null && source == app.connection.sourceId()) {
                                change(app, source) {
                                    if (app.cache(source).snapshot().tasks.any { it.id == taskId }) {
                                        app.repository(source).update(taskId, Changes(completed = true))
                                    }
                                }
                            }
                        } finally {
                            if (running.contains(params)) params.completeWork(work)
                        }
                    }
                } else if (running.contains(params)) {
                    app.connection.sourceId()?.let { source -> change(app, source) { app.repository(source).refresh() } }
                }
            } catch (_: SecurityException) {
                // The scheduler can withdraw a job while a work item is being acknowledged.
            } catch (_: IllegalArgumentException) {
                // The scheduler no longer owns this work item.
            } finally {
                if (running.remove(params)) {
                    if (params.jobId != COMPLETE_JOB) jobFinished(params, false)
                    ScheduleWidget.render(app)
                }
            }
        }
        return true
    }

    private fun change(app: MyCalenderApp, source: String, block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            app.cache(source).noteError(actionError(error))
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params)
        return true
    }

    companion object {
        private const val PERIODIC_JOB = 101
        private const val REFRESH_JOB = 102
        private const val COMPLETE_JOB = 103
        private const val SOURCE = "source"
        private const val TASK = "task"

        fun ensurePeriodic(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(PERIODIC_JOB) != null) return
            val schedule = job(context, PERIODIC_JOB).setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(TimeUnit.MINUTES.toMillis(30))
                .build()
            scheduler.schedule(schedule)
        }

        fun refresh(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(REFRESH_JOB) != null) return true
            return scheduler.schedule(job(context, REFRESH_JOB).setOverrideDeadline(0).build()) ==
                JobScheduler.RESULT_SUCCESS
        }

        fun complete(context: Context, source: String, taskId: String): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            val work = JobWorkItem(Intent().putExtra(SOURCE, source).putExtra(TASK, taskId))
            return scheduler.enqueue(job(context, COMPLETE_JOB).setOverrideDeadline(0).build(), work) ==
                JobScheduler.RESULT_SUCCESS
        }

        private fun job(context: Context, id: Int) =
            JobInfo.Builder(id, ComponentName(context, RefreshJobService::class.java))
    }
}

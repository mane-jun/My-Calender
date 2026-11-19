package com.mycalender.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.Toast
import com.mycalender.MyCalenderApp
import com.mycalender.R
import com.mycalender.editor.TaskEditorActivity
import com.mycalender.settings.ConnectionActivity
import com.mycalender.sync.RefreshJobService
import com.mycalender.tasks.DueDate
import com.mycalender.tasks.Snapshot
import com.mycalender.tasks.Task
import com.mycalender.tasks.groupTasks
import com.mycalender.tasks.seoulZone
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class ScheduleWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, ids)
        if ((context.applicationContext as MyCalenderApp).connection.sourceId() != null) {
            RefreshJobService.ensurePeriodic(context)
            RefreshJobService.refresh(context)
        }
    }

    companion object {
        fun render(context: Context, widgets: IntArray? = null) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = widgets ?: manager.getAppWidgetIds(ComponentName(context, ScheduleWidget::class.java))
            if (ids.isEmpty()) return

            val app = context.applicationContext as MyCalenderApp
            val source = app.connection.sourceId()
            val snapshot = app.snapshot()
            ids.forEach { id ->
                val view = RemoteViews(context.packageName, R.layout.schedule_widget)
                val configured = source != null
                view.setTextViewText(R.id.widget_status, if (configured) summary(snapshot) else "노션을 연결해 주세요.")
                view.setTextViewText(R.id.widget_empty, if (configured) "표시할 일정이 없어요." else "설정을 열어 연결할 수 있어요.")
                view.setViewVisibility(R.id.widget_add, if (configured) View.VISIBLE else View.GONE)
                view.setViewVisibility(R.id.widget_refresh, if (configured) View.VISIBLE else View.GONE)
                view.setOnClickPendingIntent(R.id.widget_title, activity(context, 1, Intent(context, ConnectionActivity::class.java)))
                view.setOnClickPendingIntent(R.id.widget_status, activity(context, 1, Intent(context, ConnectionActivity::class.java)))
                view.setOnClickPendingIntent(R.id.widget_add, activity(context, 2, TaskEditorActivity.intent(context, source)))
                view.setOnClickPendingIntent(R.id.widget_refresh, activity(context, 3, WidgetActionActivity.intent(context, "refresh")))

                val adapter = Intent(context, ScheduleWidgetRows::class.java)
                    .setData(Uri.parse("my-calender://widget/$id"))
                view.setRemoteAdapter(R.id.widget_list, adapter)
                view.setEmptyView(R.id.widget_list, R.id.widget_empty)

                val mutable = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                val rowIntent = Intent(context, WidgetActionActivity::class.java)
                    .setData(Uri.parse("my-calender://row/$id"))
                val template = PendingIntent.getActivity(context, id, rowIntent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
                view.setPendingIntentTemplate(R.id.widget_list, template)
                manager.updateAppWidget(id, view)
                manager.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
            }
        }

        private fun activity(context: Context, request: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun summary(snapshot: Snapshot): String {
            val stamp = snapshot.updatedAt?.atZone(seoulZone)?.format(DateTimeFormatter.ofPattern("M/d HH:mm"))
            return when {
                snapshot.error != null && stamp != null -> "$stamp 기준 · ${snapshot.error}"
                snapshot.error != null -> snapshot.error
                stamp != null -> "마지막 갱신 $stamp"
                else -> "노션을 새로고침해 주세요."
            }
        }
    }
}

class ScheduleWidgetRows : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Rows(application as MyCalenderApp)

    private class Rows(private val app: MyCalenderApp) : RemoteViewsFactory {
        private sealed interface Row {
            data class Heading(val label: String) : Row
            data class Item(val task: Task, val source: String) : Row
        }

        @Volatile private var rows: List<Row> = emptyList()
        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            val source = app.connection.sourceId()
            rows = if (source == null) emptyList() else buildList {
                for ((section, tasks) in groupTasks(app.cache(source).snapshot().tasks, LocalDate.now(seoulZone))) {
                    add(Row.Heading(section.label))
                    for (task in tasks) add(Row.Item(task, source))
                }
            }
        }

        override fun onDestroy() { rows = emptyList() }
        override fun getCount() = rows.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 2
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews? = when (val row = rows.getOrNull(position)) {
            null -> null
            is Row.Heading -> RemoteViews(app.packageName, R.layout.schedule_heading).apply {
                setTextViewText(R.id.section_label, row.label)
            }
            is Row.Item -> RemoteViews(app.packageName, R.layout.schedule_row).apply {
                val task = row.task
                setTextViewText(R.id.row_title, task.title.ifBlank { "제목 없는 일정" })
                setTextViewText(R.id.row_due, dueLabel(task.due, task.dueEnd))
                setViewVisibility(R.id.row_star, if (task.important) View.VISIBLE else View.GONE)
                setContentDescription(R.id.row_check, "${task.title} 완료")
                setOnClickFillInIntent(R.id.row_check, WidgetActionActivity.fill("complete", row.source, task.id))
                setOnClickFillInIntent(R.id.row_body, WidgetActionActivity.fill("edit", row.source, task.id))
            }
        }
    }
}

fun dueLabel(due: DueDate?, rangeEnd: DueDate? = null): String {
    if (due == null) return "날짜 미정"
    val day = due.date.format(DateTimeFormatter.ofPattern("M/d"))
    val time = due.time?.format(DateTimeFormatter.ofPattern("HH:mm"))
    val start = if (time == null) "$day · 종일" else "$day · $time"
    return if (rangeEnd == null) start else "$start ~ ${dueLabel(rangeEnd)}"
}

class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MyCalenderApp
        val source = intent.getStringExtra(SOURCE)
        val task = intent.getStringExtra(TASK)
        when (intent.getStringExtra(ACTION)) {
            "refresh" -> {
                if (!RefreshJobService.refresh(this)) toast("새로고침을 예약하지 못했어요.")
            }
            "complete" -> if (source != null && task != null && source == app.connection.sourceId()) {
                if (!RefreshJobService.complete(this, source, task)) toast("완료를 예약하지 못했어요.")
            }
            "edit" -> if (source != null && task != null && source == app.connection.sourceId()) {
                startActivity(TaskEditorActivity.intent(this, source, taskId = task))
            }
        }
        finish()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val ACTION = "action"
        private const val SOURCE = "source"
        private const val TASK = "task"

        fun intent(context: Context, action: String): Intent =
            Intent(context, WidgetActionActivity::class.java).putExtra(ACTION, action)

        fun fill(action: String, source: String, task: String): Intent =
            Intent().putExtra(ACTION, action).putExtra(SOURCE, source).putExtra(TASK, task)
    }
}

package com.mycalender.settings

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import com.mycalender.editor.TaskEditorActivity
import com.mycalender.notion.Connection
import com.mycalender.notion.NotionApi
import com.mycalender.notion.notionId
import com.mycalender.sync.RefreshJobService
import com.mycalender.tasks.Changes
import com.mycalender.tasks.TaskRepository
import com.mycalender.tasks.actionError
import com.mycalender.tasks.groupTasks
import com.mycalender.tasks.seoulZone
import com.mycalender.ui.ScreenActivity
import com.mycalender.widget.ScheduleWidget
import com.mycalender.widget.dueLabel
import java.time.LocalDate

class ConnectionActivity : ScreenActivity() {
    private var editingConnection = false
    private var connectionIdInput: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        editingConnection = savedInstanceState?.getBoolean("editing_connection") ?: (app.connection.sourceId() == null)
        if (editingConnection) showConnection(savedInstanceState?.getString("entered_source"))
    }

    override fun onResume() {
        super.onResume()
        if (editingConnection || app.connection.sourceId() == null) {
            if (connectionIdInput == null) showConnection()
        } else showSchedule()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("editing_connection", editingConnection)
        outState.putString("entered_source", connectionIdInput?.text?.toString())
        super.onSaveInstanceState(outState)
    }

    private fun showConnection(enteredSource: String? = null) {
        val root = page()
        val source = app.connection.sourceId()
        root.add(text("노션 연결", 29f, bold = true))
        root.add(text("일정 DB에 내부 연결을 추가하고 아래 정보를 입력하세요.", muted = true), 8)
        root.add(text("데이터 소스 ID", bold = true), 24)
        val id = field(enteredSource ?: source.orEmpty(), "32자리 데이터 소스 ID").apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        connectionIdInput = id
        root.add(id, 3)
        root.add(text("내부 연결 토큰", bold = true), 16)
        val token = field("", "노션 내부 연결 토큰").apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            isSaveEnabled = false
        }
        root.add(token, 3)
        val state = text("", muted = true)
        lateinit var connect: android.widget.Button
        connect = button("연결하고 일정 불러오기", primary = true) {
            val enteredId = id.text.toString()
            val enteredToken = token.text.toString().trim()
            if (enteredToken.isEmpty()) {
                state.text = "노션 내부 연결 토큰을 입력해 주세요."
                return@button
            }
            state.text = "연결을 확인하고 있어요."
            connect.isEnabled = false
            app.execute({
                val config = Connection(notionId(enteredId), enteredToken)
                val api = NotionApi(config)
                api.validateConnection()
                TaskRepository(api, app.cache(config.dataSourceId)).refresh()
                app.connection.save(config)
                ScheduleWidget.render(app)
                RefreshJobService.ensurePeriodic(app)
            }) { result ->
                if (isFinishing || isDestroyed) return@execute
                connect.isEnabled = true
                result.onSuccess {
                    editingConnection = false
                    showSchedule()
                }.onFailure { state.text = actionError(it as? Exception ?: Exception(it)) }
            }
        }
        root.add(connect, 22)
        root.add(state, 10)
        if (source != null) {
            root.add(button("일정으로 돌아가기") {
                editingConnection = false
                showSchedule()
            }, 10)
        }
    }

    private fun showSchedule() {
        val source = app.connection.sourceId() ?: return showConnection()
        connectionIdInput = null
        val root = page()
        root.add(text("일정", 30f, bold = true))
        val snapshot = app.cache(source).snapshot()
        val state = text(ScheduleWidget.summary(snapshot), muted = true)
        root.add(state, 6)

        root.add(row().apply {
            cell(button("+ 추가", primary = true) { startActivity(TaskEditorActivity.intent(context, source)) })
            cell(button("새로고침") {
                state.text = "노션에서 일정을 불러오고 있어요."
                app.execute({
                    app.repository(source).refresh()
                }) { result ->
                    ScheduleWidget.render(app)
                    if (!isFinishing && !isDestroyed && !editingConnection) {
                        result.onFailure { toast(actionError(it as? Exception ?: Exception(it))) }
                        showSchedule()
                    }
                }
            }, gap = 8)
        }, 16)
        root.add(button("노션 연결 설정") {
            editingConnection = true
            showConnection()
        }, 8)

        val drafts = app.cache(source).drafts()
        if (drafts.isNotEmpty()) root.add(text("저장 확인이 필요한 항목", bold = true), 20)
        for (draft in drafts) {
            root.add(button(draft.fields.title) {
                startActivity(TaskEditorActivity.intent(this, source, draftId = draft.id))
            }, 6)
        }

        if (snapshot.tasks.isEmpty()) root.add(text("표시할 일정이 없어요.", muted = true), 20)
        for ((group, tasks) in groupTasks(snapshot.tasks, LocalDate.now(seoulZone))) {
            root.add(text(group.label, 16f, bold = true), 20)
            for (task in tasks) {
                root.add(row().apply {
                    cell(button((if (task.important) "★ " else "") +
                        "${task.title.ifBlank { "제목 없는 일정" }}  ·  ${dueLabel(task.due, task.dueEnd)}") {
                        startActivity(TaskEditorActivity.intent(context, source, taskId = task.id))
                    }, weight = 3f)
                    lateinit var done: android.widget.Button
                    done = button("완료") {
                        done.isEnabled = false
                        app.execute({ app.repository(source).update(task.id, Changes(completed = true)) }) { result ->
                            ScheduleWidget.render(app)
                            if (!isFinishing && !isDestroyed && !editingConnection) {
                                result.onFailure { toast(actionError(it as? Exception ?: Exception(it))) }
                                showSchedule()
                            }
                        }
                    }
                    cell(done, gap = 8)
                }, 5)
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}

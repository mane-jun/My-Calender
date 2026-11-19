package com.mycalender.editor

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.mycalender.tasks.*
import com.mycalender.ui.ScreenActivity
import com.mycalender.widget.ScheduleWidget
import com.mycalender.widget.dueLabel
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class TaskEditorActivity : ScreenActivity() {
    private lateinit var source: String
    private var pageId: String? = null
    private lateinit var draftId: String
    private lateinit var original: EditableFields
    private var date: DueDate? = null
    private var rangeEnd: DueDate? = null
    private lateinit var titleInput: EditText
    private lateinit var important: CheckBox
    private lateinit var feedback: TextView
    private lateinit var save: Button
    private lateinit var dateButton: Button
    private lateinit var timeButton: Button
    private lateinit var dayButton: Button
    private lateinit var clearDateButton: Button
    private var awaitingConfirmation = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val configured = app.connection.sourceId()
        if (configured == null || intent.getStringExtra(SOURCE) != configured) {
            toast("노션 연결을 먼저 확인해 주세요.")
            finish()
            return
        }
        source = configured
        pageId = intent.getStringExtra(TASK_ID)
        draftId = savedInstanceState?.getString(DRAFT_ID) ?: intent.getStringExtra(DRAFT_ID)
            ?: UUID.randomUUID().toString()

        val task = app.cache(source).snapshot().tasks.firstOrNull { it.id == pageId }
        if (pageId != null && task == null) {
            toast("이 일정은 목록에서 이동했어요. 목록을 새로고침해 주세요.")
            finish()
            return
        }

        val pending = if (pageId == null) app.cache(source).draft(draftId) else null
        rangeEnd = task?.dueEnd
        original = if (pageId != null && savedInstanceState?.containsKey("original_title") == true) {
            EditableFields(savedInstanceState.getString("original_title").orEmpty(),
                savedInstanceState.getString("original_due")?.let(DueDate::parse),
                savedInstanceState.getBoolean("original_important"))
        } else task?.editable() ?: pending?.fields ?: EditableFields("", null, false)
        awaitingConfirmation = pending?.attempted == true
        val fields = savedInstanceState?.let {
            EditableFields(
                it.getString("title").orEmpty(),
                it.getString("due")?.let(DueDate::parse),
                it.getBoolean("important"),
            )
        } ?: original
        showEditor(if (awaitingConfirmation) original else fields)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::titleInput.isInitialized) {
            outState.putString(DRAFT_ID, draftId)
            outState.putString("title", titleInput.text.toString())
            outState.putString("due", date?.forNotion())
            outState.putBoolean("important", important.isChecked)
            outState.putString("original_title", original.title)
            outState.putString("original_due", original.due?.forNotion())
            outState.putBoolean("original_important", original.important)
        }
        super.onSaveInstanceState(outState)
    }

    private fun showEditor(fields: EditableFields) {
        date = fields.due
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val root = page()
        root.add(text(if (pageId == null) "일정 추가" else "일정 수정", 27f, bold = true))
        titleInput = field(fields.title, "제목").apply {
            filters = arrayOf(InputFilter.LengthFilter(2_000))
            maxLines = 4
        }
        root.add(titleInput, 13)

        dateButton = button("") { chooseDate() }
        root.add(dateButton, 16)
        timeButton = button("시간 지정") { chooseTime() }
        dayButton = button("종일") {
            date = date?.copy(time = null) ?: DueDate(LocalDate.now(seoulZone))
            showDate()
        }
        clearDateButton = button("날짜 지우기") {
            date = null
            showDate()
        }
        root.add(row().apply {
            cell(timeButton)
            cell(dayButton, gap = 8)
            cell(clearDateButton, gap = 8)
        }, 8)
        if (rangeEnd != null) root.add(text("기간이 있는 일정의 날짜는 노션에서 수정할 수 있어요.", muted = true), 6)

        important = CheckBox(this).apply {
            text = "중요 표시"
            isChecked = fields.important
        }
        root.add(important, 10)
        feedback = text(if (awaitingConfirmation) "노션에 저장됐는지 먼저 확인할 수 있어요." else "", muted = true)
        root.add(feedback, 4)
        save = button("", primary = true) { save() }
        root.add(save, 12)
        root.add(button("닫기") { finish() }, 8)
        showDate()
        setEditable(!awaitingConfirmation)
    }

    private fun setEditable(editable: Boolean) {
        titleInput.isEnabled = editable
        important.isEnabled = editable
        val editableDate = editable && rangeEnd == null
        dateButton.isEnabled = editableDate
        timeButton.isEnabled = editableDate
        dayButton.isEnabled = editableDate
        clearDateButton.isEnabled = editableDate
        save.text = if (awaitingConfirmation) "저장 결과 확인" else "저장"
    }

    private fun showDate() {
        dateButton.text = date?.let { "${it.date.year}년 ${dueLabel(it, rangeEnd)}" } ?: "날짜 선택"
    }

    private fun chooseDate() {
        val day = date?.date ?: LocalDate.now(seoulZone)
        DatePickerDialog(this, { _, year, month, dayOfMonth ->
            date = DueDate(LocalDate.of(year, month + 1, dayOfMonth), date?.time)
            showDate()
        }, day.year, day.monthValue - 1, day.dayOfMonth).show()
    }

    private fun chooseTime() {
        val time = date?.time ?: LocalTime.of(9, 0)
        TimePickerDialog(this, { _, hour, minute ->
            date = DueDate(date?.date ?: LocalDate.now(seoulZone), LocalTime.of(hour, minute))
            showDate()
        }, time.hour, time.minute, true).show()
    }

    private fun save(resend: Boolean = false) {
        val title = titleInput.text.toString()
        if (title.isBlank()) {
            titleInput.error = "제목을 입력해 주세요."
            return
        }
        val fields = EditableFields(title, date, important.isChecked)
        setEditable(false)
        save.isEnabled = false
        feedback.text = if (awaitingConfirmation) "저장 여부를 확인하고 있어요." else "노션에 저장하고 있어요."
        app.execute({
            val repo = app.repository(source)
            val id = pageId
            if (id == null) repo.create(PendingDraft(draftId, fields), resendIfMissing = resend)
            else repo.update(id, Changes.between(original, fields))
            ScheduleWidget.render(app)
        }) { result ->
            if (isFinishing || isDestroyed) return@execute
            save.isEnabled = true
            result.onSuccess { finish() }.onFailure { failure ->
                val error = failure as? Exception ?: Exception(failure)
                val stored = if (pageId == null) app.cache(source).draft(draftId) else null
                awaitingConfirmation = stored?.attempted == true
                feedback.text = actionError(error)
                setEditable(!awaitingConfirmation)
                if (error is UnconfirmedCreate && error.canResend) {
                    AlertDialog.Builder(this)
                        .setTitle("노션에서 아직 찾지 못했어요")
                        .setMessage("노션에도 일정이 없는 것을 확인했다면 같은 초안을 다시 전송할 수 있어요.")
                        .setNegativeButton("닫기", null)
                        .setPositiveButton("다시 전송") { _, _ -> save(resend = true) }
                        .show()
                }
            }
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private const val SOURCE = "source"
        private const val TASK_ID = "task"
        private const val DRAFT_ID = "draft"

        fun intent(context: Context, source: String?, taskId: String? = null, draftId: String? = null): Intent =
            Intent(context, TaskEditorActivity::class.java).putExtra(SOURCE, source)
                .putExtra(TASK_ID, taskId).putExtra(DRAFT_ID, draftId)
    }
}

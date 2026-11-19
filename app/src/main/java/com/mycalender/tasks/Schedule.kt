package com.mycalender.tasks

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

val seoulZone: ZoneId = ZoneId.of("Asia/Seoul")

data class DueDate(val date: LocalDate, val time: LocalTime? = null) {
    fun forNotion(): String = time?.let {
        date.atTime(it).atZone(seoulZone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } ?: date.toString()

    companion object {
        fun parse(start: String, timeZone: String? = null): DueDate {
            if (start.length == 10) return DueDate(LocalDate.parse(start))
            val seoulTime = try {
                OffsetDateTime.parse(start).atZoneSameInstant(seoulZone)
            } catch (_: DateTimeParseException) {
                LocalDateTime.parse(start).atZone(timeZone?.let(ZoneId::of) ?: seoulZone)
                    .withZoneSameInstant(seoulZone)
            }
            return DueDate(seoulTime.toLocalDate(), seoulTime.toLocalTime())
        }
    }
}

data class Task(
    val id: String,
    val title: String,
    val due: DueDate?,
    val important: Boolean,
    val completed: Boolean,
    val dueEnd: DueDate? = null,
) {
    fun editable() = EditableFields(title, due, important)
}

data class EditableFields(val title: String, val due: DueDate?, val important: Boolean)
data class PendingDraft(val id: String, val fields: EditableFields, val attempted: Boolean = false)

data class Changes(
    val title: String? = null,
    val setDue: Boolean = false,
    val due: DueDate? = null,
    val important: Boolean? = null,
    val completed: Boolean? = null,
) {
    fun isEmpty() = this == Changes()

    companion object {
        fun between(original: EditableFields, edited: EditableFields): Changes {
            val dateChanged = original.due != edited.due
            return Changes(
                title = edited.title.takeIf { it != original.title },
                setDue = dateChanged,
                due = edited.due.takeIf { dateChanged },
                important = edited.important.takeIf { it != original.important },
            )
        }
    }
}

data class Snapshot(
    val tasks: List<Task> = emptyList(),
    val updatedAt: Instant? = null,
    val error: String? = null,
)

enum class TaskGroup(val label: String) {
    OVERDUE("기한 지남"),
    TODAY("오늘"),
    TOMORROW("내일"),
    WITHIN_WEEK("7일 이내"),
    LATER("이후"),
    UNDATED("날짜 미정"),
}

fun groupTasks(tasks: List<Task>, today: LocalDate): List<Pair<TaskGroup, List<Task>>> {
    val tomorrow = today.plusDays(1)
    val weekEnd = today.plusDays(7)
    val ordered = tasks.filterNot { it.completed }
        .sortedWith(compareBy<Task, LocalDate?>(nullsLast()) { it.due?.date }
            .thenBy { it.due?.time }
            .thenBy { it.title }
            .thenBy { it.id })

    val grouped = ordered.groupBy { task ->
        val date = task.due?.date
        when {
            date == null -> TaskGroup.UNDATED
            date < today -> TaskGroup.OVERDUE
            date == today -> TaskGroup.TODAY
            date == tomorrow -> TaskGroup.TOMORROW
            date <= weekEnd -> TaskGroup.WITHIN_WEEK
            else -> TaskGroup.LATER
        }
    }
    return TaskGroup.entries.mapNotNull { group -> grouped[group]?.let { group to it } }
}

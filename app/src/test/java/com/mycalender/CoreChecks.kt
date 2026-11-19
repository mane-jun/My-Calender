package com.mycalender

import com.mycalender.tasks.*
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val today = LocalDate.of(2026, 11, 19)
private val timestamp = Instant.parse("2026-11-19T08:00:00Z")
private fun due(days: Long, time: LocalTime? = null) = DueDate(today.plusDays(days), time)
private fun task(id: String, date: DueDate? = null) = Task(id, id, date, false, false)

private class MemoryStore : TaskStore {
    var state = Snapshot()
    val drafts = mutableMapOf<String, PendingDraft>()
    override fun snapshot() = state
    override fun replace(tasks: List<Task>, time: Instant) { state = Snapshot(tasks, time) }
    override fun noteError(error: String?) { state = state.copy(error = error) }
    override fun apply(task: Task) {
        state = state.copy(tasks = (state.tasks.filterNot { it.id == task.id } + task).filterNot { it.completed })
    }
    override fun draft(id: String) = drafts[id]
    override fun drafts() = drafts.values.toList()
    override fun putDraft(draft: PendingDraft) { drafts[draft.id] = draft }
    override fun removeDraft(id: String) { drafts.remove(id) }
}

private class TestGateway : TaskGateway {
    var created: Task? = null
    var postCount = 0
    var queries = 0
    var failRefresh = false
    var lookupFails = false
    var rejectUpdate = false
    var rejectLookup = false
    var commitBeforeTimeout = true
    override fun listOpen(): List<Task> {
        if (failRefresh) throw IOException("disconnected")
        return listOf(task("downloaded"))
    }
    override fun findCreated(recordId: String): Task? {
        queries++
        if (rejectLookup) throw NotionRejected(403, "permission")
        if (lookupFails && postCount > 0) throw IOException("disconnected")
        return created
    }
    override fun create(draft: PendingDraft): Task {
        postCount++
        if (commitBeforeTimeout) created = task("created", draft.fields.due)
        throw IOException("response lost")
    }
    override fun update(pageId: String, changes: Changes): Task {
        check(changes.completed == true && changes.title == null && !changes.setDue)
        if (rejectUpdate) throw IOException("disconnected")
        return task(pageId).copy(completed = true)
    }
}

fun main() {
    val groupResult = groupTasks(listOf(
        task("later", due(8)), task("missing"), task("late", due(0, LocalTime.of(23, 59))),
        task("all day", due(0)), task("tomorrow", due(1)), task("last", due(7)), task("past", due(-1)),
    ), today)
    check(groupResult.map { it.first } == TaskGroup.entries)
    check(groupResult[1].second.map { it.id } == listOf("all day", "late"))
    check(groupResult[3].second.single().id == "last")
    check(groupResult[4].second.single().id == "later")
    check(DueDate.parse("2026-11-18T15:00:00.000Z") == due(0, LocalTime.MIDNIGHT))
    check(DueDate.parse("2026-11-19") == due(0))
    check(DueDate.parse("2026-11-19T23:59:00+09:00").forNotion() == "2026-11-19T23:59:00+09:00")

    val old = task("cached")
    val store = MemoryStore().apply { replace(listOf(old), timestamp) }
    val gateway = TestGateway()
    val repo = TaskRepository(gateway, store) { timestamp }
    gateway.failRefresh = true
    check(runCatching { repo.refresh() }.isFailure)
    check(store.snapshot().tasks == listOf(old) && store.snapshot().updatedAt == timestamp)
    gateway.failRefresh = false
    repo.refresh()
    check(store.snapshot().tasks.single().id == "downloaded" && store.snapshot().error == null)

    val draft = PendingDraft("record-a", EditableFields("과제", due(0), false))
    gateway.lookupFails = true
    check(runCatching { repo.create(draft) }.exceptionOrNull() is UnconfirmedCreate)
    check(store.draft(draft.id)?.attempted == true)
    gateway.lookupFails = false
    check(repo.create(draft).id == "created")
    check(gateway.postCount == 1 && store.draft(draft.id) == null)
    check(repo.create(draft).id == "created" && gateway.postCount == 1)

    val uncertain = draft.copy(id = "record-b")
    gateway.created = null
    gateway.commitBeforeTimeout = false
    check(runCatching { repo.create(uncertain) }.exceptionOrNull() is UnconfirmedCreate)
    check(runCatching { repo.create(uncertain) }.exceptionOrNull() is UnconfirmedCreate)
    check(gateway.postCount == 2 && store.draft(uncertain.id) != null)

    val unauthorized = draft.copy(id = "record-c")
    gateway.rejectLookup = true
    check((runCatching { repo.create(unauthorized) }.exceptionOrNull() as? NotionRejected)?.status == 403)
    check(gateway.postCount == 2 && store.draft(unauthorized.id)?.attempted == false)

    gateway.rejectUpdate = true
    check(runCatching { repo.update("downloaded", Changes(completed = true)) }.isFailure)
    check(store.snapshot().tasks.any { it.id == "downloaded" })
    gateway.rejectUpdate = false
    repo.update("downloaded", Changes(completed = true))
    check(store.snapshot().tasks.none { it.id == "downloaded" })

    val before = EditableFields("원래 제목", due(1), true)
    check(Changes.between(before, before.copy(title = "변경")) == Changes(title = "변경"))
    check(Changes.between(before, before.copy(due = null)) == Changes(setDue = true))
    println("All My-Calender core checks passed.")
}

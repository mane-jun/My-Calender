package com.mycalender.tasks

import java.io.IOException
import java.time.Instant

interface TaskGateway {
    fun listOpen(): List<Task>
    fun findCreated(recordId: String): Task?
    fun create(draft: PendingDraft): Task
    fun update(pageId: String, changes: Changes): Task
}

interface TaskStore {
    fun snapshot(): Snapshot
    fun replace(tasks: List<Task>, time: Instant)
    fun noteError(error: String?)
    fun apply(task: Task)
    fun draft(id: String): PendingDraft?
    fun drafts(): List<PendingDraft>
    fun putDraft(draft: PendingDraft)
    fun removeDraft(id: String)
}

class NotionRejected(val status: Int, message: String) : IOException(message)

class UnconfirmedCreate(val canResend: Boolean, cause: Exception? = null) : IOException(
    if (canResend) "노션에서 이 항목을 아직 찾지 못했어요. 노션을 확인한 뒤 다시 전송할 수 있어요."
    else "저장 여부를 확인하지 못했어요. 연결이 돌아오면 앱에서 저장 결과를 다시 확인하세요.",
    cause,
)

fun actionError(error: Exception): String = when (error) {
    is NotionRejected, is UnconfirmedCreate, is IllegalArgumentException, is IllegalStateException ->
        error.message ?: "작업을 마치지 못했어요."
    is IOException -> "노션에 연결하지 못했어요. 네트워크를 확인해 주세요."
    else -> "일정을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요."
}

class TaskRepository(
    private val gateway: TaskGateway,
    private val store: TaskStore,
    private val now: () -> Instant = Instant::now,
) {
    fun refresh() {
        try {
            val tasks = gateway.listOpen()
            store.replace(tasks, now())
        } catch (error: Exception) {
            store.noteError(actionError(error))
            throw error
        }
    }

    fun create(draft: PendingDraft, resendIfMissing: Boolean = false): Task {
        val previous = store.draft(draft.id)
        val pending = previous?.takeIf { it.attempted } ?: draft.copy(attempted = false)
        store.putDraft(pending)
        val existing = if (pending.attempted) findSaved(pending.id) else gateway.findCreated(pending.id)
        if (existing != null) return finishCreate(pending.id, existing)
        if (pending.attempted && !resendIfMissing) throw UnconfirmedCreate(canResend = true)
        val request = pending.copy(attempted = true)
        store.putDraft(request)

        val task = try {
            gateway.create(request)
        } catch (error: NotionRejected) {
            store.putDraft(request.copy(attempted = false))
            throw error
        } catch (error: Exception) {
            findSaved(request.id)?.let { return finishCreate(request.id, it) }
            throw UnconfirmedCreate(canResend = false, cause = error)
        }
        return finishCreate(request.id, task)
    }

    fun update(pageId: String, changes: Changes) {
        if (changes.isEmpty()) return
        store.apply(gateway.update(pageId, changes))
    }

    private fun findSaved(id: String): Task? = try {
        gateway.findCreated(id)
    } catch (error: Exception) {
        throw UnconfirmedCreate(canResend = false, cause = error)
    }

    private fun finishCreate(id: String, task: Task): Task {
        store.apply(task)
        store.removeDraft(id)
        return task
    }
}

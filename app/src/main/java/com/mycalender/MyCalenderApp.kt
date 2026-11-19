package com.mycalender

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.mycalender.notion.NotionApi
import com.mycalender.settings.ConnectionStore
import com.mycalender.tasks.Snapshot
import com.mycalender.tasks.TaskCache
import com.mycalender.tasks.TaskDatabase
import com.mycalender.tasks.TaskRepository
import java.util.concurrent.Executors

class MyCalenderApp : Application() {
    val connection by lazy { ConnectionStore(this) }
    private val database by lazy { TaskDatabase(this) }
    val operations = Executors.newSingleThreadExecutor()
    private val mainThread = Handler(Looper.getMainLooper())

    fun cache(source: String) = TaskCache(database, source)
    fun snapshot(): Snapshot = connection.sourceId()?.let { cache(it).snapshot() } ?: Snapshot()

    fun repository(source: String): TaskRepository {
        val settings = connection.load()
        check(settings?.dataSourceId == source) { "현재 노션 연결을 확인해 주세요." }
        return TaskRepository(NotionApi(settings), cache(source))
    }

    fun <T> execute(task: () -> T, onResult: (Result<T>) -> Unit) {
        operations.execute {
            val result = runCatching(task)
            mainThread.post { onResult(result) }
        }
    }
}

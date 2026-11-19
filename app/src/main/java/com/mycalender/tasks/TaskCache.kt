package com.mycalender.tasks

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.Instant

class TaskDatabase(context: Context) : SQLiteOpenHelper(context, "schedule.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE tasks (
                source TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, due TEXT, due_end TEXT,
                important INTEGER NOT NULL, PRIMARY KEY (source, id)
            )
        """.trimIndent())
        db.execSQL("CREATE TABLE sync (source TEXT PRIMARY KEY NOT NULL, updated INTEGER, error TEXT)")
        db.execSQL("""
            CREATE TABLE drafts (
                source TEXT NOT NULL, id TEXT NOT NULL, title TEXT NOT NULL, due TEXT,
                important INTEGER NOT NULL, attempted INTEGER NOT NULL, PRIMARY KEY (source, id)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("이 데이터베이스 버전은 업그레이드를 지원하지 않아요.")
}

class TaskCache(private val database: TaskDatabase, private val source: String) : TaskStore {
    override fun snapshot(): Snapshot = database.readableDatabase.inTransaction {
        val tasks = query("tasks", null, "source = ?", arrayOf(source), null, null, null).use { rows ->
            buildList {
                while (rows.moveToNext()) add(Task(
                    rows.string("id")!!, rows.string("title")!!, rows.string("due")?.let(DueDate::parse),
                    rows.flag("important"), completed = false, dueEnd = rows.string("due_end")?.let(DueDate::parse),
                ))
            }
        }
        query("sync", null, "source = ?", arrayOf(source), null, null, null).use { row ->
            if (!row.moveToFirst()) Snapshot(tasks)
            else Snapshot(tasks, row.string("updated")?.toLong()?.let(Instant::ofEpochMilli), row.string("error"))
        }
    }

    override fun replace(tasks: List<Task>, time: Instant) {
        database.writableDatabase.inTransaction {
            delete("tasks", "source = ?", arrayOf(source))
            for (task in tasks) if (!task.completed) saveTask(task)
            upsert("sync", ContentValues().apply {
                put("source", source)
                put("updated", time.toEpochMilli())
                putNull("error")
            })
        }
    }

    override fun noteError(error: String?) {
        database.writableDatabase.inTransaction {
            val values = ContentValues().apply { put("error", error) }
            if (update("sync", values, "source = ?", arrayOf(source)) == 0) {
                values.put("source", source)
                insertOrThrow("sync", null, values)
            }
        }
    }

    override fun apply(task: Task) {
        val db = database.writableDatabase
        if (task.completed) db.delete("tasks", "source = ? AND id = ?", arrayOf(source, task.id))
        else db.saveTask(task)
    }

    override fun draft(id: String): PendingDraft? = database.readableDatabase.query(
        "drafts", null, "source = ? AND id = ?", arrayOf(source, id), null, null, null,
    ).use { if (it.moveToFirst()) it.toDraft() else null }

    override fun drafts(): List<PendingDraft> = database.readableDatabase.query(
        "drafts", null, "source = ?", arrayOf(source), null, null, "rowid DESC",
    ).use { rows -> buildList { while (rows.moveToNext()) add(rows.toDraft()) } }

    override fun putDraft(draft: PendingDraft) {
        database.writableDatabase.upsert("drafts", values(draft.id, draft.fields).apply {
            put("attempted", draft.attempted)
        })
    }

    override fun removeDraft(id: String) {
        database.writableDatabase.delete("drafts", "source = ? AND id = ?", arrayOf(source, id))
    }

    private fun SQLiteDatabase.saveTask(task: Task) = upsert("tasks", values(task.id, task.editable()).apply {
        put("due_end", task.dueEnd?.forNotion())
    })

    private fun values(id: String, fields: EditableFields) = ContentValues().apply {
        put("source", source)
        put("id", id)
        put("title", fields.title)
        put("due", fields.due?.forNotion())
        put("important", fields.important)
    }

    private fun Cursor.toDraft() = PendingDraft(
        string("id")!!, EditableFields(string("title")!!, string("due")?.let(DueDate::parse), flag("important")),
        flag("attempted"),
    )

    private fun Cursor.string(name: String): String? = getString(getColumnIndexOrThrow(name))
    private fun Cursor.flag(name: String) = getInt(getColumnIndexOrThrow(name)) != 0

    private fun SQLiteDatabase.upsert(table: String, values: ContentValues) {
        check(insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "일정을 기기에 저장하지 못했어요."
        }
    }

    private inline fun <T> SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            val result = block()
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }
}

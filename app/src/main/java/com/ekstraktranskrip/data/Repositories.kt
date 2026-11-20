package com.ekstraktranskrip.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class JobRepository(private val db: AppDatabase) {

    fun insert(job: JobRow) {
        db.writableDatabase.insertWithOnConflict(
            "jobs", null, AppDatabase.contentValues(job), SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun job(id: String): JobRow? = db.readableDatabase
        .rawQuery("SELECT * FROM jobs WHERE id = ?", arrayOf(id))
        .use { c -> if (c.moveToFirst()) c.toJob() else null }

    fun jobs(limit: Int = 200): List<JobRow> = db.readableDatabase
        .rawQuery("SELECT * FROM jobs ORDER BY created_at DESC LIMIT $limit", null)
        .use { c ->
            val out = ArrayList<JobRow>(c.count)
            while (c.moveToNext()) out.add(c.toJob())
            out
        }

    fun setStage(id: String, status: JobStatus, progress: Int, durationMs: Long? = null) {
        val values = ContentValues().apply {
            put("status", status.name)
            put("progress", progress.coerceIn(0, 100))
            if (durationMs != null) put("duration_ms", durationMs)
        }
        db.writableDatabase.update("jobs", values, "id = ?", arrayOf(id))
    }

    fun setProgress(id: String, progress: Int) {
        val values = ContentValues().apply { put("progress", progress.coerceIn(0, 100)) }
        db.writableDatabase.update("jobs", values, "id = ?", arrayOf(id))
    }

    fun setWavPath(id: String, path: String?) {
        val values = ContentValues().apply { put("wav_path", path) }
        db.writableDatabase.update("jobs", values, "id = ?", arrayOf(id))
    }

    fun setInputPath(id: String, path: String?) {
        val values = ContentValues().apply { put("input_path", path) }
        db.writableDatabase.update("jobs", values, "id = ?", arrayOf(id))
    }

    fun setModel(id: String, name: String?) {
        val values = ContentValues().apply { put("model_name", name) }
        db.writableDatabase.update("jobs", values, "id = ?", arrayOf(id))
    }

    /**
     * Called once at process start. A job row still marked as running cannot be
     * running: the process that owned it is gone. Without this the UI would show
     * a permanently stuck "Berjalan 43%".
     */
    fun failInterruptedJobs() {
        val values = ContentValues().apply {
            put("status", JobStatus.FAILED.name)
            put("error_code", "INTERRUPTED")
            put("error_message", "Terputus: aplikasi ditutup saat transkripsi berjalan.")
            put("finished_at", System.currentTimeMillis())
        }
        db.writableDatabase.update(
            "jobs",
            values,
            "status NOT IN (?, ?, ?)",
            arrayOf(JobStatus.DONE.name, JobStatus.FAILED.name, JobStatus.CANCELLED.name),
        )
    }

    fun finish(
        id: String,
        status: JobStatus,
        errorCode: String? = null,
        errorMessage: String? = null,
        rtf: Double? = null,
    ) {
        val values = ContentValues().apply {
            put("status", status.name)
            put("finished_at", System.currentTimeMillis())
            put("error_code", errorCode)
            put("error_message", errorMessage)
            put("rtf", rtf)
            if (status == JobStatus.DONE) put("progress", 100)
        }
        db.writableDatabase.update("jobs", values, "id = ?", arrayOf(id))
    }

    fun replaceSegments(jobId: String, segments: List<SegmentRow>) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete("segments", "job_id = ?", arrayOf(jobId))
            val statement = database.compileStatement(
                "INSERT INTO segments(job_id, idx, t0_ms, t1_ms, text) VALUES (?, ?, ?, ?, ?)",
            )
            for (s in segments) {
                statement.clearBindings()
                statement.bindString(1, jobId)
                statement.bindLong(2, s.idx.toLong())
                statement.bindLong(3, s.t0Ms)
                statement.bindLong(4, s.t1Ms)
                statement.bindString(5, s.text)
                statement.executeInsert()
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun segments(jobId: String): List<SegmentRow> = db.readableDatabase
        .rawQuery(
            "SELECT idx, t0_ms, t1_ms, text FROM segments WHERE job_id = ? ORDER BY idx",
            arrayOf(jobId),
        )
        .use { c ->
            val out = ArrayList<SegmentRow>(c.count)
            while (c.moveToNext()) {
                out.add(SegmentRow(c.getInt(0), c.getLong(1), c.getLong(2), c.getString(3)))
            }
            out
        }

    fun delete(jobId: String) {
        val database = db.writableDatabase
        database.beginTransaction()
        try {
            database.delete("segments", "job_id = ?", arrayOf(jobId))
            database.delete("summaries", "job_id = ?", arrayOf(jobId))
            database.delete("chat_messages", "job_id = ?", arrayOf(jobId))
            database.delete("jobs", "id = ?", arrayOf(jobId))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private fun Cursor.toJob(): JobRow = JobRow(
        id = getString(getColumnIndexOrThrow("id")),
        sourceName = getString(getColumnIndexOrThrow("source_name")),
        inputPath = getStringOrNull("input_path"),
        wavPath = getStringOrNull("wav_path"),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        status = JobStatus.fromDb(getString(getColumnIndexOrThrow("status"))),
        progress = getInt(getColumnIndexOrThrow("progress")),
        language = getString(getColumnIndexOrThrow("language")),
        modelName = getStringOrNull("model_name"),
        createdAt = getLong(getColumnIndexOrThrow("created_at")),
        finishedAt = getLongOrNull("finished_at"),
        errorCode = getStringOrNull("error_code"),
        errorMessage = getStringOrNull("error_message"),
        rtf = getDoubleOrNull("rtf"),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getLongOrNull(column: String): Long? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getLong(index)
    }

    private fun Cursor.getDoubleOrNull(column: String): Double? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getDouble(index)
    }
}

class SummaryRepository(private val db: AppDatabase) {

    fun get(jobId: String): SummaryRow? = db.readableDatabase
        .rawQuery("SELECT * FROM summaries WHERE job_id = ?", arrayOf(jobId))
        .use { c ->
            if (!c.moveToFirst()) return null
            SummaryRow(
                jobId = c.getString(c.getColumnIndexOrThrow("job_id")),
                model = c.getString(c.getColumnIndexOrThrow("model")),
                createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                ringkasan = c.getString(c.getColumnIndexOrThrow("ringkasan")),
                keputusan = c.getString(c.getColumnIndexOrThrow("keputusan")),
                actionItems = c.getString(c.getColumnIndexOrThrow("action_items")),
                momen = c.getString(c.getColumnIndexOrThrow("momen")),
            )
        }

    fun save(row: SummaryRow) {
        val values = ContentValues().apply {
            put("job_id", row.jobId)
            put("model", row.model)
            put("created_at", row.createdAt)
            put("ringkasan", row.ringkasan)
            put("keputusan", row.keputusan)
            put("action_items", row.actionItems)
            put("momen", row.momen)
        }
        db.writableDatabase.insertWithOnConflict(
            "summaries", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun delete(jobId: String) {
        db.writableDatabase.delete("summaries", "job_id = ?", arrayOf(jobId))
    }
}

class ChatRepository(private val db: AppDatabase) {

    fun messages(jobId: String): List<ChatMessageRow> = db.readableDatabase
        .rawQuery(
            "SELECT id, job_id, role, content, created_at FROM chat_messages WHERE job_id = ? ORDER BY id",
            arrayOf(jobId),
        )
        .use { c ->
            val out = ArrayList<ChatMessageRow>(c.count)
            while (c.moveToNext()) {
                out.add(
                    ChatMessageRow(
                        id = c.getLong(0),
                        jobId = c.getString(1),
                        role = c.getString(2),
                        content = c.getString(3),
                        createdAt = c.getLong(4),
                    ),
                )
            }
            out
        }

    fun add(jobId: String, role: String, content: String) {
        val values = ContentValues().apply {
            put("job_id", jobId)
            put("role", role)
            put("content", content)
            put("created_at", System.currentTimeMillis())
        }
        db.writableDatabase.insert("chat_messages", null, values)
    }

    fun clear(jobId: String) {
        db.writableDatabase.delete("chat_messages", "job_id = ?", arrayOf(jobId))
    }
}

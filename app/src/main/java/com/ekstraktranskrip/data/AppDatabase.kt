package com.ekstraktranskrip.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLiteOpenHelper on purpose.
 *
 * Room would need KSP, whose version must match the Kotlin compiler exactly.
 * For four tables this is less risk and less build time, and the schema is
 * explicit and easy to migrate.
 */
class AppDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE jobs (
                id TEXT PRIMARY KEY NOT NULL,
                source_name TEXT NOT NULL,
                input_path TEXT,
                wav_path TEXT,
                duration_ms INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL,
                progress INTEGER NOT NULL DEFAULT 0,
                language TEXT NOT NULL DEFAULT 'id',
                model_name TEXT,
                created_at INTEGER NOT NULL,
                finished_at INTEGER,
                error_code TEXT,
                error_message TEXT,
                rtf REAL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE segments (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL,
                idx INTEGER NOT NULL,
                t0_ms INTEGER NOT NULL,
                t1_ms INTEGER NOT NULL,
                text TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_segments_job ON segments(job_id, idx)")
        db.execSQL(
            """
            CREATE TABLE summaries (
                job_id TEXT PRIMARY KEY NOT NULL,
                model TEXT,
                created_at INTEGER NOT NULL,
                ringkasan TEXT NOT NULL,
                keputusan TEXT NOT NULL,
                action_items TEXT NOT NULL,
                momen TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE chat_messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                job_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_chat_job ON chat_messages(job_id, id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1: nothing to migrate yet. Add ALTER TABLE steps here as the schema grows.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    companion object {
        private const val NAME = "ekstraktranskrip.db"
        private const val VERSION = 1

        fun contentValues(job: JobRow): ContentValues = ContentValues().apply {
            put("id", job.id)
            put("source_name", job.sourceName)
            put("input_path", job.inputPath)
            put("wav_path", job.wavPath)
            put("duration_ms", job.durationMs)
            put("status", job.status.name)
            put("progress", job.progress)
            put("language", job.language)
            put("model_name", job.modelName)
            put("created_at", job.createdAt)
            put("finished_at", job.finishedAt)
            put("error_code", job.errorCode)
            put("error_message", job.errorMessage)
            put("rtf", job.rtf)
        }
    }
}

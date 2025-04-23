package com.hfad.camera2

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class WorkoutStat(
    val exerciseName: String,
    val totalReps: Int,
    val avgStars: Double,
    val commonError: String
)

class WorkoutStatsDB(context: Context) :
    SQLiteOpenHelper(context, "workout_stats.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE stats (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                username TEXT,
                exercise TEXT,
                reps INTEGER,
                stars INTEGER,
                error TEXT
            )
            """
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS stats")
        onCreate(db)
    }

    fun insertWorkout(username: String, exercise: String, reps: Int, stars: Int, error: String) {
        val values = ContentValues().apply {
            put("username", username)
            put("exercise", exercise)
            put("reps", reps)
            put("stars", stars)
            put("error", error)
        }
        writableDatabase.insert("stats", null, values)
    }

    fun getStatsForUser(username: String): List<WorkoutStat> {
        val db = readableDatabase
        val cursor = db.rawQuery("""
            SELECT exercise,
                   SUM(reps) as totalReps,
                   AVG(stars) as avgStars,
                   (SELECT error FROM stats WHERE username = ? AND exercise = s.exercise
                    GROUP BY error ORDER BY COUNT(*) DESC LIMIT 1) as commonError
            FROM stats s
            WHERE username = ?
            GROUP BY exercise
        """, arrayOf(username, username))

        val result = mutableListOf<WorkoutStat>()
        while (cursor.moveToNext()) {
            result.add(
                WorkoutStat(
                    exerciseName = cursor.getString(0),
                    totalReps = cursor.getInt(1),
                    avgStars = cursor.getDouble(2),
                    commonError = cursor.getString(3) ?: "None"
                )
            )
        }
        cursor.close()
        return result
    }
}

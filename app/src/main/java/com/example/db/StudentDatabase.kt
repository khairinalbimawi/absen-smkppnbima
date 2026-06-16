package com.example.db

import android.content.Context
import androidx.room.*
import com.example.model.AttendanceLog
import com.example.model.Student
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Query("SELECT * FROM students ORDER BY name ASC")
    fun getAllStudents(): Flow<List<Student>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: Student)

    @Delete
    suspend fun deleteStudent(student: Student)

    @Query("DELETE FROM students")
    suspend fun clearAllStudents()
}

@Dao
interface AttendanceLogDao {
    @Query("SELECT * FROM attendance_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<AttendanceLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: AttendanceLog): Long

    @Query("DELETE FROM attendance_logs")
    suspend fun clearLogs()
}

@Database(entities = [Student::class, AttendanceLog::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun studentDao(): StudentDao
    abstract fun attendanceLogDao(): AttendanceLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "siäksi_bima_db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class StudentRepository(private val db: AppDatabase) {
    val allStudents: Flow<List<Student>> = db.studentDao().getAllStudents()
    val allLogs: Flow<List<AttendanceLog>> = db.attendanceLogDao().getAllLogs()

    suspend fun insertStudent(student: Student) {
        db.studentDao().insertStudent(student)
    }

    suspend fun deleteStudent(student: Student) {
        db.studentDao().deleteStudent(student)
    }

    suspend fun insertLog(log: AttendanceLog): Long {
        return db.attendanceLogDao().insertLog(log)
    }

    suspend fun clearLogs() {
        db.attendanceLogDao().clearLogs()
    }

    suspend fun clearAllStudents() {
        db.studentDao().clearAllStudents()
    }
}

package com.example.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "students")
data class Student(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val studentNo: String,      // NIS siswa (e.g., "12045")
    val name: String,           // Nama Lengkap Siswa
    val studentClass: String,   // Kelas (e.g., "X-ATPH", "XI-ATU", "XII-APHP")
    val department: String,     // Jurusan (e.g., "Agribisnis Tanaman Pangan", "Ternak")
    val embedding: String       // Serat vektor wajah dipisah koma, e.g., "0.012,-0.123,..."
) {
    // Convienence to convert String embedding back to FloatArray
    fun getEmbeddingArray(): FloatArray {
        if (embedding.isBlank()) return FloatArray(0)
        return try {
            embedding.split(",").map { it.toFloat() }.toFloatArray()
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    companion object {
        fun createEmbeddingString(array: FloatArray): String {
            return array.joinToString(",")
        }
    }
}

@Entity(tableName = "attendance_logs")
data class AttendanceLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val studentNo: String,
    val name: String,
    val studentClass: String,
    val department: String,
    val status: String,          // "TERKIRIM" (Synced to GAS) / "GAGAL" (Failed) / "PENDING"
    val syncErrorMessage: String? = null,
    val photoBase64: String? = null, // Compressed Base64 String as fallback
    val logType: String = "MASUK" // "MASUK" atau "PULANG"
)

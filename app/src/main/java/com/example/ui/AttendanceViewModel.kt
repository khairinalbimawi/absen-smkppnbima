package com.example.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.db.AppDatabase
import com.example.db.StudentRepository
import com.example.engine.FaceRecognitionEngine
import com.example.model.AttendanceLog
import com.example.model.Student
import com.example.network.AttendanceApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed class DetectionState {
    object NoFace : DetectionState()
    data class FaceDetected(
        val originalFrame: Bitmap,
        val croppedFace: Bitmap,
        val boundingBox: Rect,
        val matchScore: Float,
        val matchedStudent: Student?,
        val embedding: FloatArray
    ) : DetectionState()
}

class AttendanceViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val prefs = context.getSharedPreferences("siaksi_prefs", Context.MODE_PRIVATE)

    // Database & Repository
    private val database = AppDatabase.getDatabase(context)
    val repository = StudentRepository(database)

    // APIs & Engine
    private val mApi = AttendanceApiService()
    val faceEngine = FaceRecognitionEngine(context)

    // Exposed States
    val registeredStudents: StateFlow<List<Student>> = repository.allStudents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val attendanceLogs: StateFlow<List<AttendanceLog>> = repository.allLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _detectionState = MutableStateFlow<DetectionState>(DetectionState.NoFace)
    val detectionState: StateFlow<DetectionState> = _detectionState.asStateFlow()

    private val _gasUrl = MutableStateFlow(
        prefs.getString("gas_url", "https://script.google.com/macros/s/AKfycbx9pj3qCLlJKb4J_jfhpAd2MEWVOh0QgocGCmaFH8QrRlqmC7W8dchhy34mBIO-QYSw/exec")?.let {
            if (it == "https://script.google.com/macros/s/AKfycbz_XXXXXXXXXXXXXX/exec" || it.isBlank()) {
                "https://script.google.com/macros/s/AKfycbx9pj3qCLlJKb4J_jfhpAd2MEWVOh0QgocGCmaFH8QrRlqmC7W8dchhy34mBIO-QYSw/exec".also { newUrl ->
                    prefs.edit().putString("gas_url", newUrl).apply()
                }
            } else {
                it
            }
        } ?: "https://script.google.com/macros/s/AKfycbx9pj3qCLlJKb4J_jfhpAd2MEWVOh0QgocGCmaFH8QrRlqmC7W8dchhy34mBIO-QYSw/exec"
    )
    val gasUrl: StateFlow<String> = _gasUrl.asStateFlow()

    private val _threshold = MutableStateFlow(prefs.getFloat("threshold", 0.75f))
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private val _lastStatusMessage = MutableStateFlow<String?>(null)
    val lastStatusMessage: StateFlow<String?> = _lastStatusMessage.asStateFlow()

    // Anti-double-record cooldown logic
    private val lastLoggedStudentTime = HashMap<String, Long>() // Map studentNo -> last log timestamp
    private val COOLDOWN_MS = 15000L // 15 seconds cooldown for the same student

    init {
        // Pre-populate database with some agricultural & vocational majors at Bima if empty
        viewModelScope.launch {
            registeredStudents.collect { students ->
                if (students.isEmpty()) {
                    prepopulateMockStudents()
                }
            }
        }
    }

    fun updateGasUrl(newUrl: String) {
        _gasUrl.value = newUrl
        prefs.edit().putString("gas_url", newUrl).apply()
    }

    fun updateThreshold(newVal: Float) {
        _threshold.value = newVal
        prefs.edit().putFloat("threshold", newVal).apply()
    }

    /**
     * Set details on face recognition results frame-by-frame
     */
    fun processFaceDetection(
        originalFrame: Bitmap,
        croppedFace: Bitmap,
        boundingBox: Rect,
        embedding: FloatArray
    ) {
        viewModelScope.launch(Dispatchers.Default) {
            val students = registeredStudents.value
            var bestScore = 0f
            var bestMatch: Student? = null

            // Find closest local student profile using Cosine Similarity
            for (student in students) {
                val score = faceEngine.calculateCosineSimilarity(embedding, student.getEmbeddingArray())
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = student
                }
            }

            val thresholdVal = _threshold.value
            val match = if (bestScore >= thresholdVal) bestMatch else null

            _detectionState.value = DetectionState.FaceDetected(
                originalFrame = originalFrame,
                croppedFace = croppedFace,
                boundingBox = boundingBox,
                matchScore = bestScore,
                matchedStudent = match,
                embedding = embedding
            )

            // Automate attendance logging if matching matches a real student and cooldown has cleared
            if (match != null) {
                val now = System.currentTimeMillis()
                val lastLogged = lastLoggedStudentTime[match.studentNo] ?: 0L
                if (now - lastLogged > COOLDOWN_MS) {
                    // Lock student temporarily to avoid double submission
                    lastLoggedStudentTime[match.studentNo] = now
                    
                    // Trigger attendance submission asynchronously
                    submitAttendanceLocalAndRemote(match, croppedFace)
                }
            }
        }
    }

    fun setNoFace() {
        _detectionState.value = DetectionState.NoFace
    }

    /**
     * Submit attendance record
     * 1. Capture Face Snapshot Frame
     * 2. Save locally as "PENDING"
     * 3. Send OkHttp payload to Google Apps Script
     * 4. Update local log as "TERKIRIM" or "GAGAL"
     */
    fun submitAttendanceLocalAndRemote(student: Student, faceBitmap: Bitmap) {
        viewModelScope.launch {
            _isSending.value = true
            _lastStatusMessage.value = "Memproses presensi ${student.name}..."

            val now = System.currentTimeMillis()
            var base64String = ""
            
            // Compress bitmap in background IO
            withContext(Dispatchers.IO) {
                try {
                    val baos = ByteArrayOutputStream()
                    // Compress face crop to 75% quality JPEG
                    faceBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                    val bytes = baos.toByteArray()
                    base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.e("AttendanceViewModel", "Bitmap encoding failed", e)
                }
            }

            // Save initial status locally
            val log = AttendanceLog(
                studentNo = student.studentNo,
                name = student.name,
                studentClass = student.studentClass,
                department = student.department,
                status = "PENDING",
                photoBase64 = base64String,
                timestamp = now
            )
            val logId = repository.insertLog(log)
            val finalLogTemplate = log.copy(id = logId.toInt())

            // Upload to Google Apps Script Web App
            val apiResult = mApi.sendAttendance(
                gasUrl = _gasUrl.value,
                studentNo = student.studentNo,
                name = student.name,
                studentClass = student.studentClass,
                department = student.department,
                photoBase64 = base64String
            )

            apiResult.onSuccess { response ->
                _lastStatusMessage.value = "Presensi ${student.name} berhasil terikirim!"
                repository.insertLog(finalLogTemplate.copy(status = "TERKIRIM", syncErrorMessage = "Res: $response"))
            }.onFailure { error ->
                _lastStatusMessage.value = "Gagal kirim ke GAS: ${error.localizedMessage}"
                repository.insertLog(finalLogTemplate.copy(status = "GAGAL", syncErrorMessage = error.message))
                
                // Since this was a temporary sync failure, let's reset cooldown so we can try again
                lastLoggedStudentTime.remove(student.studentNo)
            }
            _isSending.value = false
        }
    }

    fun enrollStudent(no: String, name: String, sClass: String, dept: String, embedding: FloatArray) {
        viewModelScope.launch {
            val embeddingStr = Student.createEmbeddingString(embedding)
            val newStudent = Student(
                studentNo = no,
                name = name,
                studentClass = sClass,
                department = dept,
                embedding = embeddingStr
            )
            repository.insertStudent(newStudent)
            _lastStatusMessage.value = "Siswa ${name} berhasil didaftarkan secara lokal!"
        }
    }

    fun removeStudent(student: Student) {
        viewModelScope.launch {
            repository.deleteStudent(student)
            _lastStatusMessage.value = "Siswa ${student.name} dihapus."
        }
    }

    fun clearAllLogs() {
        viewModelScope.launch {
            repository.clearLogs()
            _lastStatusMessage.value = "Log presensi lokal berhasil dibersihkan."
        }
    }

    fun clearStatus() {
        _lastStatusMessage.value = null
    }

    private suspend fun prepopulateMockStudents() {
        // Prepare some realistic preset embeddings of length 192 features
        val r1 = FloatArray(192) { i -> Math.sin(i * 0.1).toFloat() * 0.3f }
        val r2 = FloatArray(192) { i -> Math.cos(i * 0.15).toFloat() * 0.4f }
        val r3 = FloatArray(192) { i -> Math.sin(i * 0.25).toFloat() * 0.5f }

        repository.insertStudent(Student(
            studentNo = "9981",
            name = "Khairin (Admin SI-AKSI)",
            studentClass = "XII-ATPH-A",
            department = "Agribisnis Tanaman Pangan & Hortikultura",
            embedding = Student.createEmbeddingString(r1)
        ))

        repository.insertStudent(Student(
            studentNo = "3421",
            name = "Muhammad Fadli",
            studentClass = "XI-ATU",
            department = "Agribisnis Ternak Unggas",
            embedding = Student.createEmbeddingString(r2)
        ))

        repository.insertStudent(Student(
            studentNo = "5672",
            name = "Siti Rahma",
            studentClass = "X-APHP",
            department = "Agribisnis Pengolahan Hasil Pertanian",
            embedding = Student.createEmbeddingString(r3)
        ))
    }

    override fun onCleared() {
        super.onCleared()
        faceEngine.close()
    }
}

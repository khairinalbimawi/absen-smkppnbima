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

    private val _isSyncingStudents = MutableStateFlow(false)
    val isSyncingStudents: StateFlow<Boolean> = _isSyncingStudents.asStateFlow()

    private val _lastStatusMessage = MutableStateFlow<String?>(null)
    val lastStatusMessage: StateFlow<String?> = _lastStatusMessage.asStateFlow()

    // Dynamic Time-Based Schedule Settings
    private val _absenMasukStart = MutableStateFlow(prefs.getString("absen_masuk_start", "06:00") ?: "06:00")
    val absenMasukStart: StateFlow<String> = _absenMasukStart.asStateFlow()

    private val _absenMasukEnd = MutableStateFlow(prefs.getString("absen_masuk_end", "10:00") ?: "10:00")
    val absenMasukEnd: StateFlow<String> = _absenMasukEnd.asStateFlow()

    private val _absenPulangStart = MutableStateFlow(prefs.getString("absen_pulang_start", "14:00") ?: "14:00")
    val absenPulangStart: StateFlow<String> = _absenPulangStart.asStateFlow()

    private val _absenPulangEnd = MutableStateFlow(prefs.getString("absen_pulang_end", "19:00") ?: "19:00")
    val absenPulangEnd: StateFlow<String> = _absenPulangEnd.asStateFlow()

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

    fun updateSchedules(masukStart: String, masukEnd: String, pulangStart: String, pulangEnd: String) {
        _absenMasukStart.value = masukStart
        _absenMasukEnd.value = masukEnd
        _absenPulangStart.value = pulangStart
        _absenPulangEnd.value = pulangEnd
        
        prefs.edit()
            .putString("absen_masuk_start", masukStart)
            .putString("absen_masuk_end", masukEnd)
            .putString("absen_pulang_start", pulangStart)
            .putString("absen_pulang_end", pulangEnd)
            .apply()
        _lastStatusMessage.value = "Pengaturan jadwal presensi disimpan!"
    }

    fun getAttendanceTypeForCurrentTime(): String {
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        val currentStr = format.format(Date())
        
        val startM = _absenMasukStart.value
        val endM = _absenMasukEnd.value
        val startP = _absenPulangStart.value
        val endP = _absenPulangEnd.value
        
        return when {
            isTimeInRange(currentStr, startM, endM) -> "MASUK"
            isTimeInRange(currentStr, startP, endP) -> "PULANG"
            else -> "DILUAR_JADWAL"
        }
    }

    private fun isTimeInRange(current: String, start: String, end: String): Boolean {
        return try {
            val format = SimpleDateFormat("HH:mm", Locale.getDefault())
            val curr = format.parse(current)
            val st = format.parse(start)
            val en = format.parse(end)
            curr != null && st != null && en != null && !curr.before(st) && !curr.after(en)
        } catch (e: Exception) {
            false
        }
    }

    fun hasStudentAlreadyLoggedToday(studentNo: String, type: String): Boolean {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val logsList = attendanceLogs.value
        return logsList.any { log ->
            val logDateStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(log.timestamp))
            log.studentNo == studentNo && log.logType == type && logDateStr == todayStr
        }
    }

    fun syncStudentsFromGas() {
        viewModelScope.launch {
            _isSyncingStudents.value = true
            _lastStatusMessage.value = "Menghubungi server GAS untuk sinkronisasi..."
            val result = mApi.fetchStudentDatabase(_gasUrl.value)
            result.onSuccess { studentList ->
                if (studentList.isEmpty()) {
                    _lastStatusMessage.value = "Selesai: Tidak ada data siswa yang ditemukan."
                } else {
                    withContext(Dispatchers.IO) {
                        repository.clearAllStudents()
                        for (student in studentList) {
                            repository.insertStudent(student)
                        }
                    }
                    _lastStatusMessage.value = "Berhasil menyinkronkan ${studentList.size} siswa dari Google Sheets!"
                }
            }.onFailure { error ->
                _lastStatusMessage.value = "Gagal Sinkronisasi: ${error.localizedMessage}"
            }
            _isSyncingStudents.value = false
        }
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
                    val logType = getAttendanceTypeForCurrentTime()
                    if (logType == "DILUAR_JADWAL") {
                        if (now - lastLogged > 30000L) { // Limit notifications to avoid continuous voice/text nagging
                            lastLoggedStudentTime[match.studentNo] = now
                            _lastStatusMessage.value = "Ditolak: Presensi saat ini di luar jadwal absensi."
                        }
                    } else {
                        if (hasStudentAlreadyLoggedToday(match.studentNo, logType)) {
                            if (now - lastLogged > 30000L) {
                                lastLoggedStudentTime[match.studentNo] = now
                                _lastStatusMessage.value = "${match.name} sudah absen $logType hari ini!"
                            }
                        } else {
                            // Lock student temporarily to avoid double submission
                            lastLoggedStudentTime[match.studentNo] = now
                            
                            // Trigger attendance submission asynchronously
                            submitAttendanceLocalAndRemote(match, croppedFace, logType)
                        }
                    }
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
    fun submitAttendanceLocalAndRemote(student: Student, faceBitmap: Bitmap, forcedType: String? = null) {
        viewModelScope.launch {
            val logType = forcedType ?: getAttendanceTypeForCurrentTime()
            if (logType == "DILUAR_JADWAL") {
                _lastStatusMessage.value = "Presensi ditolak: Di luar jadwal absensi harian!"
                return@launch
            }
            if (hasStudentAlreadyLoggedToday(student.studentNo, logType)) {
                _lastStatusMessage.value = "${student.name} sudah melakukan absensi $logType hari ini!"
                return@launch
            }

            _isSending.value = true
            _lastStatusMessage.value = "Memproses absensi $logType ${student.name}..."

            val now = System.currentTimeMillis()
            var base64String = ""
            
            // Compress bitmap in background IO
            withContext(Dispatchers.IO) {
                try {
                    val baos = ByteArrayOutputStream()
                    // Compress face crop to 75% quality JPEG
                    if (faceBitmap.isRecycled) {
                        Log.e("AttendanceViewModel", "Bitmap is already recycled!")
                    } else {
                        faceBitmap.compress(Bitmap.CompressFormat.JPEG, 75, baos)
                        val bytes = baos.toByteArray()
                        base64String = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    }
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
                timestamp = now,
                logType = logType
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
                photoBase64 = base64String,
                logType = logType
            )

            apiResult.onSuccess { response ->
                _lastStatusMessage.value = "Absensi $logType ${student.name} terkirim!"
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
            _lastStatusMessage.value = "Siswa ${name} didaftarkan lokal!"

            if (_gasUrl.value.isNotBlank()) {
                _lastStatusMessage.value = "Menyinkronkan ${name} ke Google Sheets..."
                val result = mApi.registerStudent(
                    gasUrl = _gasUrl.value,
                    studentNo = no,
                    name = name,
                    studentClass = sClass,
                    department = dept,
                    embeddingStr = embeddingStr
                )
                result.onSuccess {
                    _lastStatusMessage.value = "Siswa ${name} terdaftar & sinkron!"
                }.onFailure { err ->
                    _lastStatusMessage.value = "Terdaftar lokal, gagal ke GAS: ${err.localizedMessage}"
                }
            } else {
                _lastStatusMessage.value = "Siswa ${name} terdaftar secara lokal!"
            }
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

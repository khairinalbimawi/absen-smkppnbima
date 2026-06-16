package com.example.network

import android.util.Log
import com.example.model.Student
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AttendanceApiService {

    // Optimized client with generous timeouts for uploading large base64 images
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true) // Crucial for Google Apps Script redirects!
        .followSslRedirects(true)
        .build()

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val TAG = "AttendanceApiService"
    }

    /**
     * Sends the attendance log to Google Apps Script Web App.
     * Running asynchronously under Dispatchers.IO.
     */
    suspend fun sendAttendance(
        gasUrl: String,
        studentNo: String,
        name: String,
        studentClass: String,
        department: String,
        photoBase64: String,
        logType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (gasUrl.isBlank()) {
            return@withContext Result.failure(Exception("URL Google Apps Script kosong. Konfigurasikan URL di Pengaturan."))
        }

        try {
            // Build requested payload
            val json = JSONObject().apply {
                put("no", studentNo)
                put("namaSiswa", name)
                put("kelas", studentClass)
                put("jurusan", department)
                put("fotoBase64", photoBase64)
                put("tipe", logType) // MASUK atau PULANG
            }

            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(gasUrl)
                .post(body)
                .build()

            Log.d(TAG, "Sending attendance payload to GAS for $name ($logType)...")
            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val responseBodyStr = response.body?.string() ?: ""
                Log.d(TAG, "GAS response code: $responseCode, content: $responseBodyStr")

                if (response.isSuccessful || responseCode == 302 || responseCode == 200) {
                    Result.success(responseBodyStr)
                } else {
                    Result.failure(Exception("GAS Server Error ($responseCode): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failure to GAS", e)
            Result.failure(e)
        }
    }

    /**
     * Gets the registered student database containing face embeddings from GAS.
     */
    suspend fun fetchStudentDatabase(gasUrl: String): Result<List<Student>> = withContext(Dispatchers.IO) {
        if (gasUrl.isBlank()) {
            return@withContext Result.failure(Exception("URL Google Apps Script kosong. Konfigurasikan URL di Pengaturan."))
        }

        try {
            val urlWithParam = if (gasUrl.contains("?")) "$gasUrl&action=get_students" else "$gasUrl?action=get_students"
            Log.d(TAG, "Fetching students from: $urlWithParam")
            val request = Request.Builder()
                .url(urlWithParam)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val responseBodyStr = response.body?.string() ?: ""
                Log.d(TAG, "GAS database fetch response: $responseCode")

                if (response.isSuccessful || responseCode == 302 || responseCode == 200) {
                    val studentsList = mutableListOf<Student>()
                    
                    val jsonArray = if (responseBodyStr.trim().startsWith("[")) {
                        org.json.JSONArray(responseBodyStr)
                    } else {
                        val obj = JSONObject(responseBodyStr)
                        when {
                            obj.has("students") -> obj.getJSONArray("students")
                            obj.has("data") -> obj.getJSONArray("data")
                            else -> throw Exception("No recognizable JSON array matches 'data' or 'students'")
                        }
                    }

                    for (i in 0 until jsonArray.length()) {
                        val sObj = jsonArray.getJSONObject(i)
                        val no = sObj.optString("studentNo", sObj.optString("no", sObj.optString("nis", "")))
                        val nameStr = sObj.optString("name", sObj.optString("namaSiswa", sObj.optString("nama", "")))
                        val sClass = sObj.optString("studentClass", sObj.optString("kelas", sObj.optString("class", "")))
                        val dept = sObj.optString("department", sObj.optString("jurusan", ""))
                        
                        val embeddingObj = sObj.opt("embedding") ?: sObj.opt("vector") ?: sObj.opt("face_embedding") ?: ""
                        val embeddingStr = when (embeddingObj) {
                            is org.json.JSONArray -> {
                                val sb = StringBuilder()
                                for (j in 0 until embeddingObj.length()) {
                                    sb.append(embeddingObj.get(j).toString())
                                    if (j < embeddingObj.length() - 1) sb.append(",")
                                }
                                sb.toString()
                            }
                            else -> embeddingObj.toString()
                        }

                        if (no.isNotBlank() && nameStr.isNotBlank() && embeddingStr.isNotBlank()) {
                            studentsList.add(
                                Student(
                                    studentNo = no,
                                    name = nameStr,
                                    studentClass = sClass,
                                    department = dept,
                                    embedding = embeddingStr
                                )
                            )
                        }
                    }
                    Result.success(studentsList)
                } else {
                    Result.failure(Exception("GAS Server Error ($responseCode): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failure to GAS during database pull", e)
            Result.failure(e)
        }
    }

    /**
     * Registers a new student's face embedding to Google Apps Script.
     */
    suspend fun registerStudent(
        gasUrl: String,
        studentNo: String,
        name: String,
        studentClass: String,
        department: String,
        embeddingStr: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (gasUrl.isBlank()) {
            return@withContext Result.failure(Exception("URL Google Apps Script kosong."))
        }

        try {
            val json = JSONObject().apply {
                put("action", "register_student")
                put("no", studentNo)
                put("namaSiswa", name)
                put("kelas", studentClass)
                put("jurusan", department)
                put("embedding", embeddingStr)
            }

            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(gasUrl)
                .post(body)
                .build()

            Log.d(TAG, "Registering student on GAS for $name...")
            client.newCall(request).execute().use { response ->
                val responseCode = response.code
                val responseBodyStr = response.body?.string() ?: ""
                Log.d(TAG, "GAS registration response code: $responseCode, content: $responseBodyStr")

                if (response.isSuccessful || responseCode == 302 || responseCode == 200) {
                    Result.success(responseBodyStr)
                } else {
                    Result.failure(Exception("GAS Server Error ($responseCode): ${response.message}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failure to GAS during registration", e)
            Result.failure(e)
        }
    }
}

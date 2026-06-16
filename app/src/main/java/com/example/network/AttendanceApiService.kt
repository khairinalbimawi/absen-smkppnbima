package com.example.network

import android.util.Log
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
        photoBase64: String
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
            }

            val body = json.toString().toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url(gasUrl)
                .post(body)
                .build()

            Log.d(TAG, "Sending attendance payload to GAS for $name...")
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
}

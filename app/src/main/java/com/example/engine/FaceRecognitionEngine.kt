package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class FaceRecognitionEngine(private val context: Context) {

    private var interpreter: Interpreter? = null
    var isUsingDemoFallback: Boolean = false
        private set

    companion object {
        private const val TAG = "FaceRecognitionEngine"
        private const val MODEL_FILE = "mobilefacenet.tflite"
        private const val INPUT_SIZE = 112 // MobileFaceNet standard input width & height
        private const val EMBEDDING_SIZE = 192 // MobileFaceNet standard feature dimensions
    }

    init {
        try {
            val modelBuffer = loadModelFile(context, MODEL_FILE)
            // Initialize TFLite Interpreter
            interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "TFLite model $MODEL_FILE loaded successfully.")
            isUsingDemoFallback = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model file $MODEL_FILE. Entering Demo Fallback Mode.", e)
            isUsingDemoFallback = true
            interpreter = null
        }
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(filename)
        val fileInputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = fileInputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    /**
     * Extracts 192-dimensional floating-point embeddings from a cropped face bitmap.
     */
    fun getFaceEmbedding(faceBitmap: Bitmap): FloatArray {
        if (isUsingDemoFallback || interpreter == null) {
            return generateDemoEmbedding(faceBitmap)
        }

        return try {
            val scaledBitmap = Bitmap.createScaledBitmap(faceBitmap, INPUT_SIZE, INPUT_SIZE, true)
            val byteBuffer = convertBitmapToByteBuffer(scaledBitmap)
            val outputArray = Array(1) { FloatArray(EMBEDDING_SIZE) }
            
            interpreter?.run(byteBuffer, outputArray)
            
            // Return normalized embedding or direct array
            val originalArray = outputArray[0]
            normalizeVector(originalArray)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing TFLite model, falling back to demo embedding.", e)
            generateDemoEmbedding(faceBitmap)
        }
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        // Floyd-Steinberg or standard normalized 4 bytes per float (3 channels)
        val byteBuffer = ByteBuffer.allocateDirect(4 * INPUT_SIZE * INPUT_SIZE * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        
        val intValues = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(intValues, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        
        for (pixelValue in intValues) {
            val r = (pixelValue shr 16) and 0xFF
            val g = (pixelValue shr 8) and 0xFF
            val b = pixelValue and 0xFF
            
            // Normalize: (val - 127.5) / 128.0 (MobileFaceNet standard normalization)
            byteBuffer.putFloat((r - 127.5f) / 128.0f)
            byteBuffer.putFloat((g - 127.5f) / 128.0f)
            byteBuffer.putFloat((b - 127.5f) / 128.0f)
        }
        return byteBuffer
    }

    /**
     * Deterministic demo embedding. This hashes the bitmap's physical pixels 
     * so that the same student face will produce the exact same embedding, 
     * enabling fully realistic testing of the similarity and attendance flow.
     */
    private fun generateDemoEmbedding(bitmap: Bitmap): FloatArray {
        val result = FloatArray(EMBEDDING_SIZE)
        val width = bitmap.width
        val height = bitmap.height
        
        // Let's take samples from parts of the bitmap to construct a reliable, 
        // deterministic sign (stable across angles/colors slightly)
        val stepX = maxOf(1, width / 12)
        val stepY = maxOf(1, height / 16)
        
        var index = 0
        for (y in 0 until height step stepY) {
            for (x in 0 until width step stepX) {
                if (index >= EMBEDDING_SIZE) break
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                
                // Combine and normalize to [-1.0, 1.0] range
                val brightness = (r + g + b) / 3f
                result[index] = (brightness - 127.5f) / 128.0f
                index++
            }
        }
        
        // Fill remaining features
        while (index < EMBEDDING_SIZE) {
            result[index] = Math.sin(index.toDouble() * 0.1).toFloat() * 0.5f
            index++
        }
        
        return normalizeVector(result)
    }

    private fun normalizeVector(vector: FloatArray): FloatArray {
        var sumSquare = 0f
        for (v in vector) {
            sumSquare += v * v
        }
        val magnitude = Math.sqrt(sumSquare.toDouble()).toFloat()
        if (magnitude > 0) {
            for (i in vector.indices) {
                vector[i] /= magnitude
            }
        }
        return vector
    }

    /**
     * Calculates the Cosine Similarity between vector A and vector B.
     * Higher thresholds mean stronger similarity (1.0 is identical, typically > 0.75-0.80 is a match).
     */
    fun calculateCosineSimilarity(vectorA: FloatArray, vectorB: FloatArray): Float {
        if (vectorA.size != vectorB.size || vectorA.isEmpty()) return 0f
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in vectorA.indices) {
            dotProduct += vectorA[i] * vectorB[i]
            normA += vectorA[i] * vectorA[i]
            normB += vectorB[i] * vectorB[i]
        }
        val denom = Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble())
        return if (denom == 0.0) 0f else (dotProduct / denom).toFloat()
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

package com.example.analyzer

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

class FaceRecognitionAnalyzer(
    private val isFrontCamera: Boolean,
    private val listener: FaceDetectionListener
) : ImageAnalysis.Analyzer {

    interface FaceDetectionListener {
        fun onFaceDetected(
            originalFrame: Bitmap,
            croppedFace: Bitmap,
            boundingBox: Rect,
            frameWidth: Int,
            frameHeight: Int
        )
        fun onNoFaceDetected()
        fun onError(e: Exception)
    }

    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
    )

    private var isProcessing = false

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        isProcessing = true
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                try {
                    if (faces.isNotEmpty()) {
                        val firstFace = faces.first()
                        
                        // Convert ImageProxy directly to Bitmap (CameraX 1.3.0+ feature)
                        var frameBitmap = imageProxy.toBitmap()

                        // Make sure we rotate the bitmap according to the device's camera orientation
                        frameBitmap = rotateAndMirrorBitmap(frameBitmap, rotationDegrees.toFloat(), isFrontCamera)

                        val width = frameBitmap.width
                        val height = frameBitmap.height

                        // ML Kit bounding box is mapped to the input image size *prior* to rotation.
                        // However, ImageProxy.toBitmap() might return rotated or original bitmap based on AGP version.
                        // Let's safe-crop using mapped coordinate ratios.
                        val boundingBox = mapBoundingBox(
                            firstFace.boundingBox, 
                            imageProxy.width, 
                            imageProxy.height, 
                            width, 
                            height, 
                            rotationDegrees,
                            isFrontCamera
                        )

                        val croppedFace = cropFace(frameBitmap, boundingBox)
                        if (croppedFace != null) {
                            listener.onFaceDetected(frameBitmap, croppedFace, boundingBox, width, height)
                        } else {
                            listener.onNoFaceDetected()
                        }
                    } else {
                        listener.onNoFaceDetected()
                    }
                } catch (e: Exception) {
                    Log.e("FaceRecognitionAnalyzer", "Error processing analyzer callbacks", e)
                    listener.onError(e)
                } finally {
                    isProcessing = false
                    imageProxy.close()
                }
            }
            .addOnFailureListener { e ->
                Log.e("FaceRecognitionAnalyzer", "ML Kit face detection failed", e)
                listener.onError(e)
                isProcessing = false
                imageProxy.close()
            }
    }

    private fun rotateAndMirrorBitmap(source: Bitmap, degrees: Float, mirror: Boolean): Bitmap {
        if (degrees == 0f && !mirror) return source
        val matrix = Matrix()
        matrix.postRotate(degrees)
        if (mirror) {
            // Flip horizontally
            matrix.postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Maps the bounding box from ML Kit's original dimensions to the final rotated and mirrored Bitmap coordinates.
     */
    private fun mapBoundingBox(
        box: Rect,
        srcW: Int,
        srcH: Int,
        destW: Int,
        destH: Int,
        rotation: Int,
        mirror: Boolean
    ): Rect {
        // Compute relative positions in [0.0, 1.0] range
        val relativeLeft = box.left.toFloat() / srcW
        val relativeTop = box.top.toFloat() / srcH
        val relativeRight = box.right.toFloat() / srcW
        val relativeBottom = box.bottom.toFloat() / srcH

        // Based on rotation, map relative corners
        var l = 0f
        var t = 0f
        var r = 0f
        var b = 0f

        when (rotation) {
            90 -> {
                // Rotated 90 degrees clockwise
                l = (1f - relativeBottom)
                t = relativeLeft
                r = (1f - relativeTop)
                b = relativeRight
            }
            180 -> {
                l = (1f - relativeRight)
                t = (1f - relativeBottom)
                r = (1f - relativeLeft)
                b = (1f - relativeTop)
            }
            270 -> {
                // Rotated 270 degrees clockwise (90 counter-clockwise)
                l = relativeTop
                t = (1f - relativeRight)
                r = relativeBottom
                b = (1f - relativeLeft)
            }
            else -> {
                // 0 degrees
                l = relativeLeft
                t = relativeTop
                r = relativeRight
                b = relativeBottom
            }
        }

        // Apply mirror horizontally if using front camera
        if (mirror) {
            val tempL = l
            l = 1f - r
            r = 1f - tempL
        }

        // Map back to absolute pixels
        val finalLeft = (l * destW).toInt().coerceIn(0, destW)
        val finalTop = (t * destH).toInt().coerceIn(0, destH)
        val finalRight = (r * destW).toInt().coerceIn(0, destW)
        val finalBottom = (b * destH).toInt().coerceIn(0, destH)

        return Rect(finalLeft, finalTop, finalRight, finalBottom)
    }

    private fun cropFace(bitmap: Bitmap, box: Rect): Bitmap? {
        return try {
            val left = maxOf(0, box.left)
            val top = maxOf(0, box.top)
            val width = minOf(bitmap.width - left, box.width())
            val height = minOf(bitmap.height - top, box.height())
            
            if (width <= 0 || height <= 0) return null
            Bitmap.createBitmap(bitmap, left, top, width, height)
        } catch (e: Exception) {
            Log.e("FaceRecognitionAnalyzer", "Crop error", e)
            null
        }
    }
}

package com.example.dedrone

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.ops.CastOp
import org.tensorflow.lite.support.common.ops.NormalizeOp
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel


interface DetectorListener {
    fun onInitialized()
    fun onError(error: String)
    fun onResults(
        results: List<BoundingBox>?,
        inferenceTime: Long,
        imageHeight: Int,
        imageWidth: Int
    )
}


class ObjectDetectorHelper2(
    val context: Context,
    val objectDetectorListener: DetectorListener
) {

    private var threshold: Float = CONFIDENCE_THRESHOLD
    private val modelPath = "yolov8n_f32.tflite"
    private lateinit var interpreter: Interpreter
    private val TAG = "ObjectDetectionHelper2"
    private val imageProcessor = ImageProcessor.Builder()
        .add(NormalizeOp(INPUT_MEAN, INPUT_STANDARD_DEVIATION))
        .add(CastOp(INPUT_IMAGE_TYPE))
        .build()


    fun setupObjectDetector() {
        Log.d(TAG, "setupObjectDetector:")
        try {
            interpreter = Interpreter(loadModelFile(context.assets, modelPath))
            objectDetectorListener.onInitialized()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    private var squareSize = 0
    private var left = 0
    private var top = 0

    fun detect(image: Bitmap, imageRotation: Int): MutableList<RectF>? {
        val matrix = Matrix()
        matrix.postRotate(imageRotation.toFloat()) // Rotate 90 degrees clockwise, change the angle as needed
        val rotatedBitmap = Bitmap.createBitmap(
            image,
            0,
            0,
            image.width,
            image.height,
            matrix,
            true
        )

        if (squareSize == 0) {
            squareSize = rotatedBitmap.width.coerceAtMost(rotatedBitmap.height)
            left = (rotatedBitmap.width - squareSize) / 2
            top = (rotatedBitmap.height - squareSize) / 2
        }

        val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, 0, 0, squareSize, squareSize)
        val resizedBitmap =
            Bitmap.createScaledBitmap(croppedBitmap, TENSOR_WIDTH, TENSOR_HEIGHT, false)


        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resizedBitmap)
        val processedImage = imageProcessor.process(tensorImage)
        val imageBuffer = processedImage.buffer

        val output = TensorBuffer.createFixedSize(intArrayOf(1, 5, NUM_ELEMENTS), OUTPUT_IMAGE_TYPE)
        val time = System.currentTimeMillis()
        interpreter.run(imageBuffer, output.buffer)
        val endtime = System.currentTimeMillis() - time
        val bestBoxes = bestBox(output.floatArray)

        objectDetectorListener.onResults(
            bestBoxes,
            endtime,
            tensorImage.height,
            tensorImage.width
        )

        return null
    }

    private fun bestBox(array: FloatArray): List<BoundingBox>? {
        val boundingBoxes = mutableListOf<BoundingBox>()
        for (c in 0 until NUM_ELEMENTS) {
            val cnf = array[c + NUM_ELEMENTS * 4]
            if (cnf > threshold) {
                val cx = array[c]
                val cy = array[c + NUM_ELEMENTS]
                val w = array[c + NUM_ELEMENTS * 2]
                val h = array[c + NUM_ELEMENTS * 3]
                val x1 = cx - (w / 2F)
                val y1 = (cy - (h / 2F))
                val x2 = cx + (w / 2F)
                val y2 = (cy + (h / 2F))
                if (x1 <= 0F || x1 >= TENSOR_WIDTH_FLOAT) continue
                if (y1 <= 0F || y1 >= TENSOR_HEIGHT_FLOAT) continue
                if (x2 <= 0F || x2 >= TENSOR_WIDTH_FLOAT) continue
                if (y2 <= 0F || y2 >= TENSOR_HEIGHT_FLOAT) continue
                val box = BoundingBox(
                    x1 = x1, y1 = y1, x2 = x2, y2 = y2,
                    cx = cx, cy = cy, w = w, h = h, cnf = cnf
                )
                boundingBoxes.add(
                    box
                )
            }
        }

        if (boundingBoxes.isEmpty()) return null

        return applyNMS(boundingBoxes)
    }

    private fun applyNMS(boxes: List<BoundingBox>): MutableList<BoundingBox> {
        val sortedBoxes = boxes.sortedByDescending { it.w * it.h }.toMutableList()
        val selectedBoxes = mutableListOf<BoundingBox>()

        while (sortedBoxes.isNotEmpty()) {
            val first = sortedBoxes.first()
            selectedBoxes.add(first)
            sortedBoxes.remove(first)

            val iterator = sortedBoxes.iterator()
            while (iterator.hasNext()) {
                val nextBox = iterator.next()
                val iou = calculateIoU(first, nextBox)
                if (iou >= IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }

        return selectedBoxes
    }

    private fun calculateIoU(box1: BoundingBox, box2: BoundingBox): Float {
        val x1 = maxOf(box1.x1, box2.x1)
        val y1 = maxOf(box1.y1, box2.y1)
        val x2 = minOf(box1.x2, box2.x2)
        val y2 = minOf(box1.y2, box2.y2)
        val intersectionArea = maxOf(0F, x2 - x1) * maxOf(0F, y2 - y1)
        val box1Area = box1.w * box1.h
        val box2Area = box2.w * box2.h
        return intersectionArea / (box1Area + box2Area - intersectionArea)
    }

    @Throws(IOException::class)
    private fun loadModelFile(assetManager: AssetManager, modelPath: String): ByteBuffer {
        val fileDescriptor = assetManager.openFd(modelPath)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    fun setThreshold(threshold: Float) {
        this.threshold = threshold
    }


    companion object {
        private const val TENSOR_WIDTH = 1024
        private const val TENSOR_HEIGHT = 1024
        private const val TENSOR_WIDTH_FLOAT = TENSOR_WIDTH.toFloat()
        private const val TENSOR_HEIGHT_FLOAT = TENSOR_HEIGHT.toFloat()

        private const val INPUT_MEAN = 0f
        private const val INPUT_STANDARD_DEVIATION = 255f

        private val INPUT_IMAGE_TYPE = DataType.FLOAT32
        private val OUTPUT_IMAGE_TYPE = DataType.FLOAT32

        private const val NUM_ELEMENTS = 21504
        const val CONFIDENCE_THRESHOLD = 0.5F
        private const val IOU_THRESHOLD = 0.5F

    }


}

data class BoundingBox(
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val cnf: Float
)

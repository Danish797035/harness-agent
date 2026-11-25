package com.meenai.mina.cap.phone.a11y

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.meenai.mina.core.uistate.VisibleTextLine
import com.meenai.mina.core.uistate.normalize
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Both sets of built-in models run natively; the same frame is recognized in parallel, and the bitmap is released only after completion. If both sets of errors occur, an exception will be thrown (should not be interpreted as "no"). */
internal object PhoneOcr {
    private const val TAG = "PhoneOcr"

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    suspend fun read(bitmap: Bitmap): List<VisibleTextLine> {
        val recognizers = try { listOf(chinese, latin) } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        val image = try { InputImage.fromBitmap(bitmap, 0) } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        return suspendCancellableCoroutine { continuation ->
            val remaining = AtomicInteger(recognizers.size)
            val succeeded = AtomicInteger(0)
            val lines = Collections.synchronizedList(mutableListOf<VisibleTextLine>())

            fun finish(result: Text?) {
                if (result != null) succeeded.incrementAndGet()
                result?.textBlocks?.forEach { block -> block.lines.forEach { line ->
                    val box = line.boundingBox ?: return@forEach
                    val value = line.text.trim()
                    // Some recognition models do not give a confidence level (0) and treat it as if
                    // there is no
                    if (value.isNotEmpty()) lines += VisibleTextLine(value, listOf(
                        normalize(box.left, bitmap.width), normalize(box.top, bitmap.height),
                        normalize(box.right, bitmap.width), normalize(box.bottom, bitmap.height),
                    ), line.confidence.takeIf { it > 0f })
                } }
                if (remaining.decrementAndGet() == 0) {
                    val complete = lines.toList()
                    bitmap.recycle()
                    // Errors in both sets are not "no words on the screen": report the error truthfully,
                    // and read the screen result to write ocr: failed
                    if (continuation.isActive) {
                        if (succeeded.get() == 0) continuation.resumeWithException(IllegalStateException("text recognition failed"))
                        else continuation.resume(complete)
                    }
                }
            }

            for (recognizer in recognizers) try {
                val task: Task<Text> = recognizer.process(image)
                task.addOnCompleteListener { done ->
                    if (!done.isSuccessful) Log.w(TAG, "recognizer failed", done.exception)
                    finish(if (done.isSuccessful) done.result else null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "recognizer failed", e)
                finish(null)
            }
        }
    }
}

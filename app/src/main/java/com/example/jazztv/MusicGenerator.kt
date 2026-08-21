package com.example.jazz

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.random.Random



class MusicGenerator(
    context: Context,
    modelAssetName: String = "jazz_solo_model.tflite"
) {

    companion object {
        private const val N_VALUES = 90
        private const val N_A = 64
    }

    private val interpreter: Interpreter

    init {
        val modelBuffer = loadModelFile(context, modelAssetName)
        interpreter = Interpreter(modelBuffer, Interpreter.Options())
    }

    private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
        val afd = context.assets.openFd(assetName)
        FileInputStream(afd.fileDescriptor).use { input ->
            val channel = input.channel
            return channel.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
        }
    }


    fun generateNoteSequence(steps: Int = 50, temperature: Double): IntArray {

        var x = FloatArray(N_VALUES).also { it[Random.nextInt(N_VALUES)] = 1f }
        var a = FloatArray(N_A)
        var c = FloatArray(N_A)

        val result = IntArray(steps)

        Log.d("GeneratorNoteSequence", temperature.toString())

        for (t in 0 until steps) {
            val xInput = arrayOf(arrayOf(x)) // 1 * 90
            val aInput = arrayOf(a) // 1 * 64
            val cInput = arrayOf(c)

            val outProbs = Array(1) { FloatArray(N_VALUES) }
            val outA = Array(1) { FloatArray(N_A) }
            val outC = Array(1) { FloatArray(N_A) }

            val inputs = arrayOf<Any>(xInput, aInput, cInput)
            val outputs = mapOf(
                0 to outProbs,
                1 to outA,
                2 to outC
            )
            interpreter.runForMultipleInputsOutputs(inputs, outputs)


            val nextIdx = sampleFromProbs(outProbs[0], temperature)
            result[t] = nextIdx


            x = FloatArray(N_VALUES).also { it[nextIdx] = 1f }
            a = outA[0]
            c = outC[0]
        }

        return result
    }


    private fun sampleFromProbs(probs: FloatArray, temperature: Double): Int {

        val adjusted = DoubleArray(probs.size) { i ->
            Math.pow(probs[i].toDouble().coerceAtLeast(1e-9), 1.0 / temperature)
        }
        val sum = adjusted.sum()
        val r = Random.nextDouble() * sum
        var cumulative = 0.0
        for (i in adjusted.indices) {
            cumulative += adjusted[i]
            if (r <= cumulative) return i
        }
        return adjusted.size - 1
    }

    fun close() {
        interpreter.close()
    }
}

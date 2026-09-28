package com.noxos.triggerrouter.classifier

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

data class AutoencoderVerdict(val anomalous: Boolean, val reconstructionError: Float)

class OnDeviceAutoencoder(modelJson: String) {
    private val numericFeatures: List<String>
    private val protoCategories: List<String>
    private val numericMean: FloatArray
    private val numericStd: FloatArray
    private val reconstructionThreshold: Float
    private val encoderLayers: List<Layer>
    private val decoderTrunkLayers: List<Layer>
    private val decoderNumericHead: Layer
    private val decoderProtoHead: Layer

    private data class Layer(val weight: Array<FloatArray>, val bias: FloatArray, val activation: String)

    init {
        val root = JSONObject(modelJson)
        numericFeatures = root.getJSONArray("numeric_features").toStringList()
        protoCategories = root.getJSONArray("proto_categories").toStringList()
        numericMean = root.getJSONArray("numeric_mean").toFloatArray()
        numericStd = root.getJSONArray("numeric_std").toFloatArray()
        reconstructionThreshold = root.getDouble("reconstruction_threshold").toFloat()
        encoderLayers = root.getJSONArray("encoder").toLayerList()
        decoderTrunkLayers = root.getJSONArray("decoder_trunk").toLayerList()
        decoderNumericHead = root.getJSONObject("decoder_numeric_head").toLayer()
        decoderProtoHead = root.getJSONObject("decoder_proto_head").toLayer()
    }

    /**
     * [numericValues] must be keyed by the raw (untransformed) feature names in [numericFeatures];
     * "dst_port" is log1p-transformed before standardization, matching noxos-inference's training pipeline.
     * [rawProtocol] is the raw protocol string (e.g. "TCP"); it is bucketed to one of [protoCategories]
     * (case-insensitively), falling back to the last category ("other") when unrecognized.
     */
    fun evaluate(numericValues: Map<String, Float>, rawProtocol: String?): AutoencoderVerdict {
        val standardizedNumeric = FloatArray(numericFeatures.size) { i ->
            val name = numericFeatures[i]
            val raw = numericValues[name] ?: 0f
            val transformed = if (name == "dst_port") ln(1.0 + raw).toFloat() else raw
            val std = numericStd[i]
            if (std == 0f) transformed - numericMean[i] else (transformed - numericMean[i]) / std
        }

        val protoIndex = protoCategories.indexOfFirst { it.equals(rawProtocol, ignoreCase = true) }
            .let { if (it >= 0) it else protoCategories.size - 1 }
        val protoOneHot = FloatArray(protoCategories.size).also { it[protoIndex] = 1f }

        val input = standardizedNumeric + protoOneHot
        val bottleneck = encoderLayers.fold(input) { acc, layer -> layer.forward(acc) }
        val trunkOutput = decoderTrunkLayers.fold(bottleneck) { acc, layer -> layer.forward(acc) }

        val numericReconstruction = decoderNumericHead.forward(trunkOutput)
        val protoLogits = decoderProtoHead.forward(trunkOutput)

        val numericMse = meanSquaredError(standardizedNumeric, numericReconstruction)
        val protoCrossEntropy = crossEntropy(protoLogits, protoIndex)
        val error = numericMse + protoCrossEntropy

        return AutoencoderVerdict(anomalous = error > reconstructionThreshold, reconstructionError = error)
    }

    private fun meanSquaredError(original: FloatArray, reconstructed: FloatArray): Float {
        var sum = 0f
        for (i in original.indices) {
            val diff = original[i] - reconstructed[i]
            sum += diff * diff
        }
        return sum / original.size
    }

    private fun crossEntropy(logits: FloatArray, trueIndex: Int): Float {
        val maxLogit = logits.max()
        var sumExp = 0f
        for (l in logits) sumExp += exp((l - maxLogit).toDouble()).toFloat()
        val logSumExp = maxLogit + ln(sumExp.toDouble()).toFloat()
        return logSumExp - logits[trueIndex]
    }

    private fun Layer.forward(input: FloatArray): FloatArray {
        val output = FloatArray(bias.size)
        for (o in bias.indices) {
            var sum = bias[o]
            val row = weight[o]
            for (i in input.indices) {
                sum += row[i] * input[i]
            }
            output[o] = applyActivation(sum)
        }
        return output
    }

    private fun Layer.applyActivation(x: Float): Float = when (activation) {
        "relu" -> if (x > 0f) x else 0f
        "sigmoid" -> (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        "tanh" -> tanh(x)
        else -> x
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }

    private fun JSONArray.toLayerList(): List<Layer> = (0 until length()).map { getJSONObject(it).toLayer() }

    private fun JSONObject.toLayer(): Layer {
        val weightArr = getJSONArray("weight")
        val weight = Array(weightArr.length()) { r -> weightArr.getJSONArray(r).toFloatArray() }
        val bias = getJSONArray("bias").toFloatArray()
        val activation = optString("activation", "linear")
        return Layer(weight, bias, activation)
    }
}

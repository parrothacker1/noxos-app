package com.noxos.triggerrouter.classifier

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

data class AutoencoderVerdict(val anomalous: Boolean, val reconstructionError: Float)

class OnDeviceAutoencoder(modelJson: String) {
    val numericFeatureNames: List<String>
    val categoricalFeatureNames: List<String>
    private val categories: List<List<String>>
    private val numericMean: FloatArray
    private val numericStd: FloatArray
    private val sumNumericErrors: Boolean
    private val reconstructionThreshold: Float
    private val leakySlope: Float
    private val encoderLayers: List<Layer>
    private val decoderTrunkLayers: List<Layer>
    private val decoderNumericHead: Layer
    private val decoderCategoricalHeads: List<Layer>

    private data class Layer(val weight: Array<FloatArray>, val bias: FloatArray, val activation: String)

    init {
        val root = JSONObject(modelJson)
        numericFeatureNames = root.getJSONArray("numeric_features").toStringList()
        numericMean = root.getJSONArray("numeric_mean").toFloatArray()
        numericStd = root.getJSONArray("numeric_std").toFloatArray()

        val reduction = root.getString("numeric_reduction")
        require(reduction == "mean" || reduction == "sum") { "unsupported numeric_reduction: $reduction" }
        sumNumericErrors = reduction == "sum"

        val categoricalArr = root.getJSONArray("categorical_features")
        categoricalFeatureNames = (0 until categoricalArr.length()).map { categoricalArr.getJSONObject(it).getString("name") }
        categories = (0 until categoricalArr.length()).map { categoricalArr.getJSONObject(it).getJSONArray("categories").toStringList() }

        reconstructionThreshold = root.getDouble("reconstruction_threshold").toFloat()
        leakySlope = root.optDouble("leaky_relu_negative_slope", 0.01).toFloat()
        encoderLayers = root.getJSONArray("encoder").toLayerList()
        decoderTrunkLayers = root.getJSONArray("decoder_trunk").toLayerList()
        decoderNumericHead = root.getJSONObject("decoder_numeric_head").toLayer()

        val headsArr = root.getJSONArray("decoder_categorical_heads")
        val headsByName = (0 until headsArr.length()).associate { i ->
            val head = headsArr.getJSONObject(i)
            head.getString("name") to head.toLayer()
        }
        decoderCategoricalHeads = categoricalFeatureNames.map {
            headsByName[it] ?: throw IllegalArgumentException("no decoder head for categorical feature $it")
        }
    }

    /**
     * [numericValues] is keyed by raw (untransformed) names in [numericFeatureNames]; an absent one is
     * treated as raw 0, matching how training fills flows that lack it. "dst_port" is log1p-transformed
     * before standardization, matching noxos-inference's encoding.py. [categoricalValues] is keyed by
     * [categoricalFeatureNames]; a value is matched case-insensitively against that feature's categories
     * and falls back to the last one ("other") when unknown or absent.
     * Error = numeric squared errors (reduced per the model's numeric_reduction) + the sum of each
     * categorical head's cross-entropy, unweighted.
     */
    fun evaluate(numericValues: Map<String, Float>, categoricalValues: Map<String, String?>): AutoencoderVerdict {
        val standardizedNumeric = FloatArray(numericFeatureNames.size) { i ->
            val name = numericFeatureNames[i]
            val raw = numericValues[name] ?: 0f
            val transformed = if (name == "dst_port") ln(1.0 + raw).toFloat() else raw
            val std = numericStd[i]
            if (std == 0f) transformed - numericMean[i] else (transformed - numericMean[i]) / std
        }

        val trueIndexes = categoricalFeatureNames.mapIndexed { f, name ->
            val values = categories[f]
            val found = values.indexOfFirst { it.equals(categoricalValues[name], ignoreCase = true) }
            if (found >= 0 && found != values.size - 1) found else values.size - 1
        }
        var input = standardizedNumeric
        categories.forEachIndexed { f, values ->
            input += FloatArray(values.size).also { it[trueIndexes[f]] = 1f }
        }

        val bottleneck = encoderLayers.fold(input) { acc, layer -> layer.forward(acc) }
        val trunkOutput = decoderTrunkLayers.fold(bottleneck) { acc, layer -> layer.forward(acc) }

        var error = squaredErrorReduction(standardizedNumeric, decoderNumericHead.forward(trunkOutput))
        decoderCategoricalHeads.forEachIndexed { f, head ->
            error += crossEntropy(head.forward(trunkOutput), trueIndexes[f])
        }

        return AutoencoderVerdict(anomalous = error > reconstructionThreshold, reconstructionError = error)
    }

    private fun squaredErrorReduction(original: FloatArray, reconstructed: FloatArray): Float {
        var sum = 0f
        for (i in original.indices) {
            val diff = original[i] - reconstructed[i]
            sum += diff * diff
        }
        return if (sumNumericErrors) sum else sum / original.size
    }

    private fun crossEntropy(logits: FloatArray, trueIndex: Int): Float {
        val maxLogit = logits.max()
        var sumExp = 0f
        for (l in logits) sumExp += exp((l - maxLogit).toDouble()).toFloat()
        return maxLogit + ln(sumExp.toDouble()).toFloat() - logits[trueIndex]
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
        "linear" -> x
        "relu" -> if (x > 0f) x else 0f
        "leaky_relu" -> if (x > 0f) x else leakySlope * x
        "sigmoid" -> (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        "tanh" -> tanh(x)
        else -> throw IllegalArgumentException("unsupported activation: $activation")
    }

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONArray.toFloatArray(): FloatArray = FloatArray(length()) { getDouble(it).toFloat() }

    private fun JSONArray.toLayerList(): List<Layer> = (0 until length()).map { getJSONObject(it).toLayer() }

    private fun JSONObject.toLayer(): Layer {
        val weightArr = getJSONArray("weight")
        val weight = Array(weightArr.length()) { r -> weightArr.getJSONArray(r).toFloatArray() }
        val bias = getJSONArray("bias").toFloatArray()
        val activation = optString("activation", "linear")
        require(activation in SUPPORTED_ACTIVATIONS) { "unsupported activation: $activation" }
        return Layer(weight, bias, activation)
    }

    private companion object {
        val SUPPORTED_ACTIVATIONS = setOf("linear", "relu", "leaky_relu", "sigmoid", "tanh")
    }
}

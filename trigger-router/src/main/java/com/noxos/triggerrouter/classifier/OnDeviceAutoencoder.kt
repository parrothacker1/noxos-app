package com.noxos.triggerrouter.classifier

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.tanh

data class AutoencoderVerdict(val anomalous: Boolean, val reconstructionError: Float)

class OnDeviceAutoencoder(modelJson: String) {
    private val inputFeatures: List<String>
    private val featureDefaults: Map<String, Float>
    private val featureMean: FloatArray
    private val featureStd: FloatArray
    private val encoderLayers: List<Layer>
    private val decoderLayers: List<Layer>
    private val anomalyThreshold: Float

    private data class Layer(val weights: Array<FloatArray>, val biases: FloatArray, val activation: String)

    init {
        val root = JSONObject(modelJson)
        inputFeatures = root.getJSONArray("input_features").toStringList()

        val defaults = root.optJSONObject("feature_defaults") ?: JSONObject()
        featureDefaults = defaults.keys().asSequence().associateWith { defaults.getDouble(it).toFloat() }

        val normalization = root.optJSONObject("normalization")
        featureMean = normalization?.optJSONArray("mean")?.toFloatArray() ?: FloatArray(inputFeatures.size)
        featureStd = normalization?.optJSONArray("std")?.toFloatArray() ?: FloatArray(inputFeatures.size) { 1f }

        encoderLayers = root.getJSONArray("encoder_layers").toLayerList()
        decoderLayers = root.getJSONArray("decoder_layers").toLayerList()
        anomalyThreshold = root.getDouble("anomaly_threshold").toFloat()
    }

    fun evaluate(features: Map<String, Float>): AutoencoderVerdict {
        val input = FloatArray(inputFeatures.size) { i ->
            val name = inputFeatures[i]
            val raw = features[name] ?: featureDefaults[name] ?: 0f
            val std = featureStd[i]
            if (std == 0f) raw - featureMean[i] else (raw - featureMean[i]) / std
        }

        val latent = encoderLayers.fold(input) { acc, layer -> layer.forward(acc) }
        val reconstructed = decoderLayers.fold(latent) { acc, layer -> layer.forward(acc) }
        val error = meanSquaredError(input, reconstructed)

        return AutoencoderVerdict(anomalous = error > anomalyThreshold, reconstructionError = error)
    }

    private fun meanSquaredError(original: FloatArray, reconstructed: FloatArray): Float {
        var sum = 0f
        for (i in original.indices) {
            val diff = original[i] - reconstructed[i]
            sum += diff * diff
        }
        return sum / original.size
    }

    private fun Layer.forward(input: FloatArray): FloatArray {
        val output = FloatArray(biases.size)
        for (o in biases.indices) {
            var sum = biases[o]
            val row = weights[o]
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

    private fun JSONArray.toLayerList(): List<Layer> = (0 until length()).map { i ->
        val layerObj = getJSONObject(i)
        val weightsArr = layerObj.getJSONArray("weights")
        val weights = Array(weightsArr.length()) { r -> weightsArr.getJSONArray(r).toFloatArray() }
        val biases = layerObj.getJSONArray("biases").toFloatArray()
        val activation = layerObj.optString("activation", "linear")
        Layer(weights, biases, activation)
    }
}

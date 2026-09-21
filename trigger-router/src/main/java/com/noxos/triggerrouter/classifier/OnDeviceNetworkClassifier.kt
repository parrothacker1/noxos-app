package com.noxos.triggerrouter.classifier

import org.json.JSONObject
import kotlin.math.exp

data class ClassifierVerdict(val verdict: String, val safetyScore: Float)

class OnDeviceNetworkClassifier(modelJson: String) {
    private val baseScore: Double
    private val trees: List<JSONObject>
    private val featureDefaults: Map<String, Double>
    private val categories: Map<String, List<String>>

    init {
        val root = JSONObject(modelJson)
        baseScore = root.optDouble("base_score", 0.0)
        val treesArray = root.getJSONArray("trees")
        trees = (0 until treesArray.length()).map { treesArray.getJSONObject(it) }
        val defaults = root.optJSONObject("feature_defaults") ?: JSONObject()
        featureDefaults = defaults.keys().asSequence().associateWith { defaults.getDouble(it) }
        val categoriesObj = root.optJSONObject("categories") ?: JSONObject()
        categories = categoriesObj.keys().asSequence().associateWith { key ->
            val values = categoriesObj.getJSONArray(key)
            (0 until values.length()).map { values.getString(it) }
        }
    }

    fun encodeCategory(feature: String, value: String): Float? {
        val index = categories[feature]?.indexOf(value) ?: return null
        return if (index >= 0) index.toFloat() else null
    }

    fun classify(features: Map<String, Float>): ClassifierVerdict {
        var margin = baseScore
        trees.forEach { tree -> margin += evaluateTree(tree, features) }
        val attackProbability = sigmoid(margin)
        val safetyScore = (1.0 - attackProbability).toFloat()
        val verdict = when {
            attackProbability > 0.6 -> "block"
            attackProbability < 0.4 -> "allow"
            else -> "uncertain"
        }
        return ClassifierVerdict(verdict, safetyScore)
    }

    private fun evaluateTree(node: JSONObject, features: Map<String, Float>): Double {
        var current = node
        while (!current.has("leaf")) {
            val featureName = current.getString("feature")
            val threshold = current.getDouble("threshold")
            val value = (features[featureName]?.toDouble()) ?: featureDefaults[featureName]
            val goLeft = if (value == null) current.optBoolean("default_left", true) else value < threshold
            current = current.getJSONObject(if (goLeft) "left" else "right")
        }
        return current.getDouble("leaf")
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}

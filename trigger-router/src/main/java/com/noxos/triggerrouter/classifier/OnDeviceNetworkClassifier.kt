package com.noxos.triggerrouter.classifier

import org.json.JSONObject
import kotlin.math.exp

data class ClassifierVerdict(val verdict: String, val safetyScore: Float)

/**
 * Evaluates a gradient-boosted tree ensemble dumped to the JSON shape documented in
 * knowledge-graph/noxos-inference/TASKS.md (a flat, hand-specified format - not XGBoost's own
 * `booster.dump_model` output - so this interpreter doesn't have to special-case index-based
 * `fN` feature names or XGBoost's nested `children` layout). Deliberately no TFLite/ONNX
 * dependency, matching this codebase's hand-rolled-over-dependency pattern (e.g. the TCP relay).
 *
 * Internal representation mirrors noxos-inference's own service: trees predict attack
 * probability (higher = more dangerous); safetyScore is exposed inverted (1 - attackProbability)
 * to match noxos-app's "higher = safer" convention.
 */
class OnDeviceNetworkClassifier(modelJson: String) {
    private val baseScore: Double
    private val trees: List<JSONObject>
    private val featureDefaults: Map<String, Double>

    init {
        val root = JSONObject(modelJson)
        baseScore = root.optDouble("base_score", 0.0)
        val treesArray = root.getJSONArray("trees")
        trees = (0 until treesArray.length()).map { treesArray.getJSONObject(it) }
        val defaults = root.optJSONObject("feature_defaults") ?: JSONObject()
        featureDefaults = defaults.keys().asSequence().associateWith { defaults.getDouble(it) }
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

package com.noxos.triggerrouter.classifier

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnDeviceNetworkClassifierTest {

    // A hand-written, deliberately tiny two-tree ensemble - not a real trained model - just
    // enough to prove the interpreter walks splits/leaves/defaults correctly. Real trees come
    // from noxos-inference's future model-export step (see knowledge-graph/noxos-inference/TASKS.md
    // for the exact JSON contract this test is exercising).
    private val modelJson = """
        {
          "base_score": 0.0,
          "feature_defaults": { "dst_port": 443.0, "byte_count": 500.0 },
          "trees": [
            {
              "feature": "dst_port", "threshold": 1024.0,
              "left":  { "leaf": -2.0 },
              "right": { "leaf": 2.0 }
            },
            {
              "feature": "byte_count", "threshold": 10000.0, "default_left": false,
              "left":  { "leaf": 0.0 },
              "right": { "leaf": 1.0 }
            }
          ]
        }
    """.trimIndent()

    private val classifier = OnDeviceNetworkClassifier(modelJson)

    @Test
    fun `a standard low port and modest volume classifies as allow`() {
        val verdict = classifier.classify(mapOf("dst_port" to 443f, "byte_count" to 200f))

        assertEquals("allow", verdict.verdict)
    }

    @Test
    fun `a non-standard high port and huge volume classifies as block`() {
        val verdict = classifier.classify(mapOf("dst_port" to 50000f, "byte_count" to 50000f))

        assertEquals("block", verdict.verdict)
    }

    @Test
    fun `a missing feature falls back to the model's own bundled default`() {
        // dst_port omitted entirely - defaults to 443.0, which is < 1024 -> left leaf (-2.0),
        // same as if the caller had actually supplied 443f.
        val withDefault = classifier.classify(mapOf("byte_count" to 200f))
        val withExplicitValue = classifier.classify(mapOf("dst_port" to 443f, "byte_count" to 200f))

        assertEquals(withExplicitValue.verdict, withDefault.verdict)
        assertEquals(withExplicitValue.safetyScore, withDefault.safetyScore)
    }

    @Test
    fun `safety score is the inverse of attack probability, matching noxos-app's convention`() {
        val safe = classifier.classify(mapOf("dst_port" to 443f, "byte_count" to 200f))
        val dangerous = classifier.classify(mapOf("dst_port" to 50000f, "byte_count" to 50000f))

        assert(safe.safetyScore > dangerous.safetyScore) {
            "expected a safe flow to score higher than a dangerous one: safe=${safe.safetyScore} dangerous=${dangerous.safetyScore}"
        }
    }
}

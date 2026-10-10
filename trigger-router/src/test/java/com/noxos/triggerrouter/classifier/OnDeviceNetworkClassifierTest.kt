package com.noxos.triggerrouter.classifier

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnDeviceNetworkClassifierTest {

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

    @Test
    fun `tree threshold comparison uses float32 semantics like XGBoost's own internal comparison, not float64`() {
        // Verified with real IEEE-754 arithmetic, not a guessed pair: 1472.9423828125f
        // is the exact float32 value nearest to the double threshold 1472.942383519298.
        // Compared as float64 the value is strictly less than the threshold (goes left);
        // compared as float32 (XGBoost's real internal representation) they're exactly
        // equal, so "value < threshold" is false (goes right) - a genuine branch flip,
        // matching the real block->allow flip NoxOS Inference found on their side.
        val boundaryModelJson = """
            {
              "base_score": 0.0,
              "feature_defaults": {},
              "trees": [
                {
                  "feature": "x", "threshold": 1472.942383519298,
                  "left":  { "leaf": -10.0 },
                  "right": { "leaf": 10.0 }
                }
              ]
            }
        """.trimIndent()
        val boundaryClassifier = OnDeviceNetworkClassifier(boundaryModelJson)

        val verdict = boundaryClassifier.classify(mapOf("x" to 1472.9423828125f))

        assertEquals("block", verdict.verdict)
    }

    @Test
    fun `unknown top-level and per-node export fields are ignored`() {
        val extended = OnDeviceNetworkClassifier(
            """
            {
              "base_score": 0.0,
              "feature_defaults": { "dst_port": 443.0 },
              "feature_scale": { "dst_port": 19171.1 },
              "future_field": [1, 2, 3],
              "trees": [
                {
                  "feature": "dst_port", "threshold": 1024, "cover": 1234.5, "default_left": true,
                  "left":  { "leaf": -2.0, "cover": 600.25 },
                  "right": { "leaf": 2.0, "cover": 634.25 }
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals("allow", extended.classify(mapOf("dst_port" to 443f)).verdict)
        assertEquals("block", extended.classify(mapOf("dst_port" to 50000f)).verdict)
    }

    @Test
    fun `the released student with feature_scale and cover parses and classifies`() {
        val json = javaClass.classLoader!!.getResource("student_released_v1791623396.json")!!.readText()
        val released = OnDeviceNetworkClassifier(json)

        val verdict = released.classify(
            mapOf(
                "dst_port" to 443f, "src_byte_count" to 900f, "src_packet_count" to 10f,
                "dst_byte_count" to 12000f, "dst_packet_count" to 14f, "duration_millis" to 2000f
            )
        )

        assert(verdict.verdict in setOf("allow", "block", "uncertain"))
        assert(verdict.safetyScore in 0f..1f)
    }
}

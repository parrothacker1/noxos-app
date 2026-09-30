package com.noxos.triggerrouter.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Two layers of checking: (1) tiny synthetic models with hand-verified numbers that each isolate one mechanism
// (per-protocol thresholds, log1p/sqrt transforms and clamping, mean vs sum, leaky slope, contract rejection);
// (2) the real released noxos-inference model (autoencoder-latest, version 1790722998, the MIRAGE-trained first
// gate) on the three golden vectors from ML-AUTOENCODER-CONTRACT.md (tolerance 1e-4) plus extra rows whose
// expected errors come from an independent float64 numpy forward pass over the same model.json.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnDeviceAutoencoderTest {

    private fun model(
        transforms: String = """{"dst_port": "log1p", "src_byte_count": "sqrt"}""",
        reduction: String = "mean",
        thresholds: String = """{"tcp": 1.0, "udp": 1.0}""",
        numericHead: String = """{ "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }""",
        protoBias: String = "[2.0, 1.0, -1.0]"
    ) = """
        {
          "numeric_features": ["dst_port", "src_byte_count"],
          "numeric_mean": [0.0, 0.0],
          "numeric_std": [1.0, 1.0],
          "numeric_reduction": "$reduction",
          "numeric_transforms": $transforms,
          "categorical_features": [ { "name": "proto", "categories": ["tcp", "udp", "other"] } ],
          "reconstruction_thresholds": $thresholds,
          "encoder": [
            { "weight": [[1,0,0,0,0], [0,1,0,0,0]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_trunk": [
            { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_numeric_head": $numericHead,
          "decoder_categorical_heads": [
            { "name": "proto", "weight": [[0,0], [0,0], [0,0]], "bias": $protoBias, "activation": "linear" }
          ],
          "leaky_relu_negative_slope": 0.01
        }
    """.trimIndent()

    // dst_port = e - 1 -> log1p = 1.0; src_byte_count = 4 -> sqrt = 2.0; with mean 0 / std 1 the z-scores are [1, 2].
    private val numerics = mapOf("dst_port" to (Math.E - 1).toFloat(), "src_byte_count" to 4f)

    private val zeroNumericHead = """{ "weight": [[0,0], [0,0]], "bias": [0.0, 0.0], "activation": "linear" }"""
    private val uniformProto = "[0.0, 0.0, 0.0]"

    @Test
    fun `the flag threshold is chosen by protocol, so each protocol is judged against its own`() {
        // numeric head is identity (numeric error 0), proto head fixed logits [2,1,-1]:
        // CE(tcp) = 0.34901221676818617, CE(udp) = 1.3490122167681862.
        val autoencoder = OnDeviceAutoencoder(model(thresholds = """{"tcp": 0.3, "udp": 5.0}"""))

        val tcp = autoencoder.evaluate(numerics, mapOf("proto" to "tcp"))!!
        val udp = autoencoder.evaluate(numerics, mapOf("proto" to "udp"))!!

        assertEquals(0.34901222f, tcp.reconstructionError, 1e-4f)
        assertTrue("0.349 > tcp threshold 0.3", tcp.anomalous)
        assertEquals(1.3490122f, udp.reconstructionError, 1e-4f)
        assertFalse("1.349 < udp threshold 5.0 (would flip if tcp's threshold were used)", udp.anomalous)
    }

    @Test
    fun `a protocol the model has no threshold for is not scored at all`() {
        val autoencoder = OnDeviceAutoencoder(model())

        assertNull(autoencoder.evaluate(numerics, mapOf("proto" to "icmp")))
        assertNull(autoencoder.evaluate(numerics, mapOf("proto" to "other")))
        assertNull(autoencoder.evaluate(numerics, emptyMap()))
    }

    @Test
    fun `protocol matching ignores case`() {
        val autoencoder = OnDeviceAutoencoder(model())

        assertEquals(
            autoencoder.evaluate(numerics, mapOf("proto" to "tcp"))!!.reconstructionError,
            autoencoder.evaluate(numerics, mapOf("proto" to "TCP"))!!.reconstructionError,
            0f
        )
    }

    @Test
    fun `dst_port uses log1p and the other features use sqrt, both from the model file`() {
        // forced-zero numeric reconstruction: MSE = mean(1^2, 2^2) = 2.5; uniform proto head: CE = ln(3) = 1.0986122886681098
        val verdict = OnDeviceAutoencoder(model(numericHead = zeroNumericHead, protoBias = uniformProto))
            .evaluate(numerics, mapOf("proto" to "tcp"))!!

        assertEquals(3.5986123f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `a raw value is clamped at zero before its transform instead of producing NaN`() {
        // src_byte_count -9 -> clamped to 0 -> sqrt 0 -> z = [1, 0]: MSE = 0.5, plus ln(3) = 1.5986122886681098
        val verdict = OnDeviceAutoencoder(model(numericHead = zeroNumericHead, protoBias = uniformProto))
            .evaluate(mapOf("dst_port" to (Math.E - 1).toFloat(), "src_byte_count" to -9f), mapOf("proto" to "tcp"))!!

        assertFalse(verdict.reconstructionError.isNaN())
        assertEquals(1.5986123f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `a missing numeric feature counts as raw zero`() {
        // src_byte_count absent -> 0 -> sqrt 0 -> same as the clamped case above
        val verdict = OnDeviceAutoencoder(model(numericHead = zeroNumericHead, protoBias = uniformProto))
            .evaluate(mapOf("dst_port" to (Math.E - 1).toFloat()), mapOf("proto" to "udp"))!!

        assertEquals(1.5986123f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `mean reduction averages the numeric squared errors while sum reduction adds them`() {
        val mean = OnDeviceAutoencoder(model(reduction = "mean", numericHead = zeroNumericHead, protoBias = uniformProto))
            .evaluate(numerics, mapOf("proto" to "tcp"))!!
        val sum = OnDeviceAutoencoder(model(reduction = "sum", numericHead = zeroNumericHead, protoBias = uniformProto))
            .evaluate(numerics, mapOf("proto" to "tcp"))!!

        assertEquals(3.5986123f, mean.reconstructionError, 1e-4f)
        assertEquals(6.0986123f, sum.reconstructionError, 1e-4f)
    }

    @Test
    fun `leaky_relu uses the model's own negative slope`() {
        // x=1 -> sqrt 1 -> pre-activation 1-5=-4 -> leaky (slope 0.1) -> -0.4 -> squared error (1+0.4)^2 = 1.96,
        // plus proto CE over uniform [0,0] = ln(2) -> 2.653147180559945
        val autoencoder = OnDeviceAutoencoder(
            """
            {
              "numeric_features": ["src_byte_count"],
              "numeric_mean": [0.0],
              "numeric_std": [1.0],
              "numeric_reduction": "mean",
              "numeric_transforms": {"src_byte_count": "sqrt"},
              "categorical_features": [ { "name": "proto", "categories": ["tcp", "other"] } ],
              "reconstruction_thresholds": {"tcp": 100.0},
              "encoder": [ { "weight": [[1,0,0]], "bias": [-5.0], "activation": "leaky_relu" } ],
              "decoder_trunk": [ { "weight": [[1]], "bias": [0.0], "activation": "linear" } ],
              "decoder_numeric_head": { "weight": [[1]], "bias": [0.0], "activation": "linear" },
              "decoder_categorical_heads": [
                { "name": "proto", "weight": [[0], [0]], "bias": [0.0, 0.0], "activation": "linear" }
              ],
              "leaky_relu_negative_slope": 0.1
            }
            """.trimIndent()
        )

        val verdict = autoencoder.evaluate(mapOf("src_byte_count" to 1f), mapOf("proto" to "tcp"))!!

        assertEquals(2.6531472f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `an unusable model file is rejected instead of silently miscalibrating`() {
        assertThrows(IllegalArgumentException::class.java) { OnDeviceAutoencoder(model(reduction = "median")) }
        assertThrows(IllegalArgumentException::class.java) {
            OnDeviceAutoencoder(model().replace("\"linear\"", "\"swish\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnDeviceAutoencoder(model(transforms = """{"dst_port": "log1p", "src_byte_count": "cbrt"}"""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnDeviceAutoencoder(model(transforms = """{"dst_port": "log1p"}"""))
        }
    }

    private val releasedModel: OnDeviceAutoencoder by lazy {
        val json = javaClass.classLoader!!.getResource("autoencoder_released_v1790722998.json")!!.readText()
        OnDeviceAutoencoder(json)
    }

    private fun flow(
        port: Float, src: Float, srcPackets: Float, dst: Float, duration: Float, handshake: Float, smean: Float
    ) = mapOf(
        "dst_port" to port, "src_byte_count" to src, "src_packet_count" to srcPackets, "dst_byte_count" to dst,
        "duration_millis" to duration, "handshake_latency_millis" to handshake, "smean" to smean
    )

    private fun assertMatches(expected: Double, actual: Float, delta: Float = 1e-4f) {
        assertEquals(expected.toFloat(), actual, delta)
    }

    @Test
    fun `released model wants exactly the seven numeric inputs and proto, in the documented order`() {
        assertEquals(
            listOf(
                "dst_port", "src_byte_count", "src_packet_count", "dst_byte_count",
                "duration_millis", "handshake_latency_millis", "smean"
            ),
            releasedModel.numericFeatureNames
        )
        assertEquals(listOf("proto"), releasedModel.categoricalFeatureNames)
    }

    @Test
    fun `golden vector - tcp sample is not flagged`() {
        val verdict = releasedModel.evaluate(
            flow(80f, 102123.89269626162f, 1894.6602989194519f, 5167702.0398060735f, 19908.723520133364f, 50.00185966491699f, 53.90089862256793f),
            mapOf("proto" to "tcp")
        )!!

        assertMatches(0.021474, verdict.reconstructionError)
        assertFalse(verdict.anomalous)
    }

    @Test
    fun `golden vector - udp sample is flagged against the udp threshold`() {
        val verdict = releasedModel.evaluate(
            flow(44790f, 197483.59723275586f, 340.5582810061172f, 109026.41195575793f, 12103.02585013446f, 0f, 579.8819416439578f),
            mapOf("proto" to "udp")
        )!!

        assertMatches(0.140742, verdict.reconstructionError)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `golden vector - tcp big upload is flagged`() {
        val verdict = releasedModel.evaluate(
            flow(443f, 40000000f, 30000f, 50000f, 20000f, 30f, 1333.3333333333333f),
            mapOf("proto" to "tcp")
        )!!

        assertMatches(17.171206, verdict.reconstructionError, delta = 1e-3f)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `released model matches numpy on a small ordinary tcp flow`() {
        // independent float64 forward pass: 0.0003794095679668202
        val verdict = releasedModel.evaluate(flow(443f, 1200f, 10f, 5000f, 300f, 20f, 120f), mapOf("proto" to "tcp"))!!

        assertMatches(0.0003794095679668202, verdict.reconstructionError, delta = 2e-5f)
        assertFalse(verdict.anomalous)
    }

    @Test
    fun `released model matches numpy when raw values are negative (clamped at zero)`() {
        // independent float64 forward pass: 2.0918525500077445
        val verdict = releasedModel.evaluate(flow(-5f, -100f, 10f, 5000f, 300f, 20f, 120f), mapOf("proto" to "tcp"))!!

        assertMatches(2.0918525500077445, verdict.reconstructionError, delta = 1e-3f)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `released model matches numpy when almost every input is missing`() {
        // only dst_port=53 present; independent float64 forward pass: 0.1827482519345807
        val verdict = releasedModel.evaluate(mapOf("dst_port" to 53f), mapOf("proto" to "udp"))!!

        assertMatches(0.1827482519345807, verdict.reconstructionError)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `released model does not score a protocol it was not calibrated on`() {
        assertNotNull(releasedModel.evaluate(mapOf("dst_port" to 53f), mapOf("proto" to "udp")))
        assertNull(releasedModel.evaluate(mapOf("dst_port" to 0f), mapOf("proto" to "icmp")))
    }
}

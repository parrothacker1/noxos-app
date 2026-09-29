package com.noxos.triggerrouter.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Two layers of checking: (1) tiny synthetic models with hand-verified numbers that isolate one
// mechanism each (two categorical heads summed, mean vs sum reduction, dst_port log1p, missing
// values, leaky_relu slope, rejecting unknown contracts); (2) the real released noxos-inference
// model (autoencoder-latest, version 1790704070) run on realistic rows, with expected errors from an
// independent float64 numpy forward pass over the same model.json - so a wrong wiring of the real
// 24-wide input/two-head structure is caught, not just this file agreeing with itself.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnDeviceAutoencoderTest {

    // 2 numeric + proto(3) + state(3) = 8 inputs. The encoder copies the two numerics into a 2-wide
    // bottleneck, trunk and numeric head are identity (numeric error 0), and both categorical heads
    // ignore the trunk (zero weight) and emit fixed logits, so error = CE(proto) + CE(state) only.
    private val twoHeadModelJson = """
        {
          "numeric_features": ["dst_port", "src_byte_count"],
          "numeric_mean": [0.0, 0.0],
          "numeric_std": [1.0, 1.0],
          "numeric_reduction": "mean",
          "categorical_features": [
            { "name": "proto", "categories": ["tcp", "udp", "other"] },
            { "name": "state", "categories": ["FIN", "INT", "other"] }
          ],
          "reconstruction_threshold": 2.0,
          "encoder": [
            { "weight": [[1,0,0,0,0,0,0,0], [0,1,0,0,0,0,0,0]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_trunk": [
            { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_numeric_head": { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" },
          "decoder_categorical_heads": [
            { "name": "proto", "weight": [[0,0], [0,0], [0,0]], "bias": [2.0, 1.0, -1.0], "activation": "linear" },
            { "name": "state", "weight": [[0,0], [0,0], [0,0]], "bias": [0.5, -0.5, 1.5], "activation": "linear" }
          ],
          "leaky_relu_negative_slope": 0.01
        }
    """.trimIndent()
    private val twoHeadModel = OnDeviceAutoencoder(twoHeadModelJson)

    // dst_port = e - 1 -> log1p = 1.0, so with mean 0 / std 1 the standardized numerics are [1.0, 2.0].
    private val numerics = mapOf("dst_port" to (Math.E - 1).toFloat(), "src_byte_count" to 2f)

    @Test
    fun `both categorical heads' cross-entropies are summed into the error`() {
        // proto tcp: 0.34901221676818617, state INT: 2.4076059644443806 -> 2.7566181812125667
        val verdict = twoHeadModel.evaluate(numerics, mapOf("proto" to "tcp", "state" to "INT"))

        assertEquals(2.7566182f, verdict.reconstructionError, 1e-4f)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `an error under the model's threshold is normal`() {
        // proto udp: 1.3490122167681862, state other (via unknown value): 0.40760596444438035 -> 1.7566181812125665
        val verdict = twoHeadModel.evaluate(numerics, mapOf("proto" to "udp", "state" to "SYN-SENT"))

        assertEquals(1.7566182f, verdict.reconstructionError, 1e-4f)
        assertFalse(verdict.anomalous)
    }

    @Test
    fun `a missing categorical value buckets to other and matching ignores case`() {
        val absent = twoHeadModel.evaluate(numerics, emptyMap())
        val explicitOther = twoHeadModel.evaluate(numerics, mapOf("proto" to "other", "state" to "other"))
        val mixedCase = twoHeadModel.evaluate(numerics, mapOf("proto" to "TCP", "state" to "int"))

        assertEquals(explicitOther.reconstructionError, absent.reconstructionError, 0f)
        assertEquals(2.7566182f, mixedCase.reconstructionError, 1e-4f)
    }

    private fun forcedZeroNumericHeadModel(reduction: String) = """
        {
          "numeric_features": ["dst_port", "src_byte_count"],
          "numeric_mean": [0.0, 0.0],
          "numeric_std": [1.0, 1.0],
          "numeric_reduction": "$reduction",
          "categorical_features": [
            { "name": "proto", "categories": ["tcp", "udp", "other"] },
            { "name": "state", "categories": ["FIN", "INT", "other"] }
          ],
          "reconstruction_threshold": 100.0,
          "encoder": [
            { "weight": [[1,0,0,0,0,0,0,0], [0,1,0,0,0,0,0,0]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_trunk": [
            { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_numeric_head": { "weight": [[0,0], [0,0]], "bias": [0.0, 0.0], "activation": "linear" },
          "decoder_categorical_heads": [
            { "name": "proto", "weight": [[0,0], [0,0], [0,0]], "bias": [0.0, 0.0, 0.0], "activation": "linear" },
            { "name": "state", "weight": [[0,0], [0,0], [0,0]], "bias": [0.0, 0.0, 0.0], "activation": "linear" }
          ]
        }
    """.trimIndent()

    @Test
    fun `mean reduction averages the numeric squared errors while sum reduction adds them`() {
        // standardized numerics [1, 2] vs a forced-zero reconstruction: squared errors 1 and 4.
        // Both uniform categorical heads contribute ln(3) each = 2.1972245773362196.
        val categorical = mapOf("proto" to "tcp", "state" to "FIN")

        val mean = OnDeviceAutoencoder(forcedZeroNumericHeadModel("mean")).evaluate(numerics, categorical)
        val sum = OnDeviceAutoencoder(forcedZeroNumericHeadModel("sum")).evaluate(numerics, categorical)

        assertEquals(4.6972246f, mean.reconstructionError, 1e-4f)
        assertEquals(7.1972246f, sum.reconstructionError, 1e-4f)
    }

    @Test
    fun `dst_port is log1p-transformed before standardization`() {
        // Same forced-zero model, mean-reduced. Skipping log1p would standardize dst_port to ~1.718
        // instead of 1.0 and change the numeric term from 2.5 to (1.718^2 + 4)/2 = 3.476.
        val verdict = OnDeviceAutoencoder(forcedZeroNumericHeadModel("mean"))
            .evaluate(numerics, mapOf("proto" to "tcp", "state" to "FIN"))

        assertEquals(2.5f + 2.1972246f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `a missing numeric feature counts as raw zero`() {
        // src_byte_count absent -> 0.0; standardized [1.0, 0.0] -> squared errors 1 and 0 -> mean 0.5.
        val verdict = OnDeviceAutoencoder(forcedZeroNumericHeadModel("mean"))
            .evaluate(mapOf("dst_port" to (Math.E - 1).toFloat()), mapOf("proto" to "tcp", "state" to "FIN"))

        assertEquals(0.5f + 2.1972246f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `leaky_relu uses the model's own negative slope`() {
        // x=1: pre-activation 1-5=-4 -> leaky (slope 0.1) -> -0.4 -> numeric reconstruction -0.4
        // squared error (1 - (-0.4))^2 = 1.96, plus proto CE over uniform [0,0] = ln(2) -> 2.653147180559945
        val model = OnDeviceAutoencoder(
            """
            {
              "numeric_features": ["src_byte_count"],
              "numeric_mean": [0.0],
              "numeric_std": [1.0],
              "numeric_reduction": "mean",
              "categorical_features": [ { "name": "proto", "categories": ["tcp", "other"] } ],
              "reconstruction_threshold": 100.0,
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

        val verdict = model.evaluate(mapOf("src_byte_count" to 1f), mapOf("proto" to "tcp"))

        assertEquals(2.6531472f, verdict.reconstructionError, 1e-4f)
    }

    @Test
    fun `an unrecognized numeric reduction or activation is rejected instead of silently miscalibrating`() {
        assertThrows(IllegalArgumentException::class.java) {
            OnDeviceAutoencoder(forcedZeroNumericHeadModel("median"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OnDeviceAutoencoder(forcedZeroNumericHeadModel("mean").replace("\"linear\"", "\"swish\""))
        }
    }

    private val releasedModel: OnDeviceAutoencoder by lazy {
        val json = javaClass.classLoader!!.getResource("autoencoder_released_v1790704070.json")!!.readText()
        OnDeviceAutoencoder(json)
    }

    private fun realRow(
        port: Float = 0f, sb: Float = 0f, sp: Float = 0f, db: Float = 0f, dp: Float = 0f,
        dur: Float = 0f, hs: Float = 0f, smean: Float = 0f, dmean: Float = 0f, dttl: Float = 0f
    ) = mapOf(
        "dst_port" to port, "src_byte_count" to sb, "src_packet_count" to sp, "dst_byte_count" to db,
        "dst_packet_count" to dp, "duration_millis" to dur, "handshake_latency_millis" to hs,
        "smean" to smean, "dmean" to dmean, "dttl" to dttl
    )

    private fun assertMatchesIndependentComputation(expected: Double, actual: Float) {
        assertEquals(expected.toFloat(), actual, 2e-5f + 1e-5f * expected.toFloat())
    }

    @Test
    fun `released model exposes the ten numeric and two categorical inputs it needs`() {
        assertEquals(
            listOf(
                "dst_port", "src_byte_count", "src_packet_count", "dst_byte_count", "dst_packet_count",
                "duration_millis", "handshake_latency_millis", "smean", "dmean", "dttl"
            ),
            releasedModel.numericFeatureNames
        )
        assertEquals(listOf("proto", "state"), releasedModel.categoricalFeatureNames)
    }

    @Test
    fun `released model scores a normal-looking flow below its threshold`() {
        // numpy float64 forward pass on the same model.json: 0.0017262964870170482 (threshold 0.004233994521200657)
        val verdict = releasedModel.evaluate(
            realRow(53f, 100f, 2f, 200f, 8f, 5f, 0f, 50f, 25f, 0f),
            mapOf("proto" to "udp", "state" to "INT")
        )

        assertMatchesIndependentComputation(0.0017262964870170482, verdict.reconstructionError)
        assertFalse(verdict.anomalous)
    }

    @Test
    fun `released model flags a flow it reconstructs poorly`() {
        // numpy: 0.2844894289183018
        val verdict = releasedModel.evaluate(
            realRow(443f, 1200f, 10f, 5000f, 8f, 300f, 20f, 120f, 625f, 252f),
            mapOf("proto" to "tcp", "state" to "FIN")
        )

        assertMatchesIndependentComputation(0.2844894289183018, verdict.reconstructionError)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `released model matches numpy on unknown categories and a wildly out-of-range flow`() {
        // proto sctp and state XYZ both bucket to other; numpy: 1521.3205029819478
        val verdict = releasedModel.evaluate(
            realRow(31337f, 900000f, 700f, 20f, 2f, 90000f, 300f, 1285.7f, 10f, 254f),
            mapOf("proto" to "sctp", "state" to "XYZ")
        )

        assertMatchesIndependentComputation(1521.3205029819478, verdict.reconstructionError)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `released model matches numpy when almost every input is missing`() {
        // only dst_port=80 present, no categoricals at all; numpy: 16.964874280983516
        val verdict = releasedModel.evaluate(mapOf("dst_port" to 80f), emptyMap())

        assertMatchesIndependentComputation(16.964874280983516, verdict.reconstructionError)
    }

    @Test
    fun `released model matches numpy for mixed-case categorical strings`() {
        // "TCP"/"int" must land on the tcp/INT buckets; numpy: 13.909597414083054
        val verdict = releasedModel.evaluate(
            realRow(443f, 500f, 5f, 800f, 4f, 100f, 10f, 100f, 200f, 0f),
            mapOf("proto" to "TCP", "state" to "int")
        )

        assertMatchesIndependentComputation(13.909597414083054, verdict.reconstructionError)
    }
}

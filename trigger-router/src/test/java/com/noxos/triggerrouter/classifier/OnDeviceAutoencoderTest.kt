package com.noxos.triggerrouter.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// This exercises the real noxos-inference autoencoder export contract (see
// ML-NETWORK-DESIGN.md item 15): a shared encoder -> decoder trunk feeding two
// separate reconstruction heads (numeric MSE, proto cross-entropy). The fixture
// models below use tiny hand-verified dimensions (2 numeric features, 3 proto
// buckets, 2-wide bottleneck), not the real model's real dimensions - every
// expected number is precomputed independently (Python, double precision) so
// these tests catch a wrong wiring of the two heads, not just "does it run."
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnDeviceAutoencoderTest {

    // Encoder copies the two standardized numeric inputs straight into the 2-wide
    // bottleneck (ignores the 3-wide proto one-hot entirely); trunk and numeric
    // head are both identity, so numeric MSE is always 0 here - isolates the
    // proto cross-entropy term. Proto head ignores the trunk output (zero weight)
    // and always emits fixed logits [2.0, 1.0, -1.0], so cross-entropy depends
    // only on which true index is selected.
    private val protoOnlyModelJson = """
        {
          "numeric_features": ["dst_port", "src_byte_count"],
          "proto_categories": ["tcp", "udp", "other"],
          "numeric_mean": [0.0, 0.0],
          "numeric_std": [1.0, 1.0],
          "reconstruction_threshold": 1.0,
          "encoder": [
            { "weight": [[1,0,0,0,0], [0,1,0,0,0]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_trunk": [
            { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }
          ],
          "decoder_numeric_head": { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" },
          "decoder_proto_head": { "weight": [[0,0], [0,0], [0,0]], "bias": [2.0, 1.0, -1.0], "activation": "linear" }
        }
    """.trimIndent()
    private val protoOnlyModel = OnDeviceAutoencoder(protoOnlyModelJson)

    // dst_port = e - 1 -> log1p(dst_port) = 1.0 exactly with mean=0/std=1 -> standardized [1.0, 2.0]
    private val dstPortRawForLog1pOne = (Math.E - 1).toFloat()

    @Test
    fun `known protocol with low cross-entropy against the fixed logits scores as normal`() {
        // true index 0 (tcp): logsumexp([2,1,-1]) - 2 = 0.34901221676818617; numeric MSE is 0.
        val verdict = protoOnlyModel.evaluate(
            mapOf("dst_port" to dstPortRawForLog1pOne, "src_byte_count" to 2f),
            "tcp"
        )

        assertEquals(0.34901222f, verdict.reconstructionError, 1e-4f)
        assertFalse(verdict.anomalous)
    }

    @Test
    fun `known protocol with high cross-entropy against the fixed logits scores as anomalous`() {
        // true index 1 (udp): logsumexp([2,1,-1]) - 1 = 1.3490122167681862
        val verdict = protoOnlyModel.evaluate(
            mapOf("dst_port" to dstPortRawForLog1pOne, "src_byte_count" to 2f),
            "udp"
        )

        assertEquals(1.3490122f, verdict.reconstructionError, 1e-4f)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `an unrecognized protocol buckets to the last category instead of a random one`() {
        // true index 2 ("other", via fallback for an unlisted protocol like ARP):
        // logsumexp([2,1,-1]) - (-1) = 3.349012216768186 - distinct from both the
        // tcp and udp cases above, proving the fallback landed on index 2, not 0 or 1.
        val verdict = protoOnlyModel.evaluate(
            mapOf("dst_port" to dstPortRawForLog1pOne, "src_byte_count" to 2f),
            "ARP"
        )

        assertEquals(3.3490121f, verdict.reconstructionError, 1e-4f)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `dst_port is log1p-transformed before standardization, not used raw`() {
        // mean=[1.0, 0.0] so the log1p transform's effect on dst_port is visible.
        // numeric head is forced to reconstruct [0,0] regardless of input (zero
        // weight+bias), so MSE directly reflects the standardized input values.
        // proto head is forced to uniform logits [0,0,0] -> CE = ln(3) regardless
        // of protocol, isolating the numeric-side transform under test.
        val model = OnDeviceAutoencoder(
            """
            {
              "numeric_features": ["dst_port", "src_byte_count"],
              "proto_categories": ["tcp", "udp", "other"],
              "numeric_mean": [1.0, 0.0],
              "numeric_std": [1.0, 1.0],
              "reconstruction_threshold": 100.0,
              "encoder": [
                { "weight": [[1,0,0,0,0], [0,1,0,0,0]], "bias": [0.0, 0.0], "activation": "linear" }
              ],
              "decoder_trunk": [
                { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }
              ],
              "decoder_numeric_head": { "weight": [[0,0], [0,0]], "bias": [0.0, 0.0], "activation": "linear" },
              "decoder_proto_head": { "weight": [[0,0], [0,0], [0,0]], "bias": [0.0, 0.0, 0.0], "activation": "linear" }
            }
            """.trimIndent()
        )

        // dst_port raw = e^2 - 1 -> log1p = 2.0 -> standardized (2.0-1.0)/1.0 = 1.0
        // src_byte_count raw = 2.0 -> standardized (2.0-0.0)/1.0 = 2.0
        // MSE against forced-zero reconstruction = mean(1.0^2, 2.0^2) = 2.5
        // CE = ln(3) = 1.0986122886681098 -> total = 3.59861228866811
        // (skipping log1p would give standardized dst_port = 5.389... and total ~17.6 instead)
        val dstPortRaw = (Math.exp(2.0) - 1).toFloat()
        val verdict = model.evaluate(mapOf("dst_port" to dstPortRaw, "src_byte_count" to 2f), "tcp")

        assertEquals(3.5986123f, verdict.reconstructionError, 1e-3f)
    }

    @Test
    fun `a missing numeric feature defaults to raw zero before standardization`() {
        // Same forced-zero heads as the log1p test, mean=[0,0]/std=[1,1] this time.
        // dst_port provided (raw = e-1 -> log1p = 1.0 -> standardized 1.0);
        // src_byte_count omitted entirely -> defaults to raw 0.0 -> standardized 0.0.
        // MSE against forced-zero reconstruction = mean(1.0^2, 0.0^2) = 0.5
        // CE = ln(3) = 1.0986122886681098 -> total = 1.5986122886681098
        val model = OnDeviceAutoencoder(
            """
            {
              "numeric_features": ["dst_port", "src_byte_count"],
              "proto_categories": ["tcp", "udp", "other"],
              "numeric_mean": [0.0, 0.0],
              "numeric_std": [1.0, 1.0],
              "reconstruction_threshold": 100.0,
              "encoder": [
                { "weight": [[1,0,0,0,0], [0,1,0,0,0]], "bias": [0.0, 0.0], "activation": "linear" }
              ],
              "decoder_trunk": [
                { "weight": [[1,0], [0,1]], "bias": [0.0, 0.0], "activation": "linear" }
              ],
              "decoder_numeric_head": { "weight": [[0,0], [0,0]], "bias": [0.0, 0.0], "activation": "linear" },
              "decoder_proto_head": { "weight": [[0,0], [0,0], [0,0]], "bias": [0.0, 0.0, 0.0], "activation": "linear" }
            }
            """.trimIndent()
        )

        val verdict = model.evaluate(mapOf("dst_port" to dstPortRawForLog1pOne), "tcp")

        assertEquals(1.5986123f, verdict.reconstructionError, 1e-3f)
    }

    @Test
    fun `relu activation in the encoder clips negative pre-activation values to zero`() {
        // A 1-numeric-feature model whose encoder relu-clips a negative pre-activation,
        // isolating that the shared Layer.forward/applyActivation path still works
        // correctly under the new dual-head contract.
        val model = OnDeviceAutoencoder(
            """
            {
              "numeric_features": ["dst_port"],
              "proto_categories": ["tcp", "other"],
              "numeric_mean": [0.0],
              "numeric_std": [1.0],
              "reconstruction_threshold": 100.0,
              "encoder": [
                { "weight": [[1,0,0]], "bias": [-5.0], "activation": "relu" }
              ],
              "decoder_trunk": [
                { "weight": [[1]], "bias": [0.0], "activation": "linear" }
              ],
              "decoder_numeric_head": { "weight": [[1]], "bias": [0.0], "activation": "linear" },
              "decoder_proto_head": { "weight": [[0], [0]], "bias": [0.0, 0.0], "activation": "linear" }
            }
            """.trimIndent()
        )

        // dst_port raw=1 -> log1p(1)=ln(2)=0.6931472 -> standardized 0.6931472 (mean 0, std 1)
        // pre-activation = 0.6931472 - 5 = -4.3068528 -> relu clips to 0 -> numeric reconstruction 0
        // MSE = (0.6931472 - 0)^2 = 0.48045
        // proto logits [0,0] uniform -> CE = ln(2) = 0.6931472
        // total = 0.4804530139182014 + 0.6931471805599453 = 1.1736001944781467
        val verdict = model.evaluate(mapOf("dst_port" to 1f), "tcp")

        assertEquals(1.1736002f, verdict.reconstructionError, 1e-3f)
    }
}

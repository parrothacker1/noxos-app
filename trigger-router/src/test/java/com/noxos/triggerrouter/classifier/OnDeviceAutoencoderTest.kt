package com.noxos.triggerrouter.classifier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OnDeviceAutoencoderTest {

    // Synthetic placeholder model, same spirit as the two-tree fixture used for
    // OnDeviceNetworkClassifierTest before a real trained model existed: a single
    // encoder unit that averages the two inputs, a decoder that broadcasts that
    // average back to both outputs. A real trained autoencoder isn't built yet
    // (see ML-NETWORK-DESIGN.md item 15) - this fixture exercises the runner's
    // actual math (forward pass, reconstruction error, threshold) against known,
    // hand-verified numbers, independent of what any real model will look like.
    private val modelJson = """
        {
          "input_features": ["a", "b"],
          "feature_defaults": { "a": 5.0, "b": 5.0 },
          "encoder_layers": [
            { "weights": [[0.5, 0.5]], "biases": [0.0], "activation": "linear" }
          ],
          "decoder_layers": [
            { "weights": [[1.0], [1.0]], "biases": [0.0, 0.0], "activation": "linear" }
          ],
          "anomaly_threshold": 1.0
        }
    """.trimIndent()

    private val autoencoder = OnDeviceAutoencoder(modelJson)

    @Test
    fun `matching inputs reconstruct perfectly and score as normal`() {
        val verdict = autoencoder.evaluate(mapOf("a" to 10f, "b" to 10f))

        assertEquals(0f, verdict.reconstructionError)
        assertFalse(verdict.anomalous)
    }

    @Test
    fun `divergent inputs reconstruct poorly and score as anomalous`() {
        // encoder averages to 10, decoder broadcasts [10, 10]; original was [0, 20]
        // -> squared errors 100 and 100 -> MSE 100, verified by hand.
        val verdict = autoencoder.evaluate(mapOf("a" to 0f, "b" to 20f))

        assertEquals(100f, verdict.reconstructionError)
        assertTrue(verdict.anomalous)
    }

    @Test
    fun `error exactly at the threshold is not anomalous, strictly greater is`() {
        // Construct inputs whose MSE lands exactly on the 1.0 threshold: a=9, b=11
        // -> latent=10 -> reconstructed=[10,10] -> squared errors 1 and 1 -> MSE 1.0.
        val atThreshold = autoencoder.evaluate(mapOf("a" to 9f, "b" to 11f))
        assertEquals(1f, atThreshold.reconstructionError)
        assertFalse(atThreshold.anomalous)
    }

    @Test
    fun `missing features fall back to the model's own bundled defaults`() {
        val withDefaults = autoencoder.evaluate(emptyMap())
        val withExplicitDefaults = autoencoder.evaluate(mapOf("a" to 5f, "b" to 5f))

        assertEquals(withExplicitDefaults.reconstructionError, withDefaults.reconstructionError)
        assertEquals(withExplicitDefaults.anomalous, withDefaults.anomalous)
    }

    @Test
    fun `relu activation clips negative pre-activation values to zero`() {
        val reluModel = OnDeviceAutoencoder(
            """
            {
              "input_features": ["x"],
              "feature_defaults": {},
              "encoder_layers": [
                { "weights": [[1.0]], "biases": [-5.0], "activation": "relu" }
              ],
              "decoder_layers": [
                { "weights": [[1.0]], "biases": [0.0], "activation": "linear" }
              ],
              "anomaly_threshold": 100.0
            }
            """.trimIndent()
        )

        // x=2 -> pre-activation 2-5=-3 -> relu clips to 0 -> reconstructed=0
        // -> squared error (2-0)^2 = 4.
        val verdict = reluModel.evaluate(mapOf("x" to 2f))

        assertEquals(4f, verdict.reconstructionError)
    }
}

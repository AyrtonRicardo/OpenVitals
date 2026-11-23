package tech.mmarca.openvitals.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyCircumferenceModelsTest {

    @Test fun `a tape reading above zero and up to three metres is accepted`() {
        assertTrue(circumferenceAccepts(0.1))
        assertTrue(circumferenceAccepts(82.5))
        assertTrue(circumferenceAccepts(300.0))
    }

    @Test fun `zero, negatives, values past three metres and NaN are refused`() {
        listOf(0.0, -1.0, 300.01, Double.NaN, Double.POSITIVE_INFINITY).forEach { value ->
            assertFalse(value.toString(), circumferenceAccepts(value))
        }
    }
}

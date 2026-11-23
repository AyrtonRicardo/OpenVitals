package tech.mmarca.openvitals.features.progressphotos

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressPhotoScalingTest {

    @Test fun `the first decode halves while the long edge stays at or above the target`() {
        assertEquals(1, sampleSize(longEdge = 2560, target = 2560))
        assertEquals(1, sampleSize(longEdge = 4000, target = 2560))
        assertEquals(2, sampleSize(longEdge = 5120, target = 2560))
        assertEquals(4, sampleSize(longEdge = 12000, target = 2560))
        assertEquals(1, sampleSize(longEdge = 800, target = 2560))
    }

    @Test fun `scaling keeps the shape and never enlarges`() {
        assertEquals(2560 to 1920, scaledSize(width = 4000, height = 3000, maxEdge = 2560))
        assertEquals(540 to 720, scaledSize(width = 3000, height = 4000, maxEdge = 720))
        assertEquals(600 to 400, scaledSize(width = 600, height = 400, maxEdge = 720))
    }
}

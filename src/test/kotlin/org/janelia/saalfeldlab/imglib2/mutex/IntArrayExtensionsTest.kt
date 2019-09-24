package org.janelia.saalfeldlab.imglib2.mutex

import org.junit.Assert
import org.junit.Test

class IntArrayExtensionsTest {

    @Test
    fun testIndexQuicksort() {
        val values = doubleArrayOf(3.0, 2.0, 4.0, 1.0)

        // ascending
        with (IntArrayExtensions) {
            val indices = IntArray(values.size) { it }
            indices.quicksortBy { values[it] }
            Assert.assertArrayEquals(intArrayOf(3, 1, 0, 2), indices)
            Assert.assertArrayEquals(doubleArrayOf(1.0, 2.0, 3.0, 4.0), indices.map { values[it] }.toDoubleArray(), 0.0)
        }

        // descending
        with (IntArrayExtensions) {
            val indices = IntArray(values.size) { it }
            indices.quicksortBy { -values[it] }
            Assert.assertArrayEquals(intArrayOf(2, 0, 1, 3), indices)
            Assert.assertArrayEquals(doubleArrayOf(4.0, 3.0, 2.0, 1.0), indices.map { values[it] }.toDoubleArray(), 0.0)
        }
    }

}

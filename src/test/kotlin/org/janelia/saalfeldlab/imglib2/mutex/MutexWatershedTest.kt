package org.janelia.saalfeldlab.imglib2.mutex

import org.junit.Assert
import org.junit.Test

class MutexWatershedTest {

    @Test
    fun `single chain with one mutex edge`() {

        val edges = EdgeArray(Pair(0, 1), Pair(1, 2), Pair(2, 3))

        val edgeWeights = doubleArrayOf(1.2, 1.1, 0.5)

        val mutexEdges = EdgeArray(Pair(0, 3))

        val mutexEdgeWeights = doubleArrayOf(5.0)

        val uf = MutexWatershed.computeMutexWatershedClustering(
                4,
                edges,
                mutexEdges,
                edgeWeights,
                mutexEdgeWeights)

        val indices = with (UnionFindExtensions) { uf.label(4) }

        Assert.assertArrayEquals(longArrayOf(0, 0, 0, 3), indices)

    }

}

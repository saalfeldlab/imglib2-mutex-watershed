package org.janelia.saalfeldlab.imglib2.mutex

import net.imglib2.RandomAccessibleInterval
import net.imglib2.img.array.ArrayImgs
import net.imglib2.type.numeric.IntegerType
import net.imglib2.util.Intervals
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.junit.Assert
import org.junit.Test
import java.util.Arrays

class MutexWatershedTest {

    @Test
    fun `single chain with one mutex edge`() {

        val edges = EdgeArray(Pair(0, 1), Pair(1, 2), Pair(2, 3))

        val edgeWeights = doubleArrayOf(1.2, 1.1, 0.5)

        val mutexEdges = EdgeArray(Pair(0, 3))

        val mutexEdgeWeights = doubleArrayOf(5.0)

        // with array store
        run {
            val uf = MutexWatershed.computeMutexWatershedClustering(
                    4,
                    edges,
                    mutexEdges,
                    edgeWeights,
                    mutexEdgeWeights,
                    MutexStorageArray(4))

            val indices = with (UnionFindExtensions) { uf.label(4) }

            Assert.assertArrayEquals(longArrayOf(0, 0, 0, 3), indices)
        }

        // with hash map store
        run {
            val uf = MutexWatershed.computeMutexWatershedClustering(
                    4,
                    edges,
                    mutexEdges,
                    edgeWeights,
                    mutexEdgeWeights,
                    MutexStorageHashMap())

            val indices = with (UnionFindExtensions) { uf.label(4) }

            Assert.assertArrayEquals(longArrayOf(0, 0, 0, 3), indices)
        }

    }

    @Test
    fun `simple 2D test with 3 regions`() {
        // expected labeling (ids may differ):
        // 1 - 1 - 2 - 3
        //
        // 1 - 2 - 2 - 3
        //
        // 1 - 1 - 3 - 3

        val groundTruthLabeling = longArrayOf(
                1, 1, 2, 3,
                1, 2, 2, 3,
                1, 1, 3, 3)

        val nodes = longArrayOf(
                0, 1,  2,  3,
                4, 5,  6,  7,
                8, 9, 10, 11)
        val dims = longArrayOf(6, 3)
        val groundTruthImg = ArrayImgs.unsignedLongs(groundTruthLabeling, *dims)
        val nodeIdImg = ArrayImgs.unsignedLongs(LongArray(groundTruthLabeling.size) { it.toLong() }, *dims)

        val nearestNeighborEdges = listOf(
                // horizontal
                Pair(Pair(0, 1), 1.00), Pair(Pair(1,  2), 0.10), Pair(Pair( 2,  3), 0.20),
                Pair(Pair(4, 5), 0.05), Pair(Pair(5,  6), 0.97), Pair(Pair( 6,  7), 0.15),
                Pair(Pair(8, 9), 0.99), Pair(Pair(9, 10), 0.02), Pair(Pair(10, 11), 0.93),

                // vertical
                Pair(Pair(0, 4), 0.76), Pair(Pair(1, 5), 0.03), Pair(Pair(2,  6), 0.84), Pair(Pair(3,  7), 0.91),
                Pair(Pair(4, 8), 0.84), Pair(Pair(5, 9), 0.07), Pair(Pair(6, 10), 0.13), Pair(Pair(7, 11), 0.77))

        val longDistanceEdges = listOf(Pair(Pair(0, 11), 0.71), Pair(Pair(1, 6), 0.67), Pair(Pair(6, 11), 0.84))



        val edges = EdgeArray().also { e -> nearestNeighborEdges.forEach { e.addEdge(it.first.first.toLong(), it.first.second.toLong()) } }
        val edgeWeights = nearestNeighborEdges.map { it.second }.toDoubleArray()

        // with array store
        Assert.assertEquals(
                1,
                MutexWatershed.computeMutexWatershedClustering(
                        nodes.size,
                        edges,
                        EdgeArray(),
                        edgeWeights,
                        doubleArrayOf(),
                        MutexStorageArray(nodes.size)).setCount())

        // with hash map store
        Assert.assertEquals(
                1,
                MutexWatershed.computeMutexWatershedClustering(
                        nodes.size,
                        edges,
                        EdgeArray(),
                        edgeWeights,
                        doubleArrayOf(),
                        MutexStorageHashMap()).setCount())


        val mutexEdges = EdgeArray().also { e -> longDistanceEdges.forEach { e.addEdge(it.first.first.toLong(), it.first.second.toLong()) } }
        val mutexEdgeWeights = longDistanceEdges.map { it.second }.toDoubleArray()

        for (store in listOf(MutexStorageArray(nodes.size), MutexStorageHashMap())) {
            val uf = MutexWatershed.computeMutexWatershedClustering(
                    nodes.size,
                    edges,
                    mutexEdges,
                    edgeWeights,
                    mutexEdgeWeights,
                    store)

            Assert.assertEquals(nodes.size.toLong(), uf.size())
            Assert.assertEquals(groundTruthLabeling.distinct().size.toLong(), uf.setCount())

            val labeled = with(UnionFindExtensions) { uf.label(nodes.size) }

            val labelMapping = mapOf(Pair(labeled[0], 1L), Pair(labeled[2], 2L), Pair(labeled[3], 3L))
            val relabeled = labeled.map { labelMapping[it]!! }.toLongArray()
            Assert.assertArrayEquals(relabeled, groundTruthLabeling)
        }

    }

}

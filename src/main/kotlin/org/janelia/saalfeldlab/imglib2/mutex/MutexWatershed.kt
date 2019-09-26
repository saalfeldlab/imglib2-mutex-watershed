package org.janelia.saalfeldlab.imglib2.mutex

import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.algorithm.util.unionfind.UnionFind

class MutexWatershed {

    companion object {
        // check https://github.com/constantinpape/affogato/blob/edf9856899999aa3d0daf630ee67f8b5fb33a3cb/include/affogato/segmentation/mutex_watershed.hxx#L81
        @JvmStatic
        @JvmOverloads
        fun computeMutexWatershedClustering(
                numLabels: Int,
                edges: EdgeArray,
                mutexEdges: EdgeArray,
                edgeWeights: DoubleArray,
                mutexEdgeWeights: DoubleArray,
                mutexStorage: MutexStorage = MutexStorageArray(numLabels)): UnionFind {
            require(edges.size == edgeWeights.size) { "Edges and edge weights do not have the same size: ${edges.size} != ${edgeWeights.size}" }
            require(mutexEdges.size == mutexEdgeWeights.size) { "Mutex edges and mutex edge weights do not have the same size: ${mutexEdges.size} != ${mutexEdgeWeights.size}" }

            val uf = IntArrayUnionFind(numLabels)

            val numEdges = edges.size
            val numMutex = mutexEdges.size

            val indices = IntArray(numEdges + numMutex) { it }
            with (IntArrayExtensions) { indices.quicksortBy { - if (it < numEdges) edgeWeights[it] else mutexEdgeWeights[it - numEdges] } }

            for (edgeId in indices) {
                val isMutexEdge = edgeId >= numEdges
                val actualEdgeId = if (isMutexEdge) edgeId - numEdges else edgeId
                if (isMutexEdge)
                    mutexEdges.index = actualEdgeId
                else
                    edges.index = actualEdgeId
                val from = if (isMutexEdge) mutexEdges.fromAtCurrentIndex else edges.fromAtCurrentIndex
                val to = if (isMutexEdge) mutexEdges.toAtCurrentIndex else edges.toAtCurrentIndex

                val rootFrom = uf.findRoot(from)
                val rootTo = uf.findRoot(to)

                if (rootFrom == rootTo)
                    continue

                if (mutexStorage.checkMutex(rootFrom, rootTo))
                    continue

                if (isMutexEdge)
                    mutexStorage.insertMutex(rootFrom, rootTo, actualEdgeId.toLong())
                else {
                    uf.join(rootFrom, rootTo)

                    // Always only have mutexes for true roots!
                    // If the newly assigned root == rootTo, merge into rootTo,
                    // merge into rootFrom, otherwise
                    if (uf.findRoot(rootFrom) == rootTo)
                        mutexStorage.mergeMutexes(rootFrom, rootTo)
                    else
                        mutexStorage.mergeMutexes(rootTo, rootFrom)
                }
            }

            return uf

        }
    }

}

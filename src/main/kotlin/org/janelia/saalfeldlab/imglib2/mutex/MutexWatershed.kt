package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.TLongList
import gnu.trove.map.TLongIntMap
import gnu.trove.map.TLongObjectMap
import it.unimi.dsi.fastutil.ints.IntComparator
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue
import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.algorithm.util.unionfind.UnionFind
import java.util.function.IntFunction
import java.util.function.IntPredicate
import java.util.function.IntToDoubleFunction

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

            println("Quick sorting $numEdges edges by weight (descending)")

            val indices = IntArray(numEdges + numMutex) { it }
            with(IntArrayExtensions) {
                indices.quicksortBy(object : IntArrayExtensions.Values {
                    override fun get(index: Int): Double = -if (index < numEdges) edgeWeights[index] else mutexEdgeWeights[index - numEdges]
                })
            }
//            with (IntArrayExtensions) { indices.quicksortBy { - if (it < numEdges) edgeWeights[it] else mutexEdgeWeights[it - numEdges] } }

            println("USING MUTEX STORAGE ${mutexStorage::class}")

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

        @JvmStatic
        @JvmOverloads
        fun computeMutexWatershedClusteringPrim(
                numLabels: Int,
                edges: EdgeArray,
                mutexEdges: EdgeArray,
                edgeWeights: DoubleArray,
                mutexEdgeWeights: DoubleArray,
                mutexStorage: MutexStorage,
                edgeNeighbors: EdgeNeighbors
        ): IntArrayUnionFind {

            val uf = IntArrayUnionFind(numLabels)
            val numEdges = edges.size
            val numMutex = mutexEdges.size
            val indices = IntArray(numEdges + numMutex) { it }
            val visited = BooleanArray(indices.size) { false }
            val edgeWeight = IntToDoubleFunction { if (it < numEdges) edgeWeights[it] else mutexEdgeWeights[it - numEdges] }
            // compare in descending order
            val comparator = IntComparator { i1, i2 -> edgeWeight.applyAsDouble(i2).compareTo(edgeWeight.applyAsDouble(i1)) }
            val priorityQueue = IntHeapPriorityQueue(comparator)
            val appropriateEdgeArray= IntFunction { if (it < numEdges) edges.also { a -> a.index = it } else mutexEdges.also { a -> a.index = it - numEdges } }
            val wasEdgeVisited = IntPredicate { visited[it] }

            edgeNeighbors.appendNeighbors(
                    0,
                    uf,
                    wasEdgeVisited,
                    priorityQueue)

            while (!priorityQueue.isEmpty) {

                val edgeId = priorityQueue.dequeueInt()
                if (visited[edgeId])
                    continue
                visited[edgeId] = true
                val edgeArray = appropriateEdgeArray.apply(edgeId)
                val from = edgeArray.fromAtCurrentIndex
                val to = edgeArray.toAtCurrentIndex

                val rootFrom = uf.findRoot(from)
                val rootTo = uf.findRoot(to)

                if (rootFrom == rootTo)
                    continue

                if (mutexStorage.checkMutex(rootFrom, rootTo))
                    continue

                val isMutexEdge = edgeId >= numEdges

                if (isMutexEdge)
                    mutexStorage.insertMutex(rootFrom, rootTo, (edgeId - numEdges).toLong())
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

                edgeNeighbors.appendNeighbors(
                        to,
                        uf,
                        wasEdgeVisited,
                        priorityQueue)

            }

            return uf

        }
    }

    interface EdgeNeighbors {

        fun appendNeighbors(
                nodeId: Long,
                uf: UnionFind,
                wasEdgeVisited: IntPredicate,
                queue: IntHeapPriorityQueue)

    }

    class DefaultEdgeNeighbors(
            private val nodeNeighborMap: TLongObjectMap<LongArray>,
            private val nodeEdgeMap: TLongObjectMap<IntArray>) : EdgeNeighbors {

        override fun appendNeighbors(
                nodeId: Long,
                uf: UnionFind,
                wasEdgeVisited: IntPredicate,
                queue: IntHeapPriorityQueue) {
            val root = uf.findRoot(nodeId)
            val edges = nodeEdgeMap[nodeId]!!
            val neighbors = nodeNeighborMap[nodeId]!!
            for (idx in edges.indices) {
                val edge = edges[idx]
                if (wasEdgeVisited.test(edge))
                    continue
                val neighbor = neighbors[idx]
                val neighborRoot = uf.findRoot(neighbor)
                if (root != neighborRoot)
                    queue.enqueue(edge)
            }
        }

    }

}

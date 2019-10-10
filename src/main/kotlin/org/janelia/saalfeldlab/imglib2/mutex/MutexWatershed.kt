package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.array.TDoubleArrayList
import gnu.trove.map.TLongObjectMap
import gnu.trove.map.hash.TLongLongHashMap
import it.unimi.dsi.fastutil.ints.IntComparator
import it.unimi.dsi.fastutil.ints.IntHeapPriorityQueue
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.algorithm.util.unionfind.UnionFind
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.RealType
import net.imglib2.util.Intervals
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.function.*
import kotlin.random.Random

typealias IsAttractiveOffset = java.util.function.Function<LongArray, DoublePredicate>

typealias IsAttractiveIndex = IntFunction<DoublePredicate>

typealias EdgeProbabilityFromOffset = ToDoubleFunction<LongArray>

typealias EdgeProbabilityFromIndex = IntToDoubleFunction

class MutexWatershed {

    companion object {

        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

        @JvmOverloads
        @JvmStatic
        // any NaN edge will be ignored
        fun <T, I> computeMutexWatershedClustering(
            affinities: RandomAccessibleInterval<T>,
            target: RandomAccessibleInterval<I>,
            offsets: Array<LongArray>,
            edgeProbabilities: DoubleArray,
            threshold: Double = 0.5,
            random: DoubleSupplier = DoubleSupplier { Random.nextDouble() }): Long
                where
                T: RealType<T>,
                I: IntegerType<I>,
                I: NativeType<I> {
            return computeMutexWatershedClustering(
                affinities = affinities,
                target = target,
                offsets = offsets,
                edgeProbabilityFromOffset = EdgeProbabilityFromIndex {  edgeProbabilities[it] },
                isAttractiveOffsetGenerator = IsAttractiveIndex { DoublePredicate { it >= threshold } },
                random = random)
        }

        @JvmOverloads
        @JvmStatic
        // any NaN edge will be ignored
        fun <T, I> computeMutexWatershedClustering(
            affinities: RandomAccessibleInterval<T>,
            target: RandomAccessibleInterval<I>,
            offsets: Array<LongArray>,
            edgeProbabilities: DoubleArray,
            attractiveEdges: BooleanArray,
            random: DoubleSupplier = DoubleSupplier { Random.nextDouble() }): Long
                where
                T: RealType<T>,
                I: IntegerType<I>,
                I: NativeType<I> {
            return computeMutexWatershedClustering(
                affinities = affinities,
                target = target,
                offsets = offsets,
                edgeProbabilityFromOffset = EdgeProbabilityFromIndex {  edgeProbabilities[it] },
                isAttractiveOffsetGenerator = IsAttractiveIndex { val isAttractive = attractiveEdges[it]; DoublePredicate { isAttractive } },
                random = random)
        }

        @JvmOverloads
        @JvmStatic
        // any NaN edge will be ignored
        fun <T, I> computeMutexWatershedClusteringFromOffset(
            affinities: RandomAccessibleInterval<T>,
            target: RandomAccessibleInterval<I>,
            offsets: Array<LongArray>,
            edgeProbabilityFromOffset: EdgeProbabilityFromOffset = EdgeProbabilityFromOffset { 1.0 },
            isAttractiveOffsetGenerator: IsAttractiveOffset = IsAttractiveOffset { o -> val isNearest = o.map { it*it }.sum() <= 1L; DoublePredicate { isNearest } },
            random: DoubleSupplier = DoubleSupplier { Random.nextDouble() }): Long
                where
                T: RealType<T>,
                I: IntegerType<I>,
                I: NativeType<I> = Companion.computeMutexWatershedClustering(
            affinities = affinities,
            target = target,
            offsets = offsets,
            edgeProbabilityFromOffset = EdgeProbabilityFromIndex { edgeProbabilityFromOffset.applyAsDouble(offsets[it]) },
            isAttractiveOffsetGenerator = IsAttractiveIndex { isAttractiveOffsetGenerator.apply(offsets[it]) },
            random = random)

        @JvmOverloads
        @JvmStatic
        // any NaN edge will be ignored
        fun <T, I> computeMutexWatershedClustering(
            affinities: RandomAccessibleInterval<T>,
            target: RandomAccessibleInterval<I>,
            offsets: Array<LongArray>,
            edgeProbabilityFromOffset: EdgeProbabilityFromIndex = EdgeProbabilityFromIndex { 1.0 },
            isAttractiveOffsetGenerator: IsAttractiveIndex = IsAttractiveIndex { i -> val isNearest = offsets[i].map { it*it }.sum() <= 1L; DoublePredicate { isNearest } },
            random: DoubleSupplier = DoubleSupplier { Random.nextDouble() }): Long
                where
                T: RealType<T>,
                I: IntegerType<I>,
                I: NativeType<I> {

            val nDim = affinities.numDimensions()

            require(offsets.size.toLong() == affinities.dimension(nDim - 1)) {
                "Dimensionality mismatch: ${offsets.size} != ${affinities.dimension(nDim - 1)}. Number of offsets must be the same as last dimension of affinities."
            }

            var count = 0L
            Views.flatIterable(target).forEach { it.setInteger(count++) }
            val numNodes = count

            val invalidExtension = Util.getTypeFromInterval(target).createVariable().also { it.setInteger(-1L) }
            val targetExtended = Views.extendValue(target, invalidExtension)

            val edges            = EdgeArray()
            val mutexEdges       = EdgeArray()
            val edgeWeights      = TDoubleArrayList()
            val mutexEdgeWeights = TDoubleArrayList()

            for ((index, offset) in offsets.withIndex()) {
                LOG.debug("Collecting edges for offset {}: {}", index, offset)
                val edgeSlice = Views.hyperSlice(affinities, nDim - 1, index.toLong())
                val toTarget = Views.interval(targetExtended, Intervals.translate(target, *offset))

                val edgeProbability = edgeProbabilityFromOffset.applyAsDouble(index)
                val edgeReject = when {
                    edgeProbability >= 1.0 -> DoublePredicate { it.isNaN() }
                    edgeProbability <= 0.0 -> DoublePredicate { true }
                    else -> DoublePredicate { it.isNaN() || random.asDouble >= edgeProbability }
                }

                LOG.debug("Offset {} ({}) has edgeProbability={}", index, offset, edgeProbability)

                if (edgeProbability <= 0.0)
                    continue

                val isAttractive = isAttractiveOffsetGenerator.apply(index)

                LoopBuilder.setImages(edgeSlice, target, toTarget).forEachPixel(LoopBuilder.TriConsumer { a: T, f: I, t: I ->
                    val affinity = a.realDouble

                    if (edgeReject.test(affinity) || invalidExtension.valueEquals(f) || invalidExtension.valueEquals(t))
                        return@TriConsumer

                    if (isAttractive.test(affinity)) {
                        edges.addEdge(f.integerLong, t.integerLong)
                        edgeWeights.add(affinity)
                    } else {
                        mutexEdges.addEdge(f.integerLong, t.integerLong)
                        mutexEdgeWeights.add(1.0 - affinity)
                    }
                })
            }

            LOG.debug("Collected {} attractive and {} mutex edges.", edges.size, mutexEdges.size)

            val uf = computeMutexWatershedClustering(
                numLabels        = numNodes.toInt(),
                edges            = edges,
                mutexEdges       = mutexEdges,
                edgeWeights      = edgeWeights.toArray(),
                mutexEdgeWeights = mutexEdgeWeights.toArray())
            val (remapping, nextId) = with (UnionFindExtensions) { TLongLongHashMap().let { Pair(it, uf.relabelRoots(it, 1L)) } }
            Views.iterable(target).forEach { it.setInteger(remapping[uf.findRoot(it.integerLong)]) }

            return nextId

        }

        // check https://github.com/constantinpape/affogato/blob/edf9856899999aa3d0daf630ee67f8b5fb33a3cb/include/affogato/segmentation/mutex_watershed.hxx#L81
        @JvmStatic
        @JvmOverloads
        fun computeMutexWatershedClustering(
                numLabels: Int,
                edges: EdgeArray,
                mutexEdges: EdgeArray,
                edgeWeights: DoubleArray,
                mutexEdgeWeights: DoubleArray,
                mutexStorage: MutexStorage = MutexStorageArray(numLabels)): IntArrayUnionFind {
            require(edges.size == edgeWeights.size) { "Edges and edge weights do not have the same size: ${edges.size} != ${edgeWeights.size}" }
            require(mutexEdges.size == mutexEdgeWeights.size) { "Mutex edges and mutex edge weights do not have the same size: ${mutexEdges.size} != ${mutexEdgeWeights.size}" }

            val uf = IntArrayUnionFind(numLabels)

            val numEdges = edges.size
            val numMutex = mutexEdges.size

            val indices = IntArray(numEdges + numMutex) { it }
            with(IntArrayExtensions) {
                indices.quicksortBy(object : IntArrayExtensions.Values {
                    override fun get(index: Int): Double = -if (index < numEdges) edgeWeights[index] else mutexEdgeWeights[index - numEdges]
                })
            }
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

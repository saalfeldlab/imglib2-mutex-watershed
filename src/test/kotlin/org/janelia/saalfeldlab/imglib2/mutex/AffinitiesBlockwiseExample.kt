package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.map.TLongLongMap
import gnu.trove.map.TLongObjectMap
import gnu.trove.map.hash.TLongLongHashMap
import gnu.trove.map.hash.TLongObjectHashMap
import net.imglib2.FinalInterval
import net.imglib2.Interval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.util.Grids
import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.img.array.ArrayImgs
import net.imglib2.imklib.extensions.AX
import net.imglib2.imklib.extensions.flatIterable
import net.imglib2.imklib.extensions.get
import net.imglib2.imklib.extensions.iterable
import net.imglib2.imklib.extensions.maxAsLongs
import net.imglib2.imklib.extensions.minAsLongs
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.ConstantUtils
import net.imglib2.util.Intervals
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.DataType
import org.janelia.saalfeldlab.n5.DatasetAttributes
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.DoubleSupplier
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

fun main() {
    val containerPath = "/home/hanslovskyp/workspace/mutex-watershed/mutex-watershed-notebook/sample_A.n5"
    val container     = N5FSWriter(containerPath)
    val dataset       = "affinities"

    val affinities= N5Utils.open<FloatType>(container, dataset)
//             unccomment the following for subset of image:
//            .let {
//                it[Intervals.createMinMax(0L, 0L, 0L, it.min(3), 768L, 768L, 512L, it.max(3))]
//            }
    // TODO load actual mask and glia mask
    val gliaMask = ConstantUtils.constantRandomAccessible(FloatType(0.0f), 3)
    val marginLower = intArrayOf(128, 128, 128)
    val marginUpper = intArrayOf(256, 256, 128)
    val validMin = LongArray(3) { affinities.min(it) + marginLower[it] }
    val validMax = LongArray(3) { affinities.max(it) - marginUpper[it] }
    val validInterval = FinalInterval(validMin, validMax)
    val mask = Views.extendZero(ConstantUtils.constantRandomAccessibleInterval(
            UnsignedLongType(1),
            validInterval.numDimensions(),
            validInterval))
    val resolution = doubleArrayOf(108.0, 108.0, 120.0)
    val blockSize = IntArray(3) {64}
    val blocksPerTask = intArrayOf(1, 1, 1)
    val taskSize = IntArray(3) {blockSize[it] * blocksPerTask[it]}
    val numThreads = 8
    val es = Executors.newFixedThreadPool(numThreads)

    val offsets       = arrayOf(
        longArrayOf(-1, 0, 0), longArrayOf(-2, 0, 0), longArrayOf(-5, 0, 0), longArrayOf(-10, 0, 0),
        longArrayOf(0, -1, 0), longArrayOf(0, -2, 0), longArrayOf(0, -5, 0), longArrayOf(0, -10, 0),
        longArrayOf(0, 0, -1), longArrayOf(0, 0, -2), longArrayOf(0, 0, -5), longArrayOf(0, 0, -10))
        .map { it.reversedArray() }

//    offsets.forEachIndexed { index, o -> println("Offset $index: ${Arrays.toString(o)}") }

    val defaultProbability = 0.05
    val probability1 = 1.0
    val probability2 = defaultProbability
    val probability5 = defaultProbability
    val probability10 = defaultProbability

    val probabilities = doubleArrayOf(
        probability1, probability2, probability5, probability10,
        probability1, probability2, probability5, probability10,
        probability1, probability2, probability5, probability10)

    val withoutChannels = affinities[AX, AX, AX, 0L]

    val mutexWatershedDataset = "mutex-watershed"
    val relabeledMutexWatershedDataset = "mutex-watershed-relabeled"
    val mergedMutexWatershedDataset = "mutex-watershed-merged"

    val attributes = DatasetAttributes(Intervals.dimensionsAsLongArray(withoutChannels), blockSize, DataType.UINT64, GzipCompression())

    for (ds in arrayOf(mutexWatershedDataset, relabeledMutexWatershedDataset, mergedMutexWatershedDataset)) {
        container.createDataset(ds, attributes)
        container.setAttribute(ds, "resolution", resolution)
        container.getAttribute(dataset, "offset", LongArray::class.java)?.let { container.setAttribute(ds, "offset", it) }
    }

    val blocks = Grids
            .collectAllContainedIntervals(withoutChannels.minAsLongs(), withoutChannels.maxAsLongs(), taskSize)
            .map { if (it is FinalInterval) it else FinalInterval(it) }

    val rng = Random(100L)

    // generate initial watershed segmentation. Label ids are not unique across blocks
    val futures = blocks.map { block ->


        val task = Callable<Pair<FinalInterval, Long>> {

            if (Intervals.isEmpty(Intervals.intersect(block, validInterval))) {
//                println("Block $block outside valid data interval $validInterval. Not doing anything.")
                return@Callable Pair(block, 0L)
            }

            val minWithChannel = LongArray(3) { block.min(it) } + longArrayOf(affinities.min(3))
            val maxWithChannel = LongArray(3) { block.max(it) } + longArrayOf(affinities.max(3))
            val intervalWithChannel = FinalInterval(minWithChannel, maxWithChannel)

            val blockOffset = LongArray(3) { block.min(it) / blockSize[it] }

//            println("Running mutex watershed for block $block")
            val target = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(block))
            val maskFrom = Views.interval(mask, block)
            val gliaMaskFrom = Views.interval(gliaMask, block)
            val affinitiesBlock: RandomAccessibleInterval<FloatType> = if (maskFrom.anyZero() || gliaMaskFrom.anyNotOne()) {
//                println("Copying affinities and adjusting for mask and glia mask")
                val affinitiesCopy = ArrayImgs.floats(*Intervals.dimensionsAsLongArray(intervalWithChannel))
                for ((index, offset) in offsets.withIndex()) {
                    val affinitiesSlice = Views.hyperSlice(Views.zeroMin(affinities[intervalWithChannel]), 3, index.toLong())
                    val affinitiesCopySlice = Views.hyperSlice(affinitiesCopy, 3, index.toLong())
                    val maskTo = Views.interval(mask, Intervals.translate(block, *offset))
                    val gliaMaskTo = Views.interval(gliaMask, Intervals.translate(block, *offset))
                    LoopBuilder
                            .setImages(affinitiesSlice, affinitiesCopySlice, maskFrom, maskTo, gliaMaskFrom, gliaMaskTo)
                            .forEachPixel(LoopBuilder.SixConsumer { s, t, mf, mt, gf, gt ->
                                if (mf.integerLong == 0L || mt.integerLong == 0L)
                                    t.set(Float.NaN)
                                else {
                                    val gwf = 1.0 - min(max(gf.realDouble, 0.0), 1.0)
                                    val gwt = 1.0 - min(max(gt.realDouble, 0.0), 1.0)
                                    t.setReal(s.realDouble * gwf * gwt)
                                }
                            })
                }
                affinitiesCopy
            } else
                Views.zeroMin(affinities[intervalWithChannel])
            val nextId = MutexWatershed.computeMutexWatershedClustering(
                    affinities = affinitiesBlock,
                    target = target,
                    offsets = offsets.toTypedArray(),
                    edgeProbabilities = probabilities,
                    // use either attractiveEdges or threshold
//                    attractiveEdges = offsets.map { o -> o.map { it * it }.sum() <= 1L }.toBooleanArray(),
                    threshold = 0.5,
                    random = DoubleSupplier { rng.nextDouble() })

//            println("Saving mutex watershed in dataset `mutex-watershed'. Max id=${nextId - 1} for block $block")

            var index = 1L
            val mapping = TLongLongHashMap()
            mapping.put(0, 0)

            val counts = TLongLongHashMap()
            target.flatIterable().forEach {
                val k = it.integerLong
                if (!mapping.contains(k)) {
                    mapping.put(k, index)
                    ++index
                }
                val v = mapping[k]
                it.setInteger(v)
                counts.put(v, counts[v] + 1)
            }

            target.flatIterable().forEach {
                val v = it.integerLong
                if (counts[v] <= 1) {
                    it.setZero()
                    counts.remove(v)
                    counts.put(0L, counts[0L] + 1)
                }
            }

            N5Utils.saveBlock(target, container, mutexWatershedDataset, attributes, blockOffset)

            Pair(block, index + 1)
        }
        es.submit(task)
    }

    // relabel with non-overlapping labels
    val counts = futures.map { it.get() }
//    println(counts)
    var totalCount = 0L
    val accumulatedCounts = mutableListOf<Pair<FinalInterval, Long>>()
    for ((block, count) in counts) {
        accumulatedCounts.add(Pair(block, totalCount + 1))
        totalCount += count
    }
    totalCount += 1

    run {
        val labels = N5Utils.open<UnsignedLongType>(container, mutexWatershedDataset)
//    println("Relabeling to ensure non-overlap label ids")
        val futures2 = accumulatedCounts.map { (block, startIndex) ->
            val task = Callable<Any?> {
                var index = startIndex
                val target = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(block))
                val idMapping = TLongLongHashMap()
                var anyNonZero = false
                LoopBuilder.setImages(Views.interval(labels, block), target).forEachPixel(BiConsumer { s, t ->
                    val v = s.integerLong
                    if (v == 0L)
                        t.setZero()
                    else {
                        if (!idMapping.contains(v)) {
                            idMapping.put(v, index)
                            ++index
                        }
                        t.setInteger(idMapping[v])
                        anyNonZero = true
                    }
                })
                val blockOffset = LongArray(3) { block.min(it) / blockSize[it] }

//                println("${index - startIndex} ${idMapping.size()} ${counts.firstOrNull { it.first == block }?.second}")
                if (anyNonZero)
                    N5Utils.saveBlock(target, container, relabeledMutexWatershedDataset, attributes, blockOffset)
                null
            }
            es.submit(task)
        }
        futures2.forEach { it.get() }
    }

    val uf = IntArrayUnionFind(totalCount.toInt() + 1)
    run {
        val labels = N5Utils.open<UnsignedLongType>(container, relabeledMutexWatershedDataset)

        for (d in 0 until 3) {
            val futures3 = blocks.map { block ->
                val task = Callable<TLongLongMap> {
                    val mapWithCountsForward = TLongObjectHashMap<TLongLongMap>()
                    val mapWithCountsBackward = TLongObjectHashMap<TLongLongMap>()
                    val map = TLongLongHashMap()
                    val max = block.maxAsLongs()
                    val min = block.minAsLongs().also { it[d] = max[d] }
                    val thisSlice = Views.interval(labels, min, max)
                    val thatSlice = Views.interval(labels, Intervals.translate(thisSlice, 1L, d))

                    if (!labels.contains(thatSlice))
                        return@Callable map

                    LoopBuilder.setImages(thisSlice, thatSlice).forEachPixel(BiConsumer { p1, p2 ->
                        val v1 = p1.integerLong
                        val v2 = p2.integerLong

                        if (v1 == 0L || v2 == 0L)
                            return@BiConsumer

                        if (!mapWithCountsForward.contains(v1))
                            mapWithCountsForward.put(v1, TLongLongHashMap())
                        mapWithCountsForward[v1].let { it.put(v2, it[v2] + 1) }
                        if (!mapWithCountsBackward.contains(v2))
                            mapWithCountsBackward.put(v2, TLongLongHashMap())
                        mapWithCountsBackward[v2].let { it.put(v1, it[v1] + 1) }
                    })

    //                println("m1 $mapWithCountsForward m2 $mapWithCountsBackward")
                    mapWithCountsForward.forEachEntry { k1, v1 ->
                        val argMax1 = v1.argMax()
                        if (argMax1 != 0L && mapWithCountsBackward.containsKey(argMax1) && mapWithCountsBackward[argMax1].argMax() == k1)
                            map.put(k1, argMax1)
                        true
                    }

                    map

                }
                es.submit(task)
            }
            futures3.forEach { it.get().forEachEntry { v1, v2 -> uf.join(uf.findRoot(v1), uf.findRoot(v2)); true } }
        }
    }

    if (true) {
        val labels = N5Utils.open<UnsignedLongType>(container, relabeledMutexWatershedDataset)
        labels.cache.invalidateAll()
        val futures4 = blocks.map { block ->
            val task = Callable<Any?> {
                val target = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(block))
                var anyNonZero = false
                LoopBuilder.setImages(Views.interval(labels, block), target).forEachPixel(BiConsumer { s, t ->
                    val v = s.integerLong
                    if (v == 0L)
                        t.setZero()
                    else {
                        t.setInteger(uf.findRoot(v))
                        anyNonZero = true
                    }
                })
                val blockOffset = LongArray(3) { block.min(it) / blockSize[it] }

                if (anyNonZero)
                    N5Utils.saveBlock(target, container, mergedMutexWatershedDataset, attributes, blockOffset)
                null
            }
            es.submit(task)
        }
        futures4.forEach { it.get() }
    }

    es.shutdown()
}

private fun <T: RealType<T>> RandomAccessibleInterval<T>.anyZero(): Boolean {
    val comp = Util.getTypeFromInterval(this).createVariable().also { it.setZero() }
    return iterable().any { comp.valueEquals(it) }
}

private fun <T: RealType<T>> RandomAccessibleInterval<T>.anyNotOne(): Boolean {
    val comp = Util.getTypeFromInterval(this).createVariable().also { it.setReal(1.0) }
    return iterable().any { !comp.valueEquals(it) }
}

private fun Interval.contains(other: Interval) = Intervals.contains(this, other)

private fun Interval.minAsLongs() = Intervals.minAsLongArray(this)
private fun Interval.maxAsLongs() = Intervals.maxAsLongArray(this)

private fun TLongLongMap.argMax(): Long {
    var argMax = -1L
    var max = Long.MIN_VALUE
    forEachEntry { k, v ->
        if (v > max) {
            max = v
            argMax = k
        }
        true
    }
    return argMax
}

package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.map.TLongLongMap
import gnu.trove.map.hash.TLongLongHashMap
import gnu.trove.map.hash.TLongObjectHashMap
import net.imglib2.FinalInterval
import net.imglib2.Interval
import net.imglib2.RandomAccessibleInterval
import net.imglib2.algorithm.util.Grids
import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.converter.Converter
import net.imglib2.converter.Converters
import net.imglib2.img.array.ArrayImgs
import net.imglib2.imklib.extensions.AX
import net.imglib2.imklib.extensions.flatIterable
import net.imglib2.imklib.extensions.get
import net.imglib2.imklib.extensions.iterable
import net.imglib2.imklib.extensions.maxAsLongs
import net.imglib2.imklib.extensions.minAsLongs
import net.imglib2.loops.LoopBuilder
import net.imglib2.type.NativeType
import net.imglib2.type.logic.BitType
import net.imglib2.type.numeric.RealType
import net.imglib2.type.numeric.integer.UnsignedLongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.ConstantUtils
import net.imglib2.util.Intervals
import net.imglib2.util.StopWatch
import net.imglib2.util.Util
import net.imglib2.view.Views
import org.janelia.saalfeldlab.n5.*
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.BiConsumer
import java.util.function.DoubleSupplier
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime


@ExperimentalTime
fun main() {
    val samples = arrayOf("A", "B", "C", "0", "1", "2").reversedArray()
    val setups = intArrayOf(0, 3)
    val thresholds = arrayOf(null, 0.5, 0.3, 0.7, 0.1, 0.9)
    val numThreads = 47
    val es = Executors.newFixedThreadPool(numThreads)

    val overwriteExisting = false

    for (setup in setups)
        for (threshold in thresholds)
            for (sample in samples) {
                val time = measureTime { runMutexWatersheds(sample, setup, threshold, overwriteExisting, es) }
                println("Ran mutex watersheds for sample=$sample setup=$setup threshold=$threshold in ${time.inSeconds} seconds")
                val gliaTime = measureTime { writeGlia(CremiData(sample, setup, 500000, threshold), es, true) }
                println("Wrote glia for sample=$sample setup=$setup threshold=$threshold in ${gliaTime.inSeconds} seconds")
            }

    es.shutdown()

}

private class CremiData(
    private val sample: String,
    private val setup: Int,
    private val iteration: Int,
    private val threshold: Double?) {
    val datasetBasePath = "volumes/predictions/neuron_ids-unlabeled-unmask-background/$setup/$iteration"
    val containerPath = "/nrs/saalfeld/hanslovskyp/experiments/quasi-isotropic-predictions/affinities-glia/neuron_ids-unlabeled-unmask-background/predictions/CREMI/sample_$sample.n5"
    val container = N5FSWriter(containerPath)
    val gliaMaskContainer = N5FSReader(containerPath)
    val gliaMaskDataset = "$datasetBasePath/glia"
    val dataset = "$datasetBasePath/affinities"
    val maskDataset = "volumes/masks/prediction-mask"
    val maskContainer = N5FSReader(containerPath)
    val mutexWatershedBase = threshold?.let { "mutex-watershed-threshold=$it" } ?: "mutex-watershed"
    val mutexWatershedDataset = "$datasetBasePath/$mutexWatershedBase"
    val relabeledMutexWatershedDataset = "$mutexWatershedDataset-relabeled"
    val mergedMutexWatershedDataset = "$mutexWatershedDataset-merged"
    val mergedMutexWatershedDatasetWithGlia = "$mergedMutexWatershedDataset-with-glia"
    val outputContainer = N5FSWriter(containerPath)
}
private val resolution = doubleArrayOf(108.0, 108.0, 120.0)
private val blockSize = IntArray(3) {64}
private val blocksPerTask = intArrayOf(1, 1, 1)
private val taskSize = IntArray(3) {blockSize[it] * blocksPerTask[it]}

fun runMutexWatersheds(
    sample: String,
    setup: Int,
    threshold: Double?,
    overwriteExisting: Boolean,
    es: ExecutorService
) {
    val cremiData = CremiData(sample, setup, 500000, threshold)

    val affinities= N5Utils.open<FloatType>(cremiData.container, cremiData.dataset)
    // TODO load actual mask and glia mask
    val gliaMask = cremiData.gliaMaskDataset
        ?.takeIf { cremiData.gliaMaskContainer.datasetExists(it) }
        ?.let { cremiData.gliaMaskContainer.loadWithOffset<FloatType>(cremiData.gliaMaskDataset) }
        ?.let { Views.extendZero(it) }
        ?: ConstantUtils.constantRandomAccessible(FloatType(0.0f), 3)
    val mask = cremiData.maskDataset
        ?.takeIf { cremiData.maskContainer.datasetExists(it) }
        ?.let { cremiData.maskContainer.loadWithOffset<UnsignedLongType>(cremiData.maskDataset) }
        ?.let{ Views.extendZero(it) }
        ?: run {
            val marginLower = intArrayOf(128, 128, 128)
            val marginUpper = intArrayOf(256, 256, 128)
            val validMin = LongArray(3) { affinities.min(it) + marginLower[it] }
            val validMax = LongArray(3) { affinities.max(it) - marginUpper[it] }
            val validInterval = FinalInterval(validMin, validMax)
            Views.extendZero(
                ConstantUtils.constantRandomAccessibleInterval(
                    UnsignedLongType(1),
                    validInterval.numDimensions(),
                    validInterval
                )
            )
        }

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

    if (cremiData.outputContainer.exists(cremiData.mergedMutexWatershedDataset)
        && cremiData.outputContainer.getAttribute(cremiData.mergedMutexWatershedDataset, "completedSuccessfully", Boolean::class.java) == true
        && !overwriteExisting) {
        println("Already successfully completed mutex watersheds for dataset ${cremiData.mergedMutexWatershedDataset} in container ${cremiData.outputContainer} -- skipping.")
        return
    }

    val attributes = DatasetAttributes(Intervals.dimensionsAsLongArray(withoutChannels), blockSize, DataType.UINT64, GzipCompression())

    for (ds in arrayOf(cremiData.mutexWatershedDataset, cremiData.relabeledMutexWatershedDataset, cremiData.mergedMutexWatershedDataset)) {
        cremiData.outputContainer.createDataset(ds, attributes)
        cremiData.outputContainer.setAttribute(ds, "resolution", resolution)
        cremiData
            .container
            .getAttribute(cremiData.dataset, "offset", DoubleArray::class.java)
            ?.let { cremiData.outputContainer.setAttribute(ds, "offset", it) }
    }

    val blocks = Grids
            .collectAllContainedIntervals(withoutChannels.minAsLongs(), withoutChannels.maxAsLongs(), taskSize)
            .map { if (it is FinalInterval) it else FinalInterval(it) }

    val rng = Random(100L)

    // generate initial watershed segmentation. Label ids are not unique across blocks
    val futures = blocks.map { block ->


        val task = Callable<Pair<FinalInterval, Long>> {

//            if (Intervals.isEmpty(Intervals.intersect(block, validInterval))) {
////                println("Block $block outside valid data interval $validInterval. Not doing anything.")
//                return@Callable Pair(block, 0L)
//            }

            val minWithChannel = LongArray(3) { block.min(it) } + longArrayOf(affinities.min(3))
            val maxWithChannel = LongArray(3) { block.max(it) } + longArrayOf(affinities.max(3))
            val intervalWithChannel = FinalInterval(minWithChannel, maxWithChannel)

            val blockOffset = LongArray(3) { block.min(it) / blockSize[it] }

//            println("Running mutex watershed for block $block")
            val target = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(block))
            val maskFrom = Views.interval(mask, block)
            val gliaMaskFrom = Views.interval(gliaMask, block)
            val sw = StopWatch.createAndStart()
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
                                if (mf.integerLong != 1L || mt.integerLong != 1L)
                                    t.set(Float.NaN)
                                else {
                                    val gwf = 1.0 - min(max(gf.realDouble, 0.0), 1.0)
                                    val gwt = 1.0 - min(max(gt.realDouble, 0.0), 1.0)
                                    // ignore edges entirely inside glia
                                    if (gwf < 0.5 && gwt < 0.5)
                                        t.set(Float.NaN)
                                    else
                                        t.setReal(s.realDouble * gwf * gwt)
                                }
                            })
                }
                affinitiesCopy
            } else
                Views.zeroMin(affinities[intervalWithChannel])
            sw.stop()
//            println("Prepared affinities in ${StopWatch.secondsToString(sw.seconds())}")
            val nextId = if (threshold === null)
                MutexWatershed.computeMutexWatershedClustering(
                    affinities = affinitiesBlock,
                    target = target,
                    offsets = offsets.toTypedArray(),
                    edgeProbabilities = probabilities,
                    attractiveEdges = offsets.map { o -> o.map { it * it }.sum() <= 1L }.toBooleanArray(),
                    random = DoubleSupplier { rng.nextDouble() })
            else
                MutexWatershed.computeMutexWatershedClustering(
                    affinities = affinitiesBlock,
                    target = target,
                    offsets = offsets.toTypedArray(),
                    edgeProbabilities = probabilities,
                    threshold = threshold,
                    random = DoubleSupplier { rng.nextDouble() })

//            println("Saving mutex watershed in dataset `mutex-watershed'. Max id=${nextId - 1} for block $block")

            val counts = TLongLongHashMap()
            target.flatIterable().forEach {
                val v = it.integerLong
                counts.put(v, counts[v] + 1)
            }

            var index = 1L
            val mapping = TLongLongHashMap()
            mapping.put(0, 0)

            target.flatIterable().forEach {
                val k = it.integerLong
                if (counts[k] <= 1) {
                    it.setZero()
                    counts.remove(k)
                    counts.put(0L, counts[0L] + 1)
                } else {
                    if (!mapping.contains(k)) {
                        mapping.put(k, index)
                        ++index
                    }
                    it.setInteger(mapping[k])
                }
            }

            N5Utils.saveBlock(target, cremiData.outputContainer, cremiData.mutexWatershedDataset, attributes, blockOffset)

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
        val labels = N5Utils.open<UnsignedLongType>(cremiData.outputContainer, cremiData.mutexWatershedDataset)
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
                    N5Utils.saveBlock(target, cremiData.outputContainer, cremiData.relabeledMutexWatershedDataset, attributes, blockOffset)
                null
            }
            es.submit(task)
        }
        futures2.forEach { it.get() }
    }

    val uf = IntArrayUnionFind(totalCount.toInt() + 1)
    run {
        val labels = N5Utils.open<UnsignedLongType>(cremiData.outputContainer, cremiData.relabeledMutexWatershedDataset)

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
        val labels = N5Utils.open<UnsignedLongType>(cremiData.outputContainer, cremiData.relabeledMutexWatershedDataset)
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
                    N5Utils.saveBlock(target, cremiData.outputContainer, cremiData.mergedMutexWatershedDataset, attributes, blockOffset)
                null
            }
            es.submit(task)
        }
        futures4.forEach { it.get() }
    }

    cremiData.outputContainer.setAttribute(cremiData.mergedMutexWatershedDataset, "completedSuccessfully", true)

}

private fun writeGlia(
    cremiData: CremiData,
    es: ExecutorService,
    overwriteExisting: Boolean,
    gliaId: Long = -10L) {

    if (cremiData.outputContainer.exists(cremiData.mergedMutexWatershedDatasetWithGlia)
        && cremiData.outputContainer.getAttribute(cremiData.mergedMutexWatershedDatasetWithGlia, "completedSuccessfully", Boolean::class.java) == true
        && !overwriteExisting) {
        println("Already successfully adding glia to mutex watersheds for dataset ${cremiData.mergedMutexWatershedDatasetWithGlia} in container ${cremiData.outputContainer} -- skipping.")
        return
    }

    val gliaMask = cremiData
        .gliaMaskDataset
        .takeIf { cremiData.gliaMaskContainer.datasetExists(it) }
        ?.let { cremiData.gliaMaskContainer.loadWithOffset<FloatType>(cremiData.gliaMaskDataset) }
        ?.let { Views.extendZero(it) }
        ?.let { Converters.convert(it, Converter<FloatType, BitType> { s, t -> t.set(s.realDouble > 0.5) }, BitType()) }
    if (gliaMask === null) {
        println("No glia mask provided")
        return
    }
    val merged = cremiData
        .mergedMutexWatershedDataset
        .takeIf { cremiData.outputContainer.datasetExists(it) }
        ?.let { cremiData.outputContainer.loadWithOffset<UnsignedLongType>(cremiData.mergedMutexWatershedDataset) }

    if (merged === null) {
        println("No merged mutex watershed dataset provided")
        return
    }

    val attributes = DatasetAttributes(Intervals.dimensionsAsLongArray(merged), blockSize, DataType.UINT64, GzipCompression())
    cremiData.outputContainer.createDataset(cremiData.mergedMutexWatershedDatasetWithGlia, attributes)
    cremiData.outputContainer.setAttribute(cremiData.mergedMutexWatershedDatasetWithGlia, "resolution", resolution)
    cremiData
        .container
        .getAttribute(cremiData.dataset, "offset", DoubleArray::class.java)
        ?.let { cremiData.outputContainer.setAttribute(cremiData.mergedMutexWatershedDatasetWithGlia, "offset", it) }

    val gliaLabel = UnsignedLongType(gliaId)

    val blocks = Grids.collectAllContainedIntervalsWithGridPositions(
        Intervals.dimensionsAsLongArray(merged),
        blockSize)
    val futures = blocks.map {
        val task = Callable {
            val gliaBlock = Views.interval(gliaMask, it.a)
            val mergedBlock = Views.interval(merged, it.a)
            val target = ArrayImgs.unsignedLongs(*Intervals.dimensionsAsLongArray(it.a))

            var anyNonZero = false

            LoopBuilder
                .setImages(gliaBlock, mergedBlock, target)
                .forEachPixel(LoopBuilder.TriConsumer { g, m, t ->
                    t.set(if (g.get()) gliaLabel else m)
                    anyNonZero = anyNonZero || t.integerLong != 0L
                })

            if (anyNonZero) {
                N5Utils.saveBlock(
                    target,
                    cremiData.outputContainer,
                    cremiData.mergedMutexWatershedDatasetWithGlia,
                    attributes,
                    it.b
                )
            }
        }
        es.submit(task)
    }
    futures.forEach { it.get() }
    cremiData.outputContainer.setAttribute(cremiData.mergedMutexWatershedDatasetWithGlia, "completedSuccessfully", true)


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

private fun <T: NativeType<T>> N5Reader.loadWithOffset(dataset: String): RandomAccessibleInterval<T> {
    val resolution = this.getAttribute(dataset, "resolution", DoubleArray::class.java) ?: DoubleArray(3) { 1.0 }
    val offset = this.getAttribute(dataset, "offset", DoubleArray::class.java) ?: DoubleArray(3) { 0.0 }
    val shiftInVoxels = offset.mapIndexed { index, o -> o / resolution[index] }.toDoubleArray()
    val data = N5Utils.open<T>(this, dataset)
    return if (shiftInVoxels.all { it == 0.0 })
        data
    else
        Views.translate(data, *shiftInVoxels.map { it.toLong() }.toLongArray())
}

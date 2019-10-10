package org.janelia.saalfeldlab.imglib2.mutex

import net.imglib2.img.array.ArrayImgs
import net.imglib2.imklib.extensions.get
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import java.util.*
import java.util.function.DoubleSupplier
import kotlin.random.Random

fun main() {
    val containerPath = "/home/hanslovskyp/workspace/mutex-watershed/mutex-watershed-notebook/sample_A.n5"
    val container     = N5FSWriter(containerPath)
    val dataset       = "affinities"
    val affinities    = N5Utils.open<FloatType>(container, dataset)
    val resolution    = doubleArrayOf(108.0, 108.0, 120.0)
// val blockSize     = intArrayOf(192, 192, 128)
// val blockMin      = longArrayOf(256, 256, 256)
// val blockMax      = LongArray(blockMin.size) { blockMin[it] + blockSize[it] - 1 }
    val margin        = longArrayOf(550, 550, 250)
    val blockMin      = LongArray(3) { affinities.min(it) + margin[it] }
    val blockMax      = LongArray(3) { affinities.max(it) - margin[it] }
    val blockSize     = LongArray(3) { blockMax[it] - blockMin[it] + 1 }
    val offset        = DoubleArray(resolution.size) { blockMin[it] * resolution[it] }
    println(Arrays.toString(blockSize))

    val offsets       = arrayOf(
        longArrayOf(-1, 0, 0), longArrayOf(-2, 0, 0), longArrayOf(-5, 0, 0), longArrayOf(-10, 0, 0),
        longArrayOf(0, -1, 0), longArrayOf(0, -2, 0), longArrayOf(0, -5, 0), longArrayOf(0, -10, 0),
        longArrayOf(0, 0, -1), longArrayOf(0, 0, -2), longArrayOf(0, 0, -5), longArrayOf(0, 0, -10))
        .map { it.reversedArray() }

    offsets.forEachIndexed { index, offset -> println("Offset $index: ${Arrays.toString(offset)}") }

    val defaultProbability = 0.05
    val probability1 = 1.0
    val probability2 = defaultProbability
    val probability5 = defaultProbability
    val probability10 = defaultProbability

    val probabilities = doubleArrayOf(
        probability1, probability2, probability5, probability10,
        probability1, probability2, probability5, probability10,
        probability1, probability2, probability5, probability10)

    val target       = ArrayImgs.longs(*blockSize.map { it.toLong() }.toLongArray())
    var currentIndex = 0L
    target.forEach { it.setInteger(currentIndex++) }
    val rng            = Random(seed = 100L)

    println("Computing mutex watershed for dataset of size ${target}")

    val nextId = MutexWatershed.computeMutexWatershedClustering(
        affinities        = affinities[Intervals.createMinMax(blockMin[0], blockMin[1], blockMin[2], 0, blockMax[0], blockMax[1], blockMax[2], offsets.size.toLong() - 1)],
        target            = target,
        offsets           = offsets.toTypedArray(),
        edgeProbabilities = probabilities,
        // use either attractiveEdges or threshold
        attractiveEdges   = offsets.map { o -> o.map { it*it }.sum() <= 1L }.toBooleanArray(),
//        threshold         = 0.5
        random            = DoubleSupplier { rng.nextDouble() })

    println("Saving mutex watershed in dataset `mutex-watershed'. Max id=${nextId-1}")

    N5Utils.save(target, container, "mutex-watershed", intArrayOf(64, 64, 64), GzipCompression())

    container.setAttribute("mutex-watershed", "resolution", resolution)
    container.setAttribute("mutex-watershed", "offset", offset)
}

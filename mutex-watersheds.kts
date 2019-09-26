#!/usr/bin/env kscript

//KOTLIN_OPTS -J-Xmx25g

@file:MavenRepository("scijava.public", "https://maven.scijava.org/content/groups/public")

@file:DependsOn("org.janelia.saalfeldlab:imglib2-mutex-watershed:0.1.0-SNAPSHOT")
@file:DependsOn("org.janelia.saalfeldlab:n5:2.1.1")
@file:DependsOn("org.janelia.saalfeldlab:n5-imglib2:3.4.0")
@file:DependsOn("org.apache.commons:commons-compress:1.18")
@file:DependsOn("net.imglib2:imglib2:5.8.0")
@file:DependsOn("net.imglib2:imklib:0.1.2-SNAPSHOT")

import java.lang.Thread
import java.util.Arrays
import java.util.function.DoublePredicate

import kotlin.random.Random

import gnu.trove.list.array.TDoubleArrayList
import net.imglib2.FinalInterval
import net.imglib2.img.array.ArrayImgs
import net.imglib2.imklib.extensions.*
import net.imglib2.type.numeric.integer.LongType
import net.imglib2.type.numeric.real.FloatType
import net.imglib2.util.Intervals
import net.imglib2.view.Views
import org.janelia.saalfeldlab.imglib2.mutex.EdgeArray
import org.janelia.saalfeldlab.imglib2.mutex.MutexWatershed
import org.janelia.saalfeldlab.n5.GzipCompression
import org.janelia.saalfeldlab.n5.N5FSWriter
import org.janelia.saalfeldlab.n5.imglib2.N5Utils


// This is necessary to get auto-detection of n5 compression to work, for some reason.
Thread
    .currentThread()
    .setContextClassLoader(java.lang.invoke.MethodHandles.lookup().lookupClass().getClassLoader())

val containerPath = "/home/hanslovskyp/workspace/mutex-watershed/mutex-watershed-notebook/sample_A.n5"
val container     = N5FSWriter(containerPath)
val dataset       = "affinities"
val affinities    = N5Utils.open<FloatType>(container, dataset)
val resolution    = doubleArrayOf(108.0, 108.0, 120.0)
// val blockSize     = intArrayOf(192, 192, 128)
// val blockMin      = longArrayOf(256, 256, 256)
// val blockMax      = LongArray(blockMin.size) { blockMin[it] + blockSize[it] - 1 }
val margin        = longArrayOf(450, 450, 150)
val blockMin      = LongArray(3) { affinities.min(it) + margin[it] }
val blockMax      = LongArray(3) { affinities.max(it) - margin[it] }
val blockSize     = LongArray(3) { blockMax[it] - blockMin[it] + 1 }
val offset        = DoubleArray(resolution.size) { blockMin[it] * resolution[it] }
println("${Arrays.toString(blockSize)}")

val offsets       = arrayOf(
        longArrayOf(-1, 0, 0), longArrayOf(-2, 0, 0), longArrayOf(-5, 0, 0), longArrayOf(-10, 0, 0),
        longArrayOf(0, -1, 0), longArrayOf(0, -2, 0), longArrayOf(0, -5, 0), longArrayOf(0, -10, 0),
        longArrayOf(0, 0, -1), longArrayOf(0, 0, -2), longArrayOf(0, 0, -5), longArrayOf(0, 0, -10))
        .map { it.reversedArray() }

offsets.forEachIndexed { index, offset -> println("Offset $index: ${Arrays.toString(offset)}") }

val defaultProbability = 0.05
val probability1 = 1.0
val probability2 = 0.0
val probability5 = 0.05
val probability10 = 0.0

val probabilities = doubleArrayOf(
    probability1, probability2, probability5, probability10,
    probability1, probability2, probability5, probability10,
    probability1, probability2, probability5, probability10)

val edges            = EdgeArray()
val mutexEdges       = EdgeArray()
val edgeWeights      = TDoubleArrayList()
val mutexEdgeWeights = TDoubleArrayList()

val target       = ArrayImgs.longs(*blockSize.map { it.toLong() }.toLongArray())
var currentIndex = 0L
target.forEach { it.setInteger(currentIndex++) }
val numNodes       = currentIndex
val targetExtended = Views.extendValue(target, LongType(-1))
val rng            = Random(seed = 100L)

for ((index, offset) in offsets.withIndex()) {
        val block          = affinities[SL, SL, SL, index][FinalInterval(blockMin, blockMax)].zeroMin()
        val affinityCursor = block.flatIterable().cursor()
        val fromCursor     = target.flatIterable().cursor()
        val toTarget       = targetExtended[Intervals.translate(target, *offset)]
        val toCursor       = toTarget.flatIterable().cursor()
        val isNearest      = offset.any { it == -1L }
        val probability    = probabilities[index]
        val edgeReject     = if (probability < 0.0 || probability == 1.0) DoublePredicate { it.isNaN() } else DoublePredicate { it.isNaN() || rng.nextDouble() >= probability  }
        val isAttractive   = DoublePredicate { isNearest } // use this line to use only nearest neighbor edges for attractive
        // val isAttractive   = DoublePredicate { isNearest || it >= 0.5 } // use this line to "threshold" at 0.5 do decide if edge is attractive

        if (probability == 0.0) continue

        println("Collecting edges and weights for offset=${Arrays.toString(offset)}")
        while (toCursor.hasNext()) {
                val affinity = affinityCursor.next().realDouble
                val from     = fromCursor.next().integerLong
                val to       = toCursor.next().integerLong

                if (edgeReject.test(affinity) || to < 0 || from < 0)
                        continue

                if (isAttractive.test(affinity)) {
                        edges.addEdge(from, to)
                        edgeWeights.add(affinity)
                } else {
                        mutexEdges.addEdge(from, to)
                        mutexEdgeWeights.add(1.0 - affinity)
                }
        }
}

println("Num edges: ${edgeWeights.size()} and num mutex edges: ${mutexEdgeWeights.size()}")

val uf = MutexWatershed.computeMutexWatershedClustering(
        numLabels        = numNodes.toInt(),
        edges            = edges,
        mutexEdges       = mutexEdges,
        edgeWeights      = edgeWeights.toArray(),
        mutexEdgeWeights = mutexEdgeWeights.toArray())

target.forEach { it.setInteger(uf.findRoot(it.integerLong)) }

println("Saving mutex watershed in dataset `mutex-watershed'")

N5Utils.save(target, container, "mutex-watershed", intArrayOf(64, 64, 64), GzipCompression())

container.setAttribute("mutex-watershed", "resolution", resolution)
container.setAttribute("mutex-watershed", "offset", offset)

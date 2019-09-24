package org.janelia.saalfeldlab.imglib2.mutex

import net.imglib2.algorithm.util.unionfind.UnionFind

class UnionFindExtensions {

    companion object {

        fun UnionFind.label(numLabels: Int) = LongArray(numLabels) { this.findRoot(it.toLong()) }

        fun UnionFind.relabel(longArray: LongArray) = longArray.indices.forEach { longArray[it] = this.findRoot(it.toLong()) }
    }

}

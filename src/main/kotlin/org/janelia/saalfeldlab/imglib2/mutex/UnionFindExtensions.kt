package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.map.TLongLongMap
import net.imglib2.algorithm.util.unionfind.IntArrayUnionFind
import net.imglib2.algorithm.util.unionfind.UnionFind

class UnionFindExtensions {

    companion object {

        fun UnionFind.label(numLabels: Int) = LongArray(numLabels) { this.findRoot(it.toLong()) }

        fun UnionFind.relabel(longArray: LongArray) = longArray.indices.forEach { longArray[it] = this.findRoot(it.toLong()) }

        fun IntArrayUnionFind.relabelRoots(map: TLongLongMap, startIndex: Long = 0L): Long {
            var currentIndex = startIndex
            for (id in 0L until size()) {
                val root = findRoot(id)
                if (root == id)
                    map.put(root, currentIndex++)
            }
            return currentIndex
        }

    }

}

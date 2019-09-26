package org.janelia.saalfeldlab.imglib2.mutex

import net.imglib2.util.Util

class IntArrayExtensions {

    interface Values {
        operator fun get(index: Int): Double
    }

    companion object {

        fun IntArray.quicksortBy(values: (Int) -> Double) = quicksortBy(object : Values {
            override fun get(index: Int) = values(index)
        } )

        fun IntArray.quicksortBy(values: Values) = quicksortIndices(this, values)

        @JvmStatic
        fun quicksortIndices(indexArray: IntArray, values: Values) = quicksortIndices(indexArray, values, 0, indexArray.size - 1)

        // adapted from net.imglib2.util.Util.quicksort
        private fun quicksortIndices(indexArray: IntArray, values: Values, left: Int, right: Int) {
            var i = left
            var j = right
            val x = values[indexArray[(left + right) / 2]]
            do {
                while (values[indexArray[i]] < x)
                    ++i
                while (x < values[indexArray[j]])
                    --j
                if (i <= j) {
                    val temp = indexArray[i]
                    indexArray[i] = indexArray[j]
                    indexArray[j] = temp

                    ++i
                    --j
                }
            } while (i <= j)
            if (left < j)
                quicksortIndices(indexArray, values, left, j)
            if (i < right)
                quicksortIndices(indexArray, values, i, right)
        }
    }

}

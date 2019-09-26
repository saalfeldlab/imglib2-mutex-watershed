package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.TLongList
import gnu.trove.list.array.TLongArrayList
import kotlin.math.max

class TLongListExtensions {
    companion object {

        fun TLongList.upperBound(value: Long): Int {
            var low = 0
            var count = size() - low

            while (count > 0) {
                var current = low
                val step = count / 2
                current += step
                if (value >= get(current)) {
                    low = current + 1
                    count -= step + 1
                } else
                    count = step
            }
            return low
        }

        operator fun TLongList.plusAssign(value: Long) {
            this.add(value)
        }

        fun TLongList.merge(that: TLongList) = merge(that, into = TLongArrayList(max(this.size(), that.size())))

        fun <L: TLongList> TLongList.merge(that: TLongList, into: L): L {
            // https://github.com/constantinpape/affogato/blob/master/include/affogato/segmentation/mutex_watershed.hxx#L70-L72
            // Why use std::merge here over std::union or even just manually create a sorted union?
//            val into = TLongArrayList(max(this.size(), that.size()))

            var indexThis = 0
            var indexThat = 0
            val sizeThis = this.size()
            val sizeThat = that.size()

            // could be optimized with special case implementaiton for TLongArrayList that grabs the underlying storage array via reflections
            while (indexThis < sizeThis) {
                if (indexThat == sizeThat) {
                    while (indexThis < sizeThis)
                        into += this[indexThis++]
                    break
                }

                if (that[indexThat] < this[indexThis])
                    into += that[indexThat++]
                else
                    into += this[indexThis++]
            }

            while (indexThat < sizeThat)
                into += that[indexThat++]

            return into
        }
    }
}

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

        fun TLongList.merge(that: TLongList): TLongList {
            // https://github.com/constantinpape/affogato/blob/master/include/affogato/segmentation/mutex_watershed.hxx#L70-L72
            // Why use std::merge here over std::union or even just manually create a sorted union?
            val merged = TLongArrayList(max(this.size(), that.size()))

            var indexThis = 0
            var indexThat = 0
            val sizeThis = this.size()
            val sizeThat = that.size()

            // could be optimized with special case implementaiton for TLongArrayList that grabs the underlying storage array via reflections
            while (indexThis < sizeThis) {
                if (indexThat == sizeThat) {
                    while (indexThis < sizeThis)
                        merged += this[indexThis++]
                    break
                }

                if (that[indexThat] < this[indexThis])
                    merged += that[indexThat++]
                else
                    merged += this[indexThis++]
            }

            while (indexThat < sizeThat)
                merged += that[indexThat++]

            return merged

        }
    }
}

package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.array.TLongArrayList
import org.junit.Assert
import org.junit.Test

class TLongListExtensionsTest {

    @Test
    fun testUpperBound() {
        val list = TLongArrayList(longArrayOf(1, 2, 3))
        with (TLongListExtensions) {
            Assert.assertEquals(0, list.upperBound(0))
            Assert.assertEquals(1, list.upperBound(1))
            Assert.assertEquals(2, list.upperBound(2))
            Assert.assertEquals(3, list.upperBound(3))
            Assert.assertEquals(3, list.upperBound(4))
        }
    }

    @Test
    fun testMerge() {
        val list1 = TLongArrayList(longArrayOf(1, 3, 4, 4))
        val list2 = TLongArrayList(longArrayOf(1, 2, 3, 5))
        with (TLongListExtensions) {
            Assert.assertEquals(TLongArrayList(longArrayOf(1, 1, 2, 3, 3, 4, 4, 5)), list1.merge(list2))
            Assert.assertEquals(TLongArrayList(longArrayOf(1, 1, 2, 3, 3, 4, 4, 5)), list2.merge(list1))
        }
    }

}

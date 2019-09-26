package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.TLongList
import gnu.trove.list.array.TLongArrayList
import gnu.trove.map.TLongObjectMap
import gnu.trove.map.hash.TLongObjectHashMap
import kotlin.math.max

typealias MutexStore = TLongObjectMap<TLongList?>

class MutexStorageHashMap : MutexStorage {

    private val mutexes: MutexStore = TLongObjectHashMap()

    override fun checkMutex(representativeOne: Long, representativeTwo: Long): Boolean {
        val listOne = mutexes.getOrEmptyListIfAbsent(representativeOne)
        val listTwo = mutexes.getOrEmptyListIfAbsent(representativeTwo)
        val sizeOne = listOne.size()
        val sizeTwo = listTwo.size()
        var indexOne = 0
        var indexTwo = 0
        while (indexOne < sizeOne && indexTwo < sizeTwo) {
            when {
                listOne[indexOne] < listTwo[indexTwo] -> ++indexOne
                listTwo[indexTwo] < listOne[indexOne] -> ++indexTwo
                else -> return true
            }
        }
        return false
    }

    override fun insertMutex(representativeOne: Long, representativeTwo: Long, mutexEdgeId: Long) {
        with (TLongListExtensions) {
            mutexes.getOrEmptyListIfAbsent(representativeOne).let { it.insert(it.upperBound(mutexEdgeId), mutexEdgeId) }
            mutexes.getOrEmptyListIfAbsent(representativeTwo).let { it.insert(it.upperBound(mutexEdgeId), mutexEdgeId) }
        }
    }

    override fun mergeMutexes(rootFrom: Long, rootTo: Long) {

        val listFrom = mutexes.getOrEmptyListIfAbsent(rootFrom)
        if (listFrom.size() == 0)
            return

        val listTo = mutexes.getOrEmptyListIfAbsent(rootTo)
        if (listTo.size() == 0) {
            mutexes.put(rootTo, listFrom)
            return
        }

        val merged = with(TLongListExtensions) { listFrom.merge(listTo) }

        mutexes.put(rootTo, merged)
    }

}

private fun MutexStore.getOrEmptyListIfAbsent(key: Long): TLongList {
    return this[key] ?: TLongArrayList().also { this.put(key, it) }
}



package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.TLongList
import gnu.trove.list.array.TLongArrayList

private typealias ArrayStore = Array<TLongList?>

class MutexStorageArray(numNodes: Int) : MutexStorage {

    private val mutexes = ArrayStore(numNodes) { null }

    override fun checkMutex(representativeOne: Long, representativeTwo: Long): Boolean {
        val listOne = mutexes.getOrEmptyListIfAbsent(representativeOne.toInt())
        val listTwo = mutexes.getOrEmptyListIfAbsent(representativeTwo.toInt())
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
            mutexes.getOrEmptyListIfAbsent(representativeOne.toInt()).let { it.insert(it.upperBound(mutexEdgeId), mutexEdgeId) }
            mutexes.getOrEmptyListIfAbsent(representativeTwo.toInt()).let { it.insert(it.upperBound(mutexEdgeId), mutexEdgeId) }
        }
    }

    override fun mergeMutexes(rootFrom: Long, rootTo: Long) {

        val listFrom = mutexes.getOrEmptyListIfAbsent(rootFrom.toInt())
        if (listFrom.size() == 0)
            return

        val listTo = mutexes.getOrEmptyListIfAbsent(rootTo.toInt())
        if (listTo.size() == 0) {
            mutexes[rootTo.toInt()] = listFrom
            return
        }

        val merged = with(TLongListExtensions) { listFrom.merge(listTo) }



        mutexes[rootTo.toInt()] = merged
    }

}

private fun ArrayStore.getOrEmptyListIfAbsent(key: Int): TLongList {
    return this[key] ?: TLongArrayList().also { this[key] = it }
}



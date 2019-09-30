package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.array.TLongArrayList
import java.util.ArrayDeque

private typealias ArrayStore = Array<TLongArrayList?>

class MutexStorageArray(numNodes: Int) : MutexStorage {

    private val mutexes = ArrayStore(numNodes) { null }

    private val listCache = ArrayDeque<TLongArrayList>(10000)

    override fun checkMutex(representativeOne: Long, representativeTwo: Long): Boolean {
        val listOne = mutexes[representativeOne.toInt()] ?: EMPTY_LIST
        val listTwo = mutexes[representativeTwo.toInt()] ?: EMPTY_LIST
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
            mutexes.getOrEmptyListIfAbsent(representativeOne.toInt(), listCache).let { it.insert(it.upperBound(mutexEdgeId), mutexEdgeId) }
            mutexes.getOrEmptyListIfAbsent(representativeTwo.toInt(), listCache).let { it.insert(it.upperBound(mutexEdgeId), mutexEdgeId) }
        }
    }

    override fun mergeMutexes(rootFrom: Long, rootTo: Long) {

        val listFrom = mutexes[rootFrom.toInt()]
        if (listFrom === null || listFrom.size() == 0)
            return

        val listTo = mutexes[rootTo.toInt()]
        if (listTo === null || listTo.size() == 0) {
            mutexes[rootTo.toInt()] = listFrom
            return
        }

        val merged = with(TLongListExtensions) { if (listCache.isEmpty()) listFrom.merge(listTo) else listFrom.merge(listTo, listCache.pop()) }

        mutexes[rootTo.toInt()] = merged
        mutexes[rootFrom.toInt()] = null

        listFrom.resetQuick()
        listTo.resetQuick()

        if (listCache.size < 10000)
            listCache.add(listFrom)
        if (listCache.size < 10000)
            listCache.add(listTo)
    }

}

private fun ArrayStore.getOrEmptyListIfAbsent(key: Int, listCache: ArrayDeque<TLongArrayList>): TLongArrayList {
    return this[key] ?: (if (listCache.isEmpty()) TLongArrayList(1) else listCache.pop()).also { this[key] = it }
}

private val EMPTY_LIST = TLongArrayList()



package org.janelia.saalfeldlab.imglib2.mutex

interface MutexStorage {

    fun checkMutex(representativeOne: Long, representativeTwo: Long): Boolean

    fun insertMutex(representativeOne: Long, representativeTwo: Long, mutexEdgeId: Long)

    fun mergeMutexes(rootFrom: Long, rootTo: Long)

}

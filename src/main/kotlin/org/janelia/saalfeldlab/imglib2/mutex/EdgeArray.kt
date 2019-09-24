package org.janelia.saalfeldlab.imglib2.mutex

import gnu.trove.list.array.TLongArrayList

class EdgeArray() {

    constructor(vararg edges: Pair<Long, Long>) : this() {
        edges.forEach { addEdge(it.first, it.second) }
    }

    private val edges = TLongArrayList()

    private var _index = -1

    private var fromIndex = -1

    private var toIndex = -1

    var index: Int
        get() = _index
        set(index) {
            this._index = index
            this.fromIndex = 2 * index
            this.toIndex = this.fromIndex + 1
        }

    val fromAtCurrentIndex: Long
        get() = edges[fromIndex]

    val toAtCurrentIndex: Long
        get() = edges[toIndex]

    fun addEdge(from: Long, to: Long) {
        with (TLongListExtensions) {
            edges += from
            edges += to
        }
    }

    val size: Int
        get() = edges.size() / 2





}

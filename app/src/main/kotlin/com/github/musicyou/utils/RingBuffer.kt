package com.github.musicyou.utils

class RingBuffer<T>(val size: Int, init: (index: Int) -> T) {
    private val list = MutableList(size, init)
    private val initFn = init

    private var index = 0

    fun getOrNull(index: Int): T? = list.getOrNull(index)

    fun append(element: T) = list.set(index++ % size, element)

    fun clear() {
        for (i in 0 until size) {
            list[i] = initFn(i)
        }
        index = 0
    }
}

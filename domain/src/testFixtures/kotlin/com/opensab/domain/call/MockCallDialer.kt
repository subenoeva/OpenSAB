package com.opensab.domain.call

class MockCallDialer(var succeeds: Boolean = true) : CallDialer {

    val placedNumbers = mutableListOf<String>()

    val placeCalls: Int get() = placedNumbers.size

    val totalCalls: Int get() = placeCalls

    override suspend fun place(number: String): Boolean {
        placedNumbers += number
        return succeeds
    }
}

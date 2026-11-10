package com.opensab.domain.contacts

/** **Owner: the test that constructs it.** */
class MockCallMenuAccess(var next: CallMenuGates = CallMenuGates.CLOSED) : CallMenuAccess {

    var gatesCalls = 0
        private set

    override suspend fun gates(): CallMenuGates {
        gatesCalls++
        return next
    }
}

/** **Owner: the test that constructs it.** */
class MockContactDirectory : ContactDirectory {

    var searchCalls = 0
        private set

    var starredCalls = 0
        private set

    /** What the last [search] was asked for, which is what pins the courtesy stripping. */
    var lastQuery: String? = null
        private set

    val totalCalls: Int get() = searchCalls + starredCalls

    var results: List<Contact> = emptyList()
    var favourites: List<Contact> = emptyList()

    override suspend fun search(query: String): List<Contact> {
        searchCalls++
        lastQuery = query
        return results
    }

    override suspend fun starred(): List<Contact> {
        starredCalls++
        return favourites
    }
}

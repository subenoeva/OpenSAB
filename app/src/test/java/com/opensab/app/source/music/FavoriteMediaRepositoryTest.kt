package com.opensab.app.source.music

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteMediaRepositoryTest {

    @Test
    fun persistsAndReadsFavoritePackage() = runTest {
        val repository = InMemoryFavoriteMediaRepository()

        assertNull(repository.favoritePackage.first())

        repository.setFavoritePackage("com.spotify.music")
        assertEquals("com.spotify.music", repository.favoritePackage.first())

        repository.setFavoritePackage("com.google.android.apps.youtube.music")
        assertEquals("com.google.android.apps.youtube.music", repository.favoritePackage.first())

        repository.setFavoritePackage(null)
        assertNull(repository.favoritePackage.first())
    }
}

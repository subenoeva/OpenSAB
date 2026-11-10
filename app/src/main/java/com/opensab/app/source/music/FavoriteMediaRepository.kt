package com.opensab.app.source.music

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

interface FavoriteMediaRepository {
    val favoritePackage: Flow<String?>
    suspend fun setFavoritePackage(packageName: String?)
}

/** For tests. */
class InMemoryFavoriteMediaRepository(
    initialPackage: String? = null
) : FavoriteMediaRepository {
    private val _favoritePackage = MutableStateFlow(initialPackage)
    override val favoritePackage: Flow<String?> = _favoritePackage

    override suspend fun setFavoritePackage(packageName: String?) {
        _favoritePackage.value = packageName
    }
}

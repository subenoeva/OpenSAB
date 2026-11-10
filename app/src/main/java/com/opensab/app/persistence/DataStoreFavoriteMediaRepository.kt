package com.opensab.app.persistence

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.opensab.app.source.music.FavoriteMediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreFavoriteMediaRepository(
    private val dataStore: DataStore<Preferences>
) : FavoriteMediaRepository {

    override val favoritePackage: Flow<String?> = dataStore.data.map { preferences ->
        preferences[KEY_FAVORITE_PACKAGE]
    }

    override suspend fun setFavoritePackage(packageName: String?) {
        dataStore.edit { preferences ->
            if (packageName != null) {
                preferences[KEY_FAVORITE_PACKAGE] = packageName
            } else {
                preferences.remove(KEY_FAVORITE_PACKAGE)
            }
        }
    }

    private companion object {
        val KEY_FAVORITE_PACKAGE = stringPreferencesKey("favorite_media_package")
    }
}

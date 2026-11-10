package com.opensab.app.source.text

import com.opensab.app.R
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterTextResolverTest {

    @Test
    fun resolvesNavigationVoiceTokensInEnglish() {
        val resolver = ClusterTextResolver { token ->
            when (token.string()) {
                R.string.cluster_nav_menu_voice_search -> "Voice Search"
                R.string.cluster_nav_voice_listening -> "Listening..."
                R.string.cluster_nav_launching -> "Starting route..."
                R.string.cluster_nav_voice_nothing_understood -> "Nothing understood"
                R.string.cluster_nav_voice_no_app -> "No navigation app"
                R.string.cluster_nav_voice_ambiguous -> "Select on phone"
                R.string.cluster_nav_voice_unlock_phone -> "Unlock your phone"
                else -> ""
            }
        }
        assertEquals("Voice Search", resolver.resolve(ClusterToken.NAV_MENU_VOICE_SEARCH))
        assertEquals("Listening...", resolver.resolve(ClusterToken.NAV_VOICE_LISTENING))
        assertEquals("Starting route...", resolver.resolve(ClusterToken.NAV_LAUNCHING))
        assertEquals("Nothing understood", resolver.resolve(ClusterToken.NAV_VOICE_NOTHING_UNDERSTOOD))
        assertEquals("No navigation app", resolver.resolve(ClusterToken.NAV_VOICE_NO_APP))
        assertEquals("Select on phone", resolver.resolve(ClusterToken.NAV_VOICE_AMBIGUOUS))
        assertEquals("Unlock your phone", resolver.resolve(ClusterToken.NAV_VOICE_UNLOCK_PHONE))
    }

    @Test
    fun resolvesMusicLoadingAndErrorTokensInEnglish() {
        val resolver = ClusterTextResolver { token ->
            when (token.string()) {
                R.string.cluster_loading -> "Loading"
                R.string.cluster_music_error -> "App Error"
                else -> ""
            }
        }
        assertEquals("Loading", resolver.resolve(ClusterToken.LOADING))
        assertEquals("App Error", resolver.resolve(ClusterToken.MUSIC_ERROR))
    }

    @Test
    fun resolvesMusicLoadingAndErrorTokensInSpanish() {
        val resolver = ClusterTextResolver { token ->
            when (token.string()) {
                R.string.cluster_loading -> "Cargando"
                R.string.cluster_music_error -> "Error de app"
                else -> ""
            }
        }
        assertEquals("Cargando", resolver.resolve(ClusterToken.LOADING))
        assertEquals("Error de app", resolver.resolve(ClusterToken.MUSIC_ERROR))
    }

    @Test
    fun resolvesMessageMenuTokensInEnglish() {
        val resolver = ClusterTextResolver { token ->
            when (token.string()) {
                R.string.cluster_message_menu_by_voice -> "By voice"
                R.string.cluster_message_menu_favorites -> "Favorites"
                else -> ""
            }
        }
        assertEquals("By voice", resolver.resolve(ClusterToken.MESSAGE_MENU_BY_VOICE))
        assertEquals("Favorites", resolver.resolve(ClusterToken.MESSAGE_MENU_FAVORITES))
    }

    @Test
    fun resolvesMessageMenuTokensInSpanish() {
        val resolver = ClusterTextResolver { token ->
            when (token.string()) {
                R.string.cluster_message_menu_by_voice -> "Por voz"
                R.string.cluster_message_menu_favorites -> "Favoritos"
                else -> ""
            }
        }
        assertEquals("Por voz", resolver.resolve(ClusterToken.MESSAGE_MENU_BY_VOICE))
        assertEquals("Favoritos", resolver.resolve(ClusterToken.MESSAGE_MENU_FAVORITES))
    }
}


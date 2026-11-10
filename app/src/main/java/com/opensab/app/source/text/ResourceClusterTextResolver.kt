package com.opensab.app.source.text

import android.content.Context
import com.opensab.cluster.text.ClusterTextResolver
import com.opensab.cluster.text.ClusterToken
import com.opensab.cluster.text.WeatherCaptionResolver
import com.opensab.domain.weather.WmoCaption

/**
 * [ClusterTextResolver] and [WeatherCaptionResolver] over `Context.getString`.
 *
 * The `applicationContext` is kept and not the one handed in: this lives as long as the process
 * does, and holding an activity's context would pin that activity for the whole ride.
 */
class ResourceClusterTextResolver(context: Context) : ClusterTextResolver, WeatherCaptionResolver {

    private val appContext = context.applicationContext

    override fun resolve(token: ClusterToken): String = appContext.getString(token.string())

    override fun resolve(caption: WmoCaption): String = appContext.getString(caption.string())
}

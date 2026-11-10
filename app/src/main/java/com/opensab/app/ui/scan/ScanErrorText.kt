package com.opensab.app.ui.scan

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.opensab.app.R

/** The only place that turns a failed connection step into words: it needs the rider's locale. */
fun ScanError.resolve(context: Context): String = when (this) {
    is ScanError.BondFailed -> detail
        ?.let { context.getString(R.string.scan_error_bond_failed_detail, it) }
        ?: context.getString(R.string.scan_error_bond_failed)

    ScanError.BondTimedOut -> context.getString(R.string.scan_error_bond_timeout)

    ScanError.TransportUnreachable -> context.getString(R.string.scan_error_transport_unreachable)

    ScanError.PermissionsMissing -> context.getString(R.string.scan_error_permissions_missing)

    is ScanError.PipelineFailed -> detail
        ?.let { context.getString(R.string.scan_error_pipeline_failed_detail, it) }
        ?: context.getString(R.string.scan_error_pipeline_failed)
}

/** Composition-side counterpart of [resolve]. */
@Composable
fun ScanError.resolved(): String = resolve(LocalContext.current)

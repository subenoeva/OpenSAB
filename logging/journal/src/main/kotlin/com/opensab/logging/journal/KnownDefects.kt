package com.opensab.logging.journal

/**
 * Defects already recorded: shown as grey notes, never as attention. Closing one removes it here
 * and bumps [SUMMARIZER_VERSION].
 */
internal object KnownDefects {
    const val CUE_UNHEARD = "CUE-UNHEARD"
    const val FGS_NO_MIC = "FGS-NO-MIC"
}

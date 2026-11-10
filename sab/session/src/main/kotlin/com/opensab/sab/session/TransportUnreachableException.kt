package com.opensab.sab.session

/** [LinkOpener.open] could not reach the motorcycle; [cause] is whatever the transport layer threw. */
class TransportUnreachableException(
    val macAddress: String,
    cause: Throwable?
) : Exception("Could not open a transport to $macAddress", cause)

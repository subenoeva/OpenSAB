package com.opensab.sab.session

/** Who asked for a [SabSession]: the ACL receiver, the vehicle scanner, or the dashboard's disconnect. */
enum class SessionOrigin { RECEIVER, SCANNER, DASHBOARD }

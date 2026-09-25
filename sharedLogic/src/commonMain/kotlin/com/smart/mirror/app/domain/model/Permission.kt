package com.smart.mirror.app.domain.model

enum class PermissionStatus {
    /** Never asked (iOS `.notDetermined`, Android first launch). */
    NOT_DETERMINED,
    GRANTED,

    /** Denied, but the system dialog can still be shown again (Android "shouldShowRationale"). */
    DENIED,

    /** Denied permanently / restricted: only the system Settings app can fix it. */
    BLOCKED,
}

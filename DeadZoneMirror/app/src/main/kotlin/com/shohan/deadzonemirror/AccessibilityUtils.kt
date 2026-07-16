package com.shohan.deadzonemirror

import android.content.ComponentName
import android.content.Context
import android.provider.Settings

/**
 * Android has no direct "isMyServiceEnabled" API, so the standard technique
 * is to read the system's enabled-services list and check whether our
 * component appears in it.
 */
fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
    val expected = ComponentName(context, serviceClass)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    return enabledServicesSetting.split(":").any { entry ->
        ComponentName.unflattenFromString(entry) == expected
    }
}

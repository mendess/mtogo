package xyz.mendess.mtogo.util

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlin.properties.ReadOnlyProperty


val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val Context.hostname: String by ReadOnlyProperty<Context, String> { thisRef, _ ->
    Settings.Global.getString(thisRef.contentResolver, "device_name")
}
val Context.appVersion: String by ReadOnlyProperty<Context, String> { thisRef, _ ->
    try {
        thisRef.packageManager.getPackageInfo(thisRef.packageName, 0).versionName
            ?: "unknown version"
    } catch (e: PackageManager.NameNotFoundException) {
        "unknown version"
    }
}

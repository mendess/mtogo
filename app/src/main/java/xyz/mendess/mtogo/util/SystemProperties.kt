package xyz.mendess.mtogo.util

import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlin.properties.ReadOnlyProperty

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
val Context.hostname: String by ReadOnlyProperty<Context, String> { thisRef, _ ->
    Settings.Global.getString(thisRef.contentResolver, "device_name")
}

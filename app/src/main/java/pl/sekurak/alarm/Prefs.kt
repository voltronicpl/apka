package pl.sekurak.alarm

import android.content.Context
import android.net.Uri

/** Ustawienia aplikacji (dzwonek, głośność, wibracje, sekret). */
class Prefs(context: Context) {
    private val p = context.applicationContext
        .getSharedPreferences("sekurak", Context.MODE_PRIVATE)

    /** Adres webhooka HA wywoływany po wyciszeniu alarmu na telefonie. */
    var webhookUrl: String
        get() = p.getString("webhook", "")!!.trim()
        set(v) = p.edit().putString("webhook", v.trim()).apply()

    /** Opcjonalny sekret – jeśli ustawiony, sygnał START z HA musi go zawierać. */
    var secret: String
        get() = p.getString("secret", "")!!.trim()
        set(v) = p.edit().putString("secret", v.trim()).apply()

    /** null = domyślny dźwięk alarmu systemu. */
    var ringtoneUri: Uri?
        get() = p.getString("ringtone", null)?.let(Uri::parse)
        set(v) = p.edit().putString("ringtone", v?.toString()).apply()

    /** Głośność alarmu w % (10–100). */
    var volume: Int
        get() = p.getInt("volume", 100)
        set(v) = p.edit().putInt("volume", v.coerceIn(10, 100)).apply()

    /** Czy na czas alarmu wymusić ustawioną głośność (potem przywraca poprzednią). */
    var forceVolume: Boolean
        get() = p.getBoolean("force_volume", true)
        set(v) = p.edit().putBoolean("force_volume", v).apply()

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()

    /** Automatyczne wyłączenie po X minutach, 0 = nigdy. */
    var autoStopMin: Int
        get() = p.getInt("auto_stop", 0)
        set(v) = p.edit().putInt("auto_stop", v.coerceAtLeast(0)).apply()
}

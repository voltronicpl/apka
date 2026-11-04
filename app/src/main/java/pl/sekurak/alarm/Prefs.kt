package pl.sekurak.alarm

import android.content.Context
import android.net.Uri

/** Ustawienia aplikacji. Wspólne + osobne dla każdego typu alarmu. */
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

    /** Głośność alarmu w % (10–100). */
    var volume: Int
        get() = p.getInt("volume", 100)
        set(v) = p.edit().putInt("volume", v.coerceIn(10, 100)).apply()

    /** Czy na czas alarmu wymusić ustawioną głośność (potem przywraca poprzednią). */
    var forceVolume: Boolean
        get() = p.getBoolean("force_volume", true)
        set(v) = p.edit().putBoolean("force_volume", v).apply()

    /** Automatyczne wyłączenie po X minutach, 0 = nigdy. */
    var autoStopMin: Int
        get() = p.getInt("auto_stop", 0)
        set(v) = p.edit().putInt("auto_stop", v.coerceAtLeast(0)).apply()

    // ---- per typ alarmu ----

    /** null = domyślny dźwięk alarmu systemu. Ogólny przejmuje dzwonek z poprzednich wersji. */
    fun ringtone(t: AlarmType): Uri? {
        val old = if (t == AlarmType.OGOLNY) p.getString("ringtone", null) else null
        return p.getString("ringtone_${t.key}", old)?.let(Uri::parse)
    }

    fun setRingtone(t: AlarmType, uri: Uri?) =
        p.edit().putString("ringtone_${t.key}", uri?.toString()).apply()

    fun vibrate(t: AlarmType): Boolean {
        val old = if (t == AlarmType.OGOLNY) p.getBoolean("vibrate", true) else true
        return p.getBoolean("vibrate_${t.key}", old)
    }

    fun setVibrate(t: AlarmType, v: Boolean) = p.edit().putBoolean("vibrate_${t.key}", v).apply()

    /** Ścieżka karty HA do otwarcia po wyciszeniu, np. /lovelace/domofon. Puste = brak. */
    fun openPath(t: AlarmType): String = p.getString("path_${t.key}", "")!!.trim()

    fun setOpenPath(t: AlarmType, v: String) = p.edit().putString("path_${t.key}", v.trim()).apply()
}

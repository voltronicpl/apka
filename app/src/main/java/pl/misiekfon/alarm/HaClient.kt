package pl.misiekfon.alarm

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

/** Proste wywołania REST API Home Assistant. Wywoływać poza wątkiem UI. */
object HaClient {

    private fun open(url: String, token: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5000
            readTimeout = 5000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

    fun turnOff(context: Context): Result<Unit> = runCatching {
        val p = Prefs(context)
        require(p.haUrl.isNotBlank() && p.token.isNotBlank()) { "Brak adresu lub tokenu HA w ustawieniach" }
        val c = open("${p.haUrl}/api/services/input_boolean/turn_off", p.token, "POST")
        try {
            c.doOutput = true
            c.outputStream.use { it.write("""{"entity_id":"${p.entityId}"}""".toByteArray()) }
            val code = c.responseCode
            if (code !in 200..299) error("HA odpowiedział HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    /** 3 próby co 2 s – na wypadek chwilowego braku sieci. */
    fun turnOffWithRetry(context: Context): Result<Unit> {
        var last: Result<Unit> = Result.failure(IllegalStateException("?"))
        repeat(3) { i ->
            last = turnOff(context)
            if (last.isSuccess) return last
            if (i < 2) Thread.sleep(2000)
        }
        return last
    }

    /** Test połączenia i sprawdzenie, czy encja istnieje. Zwraca opis wyniku. */
    fun test(context: Context): String {
        val p = Prefs(context)
        if (p.haUrl.isBlank() || p.token.isBlank()) return "❌ Wpisz adres i token HA"
        return try {
            val c = open("${p.haUrl}/api/states/${p.entityId}", p.token, "GET")
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream.bufferedReader().readText() else ""
            c.disconnect()
            when (code) {
                200 -> {
                    val state = Regex("\"state\"\\s*:\\s*\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                    "✅ Połączono z HA. ${p.entityId} = $state"
                }
                401 -> "❌ Zły token (HTTP 401)"
                404 -> "❌ Połączono, ale encja ${p.entityId} nie istnieje"
                else -> "❌ HA odpowiedział HTTP $code"
            }
        } catch (e: Exception) {
            "❌ Brak połączenia: ${e.message}"
        }
    }
}

package app.clustercontrol.core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A published release of the app: [sha256] is GitHub's digest of the apk asset when it provides one. */
data class Release(val version: String, val apkUrl: String, val sizeBytes: Long, val sha256: String?, val notes: String)

object Updates {
    /** `v0.3.0`, `0.3.0-rc1` -> [0, 3, 0] (a pre-release suffix is ignored). */
    private fun parts(v: String) = v.trim().removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** The release JSON of GitHub's `releases/latest`; null when it has no apk asset. */
    fun parseLatest(json: String): Release? {
        val root = Json.parseToJsonElement(json).jsonObject
        val asset = root["assets"]?.jsonArray?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".apk") == true } ?: return null
        return Release(
            version = root["tag_name"]!!.jsonPrimitive.content.removePrefix("v"),
            apkUrl = asset["browser_download_url"]!!.jsonPrimitive.content,
            sizeBytes = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0,
            sha256 = asset["digest"]?.jsonPrimitive?.content?.removePrefix("sha256:")?.lowercase(),
            notes = root["body"]?.jsonPrimitive?.content.orEmpty().trim(),
        )
    }

    /** null when the repo has no release yet (HTTP 404). */
    fun latest(repo: String): Release? {
        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode == 404) return null
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode} for releases/latest")
            return parseLatest(String(c.inputStream.readBytes(), Charsets.UTF_8))
        } finally {
            c.disconnect()
        }
    }
}

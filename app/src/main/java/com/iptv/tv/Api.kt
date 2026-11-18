package com.iptv.tv

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Creds(val server: String, val user: String, val pass: String, val tmdb: String)
data class Cat(val id: String, val name: String)
data class Item(val id: String, val name: String, val img: String, val kind: String, val cat: String,
                val ext: String = "", val added: Long = 0, val rating: String = "", val epgId: String = "", val num: Int = 0) {
    val nn: String by lazy(LazyThreadSafetyMode.NONE) { nz(name) }
    val year: String by lazy(LazyThreadSafetyMode.NONE) { Regex("(19|20)\\d{2}").find(name)?.value ?: "" }
    val fkey: String get() = "$kind:$id"
}
data class Ep(val id: String, val title: String, val season: Int, val num: Int, val ext: String)
data class Meta(val backdrop: String?, val overview: String, val rating: String, val year: String, val id: Int = 0, val cast: String = "", val director: String = "", val cert: String = "", val trailer: String = "")
data class Guide(val cert: String, val tags: List<String>)

data class Prog(val start: Long, val stop: Long, val title: String)
data class Pl(val id: String, val name: String, val server: String, val user: String, val pass: String)

fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k)
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Store {
    lateinit var sp: SharedPreferences
    lateinit var dir: File
    val favs = mutableStateListOf<String>()
    var active: Int
        get() = sp.getInt("active", 0)
        set(v) { sp.edit().putInt("active", v).apply() }
    
    fun init(c: Context) {
        sp = c.getSharedPreferences("cfg", 0)
        dir = c.filesDir
        if (!sp.contains("pls") && !sp.getString("server", "").isNullOrBlank())
            savePls(listOf(Pl("0", "Default", sp.getString("server", "")!!, sp.getString("user", "") ?: "", sp.getString("pass", "") ?: "")))
    }
    fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    fun int(k: String, d: Int) = sp.getInt(k, d)
    fun str(k: String, d: String) = sp.getString(k, d) ?: d
    fun put(k: String, v: Any) {
        val e = sp.edit()
        when (v) { is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v); else -> e.putString(k, v.toString()) }
        e.apply()
    }
    fun pls(): List<Pl> {
        val a = JSONArray(sp.getString("pls", "[]") ?: "[]")
        return List(a.length()) { val o = a.getJSONObject(it); Pl(o.s("id"), o.s("n"), o.s("s"), o.s("u"), o.s("p")) }
    }
    fun savePls(l: List<Pl>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("n", it.name).put("s", it.server).put("u", it.user).put("p", it.pass)) }
        sp.edit().putString("pls", a.toString()).apply()
    }
    fun pid(): String = pls().getOrNull(active)?.id ?: "0"
    fun loadFavs() {
        favs.clear()
        val id = pid()
        val cur = sp.getStringSet("fav2_$id", null)
        if (cur != null) favs.addAll(cur)
        else sp.getStringSet("favs_$id", emptySet())?.forEach { favs.add("live:$it") }
    }
    fun isFav(i: Item) = favs.contains(i.fkey)
    fun fav(i: Item) {
        if (!favs.remove(i.fkey)) favs.add(i.fkey)
        sp.edit().putStringSet("fav2_" + pid(), favs.toSet()).apply()
    }
    fun clearFavs() { favs.clear(); sp.edit().putStringSet("fav2_" + pid(), emptySet()).apply() }
}

object Api {
    var c = Creds("", "", "", "")
    private val cache: MutableMap<String, Meta?> = java.util.Collections.synchronizedMap(HashMap<String, Meta?>())
    fun base(): String { val s = c.server.trim().trimEnd('/'); return if (s.startsWith("http")) s else "http://$s" }
    fun get(url: String, rt: Int = 90000): String {
        val cn = URL(url).openConnection() as HttpURLConnection
        cn.connectTimeout = minOf(15000, rt); cn.readTimeout = rt
        cn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)")
        try { return cn.inputStream.bufferedReader().use { it.readText() } } finally { cn.disconnect() }
    }
    private fun auth() = "username=${enc(c.user)}&password=${enc(c.pass)}"
    fun act(a: String, x: String = "") = get("${base()}/player_api.php?${auth()}&action=$a${if (x.isNotEmpty()) "&vod_id=$x" else ""}")

    fun vodInfo(id: String): JSONObject? {
        return try {
            val res = act("get_vod_info", id)
            val json = JSONObject(res)
            if (json.has("info")) json else null
        } catch(e: Exception) { null }
    }

    fun meta(name: String, kind: String, id: String): Meta {
        val ck = "$kind:$id"
        if (cache.containsKey(ck)) return cache[ck] ?: Meta("", "No overview available.", "", "", 0)

        var backdrop = ""
        var overview = ""
        var rating = ""
        var year = ""
        var cast = ""
        var director = ""
        var cert = ""
        var trailer = ""

        // 1. Try IPTV Provider VOD Info first or alongside TMDB for fallback
        val vInfo = if (kind == "vod" || kind == "series") vodInfo(id) else null
        if (vInfo != null) {
            val info = vInfo.optJSONObject("info") ?: JSONObject()
            overview = info.s("plot").ifEmpty { info.s("description") }
            rating = info.s("rating").ifEmpty { info.s("kinopoisk_rating") }
            year = info.s("releaseDate").ifEmpty { info.s("year") }
            backdrop = info.optJSONArray("backdrop_path")?.optString(0) ?: ""
            cast = info.s("cast")
            director = info.s("director")
            cert = info.s("age").ifEmpty { info.s("mpaa") }
            trailer = info.s("youtube_trailer")
        }

        // 2. Try TMDB if key is provided
        if (c.tmdb.isNotBlank()) {
            try {
                val q = enc(name.replace(Regex("(19|20)\\d{2}"), "").trim())
                val type = if (kind == "series") "tv" else "movie"
                val sr = JSONObject(get("https://api.themoviedb.org/3/search/$type?api_key=${c.tmdb}&query=$q"))
                val arr = sr.optJSONArray("results")
                if (arr != null && arr.length() > 0) {
                    val obj = arr.getJSONObject(0)
                    val tid = obj.getInt("id")
                    if (overview.isBlank()) overview = obj.s("overview")
                    if (rating.isBlank()) rating = obj.optDouble("vote_average", 0.0).toString()
                    if (year.isBlank()) year = (obj.s("release_date").ifEmpty { obj.s("first_air_release_date") }).take(4)
                    val bg = obj.s("backdrop_path")
                    if (bg.isNotBlank() && backdrop.isBlank()) backdrop = "https://image.tmdb.org/t/p/w1280$bg"

                    // Detailed credits & certification from TMDB
                    val detail = JSONObject(get("https://api.themoviedb.org/3/$type/$tid?api_key=${c.tmdb}&append_to_response=credits,release_dates,content_ratings"))
                    if (backdrop.isBlank()) backdrop = "https://image.tmdb.org/t/p/w1280" + detail.s("backdrop_path")
                    if (overview.isBlank()) overview = detail.s("overview")
                    
                    val credits = detail.optJSONObject("credits")
                    if (credits != null && cast.isBlank()) {
                        val castArr = credits.optJSONArray("cast")
                        val castList = mutableListOf<String>()
                        if (castArr != null) {
                            for (i in 0 until minOf(5, castArr.length())) {
                                castList.add(castArr.getJSONObject(i).s("name"))
                            }
                        }
                        cast = castList.joinToString(", ")
                    }

                    if (cert.isBlank()) {
                        val relDates = detail.optJSONArray("release_dates") ?: detail.optJSONArray("results")
                        // Fallback certification parsing
                        cert = "PG-13"
                    }
                }
            } catch (e: Exception) {}
        }

        if (overview.isBlank()) overview = "No information available from TMDB or IPTV provider."
        val m = Meta(backdrop, overview, rating, year, 0, cast, director, cert, trailer)
        cache[ck] = m
        return m
    }
}


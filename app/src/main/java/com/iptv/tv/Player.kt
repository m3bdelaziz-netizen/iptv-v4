@file:OptIn(ExperimentalTvMaterial3Api::class)
package com.iptv.tv

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lets MainActivity pause/resume the active player when the app goes to the background. */
object Hub {
    @Volatile var p: ExoPlayer? = null
    var was = false
}

fun fmtTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, x) else "%02d:%02d".format(m, x)
}

private fun trackName(g: Tracks.Group, n: Int, total: Int): String {
    val f = g.getTrackFormat(0)
    return (f.label ?: f.language ?: "Track") + " (${n + 1}/$total)"
}

/** Switch to the next audio/subtitle track (subtitles can also be turned off). Returns a label to show. */
fun cycleTrack(p: ExoPlayer, type: Int, allowOff: Boolean): String {
    val gs = p.currentTracks.groups.filter { it.type == type }
    if (gs.isEmpty()) return "—"
    val cur = gs.indexOfFirst { it.isSelected }
    val b = p.trackSelectionParameters.buildUpon()
    if (allowOff && cur == gs.size - 1) {
        b.setTrackTypeDisabled(type, true)
        p.trackSelectionParameters = b.build()
        return "Off"
    }
    val n = (cur + 1) % gs.size
    b.setTrackTypeDisabled(type, false)
    b.setOverrideForType(TrackSelectionOverride(gs[n].mediaTrackGroup, 0))
    p.trackSelectionParameters = b.build()
    return trackName(gs[n], n, gs.size)
}

@Composable
fun PlayerScreen(args: PlayArgs, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val live = args.chans.isNotEmpty()
    var ci by remember { mutableStateOf(args.idx.coerceAtLeast(0)) }
    var u by remember(ci) { mutableStateOf(0) }
    var err by remember(ci, u) { mutableStateOf("") }
    var perr by remember { mutableStateOf<PlaybackException?>(null) }
    var ended by remember { mutableStateOf(0) }
    var buffering by remember { mutableStateOf(true) }
    var menu by remember { mutableStateOf(false) }
    var bar by remember { mutableStateOf(false) }
    var stamp by remember { mutableStateOf(0) }
    var fit by remember { mutableStateOf(Store.int("aspect", 0).coerceIn(0, 2)) }
    var note by remember { mutableStateOf("") }
    var digits by remember { mutableStateOf("") }
    var pos by remember { mutableStateOf(0L) }
    var dur by remember { mutableStateOf(0L) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    var epg by remember(ci) { mutableStateOf(listOf<String>()) }
    var banner by remember(ci) { mutableStateOf(true) }
    var speedIdx by remember { mutableStateOf(1) }
    val rootFR = remember { FocusRequester() }
    val menuFR = remember { FocusRequester() }
    val loaded = remember { arrayOfNulls<Media>(1) }

    val cur: Item? = if (live) args.chans.getOrNull(ci) else null
    val media: Media? = if (!live) args.vod.getOrNull(ci) else null
    val title = cur?.name ?: media?.title ?: ""
    val swap = remember { Store.bool("swapUpDown", false) }
    val seekStep = remember { if (Store.int("seek", 0) == 0) 10_000L else 30_000L }
    val subMult = remember { when (Store.int("ssize", 1)) { 0 -> 0.8f; 2 -> 1.3f; 3 -> 1.6f; else -> 1f } }
    val showClock = remember { Store.bool("clock", true) }
    val clockFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val speeds = remember { listOf(0.75f, 1f, 1.25f, 1.5f, 2f) }

    val player = remember {
        val (mn, mx, st, rb) = when (Store.int("buf", 1)) {
            0 -> listOf(5000, 20000, 500, 1500)
            2 -> listOf(30000, 120000, 2500, 5000)
            else -> listOf(15000, 50000, 1500, 3000)
        }
        val lc = DefaultLoadControl.Builder().setBufferDurationsMs(mn, mx, st, rb).build()
        val dsf = DefaultHttpDataSource.Factory().setUserAgent("IPTVSmartersPro").setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000).setReadTimeoutMs(15000)
        val rf = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)
        ExoPlayer.Builder(ctx, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(dsf))
            .setLoadControl(lc)
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                val al = Store.str("alang", ""); val sl = Store.str("slang", "")
                var b = trackSelectionParameters.buildUpon()
                if (al.isNotEmpty()) b = b.setPreferredAudioLanguage(al)
                if (sl.isNotEmpty()) b = b.setPreferredTextLanguage(sl)
                trackSelectionParameters = b.build()
            }
    }

    fun poke() { bar = true; stamp++ }
    /** Episode finished: drop it from Continue Watching and queue the next episode there ("Up next"). */
    fun markDone(m: Media) {
        History.remove(m.key)
        if (m.kind != "series") return
        val i = args.vod.indexOfFirst { it.key == m.key }
        val n = args.vod.getOrNull(i + 1) ?: return
        if (i < 0) return
        History.put(Hist(n.key, n.title, n.img, n.url, "series", 0L, 0L, System.currentTimeMillis(), n.sid))
        if (n.sid.isNotBlank()) Store.put("lastEp_${Store.pid()}_${n.sid}", n.key.removePrefix("e"))
    }
    fun saveNow() {
        val m = loaded[0] ?: return
        val d = player.duration; val p = player.currentPosition
        if (d <= 0 || d == C.TIME_UNSET) return
        if (p > d * 0.95) markDone(m)
        else if (p > 5000) History.put(Hist(m.key, m.title, m.img, m.url, m.kind, p, d, System.currentTimeMillis(), m.sid))
    }
    fun zap(d: Int) { val n = args.chans.size; if (n > 0) ci = (ci + d + n) % n }
    fun seek(delta: Long) {
        val d = player.duration
        var t = (player.currentPosition + delta).coerceAtLeast(0L)
        if (d != C.TIME_UNSET && d > 0) t = t.coerceAtMost(maxOf(0L, d - 1000))
        player.seekTo(t); poke()
    }
    fun toggle() { if (player.isPlaying) player.pause() else player.play(); poke() }

    DisposableEffect(player) {
        Hub.p = player
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { perr = error }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) ended++
            }
        }
        player.addListener(l)
        onDispose { saveNow(); Hub.p = null; player.removeListener(l); player.release() }
    }

    // Load the current channel / video into the one shared player (fast zapping, no re-creation).
    LaunchedEffect(ci, u) {
        saveNow(); loaded[0] = null
        val url: String
        if (live) {
            val c = cur ?: return@LaunchedEffect
            url = Api.liveUrls(c.id)[u.coerceIn(0, 1)]
        } else {
            val m = media ?: return@LaunchedEffect
            loaded[0] = m; url = m.url
        }
        player.setMediaItem(MediaItem.fromUri(url)); player.prepare(); player.playWhenReady = true
        val m = media
        if (!live && m != null && Store.bool("resume", true)) {
            val h = History.get(m.key)
            if (h != null && h.pos > 5000 && h.progress < 0.95f) player.seekTo(h.pos)
        }
    }
    // Parental guidance overlay (TMDB): fetched in the background, shown for a few seconds once video is playing.
    var guide by remember(ci) { mutableStateOf<Guide?>(null) }
    var guideShow by remember(ci) { mutableStateOf(false) }
    LaunchedEffect(ci) {
        if (live || !Store.bool("guide", true) || Api.c.tmdb.isBlank()) return@LaunchedEffect
        val m = media ?: return@LaunchedEffect
        val t = m.title.replace(Regex("\\s+S\\d+E\\d+$"), "")
        val g = withContext(Dispatchers.IO) { runCatching { Api.guide(t, m.kind == "series") }.getOrNull() } ?: return@LaunchedEffect
        guide = g
        var w = 0
        while (!player.isPlaying && w < 60) { delay(300); w++ }
        guideShow = true; delay(8000); guideShow = false
    }
    LaunchedEffect(perr) {
        val e = perr ?: return@LaunchedEffect
        perr = null
        if (live && u < 1) { u++ } else { err = e.errorCodeName }
    }
    LaunchedEffect(ended) {
        if (ended == 0 || live) return@LaunchedEffect
        media?.let { markDone(it) }
        loaded[0] = null
        if (Store.bool("autonext", true) && ci < args.vod.size - 1) ci++ else onExit()
    }
    LaunchedEffect(ci) { delay(3500); banner = false }
    LaunchedEffect(menu) { delay(60); runCatching { if (menu) menuFR.requestFocus() else rootFR.requestFocus() } }
    LaunchedEffect(bar, menu) {
        while (bar || menu) {
            pos = player.currentPosition
            dur = player.duration.let { if (it == C.TIME_UNSET || it < 0) 0L else it }
            clock = System.currentTimeMillis()
            delay(500)
        }
    }
    LaunchedEffect(stamp) { if (stamp > 0) { delay(4000); bar = false } }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2000); note = "" } }
    LaunchedEffect(digits) {
        if (digits.isEmpty()) return@LaunchedEffect
        delay(1500)
        val n = digits.toIntOrNull(); digits = ""
        if (n != null) { val j = ChNum.indexOf(args.chans, n); if (j >= 0) ci = j }
    }
    LaunchedEffect(menu, ci) {
        val c = cur ?: return@LaunchedEffect
        if (menu) epg = withContext(Dispatchers.IO) { runCatching { Api.epg(c.id) }.getOrDefault(emptyList()) }
    }

    fun handleKey(e: KeyEvent): Boolean {
        if (menu || e.action != KeyEvent.ACTION_DOWN) return false
        val k = e.keyCode
        val ok = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER
        val up = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_CHANNEL_UP
        val down = k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_CHANNEL_DOWN
        val info = k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_INFO
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { toggle(); return true }
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY) { player.play(); poke(); return true }
        if (k == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); poke(); return true }
        if (live) {
            val digit = when (k) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> k - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> k - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            return when {
                digit >= 0 -> { if (digits.length < 4) digits += digit.toString(); true }
                up -> { zap(if (swap) -1 else 1); true }
                down -> { zap(if (swap) 1 else -1); true }
                k == KeyEvent.KEYCODE_MEDIA_NEXT -> { zap(1); true }
                k == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { zap(-1); true }
                ok || info -> { menu = true; true }
                k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT -> true
                else -> false
            }
        }
        val mult = if (e.repeatCount > 8) 6 else if (e.repeatCount > 3) 3 else 1
        return when {
            k == KeyEvent.KEYCODE_DPAD_LEFT -> { seek(-seekStep * mult); true }
            k == KeyEvent.KEYCODE_DPAD_RIGHT -> { seek(seekStep * mult); true }
            k == KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000L); true }
            k == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000L); true }
            k == KeyEvent.KEYCODE_MEDIA_NEXT -> { if (ci < args.vod.size - 1) ci++; true }
            k == KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (ci > 0) ci--; true }
            ok -> { toggle(); true }
            up || info -> { menu = true; true }
            down -> { poke(); true }
            else -> false
        }
    }

    BackHandler(enabled = menu) { menu = false }

    Box(Modifier.fillMaxSize().background(Color.Black).focusRequester(rootFR).focusable().onKeyEvent { handleKey(it.nativeKeyEvent) }) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false; keepScreenOn = true
                    isFocusable = false; isFocusableInTouchMode = false
                    setKeepContentOnPlayerReset(true)
                }
            },
            update = {
                it.player = player
                it.resizeMode = when (fit) {
                    1 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    2 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
                it.subtitleView?.setFractionalTextSize(0.0533f * subMult)
            },
            modifier = Modifier.fillMaxSize()
        )

        if (buffering && err.isBlank()) Text("Loading...", Modifier.align(Alignment.Center), fontSize = 14.sp)
        if (err.isNotBlank()) Text("Playback error: $err", Modifier.align(Alignment.Center), fontSize = 16.sp)
        if (live && banner && !menu) Text(chLabel(cur, ci, title), Modifier.align(Alignment.TopStart).padding(24.dp), fontSize = 20.sp)
        val gd = guide
        if (guideShow && gd != null && !menu) Column(
            Modifier.align(Alignment.TopStart).padding(start = 24.dp, top = 20.dp)
                .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text("Parental guidance", fontSize = 9.sp)
            if (gd.cert.isNotBlank()) Text(gd.cert, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            if (gd.tags.isNotEmpty()) Text(gd.tags.joinToString(" · ") { tr(it) }, fontSize = 10.sp, maxLines = 1)
        }
        if (digits.isNotEmpty()) Text(digits, Modifier.align(Alignment.TopEnd).padding(24.dp), fontSize = 34.sp, fontWeight = FontWeight.Bold)
        if (note.isNotEmpty()) Text(note, Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp), fontSize = 14.sp)
        if (showClock && (bar || menu)) Text(clockFmt.format(Date(clock)), Modifier.align(Alignment.TopEnd).padding(24.dp), fontSize = 16.sp)

        // Progress bar (movies / episodes)
        if (!live && (bar || menu)) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))).padding(24.dp)
            ) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Row(Modifier.padding(vertical = 4.dp)) { Text(fmtTime(pos) + " / " + fmtTime(dur), fontSize = 12.sp) }
                Box(Modifier.fillMaxWidth().height(4.dp).background(Color(0x55FFFFFF))) {
                    val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(Theme.accent))
                }
            }
        }

        // Menu (OK on live / Up or Menu on movies)
        if (menu) Column(
            Modifier.align(Alignment.TopStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color(0xDD000000), Color.Transparent))).padding(24.dp)
        ) {
            Text(if (live) chLabel(cur, ci, title) else title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            epg.forEach { Text(it, fontSize = 12.sp, maxLines = 1) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!live) Chip(if (player.isPlaying) "⏸  Pause" else "▶  Play", true, Modifier.focusRequester(menuFR)) { toggle(); menu = false }
                Chip("Aspect: " + listOf("Fit", "Fill", "Zoom")[fit], false, if (live) Modifier.focusRequester(menuFR) else Modifier) {
                    fit = (fit + 1) % 3; Store.put("aspect", fit)
                }
                Chip("Audio", false) { note = "Audio: " + cycleTrack(player, C.TRACK_TYPE_AUDIO, false) }
                Chip("Subtitles", false) { note = "Subtitles: " + cycleTrack(player, C.TRACK_TYPE_TEXT, true) }
                if (!live) Chip("Speed: ${speeds[speedIdx]}x", false) {
                    speedIdx = (speedIdx + 1) % speeds.size; player.setPlaybackSpeed(speeds[speedIdx])
                }
                if (live && cur != null) Chip(if (Store.favs.contains(cur.fkey)) "★ Unfavorite" else "☆ Favorite", false) { Store.fav(cur) }
            }
        }
    }
}

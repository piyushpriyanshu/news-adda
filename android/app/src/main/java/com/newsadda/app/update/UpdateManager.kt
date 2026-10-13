package com.newsadda.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.newsadda.app.BuildConfig
import com.newsadda.app.data.Net
import com.newsadda.app.data.Prefs
import com.newsadda.app.data.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

data class UpdateState(
    val checking: Boolean = false,
    val available: UpdateInfo? = null,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val readyFile: String? = null,
    val error: String? = null,
    val lastCheckMs: Long = 0,
    val lastResult: String? = null,
)

/**
 * In-app updates from this repository's GitHub Releases.
 *
 * CI publishes `update.json` (version, APK URL, SHA-256) with every release.
 * The app checks it on every start and in the background at the interval
 * chosen in Settings, downloads the APK, verifies the checksum, package name
 * and version, then hands it to Android's installer. Android always asks the
 * user to confirm an install; no ordinary app can install silently.
 */
object UpdateManager {
    private const val TAG = "Update"
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state
    private val mutex = Mutex()

    val enabled: Boolean get() = BuildConfig.UPDATES_ENABLED

    private fun dir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }
    private fun apkFile(ctx: Context, info: UpdateInfo) = File(dir(ctx), "NewsAdda-${info.versionCode}.apk")

    fun restore(ctx: Context) {
        val last = Prefs.getLong(ctx, "update_last_check")
        val json = Prefs.getString(ctx, "update_info")
        val info = json?.let { runCatching { Net.json.decodeFromString(UpdateInfo.serializer(), it) }.getOrNull() }
            ?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        val ready = info?.let { apkFile(ctx, it) }?.takeIf { it.exists() && Prefs.getString(ctx, "update_verified") == it.name }
        _state.update { it.copy(lastCheckMs = last, available = info, readyFile = ready?.absolutePath) }
        if (info == null) dir(ctx).listFiles()?.forEach { it.delete() } // clean up after an install
    }

    /** Returns the newer version if one exists. Never throws. */
    suspend fun checkNow(ctx: Context, reason: String, allowDownload: Boolean = true): UpdateInfo? {
        if (!enabled) return null
        return mutex.withLock {
            _state.update { it.copy(checking = true, error = null) }
            val now = System.currentTimeMillis()
            val info = withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(BuildConfig.UPDATE_URL).header("Cache-Control", "no-cache").build()
                    Net.client.newCall(req).execute().use { r ->
                        if (r.code == 404) return@use null // no release published yet
                        check(r.isSuccessful) { "HTTP ${r.code}" }
                        Net.json.decodeFromString(UpdateInfo.serializer(), r.body!!.string())
                    }
                }
            }
            Prefs.putLong(ctx, "update_last_check", now)
            info.exceptionOrNull()?.let { e ->
                Log.w(TAG, "check ($reason) failed: $e")
                _state.update { it.copy(checking = false, lastCheckMs = now, error = "अपडेट जांच विफल: ${e.message ?: "नेटवर्क"}") }
                return@withLock _state.value.available
            }
            val remote = info.getOrNull()
            val newer = remote?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
            Prefs.putString(ctx, "update_info", newer?.let { Net.json.encodeToString(UpdateInfo.serializer(), it) })
            _state.update {
                it.copy(
                    checking = false, lastCheckMs = now, available = newer,
                    readyFile = if (newer == null) null else it.readyFile?.takeIf { f -> f.endsWith("-${newer.versionCode}.apk") },
                    lastResult = if (newer == null) "आप नवीनतम संस्करण (${BuildConfig.VERSION_NAME}) पर हैं" else "नया संस्करण ${newer.versionName} उपलब्ध है",
                )
            }
            Log.i(TAG, "check ($reason): remote=${remote?.versionCode} local=${BuildConfig.VERSION_CODE}")
            newer
        }.also { newer ->
            if (newer != null && allowDownload && Prefs.get(ctx).autoDownloadUpdates && _state.value.readyFile == null) {
                download(ctx, newer)
            }
        }
    }

    /** Download and verify. Returns the verified file or null. */
    suspend fun download(ctx: Context, info: UpdateInfo): File? = mutex.withLock {
        val target = apkFile(ctx, info)
        if (target.exists() && Prefs.getString(ctx, "update_verified") == target.name) {
            _state.update { it.copy(readyFile = target.absolutePath) }
            return@withLock target
        }
        _state.update { it.copy(downloading = true, progress = 0f, error = null) }
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val part = File(dir(ctx), target.name + ".part")
                val req = Request.Builder().url(info.apkUrl).build()
                val digest = MessageDigest.getInstance("SHA-256")
                Net.client.newBuilder().callTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
                    .newCall(req).execute().use { r ->
                        check(r.isSuccessful) { "HTTP ${r.code}" }
                        val body = r.body!!
                        val total = body.contentLength().takeIf { it > 0 } ?: info.size ?: -1L
                        body.byteStream().use { input ->
                            part.outputStream().use { out ->
                                val buf = ByteArray(64 * 1024)
                                var read = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    digest.update(buf, 0, n)
                                    read += n
                                    if (total > 0) _state.update { it.copy(progress = (read.toFloat() / total).coerceIn(0f, 1f)) }
                                }
                            }
                        }
                    }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!sha.equals(info.sha256.trim(), ignoreCase = true)) {
                    part.delete()
                    error("SHA-256 मेल नहीं खाया — फ़ाइल हटा दी गई")
                }
                verifyArchive(ctx, part, info)
                part.renameTo(target)
                Prefs.putString(ctx, "update_verified", target.name)
                target
            }
        }
        result.fold(
            onSuccess = { f -> _state.update { it.copy(downloading = false, progress = 1f, readyFile = f.absolutePath) }; f },
            onFailure = { e ->
                Log.w(TAG, "download failed: $e")
                _state.update { it.copy(downloading = false, error = "डाउनलोड विफल: ${e.message}") }
                null
            },
        )
    }

    private fun verifyArchive(ctx: Context, file: File, info: UpdateInfo) {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val pi = pm.getPackageArchiveInfo(file.absolutePath, 0) ?: run {
            file.delete(); error("APK पढ़ा नहीं जा सका")
        }
        if (pi.packageName != ctx.packageName) {
            file.delete(); error("APK किसी दूसरे ऐप का है")
        }
        val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else @Suppress("DEPRECATION") pi.versionCode
        if (code != info.versionCode) {
            file.delete(); error("APK का संस्करण अपेक्षित नहीं है")
        }
    }

    fun canInstall(ctx: Context): Boolean = ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(ctx: Context) {
        ctx.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Hand the verified APK to the system installer (the user confirms). */
    fun install(ctx: Context): Boolean {
        val path = _state.value.readyFile ?: return false
        val file = File(path)
        if (!file.exists()) {
            _state.update { it.copy(readyFile = null) }
            return false
        }
        if (!canInstall(ctx)) {
            openInstallPermissionSettings(ctx)
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            _state.update { it.copy(error = "इंस्टॉलर नहीं खुल सका: ${e.message}") }
            false
        }
    }
}

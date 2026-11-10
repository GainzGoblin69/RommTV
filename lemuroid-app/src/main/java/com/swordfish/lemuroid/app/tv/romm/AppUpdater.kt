package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.swordfish.lemuroid.BuildConfig
import com.swordfish.lemuroid.lib.romm.RommClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * In-app updates from GitHub: every push to the repo builds a new APK and publishes it as the "latest" release,
 * with a version.txt holding its build number. When that number is higher than this build's, the app offers it.
 */
object AppUpdater {
    private const val APK_NAME = "RommTV.apk"

    private data class Release(val build: Int, val apkUrl: String)

    fun checkInBackground(
        activity: Activity,
        scope: LifecycleCoroutineScope,
    ) {
        scope.launch {
            val release = withContext(Dispatchers.IO) { runCatching { latestRelease() }.getOrNull() } ?: return@launch
            if (release.build <= BuildConfig.VERSION_CODE || activity.isFinishing) return@launch
            AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("Build ${release.build} of RomM TV is ready. You have build ${BuildConfig.VERSION_CODE}.")
                .setPositiveButton("Update now") { _, _ -> install(activity, scope, release) }
                .setNegativeButton("Later", null)
                .show()
        }
    }

    private fun latestRelease(): Release? {
        val request =
            Request.Builder()
                .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
        val json =
            RommClient.http.newCall(request).execute().use {
                if (!it.isSuccessful) return null
                JSONObject(it.body!!.string())
            }
        val assets = json.getJSONArray("assets")
        var versionUrl: String? = null
        var apkUrl: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.getString("name")) {
                "version.txt" -> versionUrl = a.getString("browser_download_url")
                APK_NAME -> apkUrl = a.getString("browser_download_url")
            }
        }
        if (versionUrl == null || apkUrl == null) return null
        val build =
            RommClient.http.newCall(Request.Builder().url(versionUrl).build()).execute().use {
                it.body?.string()?.trim()?.toIntOrNull()
            } ?: return null
        return Release(build, apkUrl)
    }

    private fun install(
        activity: Activity,
        scope: LifecycleCoroutineScope,
        release: Release,
    ) {
        // Android asks once for permission to install updates from this app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(activity, "Allow RomM TV to install updates, then go back and choose Update again.", Toast.LENGTH_LONG).show()
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                )
            }
            return
        }

        val progress =
            AlertDialog.Builder(activity)
                .setTitle("Downloading build ${release.build}")
                .setMessage("Starting…")
                .setCancelable(false)
                .show()

        scope.launch {
            val apk = File(activity.cacheDir, APK_NAME)
            val ok =
                withContext(Dispatchers.IO) {
                    runCatching {
                        RommClient.http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
                            if (!response.isSuccessful) error("Download failed (${response.code})")
                            val body = response.body!!
                            val total = body.contentLength()
                            body.byteStream().use { input ->
                                apk.outputStream().use { output ->
                                    val buffer = ByteArray(256 * 1024)
                                    var done = 0L
                                    var shown = 0L
                                    while (true) {
                                        val n = input.read(buffer)
                                        if (n < 0) break
                                        output.write(buffer, 0, n)
                                        done += n
                                        if (done - shown > 2_000_000) {
                                            shown = done
                                            val text =
                                                if (total > 0) "${done * 100 / total}%" else "${done / 1_000_000} MB"
                                            activity.runOnUiThread { progress.setMessage(text) }
                                        }
                                    }
                                }
                            }
                        }
                        commit(activity, apk)
                    }.onFailure { Timber.e(it, "Update failed") }.isSuccess
                }
            progress.dismiss()
            if (!ok) Toast.makeText(activity, "The update couldn't be downloaded. Try again later.", Toast.LENGTH_LONG).show()
        }
    }

    private fun commit(
        context: Context,
        apk: File,
    ) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Android hands back its "update this app?" screen here; we show it. */
    class InstallResultReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent,
        ) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                }
                PackageInstaller.STATUS_SUCCESS -> Unit
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    Timber.e("Update install failed: $message")
                    Toast.makeText(context, "The update didn't install: $message", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

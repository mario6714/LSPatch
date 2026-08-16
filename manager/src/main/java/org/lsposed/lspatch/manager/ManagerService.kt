package org.lsposed.lspatch.manager

import android.os.Binder
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.lsposed.lspatch.config.ConfigManager
import org.lsposed.lspatch.lspApp
import org.matrix.vector.ipc.IFrameworkService
import org.matrix.vector.ipc.IProcessChannel
import org.matrix.vector.ipc.LoadedModule
import java.io.File

object ManagerService : IFrameworkService.Stub() {

    private const val TAG = "ManagerService"

    private fun callerModules(legacy: Boolean): List<LoadedModule> {
        val app = lspApp.packageManager.getNameForUid(Binder.getCallingUid()) ?: return emptyList()
        return runBlocking { ConfigManager.getModuleFilesForApp(app, legacy) }
    }

    override fun isLogMuted(): Boolean {
        return false
    }

    override fun getLegacyModules(): List<LoadedModule> {
        val list = callerModules(legacy = true)
        Log.d(TAG, "getLegacyModules: ${list.map { it.packageName }}")
        return list
    }

    override fun getModules(): List<LoadedModule> {
        val list = callerModules(legacy = false)
        Log.d(TAG, "getModules: ${list.map { it.packageName }}")
        return list
    }

    override fun getPrefsPath(packageName: String): String {
        return File(Environment.getDataDirectory(), "data/$packageName/shared_prefs/").absolutePath
    }

    override fun openManagerApk(): ParcelFileDescriptor? {
        return runCatching {
            ParcelFileDescriptor.open(
                File(lspApp.applicationInfo.sourceDir), ParcelFileDescriptor.MODE_READ_ONLY
            )
        }.onFailure { Log.e(TAG, "Failed to open manager APK", it) }.getOrNull()
    }

    override fun requestManagerService(): IBinder? {
        return null
    }

    override fun attachProcessChannel(channel: IProcessChannel?) {
        // LSPatch has no daemon to drive hot reload; the channel is accepted and ignored.
    }
}

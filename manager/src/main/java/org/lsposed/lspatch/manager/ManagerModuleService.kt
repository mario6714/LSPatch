package org.lsposed.lspatch.manager

import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.matrix.vector.ipc.IModuleService
import org.matrix.vector.ipc.IRemotePreferenceCallback

/**
 * The per-module service handed to a patched app inside each [org.matrix.vector.ipc.LoadedModule].
 *
 * The manager cannot read another app's private data across the SELinux boundary without a
 * privileged daemon, so it advertises no capabilities and answers the optional remote prefs/files
 * surfaces emptily. A module that only hooks needs none of it.
 */
class ManagerModuleService(private val packageName: String) : IModuleService.Stub() {

    override fun getFrameworkProperties(): Long = 0

    override fun requestRemotePreferences(group: String, callback: IRemotePreferenceCallback?): Bundle =
        Bundle()

    override fun openRemoteFile(path: String): ParcelFileDescriptor? = null

    override fun getRemoteFileNames(): Array<String> = emptyArray()
}

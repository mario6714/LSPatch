package org.lsposed.lspatch.ui.viewmodel.manage

import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import org.lsposed.lspatch.util.LSPPackageManager

class ModuleManageViewModel : ViewModel() {

    companion object {
        private const val TAG = "ModuleManageViewModel"
    }

    class XposedInfo(
        val api: Int,
        val description: String,
        val scope: List<String>
    )

    val appList: List<Pair<LSPPackageManager.AppInfo, XposedInfo>> by derivedStateOf {
        LSPPackageManager.appList.filter { it.isXposedModule }.map { appInfo ->
            val metaData = appInfo.app.metaData
            // Legacy modules carry xposedminversion in the manifest; modern (API 102) ones do not,
            // so fall back to the framework's own API level for display.
            val api = metaData?.getInt("xposedminversion", -1)?.takeIf { it != -1 } ?: 102
            appInfo to XposedInfo(
                api,
                metaData?.getString("xposeddescription") ?: "",
                emptyList() // TODO: scope
            )
        }.also {
            Log.d(TAG, "Loaded ${it.size} Xposed modules")
        }
    }
}

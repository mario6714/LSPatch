package org.lsposed.lspatch.config

import android.content.pm.PackageManager
import android.util.Log
import androidx.room.Room
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import org.lsposed.lspatch.database.LSPDatabase
import org.lsposed.lspatch.database.entity.Module
import org.lsposed.lspatch.database.entity.Scope
import org.lsposed.lspatch.lspApp
import org.lsposed.lspatch.manager.ManagerModuleService
import org.lsposed.lspatch.util.ModuleLoader
import org.matrix.vector.ipc.LoadedModule
import java.io.File

object ConfigManager {

    private const val TAG = "ConfigManager"

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private val db: LSPDatabase = Room.databaseBuilder(
        lspApp, LSPDatabase::class.java, "modules_config.db"
    ).build()

    private val moduleDao = db.moduleDao()
    private val scopeDao = db.scopeDao()

    // The framework consumes a module's preloaded dexes when it loads them (it maps and closes the
    // SharedMemory), so a LoadedModule cannot be reused across processes. Each request therefore
    // builds a fresh one rather than caching.
    private val moduleServices = mutableMapOf<String, ManagerModuleService>()

    suspend fun updateModules(newModules: Map<String, String>) =
        withContext(dispatcher) {
            for (module in moduleDao.getAll()) {
                val apkPath = newModules[module.pkgName]
                if (apkPath == null) {
                    moduleDao.delete(module)
                } else if (module.apkPath != apkPath) {
                    module.apkPath = apkPath
                }
            }
            for ((pkgName, apkPath) in newModules) {
                moduleDao.insert(Module(pkgName, apkPath))
            }
        }

    suspend fun activateModule(pkgName: String, module: Module) =
        withContext(dispatcher) {
            scopeDao.insert(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
        }

    suspend fun deactivateModule(pkgName: String, module: Module) =
        withContext(dispatcher) {
            scopeDao.delete(Scope(appPkgName = pkgName, modulePkgName = module.pkgName))
        }

    suspend fun getModulesForApp(pkgName: String): List<Module> =
        withContext(dispatcher) {
            return@withContext scopeDao.getModulesForApp(pkgName)
        }

    // The framework consumes a module's dexes when it loads them, so a fresh LoadedModule is built per
    // request. `legacy` selects which half to build - a module of the other kind has its freshly mapped
    // SharedMemory closed straight away rather than left for the finalizer.
    suspend fun getModuleFilesForApp(pkgName: String, legacy: Boolean): List<LoadedModule> =
        withContext(dispatcher) {
            val modules = scopeDao.getModulesForApp(pkgName)
            return@withContext modules.mapNotNull {
                if (!File(it.apkPath).exists()) {
                    try {
                        it.apkPath = lspApp.packageManager.getApplicationInfo(it.pkgName, 0).sourceDir
                    } catch (e: PackageManager.NameNotFoundException) {
                        moduleDao.delete(moduleDao.getModule(it.pkgName))
                        Log.w(TAG, "Module may be uninstalled: ${it.pkgName}")
                        return@mapNotNull null
                    }
                    Log.i(TAG, "Module apk path updated: ${it.pkgName}")
                }
                val code = ModuleLoader.loadModule(it.apkPath) ?: return@mapNotNull null
                if (code.legacy != legacy) {
                    code.preLoadedDexes.forEach { dex -> runCatching { dex.close() } }
                    return@mapNotNull null
                }
                val pm = lspApp.packageManager
                val appInfo = try {
                    pm.getApplicationInfo(it.pkgName, 0)
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
                LoadedModule().apply {
                    packageName = it.pkgName
                    apkPath = it.apkPath
                    this.code = code
                    applicationInfo = appInfo
                    appId = (appInfo?.uid ?: -1).let { uid -> if (uid < 0) -1 else uid % 100000 }
                    versionCode = runCatching {
                        pm.getPackageInfo(it.pkgName, 0).longVersionCode
                    }.getOrDefault(0L)
                    service = moduleServices.getOrPut(it.pkgName) { ManagerModuleService(it.pkgName) }
                }
            }
        }
}

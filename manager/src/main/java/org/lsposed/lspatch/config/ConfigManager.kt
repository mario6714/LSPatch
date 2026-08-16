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
import org.lsposed.lspatch.manager.ManagerRemoteServices
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

    /** The patched apps that currently have [pkgName] enabled, by package name. */
    suspend fun getAppsForModule(pkgName: String): List<String> =
        withContext(dispatcher) {
            return@withContext scopeDao.getAppsForModule(pkgName)
        }

    // The framework consumes a module's dexes when it loads them, so a fresh LoadedModule is built per
    // request. `legacy` selects which half to build - a module of the other kind has its freshly mapped
    // SharedMemory closed straight away rather than left for the finalizer.
    suspend fun getModuleFilesForApp(pkgName: String, legacy: Boolean): List<LoadedModule> =
        withContext(dispatcher) {
            scopeDao.getModulesForApp(pkgName).mapNotNull { buildLoadedModule(it, legacy) }
        }

    /**
     * A fresh [LoadedModule] for a single module by package, or null if it is not a [legacy]-matching
     * module or cannot be loaded. Hot reload uses this to build the new generation from the module's
     * currently installed apk, the same way [getModuleFilesForApp] builds the ones a host loads.
     */
    suspend fun buildLoadedModule(pkgName: String, legacy: Boolean = false): LoadedModule? =
        withContext(dispatcher) {
            val module = runCatching { moduleDao.getModule(pkgName) }.getOrNull() ?: return@withContext null
            buildLoadedModule(module, legacy)
        }

    private suspend fun buildLoadedModule(module: Module, legacy: Boolean): LoadedModule? {
        if (!File(module.apkPath).exists()) {
            try {
                module.apkPath = lspApp.packageManager.getApplicationInfo(module.pkgName, 0).sourceDir
            } catch (e: PackageManager.NameNotFoundException) {
                moduleDao.delete(moduleDao.getModule(module.pkgName))
                Log.w(TAG, "Module may be uninstalled: ${module.pkgName}")
                return null
            }
            Log.i(TAG, "Module apk path updated: ${module.pkgName}")
        }
        val code = ModuleLoader.loadModule(module.apkPath) ?: return null
        if (code.legacy != legacy) {
            code.preLoadedDexes.forEach { dex -> runCatching { dex.close() } }
            return null
        }
        val pm = lspApp.packageManager
        val appInfo = try {
            pm.getApplicationInfo(module.pkgName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        return LoadedModule().apply {
            packageName = module.pkgName
            apkPath = module.apkPath
            this.code = code
            applicationInfo = appInfo
            appId = (appInfo?.uid ?: -1).let { uid -> if (uid < 0) -1 else uid % 100000 }
            versionCode = runCatching {
                pm.getPackageInfo(module.pkgName, 0).longVersionCode
            }.getOrDefault(0L)
            service = ManagerRemoteServices.moduleService(module.pkgName)
        }
    }
}

package org.lsposed.lspatch.loader;

import static org.lsposed.lspatch.share.Constants.CONFIG_ASSET_PATH;
import static org.lsposed.lspatch.share.Constants.ORIGINAL_APK_ASSET_PATH;

import android.app.ActivityThread;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.CompatibilityInfo;
import android.os.Build;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import org.lsposed.lspatch.loader.util.FileUtils;
import org.lsposed.lspatch.loader.util.XLog;
import org.lsposed.lspatch.service.LocalApplicationService;
import org.lsposed.lspatch.service.RemoteApplicationService;
import org.matrix.vector.Startup;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.impl.VectorLifecycleManager;
import org.matrix.vector.impl.di.LegacyFrameworkDelegate;
import org.matrix.vector.impl.di.LegacyPackageInfo;
import org.matrix.vector.impl.di.VectorBootstrap;
import org.json.JSONObject;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedInit;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import de.robv.android.xposed.XposedHelpers;
import hidden.HiddenApiBridge;

/**
 * Created by Windysha
 */
@SuppressWarnings("unused")
public class LSPApplication {

    private static final String TAG = "LSPatch";
    private static final int FIRST_APP_ZYGOTE_ISOLATED_UID = 90000;
    private static final int PER_USER_RANGE = 100000;

    private static ActivityThread activityThread;
    private static LoadedApk stubLoadedApk;
    private static LoadedApk appLoadedApk;

    private static JSONObject config;

    public static boolean isIsolated() {
        return (android.os.Process.myUid() % PER_USER_RANGE) >= FIRST_APP_ZYGOTE_ISOLATED_UID;
    }

    public static void onLoad() throws RemoteException, IOException {
        if (isIsolated()) {
            XLog.d(TAG, "Skip isolated process");
            return;
        }
        activityThread = ActivityThread.currentActivityThread();
        var context = createLoadedApkWithContext();
        if (context == null) {
            XLog.e(TAG, "Error when creating context");
            return;
        }

        Log.d(TAG, "Initialize service client");
        IFrameworkService service;
        if (config.optBoolean("useManager")) {
            service = new RemoteApplicationService(context);
        } else {
            service = new LocalApplicationService(context);
        }

        disableProfile(context);

        // patch_loader.cpp built the framework loader with a parent that already defines XResources'
        // synthetic super, so XResources resolves cleanly. Publish that parent as Vector's
        // dummyClassLoader so XposedBridge.initXResources() short-circuits instead of building its own
        // device-Resources super and re-parenting the framework loader.
        ClassLoader frameworkLoader = XposedBridge.class.getClassLoader();
        if (frameworkLoader != null && frameworkLoader.getParent() != null) {
            XposedBridge.dummyClassLoader = frameworkLoader.getParent();
        }

        Startup.initXposed(false, ActivityThread.currentProcessName(), context.getApplicationInfo().dataDir, service);
        Startup.bootstrapXposed(false);
        // WARN: Since it uses `XResource`, the following class should not be initialized
        // before forkPostCommon is invoke. Otherwise, you will get failure of XResources
        Log.i(TAG, "Load modules");
        loadModulesAndDispatch();
        Log.i(TAG, "Modules initialized");

        switchAllClassLoader();
        SigBypass.doSigBypass(context, config.optInt("sigBypassLevel"));

        Log.i(TAG, "LSPatch bootstrap completed");
    }

    /**
     * Replays the module lifecycle for the already-loaded target app.
     *
     * <p>Vector normally drives module loading from hooks it installs on {@code LoadedApk} during
     * {@code bootstrapXposed}. But LSPatch has already built the app's {@code LoadedApk} by the time
     * those hooks exist, so they never fire for the target package. This reproduces, for that one
     * package, exactly what {@code LoadedApkHookers} would have done: instantiate the modern modules,
     * then dispatch {@code onPackageLoaded}/{@code onPackageReady} to them and the legacy
     * {@code handleLoadPackage} callbacks. {@code bootstrapXposed} has already loaded the legacy
     * modules and registered their callbacks.</p>
     */
    private static void loadModulesAndDispatch() {
        // Instantiate modern (libxposed) modules; guarded internally so the app-attach hook, were it
        // ever to fire, cannot load a second generation on top.
        try {
            XposedInit.loadModules(activityThread);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load modern modules", t);
        }

        var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(appLoadedApk, "mApplicationInfo");
        ClassLoader classLoader;
        try {
            classLoader = appLoadedApk.getClassLoader();
        } catch (Throwable t) {
            Log.e(TAG, "Failed to obtain target class loader", t);
            return;
        }
        Log.d(TAG, "Target class loader ready, dispatching lifecycle");
        var defaultClassLoader = (ClassLoader) XposedHelpers.getObjectField(appLoadedApk, "mDefaultClassLoader");
        if (defaultClassLoader == null) defaultClassLoader = classLoader;
        var appComponentFactory = XposedHelpers.getObjectField(appLoadedApk, "mAppComponentFactory");
        var resDir = (String) XposedHelpers.getObjectField(appLoadedApk, "mResDir");
        var packageName = appInfo.packageName;

        // The modern and legacy lifecycles are dispatched independently so a failure in one cannot
        // stop the other, and neither can stop the rest of onLoad (class-loader switch, sig bypass).
        try {
            // Modern lifecycle: onPackageLoaded (API 29+) then onPackageReady (API 28+).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                VectorLifecycleManager.INSTANCE.dispatchPackageLoaded(packageName, appInfo, true, defaultClassLoader);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                VectorLifecycleManager.INSTANCE.dispatchPackageReady(
                        packageName, appInfo, true, defaultClassLoader, classLoader, appComponentFactory);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Modern lifecycle dispatch failed", t);
        }

        // Legacy lifecycle. The resource-directory registration and the handleLoadPackage dispatch
        // are guarded separately: registering the res dir loads XResources, whose runtime superclass
        // may be unavailable, and that must not stop a module's method hooks from being installed.
        LegacyFrameworkDelegate delegate = VectorBootstrap.INSTANCE.getDelegate();
        if (delegate != null) {
            try {
                if (!delegate.isResourceHookingDisabled()) {
                    delegate.setPackageNameForResDir(packageName, resDir);
                }
            } catch (Throwable t) {
                // Resource hooking needs XResources, whose runtime superclass is provided by a dummy
                // class loader inserted as the framework loader's parent. On this device that parent
                // satisfies a plain loadClass, but not the superclass resolution ART performs while
                // defining XResources, so XResources fails to link. Method hooking does not touch this
                // path, so it is only warned about; see the project notes for the open investigation.
                Log.w(TAG, "setPackageNameForResDir failed; legacy resource hooking is unavailable", t);
            }
            try {
                delegate.onPackageLoaded(new LegacyPackageInfo(
                        packageName, ActivityThread.currentProcessName(), classLoader, appInfo, true));
            } catch (Throwable t) {
                Log.e(TAG, "Legacy handleLoadPackage dispatch failed", t);
            }
        }
    }

    private static Context createLoadedApkWithContext() {
        try {
            var mBoundApplication = XposedHelpers.getObjectField(activityThread, "mBoundApplication");

            stubLoadedApk = (LoadedApk) XposedHelpers.getObjectField(mBoundApplication, "info");
            var appInfo = (ApplicationInfo) XposedHelpers.getObjectField(mBoundApplication, "appInfo");
            var compatInfo = (CompatibilityInfo) XposedHelpers.getObjectField(mBoundApplication, "compatInfo");
            var baseClassLoader = stubLoadedApk.getClassLoader();

            try (var is = baseClassLoader.getResourceAsStream(CONFIG_ASSET_PATH)) {
                BufferedReader streamReader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                config = new JSONObject(streamReader.lines().collect(Collectors.joining()));
            } catch (Throwable e) {
                Log.e(TAG, "Failed to parse config file", e);
                return null;
            }
            Log.i(TAG, "Use manager: " + config.optBoolean("useManager"));
            Log.i(TAG, "Signature bypass level: " + config.optInt("sigBypassLevel"));

            Path originPath = Paths.get(appInfo.dataDir, "cache/lspatch/origin/");
            Path cacheApkPath;
            try (ZipFile sourceFile = new ZipFile(appInfo.sourceDir)) {
                cacheApkPath = originPath.resolve(sourceFile.getEntry(ORIGINAL_APK_ASSET_PATH).getCrc() + ".apk");
            }

            appInfo.sourceDir = cacheApkPath.toString();
            appInfo.publicSourceDir = cacheApkPath.toString();
            if (config.has("appComponentFactory")) {
                appInfo.appComponentFactory = config.optString("appComponentFactory");
            } else {
                // The original app declared no AppComponentFactory. The patched manifest points it
                // at the metaloader stub, which the original apk does not contain, so clearing it
                // keeps the class loader from being built against a class that cannot be found.
                appInfo.appComponentFactory = null;
            }

            if (!Files.exists(cacheApkPath)) {
                Log.i(TAG, "Extract original apk");
                FileUtils.deleteFolderIfExists(originPath);
                Files.createDirectories(originPath);
                try (InputStream is = baseClassLoader.getResourceAsStream(ORIGINAL_APK_ASSET_PATH)) {
                    Files.copy(is, cacheApkPath);
                }
            }
            cacheApkPath.toFile().setWritable(false);

            var mPackages = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mPackages");
            mPackages.remove(appInfo.packageName);
            appLoadedApk = activityThread.getPackageInfoNoCheck(appInfo, compatInfo);
            XposedHelpers.setObjectField(mBoundApplication, "info", appLoadedApk);
            // Build the class loader now, while bootstrapXposed has not yet installed the LoadedApk
            // hooks. Otherwise the first getClassLoader() call during lifecycle dispatch triggers
            // those hooks and dispatches onPackageReady a second time.
            appLoadedApk.getClassLoader();

            var activityClientRecordClass = XposedHelpers.findClass("android.app.ActivityThread$ActivityClientRecord", ActivityThread.class.getClassLoader());
            var fixActivityClientRecord = (BiConsumer<Object, Object>) (k, v) -> {
                if (activityClientRecordClass.isInstance(v)) {
                    var pkgInfo = XposedHelpers.getObjectField(v, "packageInfo");
                    if (pkgInfo == stubLoadedApk) {
                        Log.d(TAG, "fix loadedapk from ActivityClientRecord");
                        XposedHelpers.setObjectField(v, "packageInfo", appLoadedApk);
                    }
                }
            };
            var mActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mActivities");
            mActivities.forEach(fixActivityClientRecord);
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    var mLaunchingActivities = (Map<?, ?>) XposedHelpers.getObjectField(activityThread, "mLaunchingActivities");
                    mLaunchingActivities.forEach(fixActivityClientRecord);
                }
            } catch (Throwable ignored) {
            }
            Log.i(TAG, "hooked app initialized: " + appLoadedApk);

            var context = (Context) XposedHelpers.callStaticMethod(Class.forName("android.app.ContextImpl"), "createAppContext", activityThread, stubLoadedApk);
            if (config.has("appComponentFactory")) {
                try {
                    context.getClassLoader().loadClass(appInfo.appComponentFactory);
                } catch (ClassNotFoundException e) { // This will happen on some strange shells like 360
                    Log.w(TAG, "Original AppComponentFactory not found: " + appInfo.appComponentFactory);
                    appInfo.appComponentFactory = null;
                }
            }
            return context;
        } catch (Throwable e) {
            Log.e(TAG, "createLoadedApk", e);
            return null;
        }
    }

    public static void disableProfile(Context context) {
        final ArrayList<String> codePaths = new ArrayList<>();
        var appInfo = context.getApplicationInfo();
        var pkgName = context.getPackageName();
        if (appInfo == null) return;
        if ((appInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
            codePaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            Collections.addAll(codePaths, appInfo.splitSourceDirs);
        }

        if (codePaths.isEmpty()) {
            // If there are no code paths there's no need to setup a profile file and register with
            // the runtime,
            return;
        }

        // AOSP's Environment.getDataProfilesDePackageDirectory(userId, pkg); the HiddenApiBridge no
        // longer exposes it, and the path has been stable for many releases.
        var profileDir = new File("/data/misc/profiles/cur/" + (appInfo.uid / PER_USER_RANGE) + "/" + pkgName);

        var attrs = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------"));

        for (int i = codePaths.size() - 1; i >= 0; i--) {
            String splitName = i == 0 ? null : appInfo.splitNames[i - 1];
            File curProfileFile = new File(profileDir, splitName == null ? "primary.prof" : splitName + ".split.prof").getAbsoluteFile();
            Log.d(TAG, "Processing " + curProfileFile.getAbsolutePath());
            try {
                if (!curProfileFile.exists()) {
                    Files.createFile(curProfileFile.toPath(), attrs);
                    continue;
                }
                if (!curProfileFile.canWrite() && Files.size(curProfileFile.toPath()) == 0) {
                    Log.d(TAG, "Skip profile " + curProfileFile.getAbsolutePath());
                    continue;
                }
                if (curProfileFile.exists() && !curProfileFile.delete()) {
                    try (var writer = new FileOutputStream(curProfileFile)) {
                        Log.d(TAG, "Failed to delete, try to clear content " + curProfileFile.getAbsolutePath());
                    } catch (Throwable e) {
                        Log.e(TAG, "Failed to delete and clear profile file " + curProfileFile.getAbsolutePath(), e);
                    }
                    Os.chmod(curProfileFile.getAbsolutePath(), 00400);
                }
            } catch (Throwable e) {
                Log.e(TAG, "Failed to disable profile file " + curProfileFile.getAbsolutePath(), e);
            }
        }
    }

    private static void switchAllClassLoader() {
        var fields = LoadedApk.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == ClassLoader.class) {
                var obj = XposedHelpers.getObjectField(appLoadedApk, field.getName());
                XposedHelpers.setObjectField(stubLoadedApk, field.getName(), obj);
            }
        }
    }
}

package org.lsposed.lspatch.service;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;
import android.widget.Toast;

import org.lsposed.lspatch.share.Constants;
import org.matrix.vector.ipc.IFrameworkService;
import org.matrix.vector.ipc.IProcessChannel;
import org.matrix.vector.ipc.LoadedModule;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The {@link IFrameworkService} for manager mode: it binds the manager's service and forwards module
 * queries to it, so the app is served whatever modules the manager has scoped to it.
 */
public class RemoteApplicationService implements IFrameworkService {

    private static final String TAG = "LSPatch";

    /**
     * How long the app waits for the manager's binder.
     *
     * The bind carries BIND_AUTO_CREATE, so when the manager is not running this wait covers starting
     * its process from nothing before it can answer. The app's own startup is held open meanwhile,
     * which is why it is a few seconds and not more.
     */
    private static final long BIND_TIMEOUT_MS = 5000;

    private volatile IFrameworkService service;

    @SuppressLint("DiscouragedPrivateApi")
    public RemoteApplicationService(Context context, String managerPackageName) throws RemoteException {
        var packageName = (managerPackageName == null || managerPackageName.isEmpty())
                ? Constants.MANAGER_PACKAGE_NAME
                : managerPackageName;
        try {
            var intent = new Intent()
                    .setComponent(new ComponentName(packageName, Constants.MANAGER_SERVICE_NAME))
                    .putExtra("packageName", context.getPackageName());
            // TODO: Authentication
            var latch = new CountDownLatch(1);
            var conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    Log.i(TAG, "Manager binder received");
                    service = IFrameworkService.Stub.asInterface(binder);
                    latch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    Log.e(TAG, "Manager service died");
                    service = null;
                }
            };
            Log.i(TAG, "Request manager binder from " + packageName);
            boolean bound;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bound = context.bindService(
                        intent, Context.BIND_AUTO_CREATE, Executors.newSingleThreadExecutor(), conn);
            } else {
                var handlerThread = new HandlerThread("RemoteApplicationService");
                handlerThread.start();
                var handler = new Handler(handlerThread.getLooper());
                var contextImplClass = context.getClass();
                var getUserMethod = contextImplClass.getMethod("getUser");
                var bindServiceAsUserMethod = contextImplClass.getDeclaredMethod(
                        "bindServiceAsUser", Intent.class, ServiceConnection.class, int.class, Handler.class, UserHandle.class);
                var userHandle = (UserHandle) getUserMethod.invoke(context);
                bound = Boolean.TRUE.equals(bindServiceAsUserMethod.invoke(
                        context, intent, conn, Context.BIND_AUTO_CREATE, handler, userHandle));
            }
            // A refusal and a slow start are different failures. The system refuses when it will not
            // start the manager at all -- it is not installed, or its package is in a state the system
            // will not launch -- and no amount of waiting changes that, so it is reported at once and
            // on its own.
            if (!bound) {
                Log.e(TAG, "System refused to bind " + packageName + "; it may not be installed");
                Toast.makeText(context, "LSPatch manager not reachable", Toast.LENGTH_SHORT).show();
                throw new RemoteException("bindService refused for " + packageName);
            }
            var start = SystemClock.elapsedRealtime();
            boolean success = latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            var elapsed = SystemClock.elapsedRealtime() - start;
            if (!success) {
                // The app's own start is held open by this wait, so it ends rather than growing. The
                // elapsed time is logged either way: a late bind and one that never lands are the same
                // from here, and only that number tells them apart.
                Log.e(TAG, "Manager did not answer in " + elapsed + "ms");
                Toast.makeText(context, "LSPatch manager did not answer", Toast.LENGTH_SHORT).show();
                throw new RemoteException("No manager binder after " + elapsed + "ms");
            }
            Log.i(TAG, "Manager binder received in " + elapsed + "ms");
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException |
                 InterruptedException e) {
            Toast.makeText(context, "Unable to connect to Manager", Toast.LENGTH_SHORT).show();
            var r = new RemoteException("Failed to get manager binder");
            r.initCause(e);
            throw r;
        }
    }

    @Override
    public boolean isLogMuted() throws RemoteException {
        return service != null && service.isLogMuted();
    }

    @Override
    public List<LoadedModule> getLegacyModules() throws RemoteException {
        return service == null ? new ArrayList<>() : service.getLegacyModules();
    }

    @Override
    public List<LoadedModule> getModules() throws RemoteException {
        return service == null ? new ArrayList<>() : service.getModules();
    }

    @Override
    public String getPrefsPath(String packageName) {
        return new File(Environment.getDataDirectory(), "data/" + packageName + "/shared_prefs/").getAbsolutePath();
    }

    @Override
    public ParcelFileDescriptor openManagerApk() throws RemoteException {
        return service == null ? null : service.openManagerApk();
    }

    @Override
    public IBinder requestManagerService() {
        return null;
    }

    @Override
    public void attachProcessChannel(IProcessChannel channel) throws RemoteException {
        // The manager drives hot reload but is a plain app, so the framework's own channel -- which
        // gates on the system uid -- would refuse it. Hand the manager an LSPatch channel that runs the
        // in-process swap for it instead; the framework's channel is unused without a daemon.
        if (service != null) service.attachProcessChannel(new LSPatchProcessChannel());
    }

    @Override
    public IBinder asBinder() {
        return service == null ? null : service.asBinder();
    }
}

package org.lsposed.lspatch.service;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.matrix.vector.ipc.IModuleService;
import org.matrix.vector.ipc.IRemotePreferenceCallback;

/**
 * The per-module service a hooked process talks to, as required by every {@code LoadedModule}.
 *
 * <p>LSPatch has no privileged daemon to broker a module's remote preferences or files across app
 * boundaries, so this advertises no capabilities and answers the optional surfaces emptily. A module
 * that only hooks and logs never needs any of it; {@code VectorContext} tolerates the empty answers.</p>
 */
public class LocalModuleService extends IModuleService.Stub {

    @Override
    public long getFrameworkProperties() {
        return 0;
    }

    @Override
    public Bundle requestRemotePreferences(String group, IRemotePreferenceCallback callback) {
        return new Bundle();
    }

    @Override
    public ParcelFileDescriptor openRemoteFile(String path) {
        return null;
    }

    @Override
    public String[] getRemoteFileNames() {
        return new String[0];
    }
}

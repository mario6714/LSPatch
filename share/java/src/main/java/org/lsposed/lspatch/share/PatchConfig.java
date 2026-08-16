package org.lsposed.lspatch.share;

public class PatchConfig {

    public final boolean useManager;
    public final boolean debuggable;
    public final boolean overrideVersionCode;
    public final int sigBypassLevel;
    public final String originalSignature;
    public final String appComponentFactory;
    /**
     * Whether the loader dex was injected straight into the host package rather than kept as an
     * asset. Recorded so a re-patch driven from an installed app can reproduce the choice; older
     * patched apps have no such key, and Gson leaves the primitive {@code false} for them -- which
     * is exactly what those apps were built with.
     */
    public final boolean injectDex;
    public final LSPConfig lspConfig;

    public PatchConfig(
            boolean useManager,
            boolean debuggable,
            boolean overrideVersionCode,
            int sigBypassLevel,
            String originalSignature,
            String appComponentFactory,
            boolean injectDex
    ) {
        this.useManager = useManager;
        this.debuggable = debuggable;
        this.overrideVersionCode = overrideVersionCode;
        this.sigBypassLevel = sigBypassLevel;
        this.originalSignature = originalSignature;
        this.appComponentFactory = appComponentFactory;
        this.injectDex = injectDex;
        this.lspConfig = LSPConfig.instance;
    }
}

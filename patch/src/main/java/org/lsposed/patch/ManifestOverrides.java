package org.lsposed.patch;

/**
 * Optional overrides applied to the patched app's manifest.
 *
 * The patcher already rewrites a handful of manifest attributes as a side effect of injecting the
 * loader (the component factory, the debuggable flag, a minimum SDK floor). This groups the ones a
 * caller may *choose* to change, so the manifest surface is one typed object rather than a growing
 * list of loose parameters -- adding another overridable attribute is a field here and a line in
 * {@link ApkPatcher}, nothing more.
 *
 * Every field is nullable and means "leave as the original app had it" when null, so the empty
 * overrides are a no-op and an unpatched attribute is never touched.
 */
public final class ManifestOverrides {

    /**
     * A new {@code android:versionCode}, or null to keep the app's own.
     *
     * This is the number the installer orders versions by, distinct from the version name the user
     * sees. Pinning it low -- 1 is the usual choice -- keeps a later patched build from being refused
     * as a downgrade, since the installer rejects a version code below the one already installed; any
     * value is accepted. The app itself normally still reports its real version, which is compiled
     * into its code rather than read from this attribute.
     */
    public final Integer versionCode;

    /** A new {@code android:label} for the app -- what the launcher and settings show it as. */
    public final String label;

    /** A new {@code android:targetSdkVersion}, or null to keep the app's own. */
    public final Integer targetSdkVersion;

    /**
     * Force {@code android:extractNativeLibs}. An app that ships it {@code false} keeps its native
     * libraries page-aligned inside the apk; forcing {@code true} makes the installer extract them,
     * which some patched apps need to load a module's own native code. Null leaves it alone.
     */
    public final Boolean extractNativeLibs;

    /**
     * Force {@code android:usesCleartextTraffic}. Turning it on lets a debugging or
     * traffic-inspecting module reach plain-HTTP endpoints an app would otherwise refuse. Null
     * leaves it alone.
     */
    public final Boolean usesCleartextTraffic;

    private ManifestOverrides(Builder b) {
        this.versionCode = b.versionCode;
        this.label = b.label;
        this.targetSdkVersion = b.targetSdkVersion;
        this.extractNativeLibs = b.extractNativeLibs;
        this.usesCleartextTraffic = b.usesCleartextTraffic;
    }

    /** True when nothing is overridden, so the patcher can skip the work entirely. */
    public boolean isEmpty() {
        return versionCode == null && label == null && targetSdkVersion == null
                && extractNativeLibs == null && usesCleartextTraffic == null;
    }

    public static ManifestOverrides none() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Integer versionCode;
        private String label;
        private Integer targetSdkVersion;
        private Boolean extractNativeLibs;
        private Boolean usesCleartextTraffic;

        public Builder versionCode(Integer versionCode) {
            this.versionCode = versionCode;
            return this;
        }

        public Builder label(String label) {
            this.label = (label == null || label.isBlank()) ? null : label;
            return this;
        }

        public Builder targetSdkVersion(Integer targetSdkVersion) {
            this.targetSdkVersion = targetSdkVersion;
            return this;
        }

        public Builder extractNativeLibs(Boolean extractNativeLibs) {
            this.extractNativeLibs = extractNativeLibs;
            return this;
        }

        public Builder usesCleartextTraffic(Boolean usesCleartextTraffic) {
            this.usesCleartextTraffic = usesCleartextTraffic;
            return this;
        }

        public ManifestOverrides build() {
            return new ManifestOverrides(this);
        }
    }
}

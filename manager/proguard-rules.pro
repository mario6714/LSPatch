-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
 public static void check*(...);
 public static void throw*(...);
}
-assumenosideeffects class java.util.Objects {
    public static ** requireNonNull(...);
}
-assumenosideeffects public class kotlin.coroutines.jvm.internal.DebugMetadataKt {
   private static ** getDebugMetadataAnnotation(...) return null;
}

-keep class com.beust.jcommander.** { *; }
# The Shizuku user service is loaded and instantiated by name in a separate app_process (shell uid)
# by Shizuku's starter, and its methods are reached only over a binder -- neither of which R8 can
# see, because the class is not a manifest component and nothing in-app constructs it. Without these
# it is renamed or stripped in release, and the shell service never starts: dexopt, silent installs
# and the whole log collector go dead while Shizuku still reads "granted". Keep the service and the
# generated AIDL (interface, Stub, Proxy) it dispatches through.
-keep class org.lsposed.lspatch.ShizukuService { *; }
-keep class org.lsposed.lspatch.IShizukuService** { *; }

# The framework IPC surface crosses a binder to the patched app, whose loader is not obfuscated.
-keep class org.matrix.vector.ipc.** { *; }
-keep class org.lsposed.lspatch.database.** { *; }
-keep class org.lsposed.lspatch.Patcher$Options { *; }
-keep class org.lsposed.lspatch.share.LSPConfig { *; }
-keep class org.lsposed.lspatch.share.PatchConfig { *; }
-keepclassmembers class org.lsposed.patch.LSPatch {
    private <fields>;
}
-dontwarn com.google.auto.value.AutoValue$Builder
-dontwarn com.google.auto.value.AutoValue

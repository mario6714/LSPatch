package org.lsposed.lspatch.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

/**
 * Debug builds only: raises a chosen Shizuku failure on demand.
 *
 * The failure paths are the hardest part of this integration to see, because each one needs a device that is actually
 * misbehaving — a stopped service, a withdrawn grant, a system that rejects a call — and the healthy path repairs
 * itself faster than a tester can look. This lets the dialog, each of its wordings and the trace screen behind "View
 * trace" be inspected from a terminal instead.
 *
 * ```
 * adb shell am broadcast -a org.lsposed.lspatch.SIMULATE_SHIZUKU_FAILURE --es reason call
 * ```
 *
 * `reason` is one of `running`, `granted`, `service`, `call` (default `call`, the only one carrying a trace); `op`
 * optionally names the operation — `install`, `uninstall`, `logs`, `shell`, `optimize`, `grant`, `packagequery` — which
 * picks the subject line.
 *
 * The release source set holds an empty twin of this object, so none of it ships.
 */
object ShizukuDebugTrigger {

    private const val ACTION = "org.lsposed.lspatch.SIMULATE_SHIZUKU_FAILURE"

    fun register(context: Context) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val op =
                        ShizukuOp.entries.firstOrNull { it.name.equals(intent.getStringExtra("op"), ignoreCase = true) }
                            ?: ShizukuOp.Install
                    // A repeat of the same simulated failure is a deliberate second look, not the
                    // background noise the de-duplication exists to silence.
                    ShizukuApi.forgetSurfaced()
                    when (intent.getStringExtra("reason")?.lowercase()) {
                        "running" ->
                            ShizukuApi.record(
                                op,
                                ShizukuReason.NotRunning,
                                "simulated: the Shizuku service is not running on this device",
                            )
                        "granted" ->
                            ShizukuApi.record(
                                op,
                                ShizukuReason.NotGranted,
                                "simulated: Shizuku is running but has not granted LSPatch access",
                            )
                        "service" ->
                            ShizukuApi.record(
                                op,
                                ShizukuReason.ServiceUnavailable,
                                "simulated: the Shizuku shell service did not start within 3s",
                            )
                        else -> {
                            // Thrown rather than constructed: only a throwable that has actually been
                            // raised carries the frames the trace screen is there to show.
                            val thrown =
                                runCatching {
                                    throw SecurityException(
                                        "Permission Denial: opening provider from ProcessRecord (simulated)"
                                    )
                                }
                                    .exceptionOrNull()!!
                            ShizukuApi.record(op, ShizukuReason.CallFailed, thrown.toString(), thrown)
                        }
                    }
                }
            }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_EXPORTED)
    }
}

/*
 * Legacy Miniplayer for Morphe — fingerprints (21.04.223).
 *
 * - mnw: modern miniplayer controls presenter (matched by its ge() layout string). We hook
 *   onViewAttachedToWindow(View) to attach our gesture layer to the miniplayer root.
 * - owv: the miniplayer gesture/geometry controller. We capture its instance from the
 *   constructor so the extension can drive YouTube's native per-frame drag (oxb.n) and the
 *   native dismiss animation (owv.p) via reflection. owv is matched by the telemetry literal
 *   235732 used in owv.p(III)V. See docs/DECOMPILE-ANALYSIS-21.04-dismiss.md.
 */

package app.morphe.patches.youtube.layout.legacyminiplayer

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.literal
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

internal object MiniplayerControlsClassFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    filters = listOf(
        string("player_overlay_modern_mini_player_controls"),
    ),
)

internal object MiniplayerControlsOnAttachFingerprint : Fingerprint(
    classFingerprint = MiniplayerControlsClassFingerprint,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    name = "onViewAttachedToWindow",
)

/** Identifies the owv class via owv.p(III)V (telemetry literal 235732). */
internal object OwvDismissMethodFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("I", "I", "I"),
    filters = listOf(
        literal(235732),
    ),
)

/** owv constructor — we stash `this` so the extension can drive the native drag/dismiss. */
internal object OwvConstructorFingerprint : Fingerprint(
    classFingerprint = OwvDismissMethodFingerprint,
    name = "<init>",
)

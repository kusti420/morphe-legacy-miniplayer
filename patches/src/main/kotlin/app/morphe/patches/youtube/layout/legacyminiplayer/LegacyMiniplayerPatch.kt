/*
 * Legacy Miniplayer for Morphe — bytecode patch.
 *
 * Restores the classic YouTube 14.21.54 miniplayer on modern YouTube: a fixed bottom-right
 * rectangle showing the real live video, with swipe up = maximize, swipe sideways = dismiss,
 * and NO tap / NO double-tap-zoom (exactly as WatchWhileLayout.onTouchEvent defined it).
 *
 * Two parts:
 *  1) Geometry/look via morphe's Miniplayer patch flag overrides (guarded by `LEGACY` in
 *     extensions .../patches/MiniplayerPatch.java): fixed bottom-right, square, ~180dp, and
 *     oxc.a()=false so YouTube performs NO gesture handling on the miniplayer.
 *  2) Gestures via our own handler (LegacyMiniplayerController + LegacyMiniplayerGestureHandler),
 *     a faithful port of 14.x WatchWhileLayout, attached at mnw.onViewAttachedToWindow.
 */

package app.morphe.patches.youtube.layout.legacyminiplayer

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.util.findElementByAttributeValueOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.miniplayer.miniplayerPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/legacyminiplayer/LegacyMiniplayerController;"
private const val NATIVE_CLASS =
    "Lapp/morphe/extension/youtube/patches/legacyminiplayer/LegacyMiniplayerNative;"

/**
 * Round the miniplayer's video at OUR 12dp radius (YouTube's default is 8dp) so YouTube's own
 * native rounding — which is reliable across all content, unlike a View clip — matches our glass
 * overlay's 12dp corners.
 */
private val legacyMiniplayerCornerRadiusPatch = resourcePatch {
    execute {
        document("res/values/dimens.xml").use { document ->
            document.childNodes.findElementByAttributeValueOrThrow(
                attributeName = "name",
                value = "miniplayer_corner_radius",
            ).textContent = "12.0dip"
        }
    }
}

@Suppress("unused")
val legacyMiniplayerPatch = bytecodePatch(
    name = "Legacy miniplayer",
    description = "Restores the classic 14.x miniplayer: a fixed bottom-right rectangle, " +
        "swipe up to maximize, swipe sideways to dismiss.",
) {
    dependsOn(
        settingsPatch,
        sharedExtensionPatch,
        miniplayerPatch, // provides the flag-override methods this toggle drives to legacy values
        legacyMiniplayerCornerRadiusPatch, // 8dp -> 12dp so YT native rounding matches the glass
        playerTypeHookPatch, // keeps PlayerType.getCurrent() live — glass visibility gates on it
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        // region settings — a single toggle (+ tweaks) under Player settings
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_legacy_miniplayer_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_legacy_miniplayer_enabled", summary = true),
                    SwitchPreference("morphe_legacy_miniplayer_hide_overlay_buttons", summary = true),
                    SwitchPreference("morphe_legacy_miniplayer_rounded_corners", summary = true),
                    SwitchPreference("morphe_legacy_miniplayer_glass", summary = true),
                ),
            ),
        )
        // endregion

        // region attach our gesture handler to the real miniplayer view
        // mnw.onViewAttachedToWindow(View) fires with the modern_miniplayer_controls_overlay root.
        MiniplayerControlsOnAttachFingerprint.method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS->onMiniplayerAttached(Landroid/view/View;)V",
        )
        // endregion

        // region capture the owv instance (drives native per-frame drag + dismiss animation)
        OwvConstructorFingerprint.method.apply {
            // Inject right after the super() call so `this` (p0) is initialized.
            val superIndex = indexOfFirstInstructionOrThrow { opcode == Opcode.INVOKE_DIRECT }
            // owv's constructor has 14 params, so `this` (p0) is a high register — use the
            // range form so it encodes correctly (a plain invoke-static would pass null).
            addInstruction(
                superIndex + 1,
                "invoke-static/range { p0 .. p0 }, $NATIVE_CLASS->setOwv(Ljava/lang/Object;)V",
            )
        }
        // endregion
    }
}

# Morphe integration notes (verified against morphe-patches)

All references are from an actual clone of `MorpheApp/morphe-patches`. Paths are relative to
that repo. These are the APIs this module builds on.

> Caveat: `ResourceUtils`, `ResourceType`, `Logger`, `Utils`, and the patcher utilities
> (`copyResources`, `ResourceGroup`, `inputStreamFromBundledResource`) are **compiled
> dependencies**, not source in the clone — only their call sites are visible. Signatures
> below are taken from those call sites.

## 1. Getting a player overlay ViewGroup (to add our miniplayer frame)

The reusable pattern is SponsorBlock's: match the player's inset overlay `FrameLayout` and
pass it to an extension `initialize(ViewGroup)` that calls `addView`.

Fingerprint — `layout/sponsorblock/Fingerprints.kt:33` (`ControlsOverlayFingerprint`):
```kotlin
internal object ControlsOverlayFingerprint : Fingerprint(
    classFingerprint = LayoutConstructorFingerprint,
    returnType = "V", parameters = listOf(),
    filters = listOf(
        resourceLiteral(ResourceType.ID, "inset_overlay_view_layout"),
        checkCast("Landroid/widget/FrameLayout;", MatchAfterWithin(20))
    )
)
```
Wiring — `layout/sponsorblock/SponsorBlockPatch.kt:324`:
```kotlin
ControlsOverlayFingerprint.let {
    val i = it.instructionMatches.last().index
    val reg = getInstruction<OneRegisterInstruction>(i).registerA
    addInstruction(i + 1, "invoke-static {v$reg}, $EXT->initialize(Landroid/view/ViewGroup;)V")
}
```
Extension side — `sponsorblock/ui/SponsorBlockViewController.java:63` does `viewGroup.addView(...)`
and uses `setOnHierarchyChangeListener` + `bringToFront()` to stay on top.

Alternatives:
- **Generic overlay bus** (currently unwired): `PlayerOverlays.attach(ViewGroup)` /
  `PlayerOverlaysHookPatch.playerOverlayInflated(ViewGroup)` on class
  `Lcom/google/android/apps/youtube/app/common/player/overlay/YouTubePlayerOverlaysLayout;`.
- **Activity content root** (swipe controls): `window.decorView.findViewById(android.R.id.content)`
  then `addView` — `swipecontrols/SwipeControlsHostActivity.kt:70,143`.

## 2. Detecting the minimized state + play/pause

Depend on `playerTypeHookPatch`. Enum — `shared/PlayerType.kt:18`:
`NONE, HIDDEN, WATCH_WHILE_MINIMIZED, WATCH_WHILE_MAXIMIZED, WATCH_WHILE_FULLSCREEN,
WATCH_WHILE_SLIDING_*, INLINE_MINIMAL, VIRTUAL_REALITY_FULLSCREEN, WATCH_WHILE_PICTURE_IN_PICTURE`.

- `PlayerType.current` (`PlayerType.kt:73`)
- Listen (Java): `PlayerType.getOnChange().addObserver((PlayerType t) -> { ...; return Unit.INSTANCE; });`
  (`SponsorBlockViewController.java:46`)
- Helpers: `isNoneOrHidden()`, `isNoneHiddenOrMinimized()`, `isMaximizedOrFullscreen()`.
- Play/pause: `VideoState.current` (`shared/VideoState.kt:48`) ∈ `{NEW, PLAYING, PAUSED, ...}`.

The `WATCH_WHILE_MINIMIZED` transition is exactly when we should engage the legacy miniplayer.

## 3. Settings wiring (end to end)

1. Declare in `extensions/youtube/src/main/java/app/morphe/extension/youtube/settings/Settings.java`:
   ```java
   public static final BooleanSetting LEGACY_MINIPLAYER =
           new BooleanSetting("morphe_legacy_miniplayer_enabled", FALSE, true); // reboot
   public static final IntegerSetting LEGACY_MINIPLAYER_WIDTH =
           new IntegerSetting("morphe_legacy_miniplayer_width_dip", 180,
                   parent(LEGACY_MINIPLAYER));
   public static final BooleanSetting LEGACY_MINIPLAYER_SHADOW =
           new BooleanSetting("morphe_legacy_miniplayer_shadow", TRUE, parent(LEGACY_MINIPLAYER));
   ```
   Constructor overloads: `(key, default)`, `(key, default, rebootApp)`,
   `(key, default, rebootApp, parent...)`; `EnumSetting<E>(key, defaultEnum)`.
2. Add preferences in the Kotlin patch (`dependsOn(settingsPatch)`):
   `SwitchPreference("morphe_legacy_miniplayer_enabled", summary = true)` etc. Keys map to
   `${key}_title` / `${key}_summary` (`shared/misc/settings/preference/BasePreference.kt:18`).
3. Strings go in `patches/src/main/resources/addresources/values/youtube/strings.xml` as
   `morphe_..._title` / `_summary`; merged by `addResourcesPatch` (enabled via
   `misc/settings/SettingsPatch.kt:231` `addAppResources("youtube")`).

## 4. Shipping the drawables

Bundle the nine-patches under `patches/src/main/resources/legacyminiplayer/drawable*/` and copy
them in a `resourcePatch`:
```kotlin
copyResources("legacyminiplayer", ResourceGroup("drawable-xxhdpi",
    "miniplayer_shadow.9.png", "miniplayer_innerglow.9.png"))
// ...repeat per density folder
```
Resolve at runtime by name (final IDs unknown at compile time):
```java
Drawable shadow = ResourceUtils.getDrawable("miniplayer_shadow");
int id = ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, "miniplayer_shadow");
```

## 5. Video controls — `patches/VideoInformation.java`

`getVideoId()`, `getVideoTime()` (ms), `getVideoLength()`, `seekTo(long)`,
`seekToRelative(long)`, `getPlaybackSpeed()`, `changePlaybackSpeed(float)`,
`isAtEndOfVideo()`, `getChannelName()`. Play/pause is *not* here — use `VideoState.current`.

## Resolved for the target (21.04.223)

This was originally flagged as the version-fragile part. With the 21.04 APK it was resolved
concretely — see `DECOMPILE-ANALYSIS-21.04.md`. Summary: instead of hooking obfuscated
player-geometry/transition methods, the patch hooks the modern miniplayer controls presenter
`mnw` via its stable layout string (`player_overlay_modern_mini_player_controls`) at
`onViewAttachedToWindow(View)`, and performs maximize/dismiss by `performClick()`-ing the
real `modern_miniplayer_expand` / `modern_miniplayer_close` buttons it exposes. No
per-version transition-method fingerprints are required; only re-verify the class string and
button ids when moving to a very different version (e.g. 21.29+).

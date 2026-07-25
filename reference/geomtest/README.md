# Off-device geometry test

`LegacyMiniplayerGeometry` only depends on `android.graphics.Rect`, so it can be compiled and
run without an Android SDK using the minimal `Rect` stub here. This verifies the mini-rect
math (180dp bottom-right, 8dp padding, 16:9, lerp, 90dp dismiss threshold, RTL mirror).

## Run

```bash
cd reference/geomtest
mkdir -p app/morphe/extension/youtube/patches/legacyminiplayer
cp ../../extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/legacyminiplayer/LegacyMiniplayerGeometry.java \
   app/morphe/extension/youtube/patches/legacyminiplayer/
javac -d out android/graphics/Rect.java \
   app/morphe/extension/youtube/patches/legacyminiplayer/LegacyMiniplayerGeometry.java GeomTest.java
java -cp out GeomTest
```

Expected: `ALL GEOMETRY TESTS PASSED` (20 assertions).

> The `android.graphics.Rect` stub is a test double only — it is NOT shipped in the patch.
> In the real app the platform `Rect` is used.

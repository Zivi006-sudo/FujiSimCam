# FujiSim Cam

An Android camera app that shows Fujifilm film-simulation recipes **live through the viewfinder** and saves photos with the same look applied.

- Live GPU preview of the recipe (film simulation, WB + shift, DR, Highlight/Shadow, Color, Sharpness, Clarity, Grain, Color Chrome Effect, Color Chrome FX Blue, exposure compensation)
- Built-in recipes: Reggie's Portra, Kodak Gold 200, Kodachrome 64, Kodak Portra 400 v2, Nostalgia Negative, CineStill 800T, Velvia v2, Kodak Tri-X 400 (settings from Fuji X Weekly), plus factory-default sims
- Create, edit and save your own recipes (long-press a recipe chip, or tap **Edit**)
- Hold **A/B** to compare with the unprocessed image
- Tap the preview to focus
- Photos are saved to `Pictures/FujiSim`, with the recipe stored in the EXIF description

Requires Android 13+.

## Build with GitHub (no computer needed)

1. Create a new repository on github.com and upload this project's contents (keep the folder structure, including `.github/workflows/build.yml`).
2. Open the **Actions** tab. The "Build APK" workflow runs automatically (or press **Run workflow**).
3. When the run is green (~5 min), open it and download **FujiSimCam-apk** under *Artifacts*.
4. Unzip it on your phone and open `app-debug.apk`. Allow "install unknown apps" when asked.

## Build with Android Studio

Open the folder in Android Studio (stable version), connect your phone with USB debugging on, and press Run.

## Notes

This is a simulation. Phone sensors and processing differ from Fujifilm cameras, so the colours approximate the recipes rather than matching them exactly. Tuning lives in `FilmSim` (Recipe.kt) and the shader in `FilmShader.kt`.

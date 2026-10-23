# Blurpaper

A tiny, minimal Android app to blur your wallpaper before setting it. Built with Jetpack Compose and Material You (Material 3) dynamic color.

## Features

- **Blur slider** with live preview (Gaussian-style blur, like the iOS home screen)
- **Darkness slider** to help icons and widgets stand out
- **Presets**: Soft, iOS and Heavy
- **Saturation boost** under the blur, like iOS
- **Home screen mockup** overlay to check icon legibility
- **Press and hold** the preview to compare with the original
- **Haptic ticks** on the sliders at 0%, 50% and 100%
- **Drag and pinch** to reposition and zoom the image before setting
- **Home, Lock or Both** screen targets
- **Share to Blurpaper** from Gallery or any app
- **Remembers** your last image and settings
- **Material You** theming, with a themed ice crystal icon
- No internet, no ads, no tracking. The only permission is `SET_WALLPAPER`.

## Install

1. Open the **Actions** tab of this repo and select the latest successful run.
2. Download the `blurpaper-apk` artifact and unzip it.
3. Install the APK on your phone (allow "install unknown apps" if asked).

Play Protect may ask to scan the app because it is not from the Play Store. This is expected.

## Build it yourself

Requirements: JDK 17 and Android Studio (or Gradle 8.11.1).

```
gradle assembleRelease
```

The APK is created in `app/build/outputs/apk/release/`. Pushing to GitHub also builds it automatically through `.github/workflows/build.yml`.

## Details

| | |
|---|---|
| Package | `com.saikat.blurpaper` |
| Min Android | 8.0 (API 26) |
| Target / compile SDK | 36 |
| Language / UI | Kotlin, Jetpack Compose, Material 3 |

## How to use

1. Tap **Choose image**, or share a photo to Blurpaper.
2. Drag and pinch on the preview to frame it.
3. Open the **Effects** tab and adjust blur and darkness.
4. Open the **Apply** tab, pick Home, Lock or Both, then tap **Set wallpaper**.

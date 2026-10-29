# nLauncher

nLauncher is a simple launcher based of its namesake, [mLauncher](https://github.com/CodeWorksCreativeHub/mLauncher).

## Features

* Simple home screen with rearrangable fav apps and widgets
* Gestures, basic theme customization
* Support for icon packs
* Search sorts by recently opened apps
* Lightweight as possible

## Improvements over [mLauncher](https://github.com/CodeWorksCreativeHub/mLauncher).

* Consistent and minimal UI
* Search screen opens instantly by loading apps eagerly
* Intuitive and representative reordering UI for fav apps and widgets
* A much cleaner codebase with less random features (some unused)

## TODO

* Support for adding custom intents (PWAs)
* Renaming apps
* Add more widgets and add configuration for them
* Theme based on wallpaper
* Onboarding UI

## Developing

```sh
git clone --depth 1 https://github.com/SollyBunny/nLauncher && cd nLauncher
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Useful commands

```sh
./gradlew :app:ktlintFormat
./gradlew :app:lintDebug
```

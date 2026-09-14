# Stage 1 sender — React Native

Replaces `android/`. Same Pi receiver, no changes needed there.

## What this actually buys you

One codebase for the gesture, the discovery, and the cast call. That is the
whole Stage 1 sender, so Stage 1 genuinely becomes cross-platform.

It does **not** make Stage 2 cross-platform. Screen mirroring means
`MediaProjection` on Android and `ReplayKit` on iOS, and neither has a React
Native wrapper that does capture → hardware encode → RTP. Stage 2 is two native
modules either way. React Native moves the boundary, it doesn't remove it.

It also doesn't remove Gradle — `react-native run-android` still drives a Gradle
build underneath. You just stop editing the build files by hand.

## Create the project

```bash
npx @react-native-community/cli init Flick --version 0.76.5
cd Flick
npm install react-native-gesture-handler react-native-reanimated \
            react-native-safe-area-context react-native-zeroconf react-native-share-menu
```

Then copy `App.tsx`, `babel.config.js`, and `src/` from this folder over the
generated ones, and merge the dependency list from `package.json`.

```bash
cd ios && pod install && cd ..
```

## Android configuration

In `android/app/src/main/AndroidManifest.xml`, inside `<application>`:

```xml
android:usesCleartextTraffic="true"
```

and inside the main `<activity>`, alongside the existing LAUNCHER filter:

```xml
<intent-filter>
    <action android:name="android.intent.action.SEND" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:mimeType="text/plain" />
</intent-filter>
```

Set the activity's `android:launchMode="singleTask"` so a second share reuses
the running instance instead of stacking a new one.

## iOS configuration

This is where the cross-platform cost lands. Three separate things.

**1. Local network permission.** iOS 14 and later block mDNS until the user
grants it, and the prompt never appears unless both keys are present in
`ios/Flick/Info.plist`:

```xml
<key>NSLocalNetworkUsageDescription</key>
<string>Flick finds your TV on this network.</string>
<key>NSBonjourServices</key>
<array>
    <string>_phonecast._tcp</string>
</array>
```

Without `NSBonjourServices` listing the exact type, discovery returns nothing
and throws no error. It looks like a broken Pi. It isn't.

**2. Cleartext HTTP to the Pi.** App Transport Security blocks plain HTTP.
Add the narrow exception, not `NSAllowsArbitraryLoads`:

```xml
<key>NSAppTransportSecurity</key>
<dict>
    <key>NSAllowsLocalNetworking</key>
    <true/>
</dict>
```

**3. The Share Extension.** Android's share sheet works through a manifest
entry. iOS requires a separate Xcode target: File → New → Target → Share
Extension, then follow the `react-native-share-menu` iOS setup to add the App
Group and wire the extension to the host app. It's roughly an hour of Xcode
work and it has no Android equivalent.

You also need a Mac to build for iOS at all, plus a free provisioning profile
(7-day expiry, reinstall weekly) or a paid Apple Developer account.

## Run it

```bash
npm run android      # or: npm run ios
```

Physical device on the same 5 GHz network. Simulators and emulators won't see
mDNS on your LAN.

## Test loop

Same as before. With nothing shared, tap the card to load a test link, then
flick. The status line reports the round-trip in milliseconds after every cast,
which is the number to watch during the calibration run.

Tune `MIN_FLING_VELOCITY` and `MIN_FLING_DISTANCE` in `App.tsx`.

## One thing to watch

The fling check runs as a worklet on the UI thread, and so does the exit
animation. Keep it that way. If the gesture decision or the animation ever
moves to the JS thread, timing jitter of 30–80 ms creeps in under load, and
Stage 4's whole premise is that the phone's exit and the TV's entry are one
continuous motion. Nothing you can sync a clock to will fix an animation that
starts late.

If `babel.config.js` is missing the Reanimated plugin, every `'worklet'`
directive is silently ignored and you get exactly that failure with no error.
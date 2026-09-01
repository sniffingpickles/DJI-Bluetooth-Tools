# dji viewer for android

this is the phone-only osmo action 4 live viewer. no rtmp server, no usb cable,
and no mac relay sitting in the middle.

tap one button and the app:

1. finds the oa4 over ble
2. opens or approves the dji control session
3. joins the camera to the same wifi as the phone
4. discovers the camera's current dhcp address
5. keeps ble alive and opens the udp/9004 video session
6. reassembles the video and sends clean hevc access units to mediacodec

the feed we have measured is 1280×720 at roughly 30 fps. no audio stream has
shown up in this direct monitor session. this is not rtmp, and the current code
does not magically turn it into 1080p60.

## build it

android studio should open this folder normally, or build from a terminal:

```sh
./gradlew testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

minimum android version is 10 (`minSdk 29`). the tested phone is a galaxy s23
ultra.

## using it

connect the phone to the wifi network you want the camera to join. on first run,
enter that ssid and password. they are encrypted with android keystore.

tap **auto connect**. if this client identity has not been approved before, the
oa4 may ask on its own screen. long-press **auto connect** when you want to
change the saved network.

the app finds the camera's dhcp address itself, so there is no hard-coded
`10.x.x.x` address to babysit after every camera restart.

## why the picture is not gray garbage anymore

the oa4 breaks hevc access units across dji video packets. packet loss or bad
reassembly can create technically decodable but badly corrupted frames. the
viewer uses:

- complete-frame validation
- a 12-frame reorder window
- hold-last-clean-frame behavior after loss
- an oa4-specific clean-idr request for startup and recovery
- a hardware mediacodec decoder
- a fixed 16:9 surface with aspect-preserving letterboxing

## current limits

- oa4 only for now
- 1280×720 at about 30 fps in the observed profile
- no audio observed
- no recording/export ui
- still a reverse-engineering proof of concept, not a polished mimo replacement

the next interesting target is finding the actual stream-profile selector for
720p60 or 1080p60 without making the feed unstable again.

# dji bluetooth tools

some tools for talking to dji cameras without pretending the only way in is
through mimo.

right now this is mostly osmo action 4 work. there is a macos cli for reading
and changing the camera's wifi country/band settings over ble, plus an android
viewer that handles the whole ble → wifi → direct video path by itself.

this is early reverse-engineering code. it works on the camera we tested, but
there are absolutely still weird firmware cases and commands we have not mapped
yet.

> [!WARNING]
> this is not made by or endorsed by dji. radio rules depend on where the camera
> is physically being used. the camera accepting a country, band, or frequency
> does not magically make that setting legal. use your head and use settings
> allowed where you are.

## what works right now

### macos cli

- find nearby dji ble devices
- read the oa4 wifi country and band state
- set a two-letter wifi country code
- select 2.4 ghz, forced 5 ghz, or automatic/dual-band mode
- experimentally request a center frequency in mhz
- restart the camera's wifi subsystem
- plain output or newline-delimited json

radio-changing commands require `--i-understand-regulatory-risk`. yes, the flag
is long on purpose.

### android viewer

- finds the oa4 over ble
- opens/pairs the dji control session
- joins the camera to the same wifi as the phone
- discovers the camera's current dhcp address
- keeps the ble session alive while video is running
- opens the proprietary udp/9004 live-view session
- reassembles the oa4 video fragments and decodes hevc in hardware
- requests a clean idr instead of happily painting broken frames forever
- preserves the actual 16:9 picture instead of stretching it across the phone

the direct feed we have actually measured is **1280×720 at about 30 fps**. it is
not rtmp, and we have not observed an audio stream in this session. 1080p60 is a
research target, not a finished feature. see
[`android/dji-viewer`](android/dji-viewer) for the app.

## building the cli

you need macos 13+, swift 5.9+, bluetooth, and an oa4 that is awake and nearby.

```sh
git clone https://github.com/sniffingpickles/DJI-Bluetooth-Tools.git
cd DJI-Bluetooth-Tools
swift test
swift build -c release
```

run it directly while hacking:

```sh
swift run dji-tools devices
swift run dji-tools wifi status
```

or install the release binary:

```sh
install -m 755 .build/release/dji-tools /usr/local/bin/dji-tools
```

terminal needs bluetooth permission. the camera may also ask you to approve
`dji-tools` the first time it pairs, so look at the camera screen instead of
waiting thirty seconds and assuming the code exploded.

## cli examples

```sh
# find cameras
dji-tools devices

# read current state
dji-tools wifi status
dji-tools wifi country get
dji-tools wifi band get

# change state
dji-tools wifi country set US --i-understand-regulatory-risk
dji-tools wifi band set 5 --i-understand-regulatory-risk
dji-tools wifi restart --i-understand-regulatory-risk

# target one camera and get machine-readable output
dji-tools wifi status --device '<CAMERA_NAME_OR_UUID>' --json
```

run `dji-tools --help` for the rest.

## what we have confirmed on oa4

| setting | values | what happened |
|---|---|---|
| country | iso alpha-2 code | stored as `ff <CC> 00` |
| band | `0`, `1`, `2` | 2.4 ghz, forced 5 ghz, automatic/dual |
| frequency | center mhz | experimental; firmware may reject or sanitize it |
| direct monitor | hevc main | 1280×720, roughly 30 fps, no audio observed |

country and band are related. a japan-configured oa4 was seen knocking forced
5 ghz back to 2.4 ghz when softap started. with country set to `US`, the camera
successfully joined a 5 ghz lan as a station. that is an observation, not advice
to run a fake regulatory domain wherever you happen to be.

the exact packets, receivers, payloads, and honest unknowns are in
[`Docs/PROTOCOL.md`](Docs/PROTOCOL.md).

## repo layout

- `Sources/DJIProtocol` — duML framing, crc code, stream parsing, oa4 commands
- `Sources/DJITools` — macos corebluetooth transport and cli
- `Tests` — framing and command tests
- `android/dji-viewer` — standalone android live-view proof of concept
- `Docs/PROTOCOL.md` — hardware-observed protocol notes

the protocol code is kept separate from corebluetooth on purpose. linux and
windows transports would be useful; pretending we already support them would
not be.

## contributing

packet captures, hardware reports, command definitions, and other dji models
are welcome. scrub credentials and device identifiers first. read
[`CONTRIBUTING.md`](CONTRIBUTING.md) before sending a pr.

## credits

this builds on a bunch of community work:

- [osmosis](https://github.com/KonradIT/osmosis)
- [dji-remote](https://github.com/dimadesu/dji-remote)
- [osmo-download](https://github.com/SemiConscious/osmo-download)
- [dji-wifi-connect](https://github.com/sniffingpickles/DJI-Wifi-Connect)
- [lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble)
- [dji_protocol](https://github.com/samuelsadok/dji_protocol)
- [reverse-engineering-dji](https://github.com/xaionaro/reverse-engineering-dji)

the dji crc implementation follows the lineage credited to `dji-remote` by
osmosis. if we missed somebody, open an issue or pr instead of quietly being
mad about it.

dji and osmo belong to their respective trademark owner. names here are only
used to describe interoperability.

## license

mit. see [`LICENSE`](LICENSE).

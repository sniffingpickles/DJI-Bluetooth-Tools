# oa4 protocol notes

these are commands and packet shapes we actually observed on an osmo action 4.
other cameras, firmware versions, and regional variants may do something else.
when something is a guess, it is called a guess.

## ble transport

- oa4 model id: `0x0014`
- primary service: `fff0`
- enable notifications on `fff4` and `fff5`
- write raw `01 00` to `fff4` to arm the session
- write duML frames to `fff5`
- session wake: receiver `f0:02`, command `00/2b`, payload `04 00`
- pair/query: receiver `07:02`, command `07/45`, two length-prefixed strings

duML frames start with `0x55`. the header uses dji crc-8 seed `0x77` and the
frame uses dji crc-16 seed `0x3692`. a ble notification is not guaranteed to be
a whole frame, so the decoder buffers chunks until the 10-bit declared length
is available and then checks both crcs.

pair status observed in the `07/45` reply:

| value | meaning |
|---:|---|
| `01` | client already approved |
| `02` | waiting for approval on the camera |

the camera can push `07/46` during approval. reply with the sender/receiver
target swapped and the same message id/payload.

## wifi configuration commands

these use receiver `1b:02`.

| set/command | request payload | reply / notes |
|---|---|---|
| `07/18` | `ff` + iso alpha-2 + `00` | `00` accepted |
| `07/19` | empty | status + `ff` + country + `00` |
| `07/10` | `00`, `01`, or `02` | `00` accepted |
| `07/44` | empty | three-byte band state |
| `07/2b` | `00` + little-endian mhz | `00` or `ff` |
| `07/15` | empty | wifi subsystem restart; `00` accepted |

band values sent to `07/10`:

| value | requested mode | observed `07/44` tail |
|---:|---|---|
| `00` | 2.4 ghz | `00 00 00` |
| `01` | forced 5 ghz | `00 01 01` |
| `02` | automatic/dual | `00 00 01` |

values `03`, `04`, and `05` were rejected by the tested oa4. the frequency
setter is stricter than the band setter; for example, 5180 mhz was rejected in
one test. firmware may accept a setting and later sanitize it when wifi starts.

## joining the camera to a lan

this is the flow used by the android viewer. keep the ble gatt connection open
after the join or the camera/session can become flaky.

| order | receiver | command | payload |
|---:|---:|---:|---|
| 1 | `08:02` | `02/8e` | `01 01 1a 00 01 02` |
| 2 | `08:02` | `02/e1` | `1a` |
| 3 | `07:02` | `07/47` | packed ssid + packed password |

each packed string is one byte of utf-8 length followed by the bytes. a
successful `07/47` reply was `00 00`. obviously do not put real wifi passwords
in packet fixtures, docs, or issues.

## udp/9004 live-view session

after the station join, the app scans the phone's current ipv4 `/24` using the
dji udp handshake. this matters because the camera's dhcp address can change.

the eight-byte transport header is:

| offset | size | field |
|---:|---:|---|
| 0 | 2 | packet length with bit 15 set |
| 2 | 2 | session id, little-endian |
| 4 | 2 | sequence, little-endian |
| 6 | 1 | packet type |
| 7 | 1 | xor of bytes 0 through 6 |

packet types used here:

| type | purpose |
|---:|---|
| `00` | handshake |
| `01` | telemetry |
| `02` | video fragments |
| `03` | telemetry acknowledgement |
| `04` | receive-window acknowledgement |
| `05` | command wrapper |

one important gotcha: once a discovery probe receives a valid handshake reply,
reuse that same udp socket and session. opening a second source port and doing
another immediate handshake caused the oa4 to stop answering in testing.

after the handshake, the viewer sends:

- video start push to receiver `08:01`, command `00/88`
- client registration to receiver `08:02`, commands `00/81` and `00/82`
- heartbeat push to receiver `08:02`, command `00/4f` every 200 ms
- receive-window acknowledgements roughly every 20 ms
- action-specific idr request to receiver `01:02`, command `09/a8`

the `00/81` client identity is 64 bytes with `APP` near the beginning. the
viewer periodically re-registers and repeats the start push because that made
the session survive longer in hardware testing.

## video findings

- codec: hevc main
- measured coded size: 1280×720
- measured rate: about 30 fps
- audio: no audio stream observed
- transport: proprietary dji udp framing, not rtmp

video type-2 datagrams contain fragmented access units. throwing partial or
damaged units at mediacodec produces the gray/blocky mess you would expect.
the android viewer validates complete units, keeps a small reorder window,
holds the last clean frame after loss, and asks the camera for a fresh idr.

the current 720p30 stream is stable. 1080p60 and 720p60 are not unlocked yet.
possible places to keep digging are the registration payload, stream-profile
selection commands, encoder configuration pushes, and differences between mimo
preview modes. none of those are claimed solved here.

## regulatory and testing notes

the country field is just a configuration input to camera firmware. it is not a
legal oracle. the phone/lan also applies its own regulatory domain.

useful hardware reports include:

- camera model and firmware
- requested value and raw reply
- read-back value
- physical operating country
- independently observed band/channel
- whether the value survived wifi restart and full reboot

leave passwords, serial numbers, account tokens, location data, and unnecessary
bluetooth ids out of public captures.

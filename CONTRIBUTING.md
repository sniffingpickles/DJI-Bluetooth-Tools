# contributing

hardware reports and protocol work are welcome. just keep the signal-to-noise
ratio decent.

before opening a pr:

1. say which camera and firmware you tested
2. separate what you observed from what you think the bytes might mean
3. add or update tests for framing, parsing, and command payloads
4. run `swift test` and `swift build -c release`
5. if you touched android, run `./gradlew testDebugUnitTest assembleDebug`
6. remove wifi credentials, serials, tokens, location data, and unnecessary ble
   identifiers from logs and captures

keep changes focused. a raw capture plus a careful explanation is more useful
than a giant “support every dji camera” patch tested on one device.

new commands that change radio configuration need input validation, raw replies
in json mode, known recovery behavior in the docs, and the cli's explicit
regulatory acknowledgement.

research devices you own or are allowed to test. this repo is not for bypassing
ownership checks, pairing approval, accounts, or other people's cameras.

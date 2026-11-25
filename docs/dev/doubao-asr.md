# Doubao Streaming Speech Recognition 2.0

## Entry and configuration

Settings › Model › Speech-to-text "Voice while speaking service" group has only one line "Beanbao
Streaming Speech Recognition Model 2.0": If the credentials are not saved, the time mark is "Not
Set", click on the separate settings page (`app/ui/SpeechModelScreen.kt`); after saving, click the
circle to select it, go to "Settings" on the right to change the credentials, change the billing
method or delete it. On the top of the settings page, switch between the new version of the console
(voice service API Key) and the old version of the console (App ID + Access Token). Select the
hourly version `volc.seedasr.sauc.duration` or the concurrent version `volc.seedasr.sauc.concurrent`
as the billing method. You need to activate the corresponding service first. If you only change the
billing method and do not refill the credentials, the existing credentials will be used. Ark chat
service providers, chat model lists and their credentials are not involved in this process.

The storage is only encrypted and stored locally, no online services are called, and the
configuration is not marked as verified. Press and hold the microphone to establish a connection and
send audio. Service charges may apply. Credentials can be deleted individually; voice input is
turned off when deleting the currently used Beanbao credential. The system recognizes and
transcribes existing entire audio segments to maintain their own paths and does not automatically
switch to paid services.

Credentials are encrypted using existing `KeyCipher` / Android Keystore standalone alias, select
JSON with only the resource ID. The input box does not backfill keys and does not use persistent
Compose state. Diagnostics only record engine and resource IDs; server-side error messages are not
displayed directly to avoid accidentally echoing credentials or audio content.

## real time path

`SpeechChoice.Doubao` → `SpeechResolver` → `VoiceInput` → `DoubaoSpeech`. Chat input and floating
capsule share the same entrance.

- `PcmRecorder(streaming = true)` provides 16 kHz, mono, PCM16 sampling in real time, and the
  original non-streaming mode can still return the entire recording.
- `DoubaoTransport` uses a standalone OkHttp WebSocket client to connect to
  `wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async`. Credentials are only sent to fixed
  official endpoints; production clients do not follow redirects.
- After the handshake, send gzip JSON initialization first, wait for the first confirmation from the
  server, and then send the audio. 50 ms sample blocks are combined into 200 ms audio packets;
  buffered audio is also sent at 200 ms intervals. PCM uses little endian, and protocol length and
  optional sequence numbers use big endian.
- Audio is sent in parallel with result reception. Request `result_type=full`, each `Partial` covers
  the previous full text, without splicing to cause duplication. `definite` is a clause stability
  flag and cannot be used as the end condition of the entire session.
- Stop recording when released, retain the queued samples, send the last packet (flag 2, containing
  tail tone less than 200 ms, or empty final packet), continue to wait for the server's final
  flag/negative sequence number, and then send `Final`. Disconnecting without receiving the final
  packet will fail, and the incomplete text will not be regarded as the final result.
- Cancellation closes events, audio streams, and connections, suppressing late results. The
  recording ends automatically and the final packet is also sent. The handshake/initialization time
  is 10 seconds each, the waiting time is up to 10 seconds after the final packet is sent, and the
  total session is up to 75 seconds; the original UI’s press and hold recording limit is still 60
  seconds.
- The audio queue is capped at about 5 seconds, and the network send queue and response queue are
  also capped. When the upper limit is exceeded, it fails and releases the microphone without
  silently losing sound. gzip decoded responses are limited to 1 MiB.

Does not send device IMEI/MAC, historical chats or pictures. The two-way interface handles the
language according to the default recognition capability of the service, and does not shoehorn
`audio.language`, which is only supported by the one-way interface.

## Authentication and restrictions

Added `DoubaoProtocolTest`, `DoubaoSpeechTest`, `DoubaoTransportTest`, `DoubaoSettingsTest`,
covering protocol samples, real-time upload, full text replacement, tail packet, timeout,
cancellation, two generations of request headers, authentication failure, AES/GCM credential
saving/recovery/deletion. All fixtures sample mocks and fake credentials; WebSocket tests only
connect to the native MockWebServer.

A normal Android development environment can run:

```sh
./gradlew :app:testDebugUnitTest --tests 'com.meenai.mina.voice.*'
./gradlew :app:assembleDebug
```

The permissions, charges, delays and accuracy of real volcano accounts need to be verified by people
with accounts; if you don’t have an account, you can only confirm the settings page, save and delete
credentials and failure prompts.

## Data and implementation sources

Official agreement verified on 2026-11-01:

- [Large Model Streaming Speech Recognition API (Binary
  Frame)](https://docs.volcengine.com/docs/doubao-voice/large-model-streaming-automatic-speech-recognition-api-1?lang=zh)
- [Real-time speech recognition (current parameters and 2.0
  resources)](https://docs.volcengine.com/docs/doubao-voice/bidirectional-streaming-automatic-speech-recognition-websocket?lang=zh)


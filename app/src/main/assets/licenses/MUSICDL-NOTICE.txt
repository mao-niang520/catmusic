# Notices and attribution

MusicDL 轻听 is an unofficial, noncommercial Android client. It is not
affiliated with, endorsed by, or maintained by the musicdl project or any of
the music platforms it can search.

## musicdl

- Project: **CharlesPikachu / musicdl**
- Source: <https://github.com/CharlesPikachu/musicdl>
- License: PolyForm Noncommercial License 1.0.0
- Role: upstream inspiration; the app brings musicdl's search → listen → save
  workflow to Android. The app does not bundle the upstream Python code.

## MusicSquare

- Project: **CharlesPikachu / musicsquare**
- Source: <https://github.com/CharlesPikachu/musicsquare>
- License: Apache License 2.0 (a copy is included in
  `THIRD_PARTY_MUSICSQUARE_LICENSE`)
- Role: reference for the public browser-facing bridge endpoints used to search
  and resolve playable URLs for NetEase, Kuwo, JOOX and QQ Music.

## Additional public sources

- **Kugou** search and its Meting bridge, used for the 酷狗 source. Results
  without a publicly playable URL are filtered from the app.
- **5sing**, Kugou's original/cover song community, used for the 5sing source.
- **Jamendo**, a Creative Commons music catalogue, used for its public search
  and streaming API. Individual Jamendo tracks remain under the licenses
  published by their artists.

## Music services and artwork

Search results, metadata, lyrics and artwork are provided by third-party
services and remain the property of their respective rights holders. This
project does not host or redistribute audio files. It does not include account
cookies or DRM decryption. Availability changes with the upstream platforms and
their public endpoints.

## App identity

The app name, icon and user interface were created for this project. The app
uses a local development signing key for the provided APK.

## Crash reports

Version 1.1.0 removed edge-to-edge/API-30 system bar calls from the startup
path and added a visible fallback when the system WebView cannot be created.
If the app still closes immediately on a specific device, a logcat stack trace
is the most useful report.

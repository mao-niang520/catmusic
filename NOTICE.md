# Notices and third-party licenses

CatMusic original code and documentation are licensed under the PolyForm
Noncommercial License 1.0.0 (see [LICENSE](LICENSE)). This is a
source-available, noncommercial license, not an OSI open-source license.

The following third-party components keep their own copyright and license.
The root CatMusic license does not relicense them.

| Component | Use in CatMusic | License | Bundled notice |
|---|---|---|---|
| **MusicDL / musicdl** by CharlesPikachu and MusicDL Lite contributors | Online search flow and the bundled `app/src/main/assets/musicdl` page | PolyForm Noncommercial 1.0.0 | [`licenses/MUSICDL-POLYFORM-NONCOMMERCIAL.txt`](licenses/MUSICDL-POLYFORM-NONCOMMERCIAL.txt), [`licenses/MUSICDL-NOTICE.md`](licenses/MUSICDL-NOTICE.md), `app/src/main/assets/musicdl/legal/` |
| **MusicSquare** by CharlesPikachu | Reference for public browser-facing search/resolve bridges | Apache License 2.0 | [`licenses/MUSICSQUARE-APACHE-2.0.txt`](licenses/MUSICSQUARE-APACHE-2.0.txt) |
| **QWEA0 Liquid Glass Android** | Real liquid-glass surfaces | MIT | [`licenses/LIQUID-GLASS-ANDROID-MIT.txt`](licenses/LIQUID-GLASS-ANDROID-MIT.txt) |
| **Outfit** | Bundled UI font | SIL Open Font License 1.1 | [`licenses/OUTFIT-OFL.txt`](licenses/OUTFIT-OFL.txt) |
| AndroidX / Media3 / Compose | Android UI, playback and media session | Apache License 2.0 (dependency licenses) | Dependency metadata |

## Sound field

The full-screen sound field in `app/src/main/assets/soundfield/` — including
the page layout, CSS, the roughly 10,000-point cover globe, the perspective
Echo wave lattice, the WebGL shaders, gesture handling and the Kotlin bridge —
is **original CatMusic code**. It contains no third-party graphics libraries,
terrain scenes, geometries, shaders or copied assets. It is covered by the
root CatMusic PolyForm Noncommercial license and by the copyright of the
CatMusic author.

The two effects use only locally decoded PCM bands and a tiny cover preview
supplied by the app. The WebView is configured with `blockNetworkLoads = true`
and the Android app requests no microphone permission.

## Additional notes

- Music search results, metadata, artwork, lyrics and audio are provided by
  third-party platforms and remain the property of their rights holders.
  CatMusic does not host or redistribute audio files. Online features are for
  personal, noncommercial use and are subject to the platforms' terms.
- CatMusic is not affiliated with, endorsed by, or maintained by any music
  platform, MusicDL, MusicSquare, QWEA0 or any other third party.
- The developer's private signing key, `keystore.properties`, personal music
  library, downloaded audio and personal artwork are not included.
- Per the author's request, `donation_wechat.jpg` is the real WeChat donation
  QR code and is included in both this source repository and the public
  release APK; it is not a placeholder. The public APK is provided as a
  GitHub Release asset rather than committed to Git history.
- The public screenshots in `docs/images/` use synthetic album artwork and
  demo lyrics. They contain no real album art, personal media or real lyrics.
- This source export intentionally omits the developer's local MusicDL
  reference checkout. The distributable MusicDL Lite web assets that the app
  actually uses remain under `app/src/main/assets/musicdl` with their notices.

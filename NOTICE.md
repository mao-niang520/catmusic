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
| **Three.js** | Used by the developer's optional Sonic terrain scene; Three.js source is not included in this export | MIT | [`licenses/THREE-MIT.txt`](licenses/THREE-MIT.txt), [`app/src/main/assets/sonic/LICENSE-THREE.txt`](app/src/main/assets/sonic/LICENSE-THREE.txt) |
| **Outfit** | Bundled UI font | SIL Open Font License 1.1 | [`licenses/OUTFIT-OFL.txt`](licenses/OUTFIT-OFL.txt) |
| **Sonic Topography** by yin-yizhen | Source of the developer's optional soundwave terrain; **source is not included in this repository** | Non-Commercial Learning License | [`app/src/main/assets/sonic/LICENSE-SONIC-TOPOGRAPHY.txt`](app/src/main/assets/sonic/LICENSE-SONIC-TOPOGRAPHY.txt), [`docs/SONIC-ASSETS.md`](docs/SONIC-ASSETS.md) |
| AndroidX / Media3 / Compose | Android UI, playback and media session | Apache License 2.0 (dependency licenses) | Dependency metadata |

## Additional notes

- Music search results, metadata, artwork, lyrics and audio are provided by
  third-party platforms and remain the property of their rights holders.
  CatMusic does not host or redistribute audio files. Online features are for
  personal, noncommercial use and are subject to the platforms' terms.
- CatMusic is not affiliated with, endorsed by, or maintained by any music
  platform, MusicDL, MusicSquare, QWEA0, Three.js or Sonic Topography.
- The developer's private signing key, real WeChat donation QR code, release
  APKs, personal music library, downloaded audio and personal artwork are not
  included. The `donation_wechat.jpg` in this repository is a deliberately
  non-scannable placeholder.
- The public screenshot in `docs/images/` uses a synthetic album cover and
  contains no real album art or lyrics.
- This source export intentionally omits the Sonic Topography-derived
  `index.html`, `bundle.js`, `style.css`, and the `sonic-web/` source tree.
  See [docs/SONIC-ASSETS.md](docs/SONIC-ASSETS.md) before adding them.

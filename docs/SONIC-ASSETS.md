# Sonic Topography assets and licensing

CatMusic's original code, including the 10,000-point WebGL particle renderer
([`particle.js`](../app/src/main/assets/sonic/particle.js)), is included in this
repository. The **soundwave terrain page** is built on
[Sonic Topography](https://github.com/yin-yizhen/sonic-topography), which has a
**Non-Commercial Learning License**:

> This project is provided only for learning, research, and personal
> non-commercial use.
>
> You may read, study, and modify the source for learning, run it locally for
> personal non-commercial use, and share non-commercial study notes or
> screenshots with attribution.
>
> You may not use it for commercial projects or performances, paid services,
> resale, advertising services, or other profit-making activities, or remove
> its notices. The license does not grant public redistribution of its source
> code.

The full license included in this repository is
[`LICENSE-SONIC-TOPOGRAPHY.txt`](../app/src/main/assets/sonic/LICENSE-SONIC-TOPOGRAPHY.txt).

## Why the files are missing

The public repository intentionally does **not** include:

- `app/src/main/assets/sonic/index.html`
- `app/src/main/assets/sonic/bundle.js`
- `app/src/main/assets/sonic/style.css`
- the `sonic-web/` source tree

The upstream license permits local learning use and sharing non-commercial
screenshots/notes, but does not grant source redistribution. Publishing those
files would exceed that permission. They are not relicensed by CatMusic's
root PolyForm license.

## What the public build does

The Android app still compiles without these files. When the soundwave toggle
is opened, the WebView cannot load the page and CatMusic falls back to its
native lyric screen. The native local player, lyrics, MusicDL online module,
settings and the rest of the app remain usable. For a clean public build,
leave 音浪 disabled or use the native lyric page.

The original particle renderer (`particle.js`) is included for study and
adaptation, but it expects the DOM/controller provided by the Sonic page; it
is not a standalone page on its own.

## If you have written permission

If the Sonic Topography copyright holder has given you explicit written
permission to obtain and use the files, add them locally (do not commit them to
a public fork unless your permission allows redistribution):

1. Put `index.html`, `style.css`, and `bundle.js` into
   `app/src/main/assets/sonic/`.
2. Optionally restore the `sonic-web/` source tree and build it with Bun:
   `cd sonic-web; .\build.ps1`. The build script copies the page assets and the
   original `particle.js` into `app/src/main/assets/sonic/`.
3. Keep all copyright, license and attribution notices.
4. Rebuild the app with `./gradlew :app:assembleDebug`.

Do not use the result commercially without permission from all relevant
rights holders. CatMusic's own code remains under
[PolyForm Noncommercial 1.0.0](../LICENSE).

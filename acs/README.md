# Vendored ACS manifests

The app downloads its build toolchains and language servers **at runtime**. Those
downloads are described by two small JSON manifests that used to be read directly
from the `AndroidCSOfficial` repositories. They are now vendored here so this fork
does not depend on upstream to discover versions.

| File | Upstream source | Used for |
|---|---|---|
| `manifests/acs-manifest.json` | `AndroidCSOfficial/acs-build-system` (`main`) | NDK (`android-native-kit`), CMake, build-tools bundles |
| `manifests/servers-manifest.json` | `AndroidCSOfficial/acs-language-servers` (`main`) | Kotlin / C/C++ language-server downloads |

## Why the binaries are not here

Each NDK bundle is around 190 MB. Git rejects files over 100 MB, so the archives
stay on GitHub **Releases** (2 GiB limit per asset) and only the manifests live in
git. The `url` fields inside `acs-manifest.json` still point at the upstream
releases, which keeps the fork working without re-uploading ~1 GB of binaries.

## Updating

```bash
./acs/sync-manifests.sh           # refresh from upstream and validate
./acs/sync-manifests.sh --check   # exit 1 if the vendored copies are stale
```

Once you host the binaries yourself, rewrite the asset URLs at the same time:

```bash
ACS_RELEASE_BASE=https://github.com/<owner>/<repo>/releases/download \
  ./acs/sync-manifests.sh
```

## Where the URLs are wired

Changing the manifest location means editing these constants (keep them in sync
with `sync-manifests.sh`):

| File | Constant |
|---|---|
| `core/app/.../activities/IdeConfigurations/AcsProvider.kt` | `MANIFESTS_BASE_URL`, `getManifestUrl` |
| `utilities/preferences/.../preferences/LSPPreference.kt` | `manifestUrl()` |
| `java/lsp-setup/.../setup/servers/clang/Kotlin.kt` | `MANIFEST_URL` |
| `java/lsp-setup/.../setup/updater/lsp/KotlinLspUpdater.kt` | `manifestUrl` |
| `core/app/.../activities/TomIDEUpdater.kt` | `UPDATE_JSON_URL` (uses `updater.json` at the repo root) |

`composite-builds/build-logic/.../ProjectConfig.kt` still carries the upstream
owner/name for build metadata (SCM URLs); it is not a download source.

## updater.json

`updater.json` in the repository root is read by `TomIDEUpdater` and now points at
this fork's raw URL and release page. It is kept at `baseVersionCode` 1024 so a
freshly built APK (`1.0.0+gh.r04`, version code 1024) does not show a bogus update
prompt. When publishing a release:

1. bump `baseVersionCode` / `baseVersionName` and both `variants.*`,
2. upload assets named `android-code-studio-<arch>-<versionName>.apk` (the
   `apkUrl` patterns expect `armeabi-v7a` and `aarch64`),
3. keep `changelog` pointing at a `whatsnew/*.md` file in this repository.

## Licensing

The manifests themselves are configuration data. The binaries they point at keep
their own licenses (NDK: Apache-2.0 with LLVM exception, CMake: BSD-3-Clause).
If you re-host them, ship the corresponding license notices. Bundles marked
`"encrypted": true` may be copied but not repacked.

<img src="TVSlim%20Windows/packaging/tvslim-256.png" alt="" width="96" align="right">

# TV Slim

**Reversible debloat for Android TV.** TV Slim switches off the advertising, telemetry, shop-demo
leftovers and preinstalled apps that weigh a smart TV down — from a Windows PC or from an Android
phone — and keeps a log of every change, so that each one can be undone.

Nothing is ever uninstalled: packages are *disabled* and can be re-enabled at any time. And nothing
has to be installed on the television.

[Version française](README.fr.md)

![TV Slim for Windows — the packages found on a TCL television, with the details of the selected one](docs/captures/paquets.png)

## Download for Windows

Get the latest version from the **[Releases page](https://github.com/jolabs40/TVSlim/releases)**:

| File | For |
|---|---|
| `TVSlim-Windows-x.y.z.msi` | **Recommended.** Installs TV Slim for your user account — no administrator rights — and keeps it up to date. |
| `TVSlim-Windows-x.y.z-portable.zip` | Runs without installing: unzip, then open `TV Slim.exe`. |

Windows 10 or 11, 64-bit. Java is bundled: there is nothing else to install.

> **“Windows protected your PC”?** TV Slim is free, open-source software, and its installer is not
> signed with a paid certificate, so SmartScreen may warn you the first time. Click **More info**,
> then **Run anyway**. `SHA256SUMS.txt`, published alongside, lets you check the file is genuine.

## Prepare the television (once)

1. On the TV, open **Settings → About** (sometimes under *System* or *Device preferences*) and press
   OK seven times on the **build number**. Developer options appear.
2. In **Developer options**, enable network debugging — called *Network debugging*, *ADB debugging*
   or *USB debugging* depending on the TV.
3. Start TV Slim on a PC connected to the same network. It finds the TV by itself within a few
   seconds; otherwise, type its IP address (shown in *Settings → Network*).
4. On the first connection, the TV asks to **allow debugging from this computer**: tick *Always
   allow* and accept with the remote. It will not ask again, even after a reboot.

## What you can do

- **Television** — model and maker, Android version, memory, enabled and disabled packages; the
  home screen and the launchers installed, recognised by their logo, the factory home screen
  included even once disabled; the recommended replacement, Startlight Launcher (coming soon to the
  Play Store); grant the privileged permissions some apps need (`WRITE_SECURE_SETTINGS`, `DUMP`…);
  install an APK on the TV — dropped into the window or chosen from your files — after a confirmation
  that shows the package, its version and what it replaces; send an ADB shell command of your own and
  read its output.
- **Packages** — the known packages actually present on *your* TV, with risk level, size,
  description and known side effects; ready-made profiles (ads and telemetry, voice assistants,
  preinstalled streaming services…); applied in one go, after a confirmation that lists the side
  effects. Save the TV's configuration — packages and home screen — to a file, and reinject it
  later, on the same TV or on another one of the same model. Each package shows an origin icon:
  Android, maker or other. Those TV Slim does not know yet are listed separately, with nothing
  offered to do about them; their inventory can be exported — location, system rights, sensitive
  declared services, memory and storage — to help grow the catalogue. When some come from the
  maker, *Propose to the catalogue* exports it, then opens
  [a GitHub form](https://github.com/jolabs40/TVSlim/issues/new?template=nouvel-appareil.yml) to
  send it. A package described from such a report is marked *not tested*: no profile selects it.
- **Memory** — RAM: what each running process really costs, and the before/after comparison since
  your first visit. Storage: free and used space, and the largest applications.
- **Log** — every action with the command that undoes it; undo a single line or restore everything;
  export as Markdown.

### Safeguards

- Never `pm uninstall`: only `pm disable-user --user 0`, undone by `pm enable`.
- A protected list is refused whatever the selection — packages whose absence causes a boot loop or
  breaks the remote control, for instance.
- The factory home screen cannot be disabled while no third-party launcher is installed.
- An APK is installed only after a confirmation, and never by uninstalling what is already there: an
  application signed with another key is left alone.
- The one exception is the **ADB command** card: what you type there bypasses these safeguards and
  cannot be undone from TV Slim. The card says so, and every command is recorded in the log.

## Updates

TV Slim asks GitHub for a new version when it starts — this can be switched off in **About**.
Nothing is downloaded until you click **Install**. Every installer is signed with an Ed25519 key:
TV Slim checks the signature before running it, refuses anything else, and restarts on its own.

Anyone can run the same check on a download, with JDK 17 or later, from a copy of this repository
and with the `.msi.sig` file next to the installer:

```sh
java "TVSlim Windows/outils/VerifierMiseAJour.java" TVSlim-Windows-x.y.z.msi x.y.z "TVSlim Windows/gradle.properties"
```

## Privacy

TV Slim talks to your television over your local network, and to GitHub to look for updates —
nothing else. No account, no telemetry. The ADB key that lets this computer talk to your TVs is
stored encrypted by Windows (DPAPI) in `%APPDATA%\TVSlim`, next to the logs.

## Uninstall

*Settings → Apps → TV Slim → Uninstall*. Logs stay in `%APPDATA%\TVSlim`, in case something has to
be restored later; delete that folder to remove everything. To withdraw this computer's access to a
TV: *Developer options → Revoke USB debugging authorisations*.

## Build from source

Requires JDK 21.

```sh
cd "TVSlim Windows"
./gradlew run          # start the application
./gradlew jvmTest      # tests, including those of the core shared with Android
./gradlew packageMsi   # installer, in build/compose/binaries/main/msi
```

| Folder | Content |
|---|---|
| `TVSlim/` | Android apps: `core` (catalogue, engine, safeguards), `app-mobile` (TV Slim Remote), `app-tv` |
| `TVSlim Windows/` | Windows app (Kotlin, Compose Multiplatform). It compiles the sources of the Android `core` directly: one catalogue, one engine, one set of safeguards. |

### Publishing a Windows release

Push an annotated tag `windows-vX.Y.Z`: the workflow tests, builds, signs and publishes. The
signing key comes from the `TVSLIM_CLE_SIGNATURE` secret. A fork must generate its own key pair
(`java outils/GenererCleSignature.java`) and replace `clePubliqueMisesAJour` in `gradle.properties`
before its first release.

## Licence

See [LICENSE](LICENSE).

TV Slim is not affiliated with any television, TV box or launcher maker. Brand names and logos
belong to their owners and are shown only to identify the device or the application detected;
[NOTICE-LOGOS.md](NOTICE-LOGOS.md) says where each one comes from. Startlight Launcher, which TV Slim recommends, comes from the same author. Every change TV Slim
makes is reversible, but you remain in charge of what you disable.

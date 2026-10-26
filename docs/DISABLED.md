# What was disabled on this TV

Hisense SmartTV 4K (`lushan-NF`), Android 12 / Google TV, MediaTek.
Build `STT2.230929.001.102`. Captured 2026-10-25 08:46.

Re-enable everything with `tools/restore.sh`, or one package with:
```
adb -s 10.0.0.97:5555 shell pm enable --user 0 <package>
```

| Package | What it is | Why |
|---|---|---|
| `com.vt.tv.evservice` | Hisense ACR service (`ACRService`, `ACRProvider`, `PromotionActivity`) | Watches what is on screen and reports it; also serves promos |
| `com.vt.tv.smartsense` | Hisense "SmartSense" | Telemetry workers and a dynamic-domain provider |
| `com.vt.tv.smartsense.ui` | SmartSense UI | Front end for the above |
| `com.vt.tv.smartservice` | Hisense smart service | Ships a Firebase log-reporting provider |
| `com.vt.udpserver` | UDP scan service | Network discovery daemon, not needed |
| `com.vt.tv.aiot` | Hisense IoT broadcast receiver | Smart-home hooks, unused |
| `net.vidaatv.hisense.android.tv` | VIDAA Channels Plus | Contains `LauncherAdClickActivity` - serves ads into the home rows |
| `com.vidaa.oemtabcontentprovider` | VIDAA OEM tab content provider | Feeds the sponsored rows and the Hisense tab |
| `com.vt.tv.mobiledeviceconnectiontips` | "Connect your phone" tips | Promo screens |
| `com.vt.hishow` | HiShow | Screensaver plus a camera broadcast receiver |
| `com.google.android.feedback` | Google feedback reporter | Crash/usage reporting |
| `com.google.android.partnersetup` | Google partner setup | OEM attribution, only used at first boot |
| `com.vt.tv.protector` | "Protector" cleaner (turbo mode, cache cleaner) | Junk cleaner, does nothing useful |
| `com.vt.storemode` | Shop demo mode | In-store demo loop |
| `com.vt.fte` | First-time experience | Only runs during initial setup |
| `com.vt.eula` | EULA screens | Only runs during initial setup |
| `tv.mopa.ginga` | Ginga DTV middleware | Brazilian broadcast interactivity, irrelevant here |
| `com.netflix.ninja` | Netflix | You said you do not use it |
| `com.netflix.tokenmanager` | Netflix token manager | Only serves Netflix |
| `com.amazon.amazonvideo.livingroom` | Prime Video | You said you do not use it |
| `com.google.android.youtube.tvmusic` | YouTube Music | You said you do not use it |
| `com.google.android.play.games` | Play Games | You said you do not use it |
| `com.google.android.tungsten.setupwraith` | Google TV setup wizard | Steals the HOME key once the stock launcher is disabled. NOTE: disabling this deleted the saved Wi-Fi network `oxylab_mo` |
| `com.google.android.apps.tv.launcherx` | Stock Google TV launcher | Replaced by TV Home |

## Deliberately left enabled

| Package | Why it must stay |
|---|---|
| `com.mediatek.dtv.tvinput.framework.server` | The TV input framework - HDMI and the tuner |
| `com.vt.tv.input`, `com.vt.source.tuner`, `com.vt.source.external` | Input and source switching |
| `com.vt.tv.tvcenter` | The Live TV app (`LiveTVMainActivity`, `TvContentProvider`) |
| `com.vt.tv.commonui` | `KeyPadService` and the panel-key handler |
| `com.vt.avmiddleware`, `com.vt.tvservice`, `com.vt.edv.drmp` | AV middleware and DRM |
| `com.mediatek.android.tv.globalkeyoverlay` | Global remote keys |
| `com.mediatek.autopair`, `com.android.bluetooth` | Remote pairing and Bluetooth |
| `com.google.android.tv.remote.service` | Remote control service |
| `com.google.android.inputmethod.latin` | On-screen keyboard |
| `com.android.vending`, `com.google.android.gms`, `com.google.android.gsf` | Play Store and Play Services |
| `com.vt.factory`, `com.factory.tv.at`, `com.at.factory.androidtvaidlservice` | Factory menus - never disabled AND never launched; opening one can flip a factory flag that kills the Input key and CEC |

## Kept at your request

Google Assistant (`com.google.android.katniss`), Chromecast (`com.google.android.apps.mediashell`),
AirPlay (`com.mediatek.AirplayAPK`, `airplaydaemon`, `partner.airplay_sys`, `com.vt.tv.airplaykeycheck`),
DLNA and the media browser (`com.vt.tv.dlna`, `com.vt.tv.mediacenter`),
firmware updates (`com.vt.remoteupgrade`, `com.vt.tv.ota`, `com.vt.tv.usbota`),
the Hisense browser (`com.hisense.odinbrowser`) and YouTube.

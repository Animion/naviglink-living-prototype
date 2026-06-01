# Naviglink — Android driver app

Native Android aplikace pro řidiče. Reaguje na blokové čištění z `naviglink-living.onrender.com`. Klíčové vlastnosti: reálné GPS přes FusedLocationProvider, Ed25519 podpis v EncryptedSharedPreferences, native notifikační kanály.

## Stav

- ✅ Skeleton: Gradle config, manifest, theme, ikona
- ✅ Crypto: Ed25519 keypair, kanonický JSON (byte-by-byte stejný jako Python/JS), content-addressed ID
- ✅ Data: Ktor HTTP klient + SignedSubject model
- ✅ UI: HomeScreen s state machine (Idle → Querying → Alert/NoAlert → ReactionSent)
- ✅ Location: FusedLocation wrapper s runtime permission
- ✅ Service: LocationService stub (foreground), continuous polling v další iteraci
- ✅ Unit testy: 9 testů pro CanonicalJson byte-by-byte shoda

## Build

### Požadavky

- Android Studio Hedgehog (2023.1.1) nebo novější
- JDK 17
- Android device s API 29+ (Android 10) a USB debugging
- nebo Android Emulator z AVD Manageru

### Otevření projektu

1. **Android Studio → File → Open** → vyber adresář `prototype-live/android-driver/`
2. Studio detekuje root `settings.gradle.kts` a otevře projekt
3. **Gradle Sync** se spustí automaticky (první spuštění ~3–5 min — stahuje SDK + dependencies ~200 MB)
4. Pokud Studio chybí Gradle wrapper jar, zvolí "Use Gradle from: Gradle wrapper" — Studio jar dogeneruje

### Spuštění na zařízení

1. **Připoj telefon přes USB** — Settings → About → Build number 7× → Settings → Developer options → USB debugging
2. Telefon se objeví v Android Studio (toolbar dropdown vlevo od Run tlačítka)
3. **Klikni Run (zelená ▶)** nebo `Shift+F10`
4. Studio zbuilduje, podepíše debug-keystore, instaluje, spustí

Alternativně z příkazové řádky:
```bash
cd prototype-live/android-driver
./gradlew installDebug    # postaví + nainstaluje APK
./gradlew test            # spustí JVM unit testy (CanonicalJson)
```

### Manuální spuštění bez Studia

APK najdeš po buildu v `app/build/outputs/apk/debug/app-debug.apk`. Přenes na telefon a otevři.

## První spuštění

1. App spustí, zobrazí "Naviglink" header s `did:key:abc…` (16 prvních hex znaků public klíče)
2. Pop-up: **"Naviglink wants to access your location"** → Allow
3. (Android 13+) Pop-up: **"Allow notifications"** → Allow
4. Klikni **"Zkontrolovat teď"**
5. App získá GPS polohu, zavolá `GET /query?lon=…&lat=…&at=now`
6. Pokud server nic neaktivního na poloze nemá → **"✓ Bez upozornění"**
7. Pokud server vrátí aktivní subjekt (blokové čištění) → **"⚠ POZOR"** s detaily + tlačítka `Jsem na cestě` / `Nemohu`
8. Klik na reakci → app podepíše `claim` SignedSubject, POSTne na backend
9. **"✓ Hotovo. Magistrát ví, že jsi na cestě přeparkovat."**

## Test scénář

1. Otevři **<https://naviglink-admin.onrender.com/>** v PC browseru
2. Vyhlas subjekt — nakresli polygon kolem místa, kde fyzicky stojíš (např. domov)
3. Vyplň datum **dnešní** a časy tak, aby zahrnoval **právě teď** (např. `00:00` → `23:59`)
4. Klikni **Vyhlásit** → admin zobrazí zelený polygon na mapě
5. Na telefonu otevři Naviglink driver app → **Zkontrolovat teď**
6. Pokud jsi fyzicky uvnitř polygonu → **"⚠ POZOR"** zobrazeno
7. Klikni **"Jsem na cestě"** → vyletí "✓ Hotovo"
8. V admin webu klikni **"Obnovit ze serveru"** — claim se zobrazí v audit historii subjektu (přes `GET /audit/{id}`)

## Architektura

```
app/src/main/java/cz/naviglink/driver/
├── NaviglinkApp.kt              Application class, service locator
├── MainActivity.kt              Single Activity, Compose entry point
├── crypto/
│   ├── CanonicalJson.kt         Recurzivní JSON s sort_keys (byte-by-byte vs Python/JS)
│   └── NaviglinkKeystore.kt     Ed25519 + EncryptedSharedPreferences + ContentId
├── data/
│   ├── SignedSubject.kt         Kotlin data class
│   ├── NaviglinkClient.kt       Ktor + canonical sign + POST/GET
│   └── LocationRepository.kt    FusedLocationProvider wrapper
├── ui/
│   ├── NaviglinkTheme.kt        Material 3 colors
│   ├── DriverViewModel.kt       State machine (Idle/Alert/etc.)
│   └── HomeScreen.kt            Compose UI per state
└── service/
    └── LocationService.kt       Foreground service stub
```

## Klíčové designové volby

1. **Software Ed25519 (Bouncy Castle) + EncryptedSharedPreferences.** Klíče šifrovány AES-GCM master key vázaným na Android Keystore. Pro v2 zvážit native Ed25519 v Keystore (API 33+ HW-backed).
2. **Žádný DI framework.** Pro malou aplikaci stačí `NaviglinkApp` jako service locator. Hilt/Koin by byly overkill.
3. **Compose only, žádné XML layouts.** Méně boilerplate, jednodušší state-driven UI.
4. **Žádný persistent cache claimů.** Pro MVP server query → display. Room/SQLite přijde, až bude potřeba offline mode.
5. **Min SDK 29 (Android 10).** 99% telefonů v ČR; jednodušší než API 26 (zastaralé Notification handling).
6. **Foreground service zatím stub.** Continuous tracking přijde po validaci basic flow.

## Bezpečnost — co se opravdu chrání

- **Privátní Ed25519 klíč** šifrovaný AES-GCM v EncryptedSharedPreferences
- **Master key** v Android Keystore, vázán na zařízení (factory reset = klíč mrtvý, jak má)
- **`allowBackup=false`** — klíče se nezálohují přes Google cloud
- **`data_extraction_rules.xml`** — explicit exclude `naviglink_keys` z device transfer

## Co aplikace NEdělá (vědomě)

- Žádný GPS tracking na pozadí během vypnuté aplikace (zatím)
- Žádné Web Push notifikace ze serveru (zatím — pull model)
- Žádný Google Maps SDK (vystačíme s FusedLocation + později OSM)
- Žádný analytics, telemetrie, crashlytics

## Známé limity

- **První build = 5+ minut.** Gradle stahuje SDK platforms, build tools, dependencies.
- **Free tier backendu spí.** Po 15 min nečinnosti `https://naviglink-living.onrender.com` zhasne. První query po probuzení trvá ~10–30 s; pak je rychlá.
- **Render `/tmp` storage je ephemeral.** Po redeploy backendu se vyhlášené subjekty ztratí; vyhlas znovu v admin webu před driver testem.

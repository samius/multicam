# Multicam Android appka (S20 / S20 FE)

Nahrává video na SD kartu a reaguje na vzdálené start/stop příkazy ze
serveru ([`../server`](../server)). Viz plný návrh v
[`../handoff-multicam.md`](../handoff-multicam.md).

## Build

Build se dělá přes GitHub Actions (Athéna nemá Android SDK). Push do `master`
se změnou v `android/` spustí `.github/workflows/android-build.yml`, který
vyrobí:

- `multicam-debug-apk` — debug APK (artifact)
- `multicam-release-apk-unsigned` — unsigned release APK (artifact)

Stáhni artifact ze záložky **Actions** na GitHubu (`Build Android APK` →
poslední běh → `Artifacts`), nebo spusť ručně přes **Run workflow**
(workflow_dispatch).

Release APK je **nepodepsaný** — pro instalaci na telefon stačí (Android
dovolí instalaci debug-signed i unsigned APK přes `adb install`, release
by potřeboval podpis pro distribuci přes Store, což zde neděláme).

### Lokální build (pokud bys měl Android Studio / JDK17 + SDK)

```bash
cd android
./gradlew assembleDebug    # nebo gradle assembleDebug bez wrapperu
```

## Instalace na telefon

```bash
adb install -r app-debug.apk
```

## První nastavení (bez dotyku na displeji jednoho ze Samsungů)

Appka čte konfiguraci z `SharedPreferences`, výchozí hodnoty jsou rozumné,
ale server URL / token / jméno zařízení je potřeba nastavit buď:

**A) Přes scrcpy + OTG myš** (doporučeno pro první nastavení) — spusť appku,
otevři nastavení (ikona ⚙ vpravo nahoře), vyplň:
- Server URL: `https://camera.samius.cz`
- Token: (token serveru)
- Jméno zařízení: např. `s20-a`, `s20fe-b` — musí se shodovat napříč
  heartbeaty, jinak se na ovládací stránce objeví jako nové zařízení
- Rozlišení/fps/bitrate podle potřeby
- Vyber "Nahrávat na SD kartu" (zaškrtnuto výchozí)

**B) Přes adb** (bez nutnosti se appky vůbec dotknout):

```bash
adb shell am start -n com.samius.multicam/.MainActivity \
  --es serverUrl "https://camera.samius.cz" \
  --es token "TOKEN_ZDE" \
  --es deviceName "s20-a" \
  --es resolution "1920x1080" \
  --es fps "30" \
  --es bitrateKbps "18000" \
  --es segmentMinutes "12"
```

Tyto hodnoty se uloží do `Config` (SharedPreferences) a appka je použije i
po dalším restartu — nemusí se posílat znovu, dokud je nechceš změnit.

## Doporučené nastavení telefonu

- **Baterie: Neomezeno** pro appku Multicam (Nastavení → Aplikace → Multicam →
  Baterie → Neomezeno) — jinak Samsung po čase uspí foreground service.
- **Mobilní data: vypnuto** nebo appka svázaná jen na Wi-Fi (appka se o to
  pokouší sama přes `bindProcessToNetwork`, ale vypnutá mobilní data jsou
  jistota).
- **Zámek obrazovky: Žádný** — `FLAG_KEEP_SCREEN_ON` + `showWhenLocked` displej
  nezhasne, ale pokud má telefon PIN/vzor, po probuzení by blokoval UI.
- Appka běží jako **foreground service** typu `camera`, takže přežije i
  zamčenou obrazovku / vypnutý displej (lze zatemnit tlačítkem 🌙 v appce).

## Launcher mód (spuštění bez dotyku po zapnutí)

`AndroidManifest.xml` má zakomentovaný `HOME` intent-filter pro
`MainActivity`. Pokud ho odkomentuješ a appku nastavíš jako výchozí
launcher, appka se spustí hned po zapnutí telefonu bez jakéhokoli dotyku.
**Pozor:** pak se k běžné ploše dostaneš jen přes dlouhý stisk Home nebo adb
(`adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME`),
takže to zapínej, až bude appka ověřená a stabilní.

`BootReceiver` navíc appku nastartuje i po `BOOT_COMPLETED`, nezávisle na
launcher módu — stačí, že appka byla po instalaci alespoň jednou spuštěna
(Android to vyžaduje kvůli "stopped state" ochraně).

## Známá omezení v této verzi (v0.1.0)

- Vyvážení bílé: Camera2 nemá přímou "Kelvin" kontrolu; appka při zapnutém
  ručním WB jen zamkne AWB (`CONTROL_AWB_LOCK`) po automatické konvergenci.
  Funguje to jako "zafixuj aktuální barvu", ne jako přesné zadání teploty.
- Segmentace souborů má krátkou mezeru (řádově 100–300 ms) mezi segmenty,
  protože CameraX `Recorder` nemá obdobu `MediaRecorder.setNextOutputFile()`.
  Podle handoff dokumentu je to přijatelné (zvuk se synchronizuje v Resolve).
- SAF fallback (ruční výběr složky přes tlačítko "Vybrat složku na SD kartě")
  je zatím jen uložení oprávnění — zápis videa stále jde přes MediaStore na
  removable volume. Pokud telefon SD kartu jako MediaStore volume nevidí,
  použij se primárně interní úložiště s varováním v popisu souboru na
  ovládací stránce serveru.
- Lokální REC tlačítko v appce je jen informativní — skutečné ovládání
  start/stop jde výhradně přes server (viz handoff: "ovládací stránka").

# Handoff: Vzdálené ovládání nahrávání koncertu z více telefonů

## Kontext

Nahrávám koncerty na 3 telefony a potřebuji je všechny ovládat ze svého mobilu, který mám u sebe:

- **iPhone**: běží na něm **Blackmagic Camera** v roli „Remote Camera“ a funguje.
- **Samsung Galaxy S20** a **Samsung Galaxy S20 FE**: Blackmagic Camera na nich v roli kamery **nefunguje**. Ovladač je najde podle jména, ale spojení se nenaváže (točí se kolečko). Platí to pro ovládání z iPhonu i ze Samsungu. Jako *ovladač* S20 funguje. Pravděpodobná příčina: řada S20 není oficiálně podporovaná (podpora začíná u S21).
- Na jednom ze Samsungů **nefunguje dotyková obrazovka**. Nastavuje se přes scrcpy nebo OTG myš.
- Síť: všechna zařízení se připojí k mému hotspotu, případně ke cestovnímu routeru. Mám k dispozici **vlastní server**.
- Záznam: FullHD nebo 4K, **12–50 GB na koncert**, délka až ~2,5 h.

## Cíl

Vytvořit vlastní řešení pro **Samsungy S20 / S20 FE**:

1. **Android aplikace** (kamera), která nahrává video **přímo na SD kartu** telefonu a reaguje na vzdálené příkazy (start, stop).
2. **Server** s jednoduchým API a **webovou ovládací stránkou**, kterou otevřu v prohlížeči na svém mobilu (bez instalace aplikace).
3. Na ovládací stránce vidím u každé kamery stav, náhled obrazu a tlačítka start/stop, jednotlivě i pro všechny.

iPhone **zůstává na Blackmagic Camera** a ovládám ho z aplikace Blackmagic. Do tohoto řešení ho nezahrnovat, ale protokol navrhnout tak, aby šel později doplnit nativní iOS klient.

## Architektura

### Server
- Drží **požadovaný stav** pro každé zařízení i globálně, např. `{ recording: true, cmdId: 42 }`.
- Přijímá **hlášení (heartbeat)** od zařízení: id, jméno, skutečný stav nahrávání, délka aktuálního záznamu, baterie (%), nabíjení, teplota baterie, thermal status, volné místo na SD kartě, rozlišení/fps/bitrate, čas posledního hlášení.
- Přijímá **náhledové snímky** (JPEG) a servíruje poslední snímek každého zařízení.
- Servíruje **ovládací stránku** (mobile-first, jedna stránka, bez buildu).
- Technologie: Node.js nebo Go, ideálně jeden soubor nebo malý projekt. Stav v paměti stačí.
- Musí běžet jak na mém serveru (přes internet, HTTPS), tak lokálně na notebooku v síti hotspotu (HTTP v LAN).
- Jednoduchá autentizace: sdílený token v hlavičce nebo v URL.

### Protokol (návrh, upravit dle potřeby)
- `GET /api/state?device=<id>` → `{ recording: bool, cmdId: int }`. Klient polluje každou ~1 s.
- `POST /api/report` → heartbeat (JSON viz výše).
- `POST /api/preview/<id>` → JPEG (~480p, kvalita ~50, každé 1–2 s).
- `GET /api/devices` → seznam zařízení se stavem (pro ovládací stránku).
- `POST /api/command` → `{ target: "<id>" | "all", recording: bool }`, zvýší `cmdId`.
- `cmdId` zajišťuje idempotenci: po výpadku spojení se příkaz neprovede dvakrát.
- Volitelně WebSocket místo pollingu. Polling je ale primární, protože je jednodušší a robustnější.

### Android aplikace (Kotlin, nativní)
- **Cílová zařízení:** Galaxy S20, S20 FE (Android 13). minSdk podle toho.
- **Kamera:** Camera2 nebo CameraX. Nahrávání + souběžné náhledové snímky (`VideoCapture` + `ImageAnalysis`, případně Camera2 s více surface). Ověřit, že kombinace na S20 funguje v cílovém rozlišení. Pokud ne, náhled v nižší frekvenci nebo jiným způsobem.
- **Ruční nastavení:** pevná expozice/ISO/závěrka, pevné ostření, pevné vyvážení bílé (koncertní světla). Nastavitelné v aplikaci a ideálně i ze serveru.
- **Výstup:** H.264 nebo HEVC MP4, rozlišení FullHD/4K, fps 25/30, **ručně nastavený bitrate** (~15–20 Mbps FullHD, ~50 Mbps 4K).
- **Zápis na SD kartu:** přes MediaStore s volume SD karty (`MediaStore.getExternalVolumeNames()`, `MediaStore.Video.Media.getContentUri(volume)`) nebo přes SAF (jednorázový výběr složky, uložené oprávnění). Fallback na interní úložiště s varováním.
- **Segmentace:** soubory po 10–15 min (konfigurovatelné). Preferovat bez mezery (`MediaRecorder.setNextOutputFile()`). Krátká mezera je přípustná, zvuk se bere z pultu a synchronizuje se v DaVinci Resolve.
- **Odolnost:** výpadek sítě **nesmí** zastavit nahrávání. Rozhoduje poslední známý požadovaný stav. Pád aplikace nesmí poškodit už zapsané segmenty.
- **Běh v popředí:** aplikace běží jako Activity v popředí, displej se nevypíná (`FLAG_KEEP_SCREEN_ON`), možnost zatemnit obrazovku (černý overlay nebo minimální jas). Foreground service s typem camera, pokud to pomůže stabilitě.
- **Spuštění bez dotyku:** aplikace se může nastavit jako **launcher** (intent filter `HOME` + `DEFAULT`), aby se spustila po zapnutí telefonu. `setShowWhenLocked(true)` a `setTurnScreenOn(true)`. Konfigurace (URL serveru, token, jméno zařízení, parametry videa) jde zadat i přes adb (např. `adb shell am start ... --es serverUrl ...`) nebo QR kód.
- **Heartbeat:** baterie, nabíjení, teplota (`BatteryManager`), `PowerManager.getCurrentThermalStatus()`, volné místo na SD, stav nahrávání, název aktuálního souboru.
- **Síť:** pozor na Samsung „přepnout na mobilní data“ u Wi-Fi bez internetu. Pro lokální server navázat provoz na Wi-Fi síť (`ConnectivityManager.bindProcessToNetwork` / `Network.openConnection`).
- **Lokální UI:** náhled, velké tlačítko REC, stav spojení, zbývající místo a čas, teplota.

### Ovládací stránka (web)
- Mřížka kamer: náhled (obnovovaný snímek), jméno, REC indikátor + délka záznamu, baterie, teplota/throttling, volné místo, stáří posledního heartbeatu (zčervená při výpadku > 5 s).
- Tlačítka: **Start vše / Stop vše**, start/stop pro jednotlivé kamery. Stop s potvrzením, aby nešlo o omyl.
- Ovládá se jednou rukou na mobilu, tmavý režim.

## Mimo rozsah
- Živý video stream (WebRTC). Stačí JPEG snímky.
- Nativní iOS klient (jen počítat s ním v protokolu).
- Reverse engineering protokolu Blackmagic Camera.

## Volitelný průzkum (nízká priorita, oddělit od hlavní práce)
1. **Proč S20 nefunguje jako Blackmagic Remote Camera:** `adb logcat | grep -i -E "blackmagic|camera|socket|ssl"` během pokusu o připojení. Možná to obejde jiné nastavení (rozlišení, codec).
2. **Emulace Blackmagic REST API:** Blackmagic má zdokumentované REST API pro své pro kamery (Pyxis, URSA Cine, Studio). Ověřit, zda Blackmagic Camera app ovládá Pyxis přes toto API a zda by se naše Android aplikace mohla vydávat za takovou kameru, takže by šla ovládat přímo z Blackmagic aplikace na iPhonu. Je to neověřená hypotéza. Náhled obrazu touto cestou pravděpodobně nebude.

## Akceptační kritéria
- Z prohlížeče na mobilu spustím a zastavím nahrávání na obou Samsunzích (jednotlivě i najednou) do ~2 s.
- Vidím náhled z každé kamery (obnova ≤ 2 s) a jejich stav.
- Video se ukládá na SD kartu v segmentech, 2,5 h ve 4K bez ztráty dat (pokud to zvládne teplota telefonu).
- Odpojení Wi-Fi na 1 min nepřeruší nahrávání. Po obnovení se stav zase synchronizuje.
- Samsung bez dotyku po zapnutí sám nastartuje aplikaci a připojí se k serveru.
- Návod: build a instalace APK přes adb, první nastavení přes scrcpy, spuštění serveru (lokálně i na serveru), doporučené nastavení telefonů (baterie Neomezeno, vypnuté mobilní data, zámek obrazovky Žádný).

## Poznámky
- Komunikace a dokumentace česky, kód a identifikátory anglicky.
- Postupovat po krocích: nejdřív server + ovládací stránka + jednoduchý testovací klient, pak Android aplikace (nejdřív nahrávání na SD, pak polling, pak náhledy, pak launcher a autostart).

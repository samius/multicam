# Multicam

Vzdálené ovládání nahrávání koncertu z více telefonů (Samsung Galaxy S20 / S20 FE),
protože Blackmagic Camera na nich jako kamera nefunguje.

Plný návrh a akceptační kritéria: [`handoff-multicam.md`](./handoff-multicam.md).

## Komponenty

1. **server/** — Node.js/Express server. Drží požadovaný stav kamer, přijímá
   heartbeaty a náhledy, servíruje ovládací stránku (`server/public/index.html`,
   jeden soubor, bez buildu).
2. **android/** — (TODO, další krok) nativní Kotlin appka pro S20/S20 FE:
   nahrává na SD kartu a pollí server na start/stop příkazy.

iPhone zůstává na Blackmagic Camera, do tohoto repa nepatří — protokol je ale
navržený tak, aby šel později doplnit nativní iOS klient (viz "Mimo rozsah"
v handoffu).

## Protokol (shrnutí)

- `GET /api/state?device=<id>` → `{ recording, cmdId }` — klient polluje ~1×/s
- `POST /api/report` → heartbeat (baterie, teplota, thermal status, volné
  místo, rozlišení/fps/bitrate, aktuální soubor, skutečný stav nahrávání)
- `POST /api/preview/<id>` → JPEG tělo (~480p), server uloží poslední snímek
- `GET /api/preview/<id>` → vrátí poslední JPEG
- `GET /api/devices` → seznam kamer se stavem (pro ovládací stránku)
- `POST /api/command` → `{ target: "<id>"|"all", recording: bool }`, vrátí
  nové `cmdId` — klient `cmdId` porovnává, aby příkaz neprovedl dvakrát po
  výpadku spojení

Auth: sdílený token, buď header `X-Auth-Token: <token>`, nebo `?token=<token>`
v URL (kvůli `<img src>` náhledům). Token se nastavuje přes env proměnnou
`MULTICAM_TOKEN`.

## Lokální spuštění (bez Dockeru)

```bash
cd server
npm install
MULTICAM_TOKEN=muj-tajny-token PORT=8787 node server.js
```

Otevři `http://localhost:8787/` v prohlížeči, zadej token.

### Rychlý test bez telefonu (curl)

```bash
TOKEN=muj-tajny-token
BASE=http://localhost:8787

# heartbeat
curl -s -X POST $BASE/api/report -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":"s20-a","name":"S20 vlevo","recording":false,"batteryPct":87,"charging":false,"batteryTempC":31.2,"thermalStatus":"NONE","freeSpaceBytes":64000000000,"resolution":"1920x1080","fps":30,"bitrateKbps":18000}'

# zjisti stav zařízení
curl -s "$BASE/api/devices" -H "X-Auth-Token: $TOKEN" | python3 -m json.tool

# pošli příkaz start
curl -s -X POST $BASE/api/command -H "X-Auth-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"target":"s20-a","recording":true}'

# klient si ověří požadovaný stav
curl -s "$BASE/api/state?device=s20-a" -H "X-Auth-Token: $TOKEN"
```

## Nasazení na server (Docker + nginx, camera.samius.cz)

```bash
sudo mkdir -p /var/www/multicam && sudo chown samius:samius /var/www/multicam
# zkopíruj obsah tohoto repa do /var/www/multicam
cd /var/www/multicam
echo "MULTICAM_TOKEN=nejaky-silny-token" > .env
docker compose up -d --build
curl -s http://127.0.0.1:8787/   # ověření lokálně
```

Dál nginx vhost (proxy_pass na 127.0.0.1:8787) + certbot pro
`camera.samius.cz`, viz interní skill `samius-vps-project-deploy`.

## Stav projektu

- [x] Server (stav, heartbeat, preview relay, API)
- [x] Ovládací webová stránka (mobile-first, tmavý režim, start/stop s potvrzením)
- [ ] Nasazení na camera.samius.cz
- [ ] Android aplikace (Kotlin) pro S20 / S20 FE

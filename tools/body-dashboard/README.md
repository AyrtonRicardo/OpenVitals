# Body dashboard

A static web page that shows what OpenVitals writes to the body measurements folder: weight, BMI,
body composition, tape measurements and progress photos. It has no dependencies, needs no build
step and makes no request outside the server it is served from.

## How the data gets there

1. In OpenVitals, open **Settings → Body profile → Body measurements folder** and pick a folder.
   A folder on the N100's SMB share works through a document provider app such as
   *CIFS Documents Provider*; a WebDAV or Nextcloud folder works through *RSAF*. The app itself
   has no network permission: the provider app does the network part.
2. Progress photos are saved there as they are added. While the share is unreachable they wait on
   the phone and are retried; **Save photos now** retries at once.
3. **Export measurements** writes `measurements.json` with the Health Connect body records
   (weight, height, body fat, lean mass, bone mass, body water) and the tape measurements.

The folder then holds:

```text
measurements.json   body records and tape measurements (format version 1)
photos.json         the saved progress photos, each with its local day
photos/             the full images, JPEG, without camera metadata
```

## Serve it on the N100

With Docker:

```bash
cd tools/body-dashboard
BODY_FOLDER=/srv/share/OpenVitals BIND_ADDRESS=192.168.1.20 docker compose up -d
```

Then open `http://192.168.1.20:8088/`. The folder is mounted read-only, and `nginx.conf` serves
only the page's four files, the two JSON files and `photos/*.jpg`, to home-network and Tailscale
addresses.

Without Docker, any static server works when the folder is reachable as `data/` beside
`index.html`:

```bash
ln -s /srv/share/OpenVitals tools/body-dashboard/data
python3 -m http.server 8088 --bind 192.168.1.20 --directory tools/body-dashboard
```

`?data=other/path/` makes the page read another folder on the same server.

## Reaching it from outside the home

The connection is behind CGNAT, so there is no port to forward, and none should be. Use a VPN:
install Tailscale (or WireGuard) on the N100 and the phone, and set `BIND_ADDRESS` to the
N100's Tailscale address (`100.x.y.z`) to serve the dashboard over the VPN only. Do not expose
it to the internet: the photos and measurements are health data and the page has no login.

## Tests

```bash
node --test tools/body-dashboard/test/dashboard.test.mjs
```

The fixtures in `test/fixtures` are the same files the app's `BodyFolderDocumentsTest` compares
its output with, so a change to the file format fails on both sides.

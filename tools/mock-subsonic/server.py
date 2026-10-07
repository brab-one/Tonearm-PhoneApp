#!/usr/bin/env python3
"""
A small Subsonic/OpenSubsonic server for testing Tonearm, with optional mutual TLS.

It generates everything it needs on first run (needs `openssl` and `ffmpeg`):
  * a private CA, a server certificate and client certificates (.p12, password "tonearm")
  * a few albums of FLAC tones, including 24-bit/96 kHz, with cover art and synced lyrics

Usage:
  python3 server.py [--data DIR] [--port 8443] [--host 0.0.0.0] [--no-mtls] [--san IP:10.0.2.2]

Log in with user "demo", password "demo". From the Android emulator the host is 10.0.2.2.

The same address also serves a mock Lidarr (API key "lidarr-key", under /api/v1/) so requests can be
tried, and a mock Maloja (API key "maloja-key", under /apis/mlj_1/) for the Tonearm server's one-time
import of a Maloja's history (MALOJA_URL=http://127.0.0.1:8444).
With --connect http://127.0.0.1:8790 and --plain-port 8444 it also stands in for the proxy in front of a
Tonearm server (run that with NAVIDROME_URL=http://127.0.0.1:8444).
Only Python's standard library is used.
"""

import argparse
import hashlib
import json
import os
import random
import re
import shutil
import ssl
import subprocess
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import urllib.error
import urllib.request
from urllib.parse import parse_qs, urlparse

USER, PASSWORD = "demo", "demo"
# Everyone who can log in; only USER is an admin (as getUser reports it).
USERS = {USER: PASSWORD, "guest": "guest"}
P12_PASSWORD = "tonearm"

ALBUMS = [
    # id, title, artist id, year, genre, bit depth, sample rate, track titles
    ("al1", "Hi-Res Tones", "ar1", 2024, "Electronic", 24, 96000, ["Low Hum", "Middle C", "Bright Sine", "Octave Up"]),
    ("al2", "CD Quality", "ar1", 2019, "Electronic", 16, 44100, ["Forty-Four", "One Kilohertz", "Two Kilohertz"]),
    ("al3", "Studio Masters", "ar2", 2022, "Ambient", 24, 192000, ["Deep Drone", "Shimmer"]),
]
ARTISTS = {"ar1": "The Oscillators", "ar2": "Signal Garden"}

# --- Maloja mock data: listening history (some artists aren't in the library) ---------------
MALOJA_KEY = "maloja-key"
MALOJA_RECENT = [("The Oscillators", 120), ("Vector Choir", 31)]             # last 30 days
MALOJA_ALLTIME = [("The Oscillators", 400), ("Signal Garden", 240), ("Vector Choir", 31), ("Old Favourite Band", 90)]
MALOJA_TOP_TRACKS = [(["The Oscillators"], "Low Hum", 40), (["The Oscillators"], "Middle C", 30)]
# Similar artists the "server agents" know about; names without an id aren't in the library.
SIMILAR = {"ar1": [("ar2", "Signal Garden"), (None, "Neon Cascade"), (None, "Kosmische Drift")],
           "ar2": [("ar1", "The Oscillators"), (None, "Vector Choir"), (None, "Neon Cascade")]}

# --- Lidarr mock data -------------------------------------------------------------------------
LIDARR_KEY = "lidarr-key"
LIDARR_CATALOG = [
    {"artistName": "Neon Cascade", "foreignArtistId": "mb-neon-cascade", "artistType": "Group", "disambiguation": "synthwave duo",
     "overview": "Neon Cascade make shimmering analogue synthwave.",
     "albums": [("Afterglow", "mb-afterglow", "2023-03-03"), ("Night Drive", "mb-night-drive", "2021-06-01"), ("Midnight Signal", "mb-midnight-signal", "2022-11-11")]},
    {"artistName": "Vector Choir", "foreignArtistId": "mb-vector-choir", "artistType": "Group", "disambiguation": "",
     "overview": "Vector Choir layer vocoded voices over ambient drones.", "albums": [("Choral Static", "mb-choral-static", "2024-10-10")]},
    {"artistName": "Kosmische Drift", "foreignArtistId": "mb-kosmische", "artistType": "Person", "disambiguation": "German producer",
     "overview": "Long-form kosmische musik.", "albums": [("Orbit I", "mb-orbit-1", "2019-01-20")]},
    {"artistName": "Signal Garden", "foreignArtistId": "mb-signal-garden", "artistType": "Group", "disambiguation": "",
     "overview": "Signal Garden grow pure tones.", "albums": [("Studio Masters", "mb-studio-masters", "2022-02-02")]},
    {"artistName": "Aurora Lattice", "foreignArtistId": "mb-aurora-lattice", "artistType": "Group", "disambiguation": "",
     "overview": "Crystalline ambient.", "albums": [("Prism Fields", "mb-prism-fields", "2025-05-05")]},
]
# Track lists Lidarr's metadata has for these albums (others list just their title track), and album types besides "Album".
LIDARR_TRACKS = {"mb-afterglow": ["Afterglow", "Midnight Signal", "Chrome Rain"], "mb-night-drive": ["Night Drive", "Tail Lights"],
                 "mb-midnight-signal": ["Midnight Signal"]}
LIDARR_ALBUM_TYPES = {"mb-midnight-signal": "Single"}
# Brainarr (an AI import list plugin) as Lidarr reports it; its runs add these artists.
BRAINARR_LIST_ID = 7
BRAINARR_TAG = {"id": 1, "label": "brainarr"}
BRAINARR_ADDS = [("mb-neon-cascade", False), ("mb-aurora-lattice", True)]  # (artist, monitored)
LYRICS = [(0, "This is a synced lyric line"), (4000, "The tone keeps going"), (8000, "Watch the highlight move"),
          (12000, "Tap a line to seek"), (16000, "That's all, folks")]


def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def make_certs(d: Path, sans: list[str]):
    if (d / "client.p12").exists():
        return
    d.mkdir(parents=True, exist_ok=True)
    o = lambda *a: run(["openssl", *a])
    o("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3650", "-subj", "/CN=Tonearm Mock CA",
      "-keyout", str(d / "ca.key"), "-out", str(d / "ca.crt"),
      "-addext", "basicConstraints=critical,CA:TRUE", "-addext", "keyUsage=critical,keyCertSign,cRLSign")
    (d / "server.ext").write_text("subjectAltName=" + ",".join(sans) + "\nextendedKeyUsage=serverAuth\n")
    o("req", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=mock-subsonic", "-keyout", str(d / "server.key"), "-out", str(d / "server.csr"))
    o("x509", "-req", "-in", str(d / "server.csr"), "-CA", str(d / "ca.crt"), "-CAkey", str(d / "ca.key"), "-CAcreateserial",
      "-days", "825", "-extfile", str(d / "server.ext"), "-out", str(d / "server.crt"))
    (d / "client.ext").write_text("extendedKeyUsage=clientAuth\n")
    o("req", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:P-256", "-nodes", "-subj", "/CN=tonearm-listener",
      "-keyout", str(d / "client.key"), "-out", str(d / "client.csr"))
    o("x509", "-req", "-in", str(d / "client.csr"), "-CA", str(d / "ca.crt"), "-CAkey", str(d / "ca.key"), "-CAcreateserial",
      "-days", "825", "-extfile", str(d / "client.ext"), "-out", str(d / "client.crt"))
    # Modern (OpenSSL 3 default, AES-256) and legacy (3DES/RC2) encodings, to test both on-device.
    o("pkcs12", "-export", "-inkey", str(d / "client.key"), "-in", str(d / "client.crt"), "-certfile", str(d / "ca.crt"),
      "-name", "tonearm-listener", "-passout", f"pass:{P12_PASSWORD}", "-out", str(d / "client.p12"))
    o("pkcs12", "-export", "-legacy", "-inkey", str(d / "client.key"), "-in", str(d / "client.crt"), "-certfile", str(d / "ca.crt"),
      "-name", "tonearm-listener", "-passout", f"pass:{P12_PASSWORD}", "-out", str(d / "client-legacy.p12"))


def make_media(d: Path):
    d.mkdir(parents=True, exist_ok=True)
    for album_id, _, _, _, _, depth, rate, tracks in ALBUMS:
        cover = d / f"{album_id}.jpg"
        if not cover.exists():
            pattern = {"al1": "testsrc2", "al2": "smptehdbars", "al3": "mandelbrot"}[album_id]
            run(["ffmpeg", "-y", "-f", "lavfi", "-i", f"{pattern}=s=800x800", "-frames:v", "1", str(cover)])
        for n, _ in enumerate(tracks, 1):
            f = d / f"{album_id}-{n}.flac"
            if f.exists():
                continue
            freq = 110 * (2 ** ((n * 5 + len(album_id)) / 12))
            fmt = "s32" if depth == 24 else "s16"
            run(["ffmpeg", "-y", "-f", "lavfi", "-i", f"sine=frequency={freq:.1f}:sample_rate={rate}:duration={40 + n * 5}",
                 "-ac", "2", "-sample_fmt", fmt, "-bits_per_raw_sample", str(depth), "-c:a", "flac", str(f)])


class Library:
    def __init__(self, media: Path):
        self.media = media
        self.songs, self.albums = {}, {}
        self.starred: dict[str, str] = {}
        self.playlists: dict[str, dict] = {}
        self.plays: dict[str, int] = {}
        self.maloja_scrobbles: list[dict] = []
        self.lidarr_artists: dict[str, dict] = {}
        self.lidarr_tags: list[dict] = []
        self.brainarr_tags: list[int] = []
        self.lidarr_commands: dict[int, dict] = {}
        self.lidarr_albums: dict[str, dict] = {}
        self.lidarr_queue: list[dict] = []
        self.lock = threading.Lock()
        for album_id, title, artist_id, year, genre, depth, rate, tracks in ALBUMS:
            songs = []
            for n, track_title in enumerate(tracks, 1):
                path = media / f"{album_id}-{n}.flac"
                duration = 40 + n * 5
                song = {
                    "id": f"{album_id}-{n}", "parent": album_id, "isDir": False, "title": track_title, "album": title,
                    "artist": ARTISTS[artist_id], "track": n, "discNumber": 1, "year": year, "genre": genre,
                    "coverArt": album_id, "size": path.stat().st_size, "contentType": "audio/flac", "suffix": "flac",
                    "duration": duration, "bitRate": int(path.stat().st_size * 8 / duration / 1000),
                    "bitDepth": depth, "samplingRate": rate, "channelCount": 2, "albumId": album_id,
                    "artistId": artist_id, "type": "music", "path": f"{ARTISTS[artist_id]}/{title}/{n:02d} {track_title}.flac",
                    "replayGain": {"trackGain": -3.0 - n, "albumGain": -4.5, "trackPeak": 0.9, "albumPeak": 0.95},
                }
                self.songs[song["id"]] = song
                songs.append(song)
            self.albums[album_id] = {
                "id": album_id, "name": title, "artist": ARTISTS[artist_id], "artistId": artist_id, "coverArt": album_id,
                "songCount": len(songs), "duration": sum(s["duration"] for s in songs), "year": year, "genre": genre,
                "created": f"{year}-01-01T00:00:00Z", "genres": [{"name": genre}], "_songs": songs,
            }
        self.playlists["pl1"] = {"id": "pl1", "name": "Mock Favorites", "owner": USER, "public": False,
                                 "songs": ["al1-2", "al3-1", "al2-1"], "created": "2024-01-01T00:00:00Z"}

    def arrive(self, title: str, artist: str, album: str):
        """A song "downloaded by Lidarr": a new album with one song (the audio of al1-1)."""
        artist_id = next((k for k, v in ARTISTS.items() if v.lower() == artist.lower()), None) or f"arn{len(ARTISTS)}"
        ARTISTS.setdefault(artist_id, artist)
        album_id = f"new{len(self.albums)}"
        path = self.media / f"{album_id}-1.flac"
        shutil.copy(self.media / "al1-1.flac", path)
        song = dict(self.songs["al1-1"])
        song.update({"id": f"{album_id}-1", "parent": album_id, "title": title, "album": album, "artist": artist, "artistId": artist_id,
                     "albumId": album_id, "track": 1, "path": f"{artist}/{album}/01 {title}.flac"})
        self.songs[song["id"]] = song
        self.albums[album_id] = {
            "id": album_id, "name": album, "artist": artist, "artistId": artist_id, "coverArt": "al1", "songCount": 1,
            "duration": song["duration"], "year": 2026, "genre": "Rock", "created": now(), "genres": [{"name": "Rock"}], "_songs": [song],
        }
        return song

    def album(self, a):
        out = {k: v for k, v in a.items() if not k.startswith("_")}
        if a["id"] in self.starred:
            out["starred"] = self.starred[a["id"]]
        return out

    def song(self, s):
        out = dict(s)
        if s["id"] in self.starred:
            out["starred"] = self.starred[s["id"]]
        out["playCount"] = self.plays.get(s["id"], 0)
        return out

    def artist(self, artist_id, with_albums=False):
        albums = [a for a in self.albums.values() if a["artistId"] == artist_id]
        out = {"id": artist_id, "name": ARTISTS[artist_id], "coverArt": albums[0]["coverArt"], "albumCount": len(albums)}
        if artist_id in self.starred:
            out["starred"] = self.starred[artist_id]
        if with_albums:
            out["album"] = [self.album(a) for a in albums]
        return out

    def playlist(self, p, with_songs=False):
        songs = [self.songs[i] for i in p["songs"] if i in self.songs]
        out = {"id": p["id"], "name": p["name"], "comment": p.get("comment"), "owner": p["owner"], "public": p["public"], "songCount": len(songs),
               "duration": sum(s["duration"] for s in songs), "created": p["created"], "changed": p["created"],
               "coverArt": songs[0]["coverArt"] if songs else None}
        if with_songs:
            out["entry"] = [self.song(s) for s in songs]
        return out


def now():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class Handler(BaseHTTPRequestHandler):
    no_ranges = False
    stream_kbps = 0
    connect_url = None
    lib: Library = None
    server_version = "MockSubsonic/1.0"

    def log_message(self, fmt, *args):
        cert = self.connection.getpeercert() if isinstance(self.connection, ssl.SSLSocket) else None
        who = dict(x[0] for x in cert["subject"]).get("commonName") if cert else "-"
        sys.stdout.write(f"[{time.strftime('%H:%M:%S')}] client-cert={who} {fmt % args}\n")
        sys.stdout.flush()

    def send_json(self, payload, status="ok"):
        body = {"subsonic-response": {"status": status, "version": "1.16.1", "type": "mock", "serverVersion": "1.0",
                                      "openSubsonic": True, **payload}}
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def error(self, code, message):
        self.send_json({"error": {"code": code, "message": message}}, status="failed")

    def forward_connect(self, method):
        # Like the reverse proxy in front of Navidrome: /connect-tonearm goes to the Tonearm server.
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length else None
        request = urllib.request.Request(self.connect_url.rstrip("/") + self.path, data=body, method=method,
                                         headers={"Content-Type": self.headers.get("Content-Type") or "text/plain",
                                                  "X-Real-IP": self.client_address[0]})
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                status, data, kind = response.status, response.read(), response.headers.get("Content-Type")
        except urllib.error.HTTPError as e:
            status, data, kind = e.code, e.read(), e.headers.get("Content-Type")
        except OSError as e:
            status, data, kind = 502, f"Tonearm server unreachable: {e}".encode(), "text/plain"
        self.send_response(status)
        self.send_header("Content-Type", kind or "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        url = urlparse(self.path)
        if self.connect_url and url.path.startswith("/connect-tonearm"):
            return self.forward_connect("POST")
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        with self.lib.lock:
            if url.path.startswith("/apis/mlj_1/"):
                return self.maloja_post(url.path[len("/apis/mlj_1/"):], body)
            if url.path.startswith("/api/v1/"):
                if self.headers.get("X-Api-Key") != LIDARR_KEY:
                    return self.send_raw({"message": "Unauthorized"}, 401)
                return self.lidarr_post(url.path[len("/api/v1/"):], body)
        self.send_response(404)
        self.end_headers()

    def do_PUT(self):
        url = urlparse(self.path)
        if self.connect_url and url.path.startswith("/connect-tonearm"):
            return self.forward_connect("PUT")
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        with self.lib.lock:
            if url.path.startswith("/api/v1/"):
                if self.headers.get("X-Api-Key") != LIDARR_KEY:
                    return self.send_raw({"message": "Unauthorized"}, 401)
                return self.lidarr_put(url.path[len("/api/v1/"):], body)
        self.send_response(404)
        self.end_headers()

    def do_DELETE(self):
        url = urlparse(self.path)
        if self.connect_url and url.path.startswith("/connect-tonearm"):
            return self.forward_connect("DELETE")
        with self.lib.lock:
            album = re.match(r"^/api/v1/album/(\d+)$", url.path)
            if album and self.headers.get("X-Api-Key") == LIDARR_KEY:
                for mbid, state in list(self.lib.lidarr_albums.items()):
                    if state["id"] == int(album.group(1)):
                        state["monitored"] = False
                        print(f"    lidarr: removed album {mbid} ({url.query})", flush=True)
                        return self.send_raw({})
            m = re.match(r"^/api/v1/artist/(\d+)$", url.path)
            if m and self.headers.get("X-Api-Key") == LIDARR_KEY:
                entry = self.lidarr_entry(int(m.group(1)))
                if entry:
                    del self.lib.lidarr_artists[entry["foreignArtistId"]]
                    for _, mbid, _ in entry["albums"]:
                        self.lib.lidarr_albums.pop(mbid, None)
                    print(f"    lidarr: removed artist {entry['artistName']} ({url.query})", flush=True)
                    return self.send_raw({})
        self.send_response(404)
        self.end_headers()

    def send_raw(self, payload, status=200):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        url = urlparse(self.path)
        if self.connect_url and url.path.startswith("/connect-tonearm"):
            return self.forward_connect("GET")
        q = {k: v for k, v in parse_qs(url.query).items()}
        one = lambda k, d=None: q.get(k, [d])[0]
        if url.path == "/mock/arrive":
            # Test hook: a song shows up in the library, as if Lidarr had downloaded it.
            with self.lib.lock:
                song = self.lib.arrive(one("title", "Untitled"), one("artist", "Unknown"), one("album") or one("title", "Untitled"))
            print(f"    arrived: {song['artist']} - {song['title']} ({song['id']})", flush=True)
            return self.send_raw(song, 200)
        if url.path == "/mock/replace":
            # Test hook: a song's file is replaced by a better one under the same id, like a Lidarr upgrade.
            with self.lib.lock:
                song = self.lib.songs[one("id")]
                source = self.lib.songs[one("from", "al1-1")]
                shutil.copy(self.lib.media / f"{source['id']}.flac", self.lib.media / f"{song['id']}.flac")
                size = (self.lib.media / f"{song['id']}.flac").stat().st_size
                song.update({"size": size, "suffix": "flac", "bitDepth": source["bitDepth"], "samplingRate": source["samplingRate"],
                             "duration": source["duration"], "bitRate": source["bitRate"], "contentType": "audio/flac"})
            print(f"    replaced: {song['id']} now {song['bitDepth']}-bit/{song['samplingRate']} Hz, {size} bytes", flush=True)
            return self.send_raw(song, 200)
        if url.path.startswith("/mock-images/"):
            name = Path(url.path).name
            return self.send_file(self.lib.media / name, "image/jpeg")
        if url.path.startswith("/apis/mlj_1/"):
            with self.lib.lock:
                return self.maloja_get(url.path[len("/apis/mlj_1/"):], one)
        if url.path.startswith("/api/v1/"):
            if self.headers.get("X-Api-Key") != LIDARR_KEY:
                return self.send_raw({"message": "Unauthorized"}, 401)
            with self.lib.lock:
                return self.lidarr_get(url.path[len("/api/v1/"):], one)
        m = re.match(r"^/rest/(\w+?)(?:\.view)?$", url.path)
        if not m:
            self.send_response(404)
            self.end_headers()
            return
        if not self.authenticate(one):
            return
        method = m.group(1)
        handler = getattr(self, "api_" + method, None)
        if handler is None:
            return self.error(0, f"Unsupported method {method}")
        if method in ("stream", "download", "getCoverArt"):
            # Long-running transfers only read immutable data; don't block the API behind them.
            return handler(q, one)
        with self.lib.lock:
            handler(q, one)

    def authenticate(self, one):
        password = USERS.get(one("u"))
        if password is None:
            self.error(40, "Wrong username or password")
            return False
        if one("t") and one("s"):
            ok = hashlib.md5((password + one("s")).encode()).hexdigest() == one("t")
        elif one("p"):
            p = one("p")
            ok = (bytes.fromhex(p[4:]).decode() if p.startswith("enc:") else p) == password
        else:
            ok = False
        if not ok:
            self.error(40, "Wrong username or password")
        return ok

    # --- API -----------------------------------------------------------------
    def api_ping(self, q, one):
        self.send_json({})

    def api_startScan(self, q, one):
        print("    navidrome: scan started", flush=True)
        self.send_json({"scanStatus": {"scanning": True, "count": len(self.lib.songs)}})

    def api_getUser(self, q, one):
        name = one("username") or one("u")
        if name != one("u") and one("u") != USER:
            return self.error(50, "Only admins can look up other users")
        self.send_json({"user": {"username": name, "adminRole": name == USER, "streamRole": True}})

    def api_getOpenSubsonicExtensions(self, q, one):
        self.send_json({"openSubsonicExtensions": [{"name": "songLyrics", "versions": [1]}, {"name": "formPost", "versions": [1]}]})

    def api_getArtists(self, q, one):
        if not ARTISTS:
            return self.error(70, "Library not found or empty")  # what Navidrome answers for an empty library
        index = {}
        for aid in ARTISTS:
            index.setdefault(ARTISTS[aid].removeprefix("The ")[0].upper(), []).append(self.lib.artist(aid))
        self.send_json({"artists": {"ignoredArticles": "The", "index": [{"name": k, "artist": v} for k, v in sorted(index.items())]}})

    def api_getArtist(self, q, one):
        aid = one("id")
        if aid not in ARTISTS:
            return self.error(70, "Artist not found")
        self.send_json({"artist": self.lib.artist(aid, with_albums=True)})

    def api_getArtistInfo2(self, q, one):
        include_missing = one("includeNotPresent") == "true"
        similar = []
        for aid, name in SIMILAR.get(one("id"), []):
            if aid:
                similar.append(self.lib.artist(aid))
            elif include_missing:
                similar.append({"id": "-1", "name": name})   # Navidrome's id for similar artists you don't have
        self.send_json({"artistInfo2": {"biography": f"<p>{ARTISTS.get(one('id'), '')} make pure tones for testing.</p> "
                                                     "<a href='https://last.fm'>Read more on Last.fm</a>",
                                        "similarArtist": similar}})

    # --- Maloja ----------------------------------------------------------------------------
    def maloja_get(self, endpoint, one):
        if endpoint == "test":
            key = one("key")
            if key is not None and key != MALOJA_KEY:
                return self.send_raw({"status": "error", "error": "Wrong API key"}, 403)
            return self.send_raw({"status": "ok"})
        if endpoint == "serverinfo":
            return self.send_raw({"name": "Mock Maloja", "version": ["3", "2", "2"], "versionstring": "3.2.2", "db_status": {"healthy": True}})
        since = one("since")
        recent = since is not None
        if endpoint == "charts/artists":
            rows = MALOJA_RECENT if recent else MALOJA_ALLTIME
            lst = [{"scrobbles": n, "real_scrobbles": n, "artist": a, "artist_id": i, "associated_artists": [], "rank": i + 1}
                   for i, (a, n) in enumerate(rows)]
            return self.send_raw({"status": "ok", "list": lst[:int(one("max", 1000))]})
        if endpoint == "charts/tracks":
            lst = [{"scrobbles": n, "track": {"artists": a, "title": t, "album": None, "length": 60}, "track_id": i, "rank": i + 1}
                   for i, (a, t, n) in enumerate(MALOJA_TOP_TRACKS)]
            return self.send_raw({"status": "ok", "list": lst})
        if endpoint == "scrobbles":
            lst = [{"time": int(time.time()) - 600 * i, "track": {"artists": a, "title": t, "album": None, "length": 60}, "duration": 60}
                   for i, (a, t, _) in enumerate(MALOJA_TOP_TRACKS[:1])] + self.lib.maloja_scrobbles[::-1]
            return self.send_raw({"status": "ok", "list": lst, "pagination": {"page": 0}})
        if endpoint == "numscrobbles":
            return self.send_raw({"status": "ok", "amount": (151 if recent else 761) + len(self.lib.maloja_scrobbles)})
        self.send_raw({"status": "error", "error": f"unknown endpoint {endpoint}"}, 404)

    def maloja_post(self, endpoint, body):
        if endpoint != "newscrobble":
            return self.send_raw({"status": "error"}, 404)
        if body.get("key") != MALOJA_KEY:
            return self.send_raw({"status": "error", "error": "Wrong API key"}, 403)
        track = {"artists": body.get("artists", []), "title": body.get("title"), "album": None, "length": body.get("length")}
        self.lib.maloja_scrobbles.append({"time": body.get("time"), "track": track, "duration": body.get("duration")})
        print(f"    maloja scrobble: {track['artists']} - {track['title']} ({body.get('duration')}s listened)", flush=True)
        self.send_raw({"status": "success", "track": {"artists": track["artists"], "title": track["title"]}})

    # --- Lidarr ----------------------------------------------------------------------------
    def lidarr_artist(self, entry):
        added = self.lib.lidarr_artists.get(entry["foreignArtistId"])
        image = f"https://{self.headers.get('Host')}/mock-images/al{1 + LIDARR_CATALOG.index(entry) % 3}.jpg"
        out = {"id": added["id"] if added else 0, "artistName": entry["artistName"], "foreignArtistId": entry["foreignArtistId"],
               "artistType": entry["artistType"], "disambiguation": entry["disambiguation"], "overview": entry["overview"],
               "images": [{"coverType": "poster", "url": "/MediaCover/1/poster.jpg", "remoteUrl": image}], "remotePoster": image,
               "genres": ["Electronic", "Ambient"], "status": "continuing"}
        if added:
            # Like real Lidarr: library artists' remoteUrl is a path on the Lidarr host, not a URL.
            out["images"] = [{"coverType": "poster", "url": f"/MediaCover/{added['id']}/poster.jpg", "remoteUrl": f"/config/MediaCover/{added['id']}/poster.jpg"}]
            del out["remotePoster"]
            tracks = 10 * len(entry["albums"])
            monitored = added.get("monitored", True)
            out.update(monitored=monitored, added=added.get("added", "2026-01-01T00:00:00Z"), tags=added.get("tags", []),
                       statistics={"albumCount": len(entry["albums"]), "trackFileCount": tracks if added.get("onDisk") else 0,
                                   "trackCount": tracks if monitored else 0, "totalTrackCount": tracks, "percentOfTracks": 100.0 if added.get("onDisk") else 0.0})
        return out

    def add_lidarr_artist(self, mbid, tags=(), monitored=True, days_ago=0.0, on_disk=False):
        when = datetime.now(timezone.utc) - timedelta(days=days_ago)
        self.lib.lidarr_artists[mbid] = {"id": len(self.lib.lidarr_artists) + 1, "tags": list(tags), "monitored": monitored,
                                         "added": when.strftime("%Y-%m-%dT%H:%M:%SZ"), "onDisk": on_disk}

    def brainarr_list(self):
        fields = [("provider", "ollama"), ("configurationUrl", "http://localhost:11434"), ("modelSelection", "qwen2.5:latest"),
                  ("maxRecommendations", 10), ("discoveryMode", "adjacent"), ("recommendationMode", "specificAlbums")]
        return {"id": BRAINARR_LIST_ID, "name": "Brainarr", "implementation": "Brainarr", "implementationName": "Brainarr AI Music Discovery",
                "configContract": "BrainarrSettings", "listType": "program", "enableAutomaticAdd": True, "shouldMonitor": "specificAlbum",
                "rootFolderPath": "/music", "qualityProfileId": 2, "metadataProfileId": 1, "tags": list(self.lib.brainarr_tags),
                "fields": [{"name": n, "value": v} for n, v in fields]}

    def run_command(self, command):
        """Finishes a command a few seconds after it started, like a slow AI model would."""
        if command["status"] == "completed" or time.time() - command["_started"] < 4:
            return
        if command["name"] == "ImportListSync":
            added = 0
            for mbid, monitored in BRAINARR_ADDS:
                if mbid not in self.lib.lidarr_artists:
                    self.add_lidarr_artist(mbid, tags=self.lib.brainarr_tags, monitored=monitored)
                    added += 1
                    entry = next(e for e in LIDARR_CATALOG if e["foreignArtistId"] == mbid)
                    if monitored:
                        self.queue_albums(entry)
            print(f"    lidarr: Brainarr run added {added} artists", flush=True)
            command["message"] = f"Import List Sync Completed. Items found: {len(BRAINARR_ADDS)}, Artists added: {added}, Albums added: {added}"
        command["status"] = "completed"

    def queue_albums(self, entry):
        for title, _, _ in entry["albums"]:
            self.lib.lidarr_queue.append({"title": f"{entry['artistName']} - {title}", "artist": entry["artistName"],
                                          "artistMbid": entry["foreignArtistId"], "album": title, "started": time.time()})

    def lidarr_put(self, endpoint, body):
        if endpoint == "album/monitor":
            changed = []
            for state in self.lib.lidarr_albums.values():
                if state["id"] in body.get("albumIds", []):
                    state["monitored"] = bool(body.get("monitored"))
                    changed.append(state["id"])
            print(f"    lidarr: albums {changed} monitored={body.get('monitored')}", flush=True)
            return self.send_raw([], 202)
        if endpoint == f"importlist/{BRAINARR_LIST_ID}":
            self.lib.brainarr_tags = [int(t) for t in body.get("tags", [])]
            print(f"    lidarr: Brainarr list tags = {self.lib.brainarr_tags}", flush=True)
            return self.send_raw(self.brainarr_list(), 202)
        self.send_raw({"message": f"unknown endpoint {endpoint}"}, 404)

    def lidarr_album(self, entry, album):
        title, mbid, date = album
        image = f"https://{self.headers.get('Host')}/mock-images/al{1 + LIDARR_CATALOG.index(entry) % 3}.jpg"
        state = self.lib.lidarr_albums.get(mbid, {})
        return {"id": state.get("id", 0), "title": title, "foreignAlbumId": mbid, "monitored": state.get("monitored", False),
                "artistId": self.lib.lidarr_artists.get(entry["foreignArtistId"], {}).get("id", 0),
                "albumType": LIDARR_ALBUM_TYPES.get(mbid, "Album"), "secondaryTypes": [], "releaseDate": date + "T00:00:00Z", "remoteCover": image,
                "images": [{"coverType": "cover", "remoteUrl": image}], "artist": self.lidarr_artist(entry)}

    def register_albums(self, entry, monitored=False):
        """Lidarr knows every album of an artist it has, monitored or not."""
        for _, mbid, _ in entry["albums"]:
            state = self.lib.lidarr_albums.setdefault(mbid, {"id": 1 + max([a["id"] for a in self.lib.lidarr_albums.values()] or [0])})
            state.setdefault("monitored", monitored)

    def lidarr_entry(self, artist_id):
        return next((e for e in LIDARR_CATALOG if self.lib.lidarr_artists.get(e["foreignArtistId"], {}).get("id") == artist_id), None)

    def lidarr_get(self, endpoint, one):
        term = (one("term") or "").lower()
        if endpoint == "system/status":
            return self.send_raw({"appName": "Lidarr", "instanceName": "Lidarr", "version": "2.9.6.4552"})
        if endpoint == "rootfolder":
            return self.send_raw([{"id": 1, "path": "/music", "name": "Music", "defaultQualityProfileId": 2,
                                   "defaultMetadataProfileId": 1, "freeSpace": 2_400_000_000_000, "accessible": True}])
        if endpoint == "qualityprofile":
            return self.send_raw([{"id": 1, "name": "Any"}, {"id": 2, "name": "Lossless"}])
        if endpoint == "metadataprofile":
            return self.send_raw([{"id": 1, "name": "Standard"}])
        if endpoint == "artist":
            return self.send_raw([self.lidarr_artist(e) for e in LIDARR_CATALOG if e["foreignArtistId"] in self.lib.lidarr_artists])
        if endpoint == "album" and one("artistId"):
            entry = self.lidarr_entry(int(one("artistId")))
            if entry:
                self.register_albums(entry)
            return self.send_raw([self.lidarr_album(entry, a) for a in entry["albums"]] if entry else [])
        if endpoint == "wanted/missing":
            # Monitored albums Lidarr hasn't got yet.
            records = [self.lidarr_album(e, a) for e in LIDARR_CATALOG for a in e["albums"] if self.lib.lidarr_albums.get(a[1], {}).get("monitored")]
            return self.send_raw({"page": 1, "pageSize": len(records), "totalRecords": len(records), "records": records})
        if endpoint == "album/lookup":
            # Lidarr's metadata knows the catalog's albums; "artist album" finds one.
            return self.send_raw([self.lidarr_album(e, a) for e in LIDARR_CATALOG for a in e["albums"]
                                  if a[0].lower() in term and e["artistName"].lower() in term])
        if endpoint == "track":
            entry = self.lidarr_entry(int(one("artistId") or 0))
            if not entry:
                return self.send_raw([])
            self.register_albums(entry)
            tracks = []
            for title, mbid, _ in entry["albums"]:
                for name in LIDARR_TRACKS.get(mbid, [title]):
                    tracks.append({"id": len(tracks) + 1, "title": name, "albumId": self.lib.lidarr_albums[mbid]["id"], "artistId": int(one("artistId"))})
            return self.send_raw(tracks)
        if endpoint == "importlist":
            return self.send_raw([self.brainarr_list()])
        if endpoint == "tag":
            return self.send_raw(self.lib.lidarr_tags)
        if endpoint.startswith("command/"):
            command = self.lib.lidarr_commands.get(int(endpoint.split("/")[1]))
            if not command:
                return self.send_raw({"message": "NotFound"}, 404)
            self.run_command(command)
            return self.send_raw({k: v for k, v in command.items() if not k.startswith("_")})
        if endpoint.startswith("mediacover/artist/"):
            return self.send_file(self.lib.media / "al2.jpg", "image/jpeg")
        if endpoint == "artist/lookup":
            hits = [e for e in LIDARR_CATALOG if term in e["artistName"].lower()]
            if not hits and term:
                # Any other artist "exists" too, so requests for real names (e.g. from YouTube Music) can be tried.
                name = one("term").strip()
                slug = re.sub(r"[^a-z0-9]+", "-", term).strip("-")
                hits = [{"artistName": name, "foreignArtistId": f"mb-{slug}", "artistType": "Group", "disambiguation": "",
                         "overview": f"{name} (made up by the mock).", "albums": [("Greatest Hits", f"mb-{slug}-hits", "2020-01-01")]}]
                LIDARR_CATALOG.extend(hits)
            return self.send_raw([self.lidarr_artist(e) for e in hits])
        if endpoint == "search":
            out = []
            for e in LIDARR_CATALOG:
                if term in e["artistName"].lower():
                    out.append({"foreignId": e["foreignArtistId"], "artist": self.lidarr_artist(e)})
                for album in e["albums"]:
                    if term in album[0].lower() or term in e["artistName"].lower():
                        out.append({"foreignId": album[1], "album": self.lidarr_album(e, album)})
            return self.send_raw(out)
        if endpoint == "queue":
            records = []
            for i, item in enumerate(self.lib.lidarr_queue):
                elapsed = time.time() - item["started"]
                size = 320_000_000.0
                left = max(0.0, size * (1 - elapsed / 90))
                if left > 0:
                    artist_id = self.lib.lidarr_artists.get(item.get("artistMbid"), {}).get("id")
                    records.append({"id": i + 1, "title": item["title"], "status": "downloading", "trackedDownloadState": "downloading",
                                    "size": size, "sizeleft": left, "timeleft": f"00:{int(left / size * 90) // 60:02d}:{int(left / size * 90) % 60:02d}",
                                    "artistId": artist_id, "artist": {"id": artist_id or 0, "artistName": item["artist"]}, "album": {"title": item["album"]}})
            return self.send_raw({"page": 1, "pageSize": 50, "totalRecords": len(records), "records": records})
        self.send_raw({"message": f"unknown endpoint {endpoint}"}, 404)

    def lidarr_post(self, endpoint, body):
        if endpoint == "tag":
            tag = {"id": len(self.lib.lidarr_tags) + 1, "label": body.get("label", "").lower()}
            self.lib.lidarr_tags.append(tag)
            return self.send_raw(tag, 201)
        if endpoint == "command":
            command = {"id": len(self.lib.lidarr_commands) + 1, "name": body.get("name"), "status": "started", "_started": time.time(),
                       "message": "Starting Import List Refresh for List Brainarr" if body.get("name") == "ImportListSync" else None,
                       "started": now(), "body": body}
            self.lib.lidarr_commands[command["id"]] = command
            print(f"    lidarr: command {body}", flush=True)
            if body.get("name") == "AlbumSearch":
                for e in LIDARR_CATALOG:
                    for title, mbid, _ in e["albums"]:
                        if self.lib.lidarr_albums.get(mbid, {}).get("id") in body.get("albumIds", []):
                            self.lib.lidarr_queue.append({"title": f"{e['artistName']} - {title}", "artist": e["artistName"],
                                                          "artistMbid": e["foreignArtistId"], "album": title, "started": time.time()})
                command["_started"] = 0
            if body.get("name") == "ArtistSearch":
                entry = next((e for e in LIDARR_CATALOG if self.lib.lidarr_artists.get(e["foreignArtistId"], {}).get("id") == body.get("artistId")), None)
                if entry:
                    self.queue_albums(entry)
                command["_started"] = 0
            return self.send_raw({k: v for k, v in command.items() if not k.startswith("_")}, 201)
        if endpoint == "albumstudio":
            for artist in body.get("artist", []):
                for state in self.lib.lidarr_artists.values():
                    if state["id"] == artist.get("id"):
                        state["monitored"] = artist.get("monitored", True)
            print(f"    lidarr: albumstudio {body}", flush=True)
            return self.send_raw({}, 202)
        for key in ("qualityProfileId", "metadataProfileId", "rootFolderPath"):
            target = body if endpoint == "artist" else body.get("artist", {})
            if not target.get(key):
                return self.send_raw([{"propertyName": key, "errorMessage": f"'{key}' must not be empty."}], 400)
        if endpoint == "artist":
            mbid = body.get("foreignArtistId")
            entry = next((e for e in LIDARR_CATALOG if e["foreignArtistId"] == mbid), None)
            if entry is None:
                return self.send_raw([{"propertyName": "ForeignArtistId", "errorMessage": "Artist not found"}], 400)
            if mbid in self.lib.lidarr_artists:
                return self.send_raw([{"propertyName": "ForeignArtistId", "errorMessage": "This artist has already been added."}], 400)
            self.lib.lidarr_artists[mbid] = {"id": 1 + max([a["id"] for a in self.lib.lidarr_artists.values()] or [0]),
                                             "monitored": body.get("addOptions", {}).get("monitor") != "none"}
            opts = body.get("addOptions", {})
            self.register_albums(entry, monitored=opts.get("monitor") not in (None, "none"))
            print(f"    lidarr: added artist {entry['artistName']} monitor={opts.get('monitor')} search={opts.get('searchForMissingAlbums')} "
                  f"quality={body.get('qualityProfileId')} root={body.get('rootFolderPath')}", flush=True)
            if opts.get("searchForMissingAlbums") and opts.get("monitor") != "none":
                for title, _, _ in entry["albums"]:
                    self.lib.lidarr_queue.append({"title": f"{entry['artistName']} - {title}", "artist": entry["artistName"], "album": title, "started": time.time()})
            return self.send_raw(self.lidarr_artist(entry), 201)
        if endpoint == "album":
            mbid = body.get("foreignAlbumId")
            for e in LIDARR_CATALOG:
                for album in e["albums"]:
                    if album[1] == mbid:
                        self.lib.lidarr_artists.setdefault(e["foreignArtistId"], {"id": 1 + max([a["id"] for a in self.lib.lidarr_artists.values()] or [0])})
                        self.register_albums(e)
                        self.lib.lidarr_albums[mbid]["monitored"] = True
                        print(f"    lidarr: added album {album[0]} by {e['artistName']} search={body.get('addOptions', {}).get('searchForNewAlbum')}", flush=True)
                        self.lib.lidarr_queue.append({"title": f"{e['artistName']} - {album[0]}", "artist": e["artistName"], "album": album[0], "started": time.time()})
                        return self.send_raw(self.lidarr_album(e, album), 201)
            return self.send_raw([{"propertyName": "ForeignAlbumId", "errorMessage": "Album not found"}], 400)
        self.send_raw({"message": "unknown"}, 404)

    def api_getTopSongs(self, q, one):
        songs = [s for s in self.lib.songs.values() if s["artist"] == one("artist")][:int(one("count", 10))]
        self.send_json({"topSongs": {"song": [self.lib.song(s) for s in songs]}})

    def api_getAlbum(self, q, one):
        a = self.lib.albums.get(one("id"))
        if not a:
            return self.error(70, "Album not found")
        self.send_json({"album": {**self.lib.album(a), "song": [self.lib.song(s) for s in a["_songs"]]}})

    def api_getAlbumList2(self, q, one):
        albums = list(self.lib.albums.values())
        kind = one("type")
        if kind == "random":
            random.shuffle(albums)
        elif kind == "newest":
            albums.sort(key=lambda a: a["year"], reverse=True)
        elif kind == "alphabeticalByName":
            albums.sort(key=lambda a: a["name"])
        elif kind == "starred":
            albums = [a for a in albums if a["id"] in self.lib.starred]
        elif kind == "byGenre":
            albums = [a for a in albums if a["genre"] == one("genre")]
        elif kind in ("recent", "frequent"):
            albums = [a for a in albums if any(self.lib.plays.get(s["id"]) for s in a["_songs"])]
        off, size = int(one("offset", 0)), int(one("size", 10))
        self.send_json({"albumList2": {"album": [self.lib.album(a) for a in albums[off:off + size]]}})

    def api_getSong(self, q, one):
        s = self.lib.songs.get(one("id"))
        return self.send_json({"song": self.lib.song(s)}) if s else self.error(70, "Song not found")

    def api_getRandomSongs(self, q, one):
        songs = list(self.lib.songs.values())
        random.shuffle(songs)
        self.send_json({"randomSongs": {"song": [self.lib.song(s) for s in songs[:int(one("size", 10))]]}})

    def api_getSimilarSongs2(self, q, one):
        self.api_getRandomSongs(q, one)

    def api_getGenres(self, q, one):
        genres = {}
        for a in self.lib.albums.values():
            g = genres.setdefault(a["genre"], {"value": a["genre"], "songCount": 0, "albumCount": 0})
            g["albumCount"] += 1
            g["songCount"] += len(a["_songs"])
        self.send_json({"genres": {"genre": list(genres.values())}})

    def api_getSongsByGenre(self, q, one):
        songs = [s for s in self.lib.songs.values() if s["genre"] == one("genre")]
        self.send_json({"songsByGenre": {"song": [self.lib.song(s) for s in songs]}})

    def api_search3(self, q, one):
        term = (one("query") or "").strip('"').lower()
        hit = lambda text: term in text.lower()
        self.send_json({"searchResult3": {
            "artist": [self.lib.artist(a) for a in ARTISTS if hit(ARTISTS[a])][:int(one("artistCount", 20))],
            "album": [self.lib.album(a) for a in self.lib.albums.values() if hit(a["name"])][:int(one("albumCount", 20))],
            "song": [self.lib.song(s) for s in self.lib.songs.values() if hit(s["title"])][:int(one("songCount", 20))],
        }})

    def api_getStarred2(self, q, one):
        self.send_json({"starred2": {
            "artist": [self.lib.artist(a) for a in ARTISTS if a in self.lib.starred],
            "album": [self.lib.album(a) for a in self.lib.albums.values() if a["id"] in self.lib.starred],
            "song": [self.lib.song(s) for s in self.lib.songs.values() if s["id"] in self.lib.starred],
        }})

    def _star(self, q, starred):
        for key in ("id", "albumId", "artistId"):
            for item in q.get(key, []):
                if starred:
                    self.lib.starred[item] = now()
                else:
                    self.lib.starred.pop(item, None)
        self.send_json({})

    def api_star(self, q, one):
        self._star(q, True)

    def api_unstar(self, q, one):
        self._star(q, False)

    def api_scrobble(self, q, one):
        if one("submission", "true") == "true":
            self.lib.plays[one("id")] = self.lib.plays.get(one("id"), 0) + 1
        print(f"    scrobble id={one('id')} submission={one('submission')}", flush=True)
        self.send_json({})

    def api_getPlaylists(self, q, one):
        self.send_json({"playlists": {"playlist": [self.lib.playlist(p) for p in self.lib.playlists.values()]}})

    def api_getPlaylist(self, q, one):
        p = self.lib.playlists.get(one("id"))
        return self.send_json({"playlist": self.lib.playlist(p, True)}) if p else self.error(70, "Playlist not found")

    def api_createPlaylist(self, q, one):
        pid = one("playlistId")
        if pid and pid not in self.lib.playlists:
            return self.error(70, "Playlist not found")
        if pid:      # Subsonic: createPlaylist with playlistId replaces the songs
            self.lib.playlists[pid]["songs"] = q.get("songId", [])
            print(f"    playlist {pid} replaced with {len(q.get('songId', []))} songs", flush=True)
        else:
            self.lib.next_playlist = getattr(self.lib, "next_playlist", len(self.lib.playlists)) + 1
            pid = f"pl{self.lib.next_playlist}"
            self.lib.playlists[pid] = {"id": pid, "name": one("name", "New playlist"), "owner": USER, "public": False,
                                       "songs": q.get("songId", []), "created": now()}
            print(f"    playlist {pid} '{one('name')}' created with {len(q.get('songId', []))} songs", flush=True)
        self.send_json({"playlist": self.lib.playlist(self.lib.playlists[pid], True)})

    def api_updatePlaylist(self, q, one):
        p = self.lib.playlists.get(one("playlistId"))
        if not p:
            return self.error(70, "Playlist not found")
        if one("name"):
            p["name"] = one("name")
        if one("comment"):
            p["comment"] = one("comment")
        for i in sorted((int(x) for x in q.get("songIndexToRemove", [])), reverse=True):
            del p["songs"][i]
        p["songs"] += q.get("songIdToAdd", [])
        self.send_json({})

    def api_deletePlaylist(self, q, one):
        self.lib.playlists.pop(one("id"), None)
        self.send_json({})

    def api_getLyricsBySongId(self, q, one):
        lines = [{"start": t, "value": v} for t, v in LYRICS] if one("id") in self.lib.songs else []
        self.send_json({"lyricsList": {"structuredLyrics": [{"lang": "eng", "synced": True, "offset": 0, "line": lines}] if lines else []}})

    def api_getLyrics(self, q, one):
        self.send_json({"lyrics": {}})

    def api_getCoverArt(self, q, one):
        album = self.lib.albums.get(one("id")) or self.lib.albums.get(self.lib.songs.get(one("id"), {}).get("albumId"))
        if not album:
            album = next(iter(self.lib.albums.values()))
        self.send_file(self.lib.media / f"{album['id']}.jpg", "image/jpeg")

    def api_stream(self, q, one):
        song = self.lib.songs.get(one("id"))
        if not song:
            return self.error(70, "Song not found")
        path = self.lib.media / f"{song['id']}.flac"
        fmt = one("format", "raw")
        if fmt in ("raw", "flac", None):
            return self.send_file(path, "audio/flac")
        bitrate = one("maxBitRate", "192")
        codec = {"mp3": ("libmp3lame", "mp3", "audio/mpeg"), "opus": ("libopus", "ogg", "audio/ogg"),
                 "aac": ("aac", "adts", "audio/aac")}.get(fmt, ("libmp3lame", "mp3", "audio/mpeg"))
        self.send_response(200)
        self.send_header("Content-Type", codec[2])
        self.end_headers()
        proc = subprocess.Popen(["ffmpeg", "-v", "quiet", "-i", str(path), "-map", "0:a", "-c:a", codec[0], "-b:a", f"{bitrate}k",
                                 "-f", codec[1], "-"], stdout=subprocess.PIPE)
        try:
            shutil.copyfileobj(proc.stdout, self.wfile)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
            pass
        finally:
            proc.kill()

    api_download = api_stream

    def send_file(self, path: Path, content_type: str):
        size = path.stat().st_size
        start, end = 0, size - 1
        rng = self.headers.get("Range")
        if Handler.no_ranges and path.suffix == ".flac":
            # Like a reverse proxy that re-chunks streams: no ranges, no length.
            self.send_response(200)
            self.send_header("Content-Type", content_type)
            self.end_headers()
            try:
                with open(path, "rb") as f:
                    while chunk := f.read(16384):
                        self.wfile.write(chunk)
                        if Handler.stream_kbps:
                            time.sleep(len(chunk) / (Handler.stream_kbps * 1024))
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass
            self.close_connection = True
            return
        if rng and (m := re.match(r"bytes=(\d*)-(\d*)", rng)):
            if m.group(1):
                start = int(m.group(1))
                end = int(m.group(2)) if m.group(2) else size - 1
            else:
                start = size - int(m.group(2))
            self.send_response(206)
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        else:
            self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            try:
                while remaining > 0:
                    chunk = f.read(min(65536, remaining))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    remaining -= len(chunk)
            except (BrokenPipeError, ConnectionResetError, ssl.SSLError):
                pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--data", default=str(Path(__file__).parent / "data"))
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8443)
    parser.add_argument("--no-mtls", action="store_true", help="serve TLS without requiring a client certificate")
    parser.add_argument("--plain", action="store_true", help="serve plain http")
    parser.add_argument("--empty-library", action="store_true", help="serve no music, like a freshly installed server")
    parser.add_argument("--brainarr-untagged", action="store_true", help="the Brainarr import list has no tag yet")
    parser.add_argument("--no-ranges", action="store_true",
                        help="stream songs without Content-Length or range support, like some reverse proxies")
    parser.add_argument("--stream-kbps", type=int, default=0, help="with --no-ranges: stream this slowly (KiB/s), like a remote server")
    parser.add_argument("--connect", metavar="URL",
                        help="pass /connect-tonearm/ on to a Tonearm server (e.g. http://127.0.0.1:8790), like the proxy does")
    parser.add_argument("--plain-port", type=int, default=0,
                        help="also serve plain http on 127.0.0.1 at this port, like Navidrome's own port behind the proxy")
    parser.add_argument("--san", action="append", default=["DNS:localhost", "IP:127.0.0.1", "IP:10.0.2.2"],
                        help="subject alternative names for the server certificate (first run only)")
    args = parser.parse_args()

    data = Path(args.data)
    make_certs(data / "certs", args.san)
    make_media(data / "media")
    Handler.lib = Library(data / "media")
    # Brainarr's earlier picks: one you already have, one still downloading.
    Handler.no_ranges = args.no_ranges
    Handler.stream_kbps = args.stream_kbps
    Handler.connect_url = args.connect
    Handler.lib.brainarr_tags = [] if args.brainarr_untagged else [BRAINARR_TAG["id"]]
    Handler.lib.lidarr_tags = [] if args.brainarr_untagged else [dict(BRAINARR_TAG)]
    Handler.add_lidarr_artist(Handler, "mb-signal-garden", tags=Handler.lib.brainarr_tags, days_ago=3, on_disk=True)
    Handler.add_lidarr_artist(Handler, "mb-kosmische", tags=Handler.lib.brainarr_tags, days_ago=1)
    Handler.queue_albums(Handler, LIDARR_CATALOG[2])
    if args.empty_library:
        Handler.lib.albums.clear()
        Handler.lib.songs.clear()
        ARTISTS.clear()
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    scheme = "http"
    if not args.plain:
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(data / "certs" / "server.crt", data / "certs" / "server.key")
        if not args.no_mtls:
            ctx.verify_mode = ssl.CERT_REQUIRED
            ctx.load_verify_locations(data / "certs" / "ca.crt")
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        scheme = "https"
    mode = "plain http" if args.plain else ("TLS" if args.no_mtls else "mutual TLS (client certificate required)")
    print(f"Mock Subsonic on {scheme}://{args.host}:{args.port}  [{mode}]  user={USER} password={PASSWORD}")
    print(f"Client certificates: {data / 'certs' / 'client.p12'} (password '{P12_PASSWORD}')", flush=True)
    if args.plain_port:
        inside = ThreadingHTTPServer(("127.0.0.1", args.plain_port), Handler)
        threading.Thread(target=inside.serve_forever, daemon=True).start()
        print(f"Also plain http on 127.0.0.1:{args.plain_port}", flush=True)
    if args.connect:
        print(f"/connect-tonearm goes to {args.connect}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()

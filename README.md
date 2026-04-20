# DriveHost — Decentralised P2P Minecraft Hosting via Google Drive

A Fabric mod that lets you host a Minecraft server with friends — no dedicated server needed. The world lives encrypted on Google Drive, and the best available player automatically becomes the host.

## Features

- **One-click hosting** — Open port automatically via UPnP, Playit.gg, or ngrok
- **Encrypted world** — AES-256-GCM encryption, nobody can read your world files without the password
- **Automatic failover** — If the host disconnects, another player takes over seamlessly
- **No dedicated server** — Just a shared Google Drive folder and a password
- **Autosave** — World saved to Drive every 10 minutes

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) for Minecraft 1.20.4
2. Install [Fabric API](https://modrinth.com/mod/fabric-api)
3. Drop `drivehost-x.x.x.jar` into your `.minecraft/mods/` folder
4. Launch Minecraft — you'll see a **DriveHost** button on the title screen

## Quick Start

### For the World Owner (Create)

1. **Set up Google Drive folder:**
   - Go to [drive.google.com](https://drive.google.com)
   - Click **New → Folder** and name it (e.g. "MyMinecraftWorld")
   - Right-click the folder → **Share**
   - Change access to **"Anyone with the link"** → set to **Editor**
   - Copy the link — the **Folder ID** is the long string after `/folders/` in the URL
     - Example: `https://drive.google.com/drive/folders/1aBcDeFgHiJkLmNoPqRsTuVwXyZ` → Folder ID is `1aBcDeFgHiJkLmNoPqRsTuVwXyZ`

2. **Create the world in Minecraft:**
   - Launch Minecraft → Click **DriveHost** on the title screen
   - Click **Create World**
   - Paste your **Folder ID**
   - Set a **password** (share this with friends — they'll need it to join)
   - Click **Create**
   - First time only: your browser will open for Google account sign-in

3. **Share with friends:**
   - Give them the **Folder ID** and **password**
   - They need the DriveHost mod installed too

### For Players (Join)

1. Get the **Folder ID** and **password** from the world owner
2. Launch Minecraft → **DriveHost** → **Join World**
3. Enter the Folder ID and password → Click **Join**
4. First time only: your browser will open for Google account sign-in
5. You'll connect to whoever is currently hosting

## How Tunneling Works

DriveHost automatically makes your server reachable to other players using a 3-tier fallback:

| Priority | Method | Setup Required | How It Works |
|----------|--------|---------------|-------------|
| 1st | **UPnP** | None | Opens port on your router automatically (~70% of home routers) |
| 2nd | **Playit.gg** | One-time browser visit | Free tunneling service — visit a link once to activate |
| 3rd | **ngrok** | Free account + authtoken | Create account at [ngrok.com](https://ngrok.com), paste your authtoken |

If UPnP works on your router, everything is automatic — no setup needed.

### If Playit.gg is needed:
The mod will show a message: `Visit playit.gg/claim/XXXX in your browser`
- Go to that URL, accept the claim — done! This only happens once.

### If ngrok is needed:
1. Create a free account at [dashboard.ngrok.com](https://dashboard.ngrok.com)
2. Copy your authtoken from [Your Authtoken](https://dashboard.ngrok.com/get-started/your-authtoken)
3. The mod will prompt you to enter it
4. Note: TCP tunnels on ngrok's free tier may require adding a credit card for identity verification (you won't be charged)

### Manual Port Forwarding (last resort):
If all automatic methods fail:
1. Log into your router (usually `192.168.1.1` or `192.168.0.1`)
2. Find Port Forwarding settings
3. Forward **TCP port 25565** to your computer's local IP
4. Share your public IP (google "what is my IP") with friends

## Google Drive Setup Details

### Required: Google Drive folder permissions

The shared folder must be set to **"Anyone with the link can edit"** so all players can read/write the session files. The world data is encrypted — even with edit access, nobody can read it without the password.

### What gets stored on Drive

```
YourFolder/
├── world.zip.enc          ← Encrypted Minecraft world save
├── session.json.enc       ← Encrypted session state (who's hosting, etc.)
└── keycheck.json          ← Plaintext: salt + verification hash (NOT the password)
```

All `.enc` files are AES-256-GCM encrypted. `keycheck.json` contains only a salt and HMAC hash for password verification — never the actual password or encryption key.

## Security

- **AES-256-GCM** encryption for all world data
- **PBKDF2** key derivation (200,000 iterations) — brute-forcing the password is impractical
- **Passwords never saved in plaintext** — derived key cached in a local keystore
- **Decrypted files only exist temporarily** — wiped on exit and on next startup (crash recovery)
- Files on Google Drive are useless without the password

## Building from Source

### Prerequisites
- Java 17+
- Gradle (wrapper included)

### Google Cloud Console Setup (required for building)
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project (or use an existing one)
3. Enable the **Google Drive API**: APIs & Services → Library → search "Google Drive API" → Enable
4. Create OAuth credentials: APIs & Services → Credentials → Create Credentials → OAuth 2.0 Client ID
5. Choose **Desktop app** as the application type
6. Download the `credentials.json` file
7. Place it at `src/main/resources/credentials.json`

### Build
```bash
./gradlew build
```

The output JAR will be in `build/libs/drivehost-x.x.x.jar` — this is a single fat JAR with all dependencies included.

## Technical Details

| Component | Technology |
|-----------|-----------|
| Mod framework | Fabric (Minecraft 1.20.4, Java 17) |
| Encryption | AES-256-GCM |
| Key derivation | PBKDF2-SHA256 (200k iterations) |
| Cloud storage | Google Drive API v3 |
| Tunneling | UPnP (WaifUPnP) → Playit.gg → ngrok |
| Concurrency | Drive etag-based optimistic locking |

## License

MIT

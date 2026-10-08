# Orbit key bot

Premium keys for NTS Orbit, and the Discord bot that hands them out.

- **Keys work offline.** Each key is a short Ed25519-signed token (tier, key id, dates). The app
  only contains the public key (`OrbitKey.PUBLIC_KEY_HEX`) and checks the signature on the phone.
  The private signing key lives only on the machine that runs this bot. Never commit it.
- **Tiers:** `PRO` (bought on its own), `CREW2`, `CREW3`, `BALLER`. Every tier unlocks Pro in the
  app; Baller also gets the Baller model section.
- **The bot** watches the server. When someone gets the Crew 2, Crew 3 or Baller role, it DMs them a
  key for their highest tier. It remembers every key in SQLite, so nobody gets duplicates, and an
  upgrade gets the new tier. If their DMs are closed, the server owner gets a note and the member
  can run `/mykey`.
- **Slash commands:** `/genkey tier [user] [note]` (owner only: standalone Pro sales, manual keys),
  `/mykey` (shows your key again, only to you).

Key format (`orbitkey/keys.py`, `app/.../OrbitKey.java`): 73 bytes as Crockford base32,
`ORBIT-XXXXXX-…`: version+tier, key id, issue day, expiry day (0 = never), then the signature over
`NTSORBIT-KEY1` + those 9 bytes.

## Setup (Linux, systemd)

1. Discord Developer Portal → New Application → Bot → Reset Token. Turn on **Server Members Intent**.
   Invite it: `https://discord.com/oauth2/authorize?client_id=APP_ID&scope=bot+applications.commands&permissions=3072`
2. Install:

   ```
   sudo useradd --system --no-create-home --shell /usr/sbin/nologin orbitkeybot
   sudo mkdir -p /opt/orbit-keybot /etc/orbit-keybot && sudo cp -r orbitkey /opt/orbit-keybot/
   sudo python3 -m venv /opt/orbit-keybot/venv && sudo /opt/orbit-keybot/venv/bin/pip install -r requirements.txt
   sudo chown root:orbitkeybot /etc/orbit-keybot && sudo chmod 750 /etc/orbit-keybot
   sudo PYTHONPATH=/opt/orbit-keybot /opt/orbit-keybot/venv/bin/python -m orbitkey.cli init-key /etc/orbit-keybot/signing.key
   sudo chown orbitkeybot: /etc/orbit-keybot/signing.key && sudo chmod 400 /etc/orbit-keybot/signing.key
   ```
   Put the printed public key in `OrbitKey.PUBLIC_KEY_HEX`. Copy `config.example.json` to
   `/etc/orbit-keybot/config.json` and fill in the ids. Save the bot token to
   `/etc/orbit-keybot/token` (root only, `chmod 600`).
3. `sudo cp orbit-keybot.service /etc/systemd/system/ && sudo systemctl enable --now orbit-keybot`
   and `sudo cp orbitkey.sh /usr/local/bin/orbitkey`.

## Everyday use

```
sudo orbitkey gen --tier PRO --note "sold to …"   # make + record a key
sudo orbitkey list                                # what was issued, to whom, delivered or not
orbitkey verify ORBIT-…                           # check a key
journalctl -u orbit-keybot -f                     # bot log
```

Tests: `pip install pytest discord.py cryptography && python -m pytest tests`.

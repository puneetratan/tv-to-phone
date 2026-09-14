#!/usr/bin/env bash
# Provisions a Raspberry Pi (Bookworm) as the tv-to-phone Stage 1 receiver:
# a venv + systemd service running server.py, an avahi advertisement so the
# phone can find it via mDNS, and a kiosk browser autostart pointed at the
# persistent receiver page. Safe to re-run.
#
# Run this from the pi/ directory on the Pi itself:
#   ./setup-pi.sh

set -euo pipefail

INSTALL_DIR="$HOME/phonecast"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PORT=8000

echo "==> Installing OS packages"
sudo apt-get update
sudo apt-get install -y python3-venv avahi-daemon

echo "==> Copying server files to $INSTALL_DIR"
mkdir -p "$INSTALL_DIR"
cp "$SCRIPT_DIR/server.py" "$INSTALL_DIR/server.py"
cp "$SCRIPT_DIR/requirements.txt" "$INSTALL_DIR/requirements.txt"
mkdir -p "$INSTALL_DIR/static"
cp -r "$SCRIPT_DIR/static/." "$INSTALL_DIR/static/"

echo "==> Creating venv and installing dependencies"
if [ ! -d "$INSTALL_DIR/.venv" ]; then
  python3 -m venv "$INSTALL_DIR/.venv"
fi
"$INSTALL_DIR/.venv/bin/pip" install --upgrade pip
"$INSTALL_DIR/.venv/bin/pip" install -r "$INSTALL_DIR/requirements.txt"

echo "==> Writing systemd unit"
sudo tee /etc/systemd/system/phonecast.service > /dev/null <<EOF
[Unit]
Description=tv-to-phone Pi receiver
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$USER
WorkingDirectory=$INSTALL_DIR
ExecStart=$INSTALL_DIR/.venv/bin/uvicorn server:app --host 0.0.0.0 --port $PORT
Restart=on-failure
RestartSec=2

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable phonecast
sudo systemctl restart phonecast

echo "==> Advertising _phonecast._tcp over mDNS"
sudo tee /etc/avahi/services/phonecast.service > /dev/null <<EOF
<?xml version="1.0" standalone='no'?>
<!DOCTYPE service-group SYSTEM "avahi-service.dtd">
<service-group>
  <name replace-wildcards="yes">tv-to-phone on %h</name>
  <service>
    <type>_phonecast._tcp</type>
    <port>$PORT</port>
  </service>
</service-group>
EOF

sudo systemctl restart avahi-daemon

echo "==> Configuring kiosk autostart"
# Bookworm ships either Wayfire or labwc depending on the image, and they
# have unrelated autostart mechanisms -- see CLAUDE.md's environment notes.
CHROMIUM_BIN="$(command -v chromium-browser || command -v chromium || true)"
if [ -z "$CHROMIUM_BIN" ]; then
  echo "!! No chromium binary found. Install chromium (or chromium-browser)"
  echo "   and re-run this script to wire up kiosk autostart."
else
  KIOSK_CMD="$CHROMIUM_BIN --kiosk --noerrdialogs --disable-infobars --check-for-update-interval=31536000 --autoplay-policy=no-user-gesture-required http://localhost:$PORT/"
  MARKER="# tv-to-phone kiosk autostart"

  if command -v wayfire >/dev/null 2>&1 || [ -f "$HOME/.config/wayfire.ini" ]; then
    echo "Detected Wayfire"
    WAYFIRE_INI="$HOME/.config/wayfire.ini"
    mkdir -p "$(dirname "$WAYFIRE_INI")"
    touch "$WAYFIRE_INI"
    if ! grep -q "$MARKER" "$WAYFIRE_INI" 2>/dev/null; then
      if ! grep -q "^\[autostart\]" "$WAYFIRE_INI" 2>/dev/null; then
        printf '\n[autostart]\n' >> "$WAYFIRE_INI"
      fi
      printf '%s\nphonecast = %s\n' "$MARKER" "$KIOSK_CMD" >> "$WAYFIRE_INI"
    fi
  elif command -v labwc >/dev/null 2>&1 || [ -d "$HOME/.config/labwc" ]; then
    echo "Detected labwc"
    LABWC_AUTOSTART="$HOME/.config/labwc/autostart"
    mkdir -p "$(dirname "$LABWC_AUTOSTART")"
    touch "$LABWC_AUTOSTART"
    chmod +x "$LABWC_AUTOSTART"
    if ! grep -q "$MARKER" "$LABWC_AUTOSTART" 2>/dev/null; then
      printf '%s\n%s &\n' "$MARKER" "$KIOSK_CMD" >> "$LABWC_AUTOSTART"
    fi
  else
    echo "!! Could not detect Wayfire or labwc. Add this to your compositor's"
    echo "   autostart by hand:"
    echo "   $KIOSK_CMD"
  fi
fi

echo "==> Done"
echo "Service:  sudo systemctl status phonecast"
echo "Logs:     journalctl -u phonecast -f"
echo "Discover: avahi-browse -rt _phonecast._tcp"
echo "Reboot to launch the kiosk browser, or start it manually with:"
echo "  $CHROMIUM_BIN --kiosk http://localhost:$PORT/"

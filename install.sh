#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$(readlink -f "$0")")"
command -v adb >/dev/null || echo "⚠ adb introuvable : installe android-tools (Arch) ou adb (Debian/Ubuntu)."
python3 -m venv .venv
.venv/bin/pip install -q --upgrade pip
.venv/bin/pip install -q -r requirements.txt
echo "✔ Prêt. Branche le téléphone (débogage USB), ouvre Clash of Clans sur le village, puis :"
echo "   .venv/bin/python bot_coc.py --attaques 1"

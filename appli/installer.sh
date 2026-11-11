#!/bin/bash
# Compile l'appli et l'ajoute au menu K (rien en dehors de ton dossier personnel).
set -e
cd "$(dirname "$0")"
taskset -c 1-7 nice -n 19 cargo build --release
install -Dm755 target/release/bot-coc "$HOME/.local/bin/bot-coc"
install -Dm644 donnees/org.glime.botcoc.svg "$HOME/.local/share/icons/hicolor/scalable/apps/org.glime.botcoc.svg"
sed "s|@BIN@|$HOME/.local/bin/bot-coc|" donnees/org.glime.botcoc.desktop.in \
    > "$HOME/.local/share/applications/org.glime.botcoc.desktop"
kbuildsycoca6 >/dev/null 2>&1 || true
echo "Bot CoC est dans le menu K."

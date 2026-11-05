<p align="center"><img src="docs/banner-bot-coc.png" alt="bot_coc" width="100%"></p>

<p align="center"><img src="https://img.shields.io/badge/python-3-3776ab?style=flat-square" alt="python"> <img src="https://img.shields.io/badge/OpenCV-template%20matching-5c3ee8?style=flat-square" alt="opencv"> <img src="https://img.shields.io/badge/Android-ADB%20%2B%20scrcpy-3ddc84?style=flat-square" alt="android"> <img src="https://img.shields.io/badge/license-MIT-610089?style=flat-square" alt="license"></p>

<p align="center"><a href="README.fr.md">🇫🇷 Version française</a></p>

**A Clash of Clans attack loop driven by image recognition** — the phone is controlled over ADB, OpenCV finds the buttons on screen, and no button coordinate is hard-coded.

> [!WARNING]
> Automating the game is against Supercell's Terms of Service, and Supercell runs ban waves against bots. Use it on an account you're ready to lose. This is a personal computer-vision project, shared for learning.

<p align="center"><img src="docs/bot-coc-boucle.png" width="100%" alt="attack loop"></p>

## How it works

- **Vision**: raw ADB screenshots, OpenCV template matching with color/contrast checks for every button (`templates/`). Positions found once are remembered to speed things up.
- **Fingers**: the official [scrcpy](https://github.com/Genymobile/scrcpy) server (bundled in `outils/`, Apache-2.0) is pushed to the phone and driven directly: real two-finger pinch to zoom out (no root), fast taps and swipes. Falls back to `adb input` if needed.
- **Reading numbers**: a tiny OCR on the game's own digit glyphs to read loot and troop counts — skips villages below a loot threshold (`--butin`, gold + elixir + 120 × dark elixir).
- **Deployment**: troop bar cards are recognized whatever the background (new cards are learned automatically); the map edges are probed once then saved in `templates/carte.json`; troops, heroes, siege machine and spells (Haste, Rage) are placed along the edges, then checked and redeployed until everything is out.
- **Builder Base** (`--nuit`): two stages, deploys all around the enemy base, triggers each camp's ability 5 s after it lands, the hero ability whenever its gauge is full, and taps the ×4 speed button only when it shows 1×.

<p align="center"><img src="docs/bot-coc-run.png" width="820" alt="example run"><br><sub>example output</sub></p>

## Install

```bash
git clone https://github.com/glime32/bot-coc && cd bot-coc
./install.sh                       # creates .venv with opencv-python + numpy
```

Needs `adb` and an Android phone with **USB debugging** on. The bundled templates were captured on a French-language game at 2340×1080; for another language or screen, recreate them with `capture NOM`.

## Commands

```bash
.venv/bin/python bot_coc.py                  # main village, endless loop (Ctrl+C to stop)
.venv/bin/python bot_coc.py --nuit           # Builder Base
.venv/bin/python bot_coc.py --attaques 1     # a single attack to check
.venv/bin/python bot_coc.py --duree 90       # surrender after 90 s of battle
.venv/bin/python bot_coc.py --butin 3M       # attack only if loot ≥ 3 million
.venv/bin/python bot_coc.py test             # analyze the current screen -> debug/
.venv/bin/python bot_coc.py capture NOM      # create templates/NOM.png (frame the button)
.venv/bin/python bot_coc.py chiffres         # re-learn the digits on your screen
.venv/bin/python bot_coc.py recalibrer       # probe the map edges again next battle
```

Fan-made, not affiliated with or endorsed by Supercell.

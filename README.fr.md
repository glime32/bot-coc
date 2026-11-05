<p align="center"><img src="docs/banner-bot-coc.png" alt="bot_coc" width="100%"></p>

<p align="center"><img src="https://img.shields.io/badge/python-3-3776ab?style=flat-square" alt="python"> <img src="https://img.shields.io/badge/OpenCV-template%20matching-5c3ee8?style=flat-square" alt="opencv"> <img src="https://img.shields.io/badge/Android-ADB%20%2B%20scrcpy-3ddc84?style=flat-square" alt="android"> <img src="https://img.shields.io/badge/license-MIT-610089?style=flat-square" alt="license"></p>

<p align="center"><a href="README.md">🇬🇧 English version</a></p>

**Une boucle d'attaques Clash of Clans par reconnaissance d'image** : le téléphone est piloté par ADB, OpenCV trouve les boutons à l'écran, et aucune coordonnée de bouton n'est écrite en dur.

> [!WARNING]
> Automatiser le jeu est contraire aux conditions d'utilisation de Supercell, qui fait des vagues de bannissement contre les bots. À utiliser sur un compte que tu es prêt à perdre. C'est un projet perso de vision par ordinateur, partagé pour apprendre.

<p align="center"><img src="docs/bot-coc-boucle.png" width="100%" alt="boucle d'attaque"></p>

## Comment ça marche

- **Vision** : captures brutes par ADB, recherche de chaque bouton par template matching OpenCV avec vérification de la couleur et du contraste (`templates/`). Les positions trouvées sont retenues pour aller plus vite.
- **Doigts** : le serveur officiel de [scrcpy](https://github.com/Genymobile/scrcpy) (fourni dans `outils/`, Apache-2.0) est envoyé sur le téléphone et piloté directement : vrai pincement à deux doigts pour dézoomer (sans root), touches et glissements rapides. Repli sur `adb input` si besoin.
- **Lecture des chiffres** : un petit OCR sur les chiffres du jeu pour lire le butin et les compteurs de troupes. Il passe les villages sous un seuil (`--butin`, or + élixir + 120 × élixir noir).
- **Déploiement** : les cartes de la barre sont reconnues quel que soit le décor (les nouvelles sont apprises toutes seules) ; les bords de la carte sont cherchés une fois puis gardés dans `templates/carte.json` ; troupes, héros, engin et sorts (précipitation, rage) sont posés le long des bords, puis vérifiés et reposés jusqu'à ce que tout soit sorti.
- **Base des ouvriers** (`--nuit`) : deux étapes, pose tout autour de la base adverse, compétence de chaque camp 5 s après sa pose, compétence du héros dès que sa jauge est pleine, et bouton ×4 touché seulement quand il affiche 1×.

<p align="center"><img src="docs/bot-coc-run.png" width="820" alt="exemple"><br><sub>exemple de sortie</sub></p>

## Installation

```bash
git clone https://github.com/glime32/bot-coc && cd bot-coc
./install.sh                       # crée .venv avec opencv-python + numpy
```

Il faut `adb` et un téléphone Android avec le **débogage USB**. Les templates fournis viennent d'un jeu en français en 2340×1080 ; pour une autre langue ou un autre écran, refais-les avec `capture NOM`.

## Commandes

```bash
.venv/bin/python bot_coc.py                  # village principal, boucle sans fin (Ctrl+C)
.venv/bin/python bot_coc.py --nuit           # base des ouvriers
.venv/bin/python bot_coc.py --attaques 1     # une seule attaque pour vérifier
.venv/bin/python bot_coc.py --duree 90       # capitule au bout de 90 s de combat
.venv/bin/python bot_coc.py --butin 3M       # attaque seulement si le butin ≥ 3 millions
.venv/bin/python bot_coc.py test             # analyse l'écran actuel -> debug/
.venv/bin/python bot_coc.py capture NOM      # crée templates/NOM.png (tu encadres le bouton)
.venv/bin/python bot_coc.py chiffres         # réapprend les chiffres sur ton écran
.venv/bin/python bot_coc.py recalibrer       # recherche les bords de la carte au prochain combat
```

Outil de fan, ni affilié ni approuvé par Supercell.

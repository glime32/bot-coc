#!/usr/bin/env python3
"""
bot_coc.py — boucle d'attaques Clash of Clans par reconnaissance d'image.

Le téléphone est piloté par ADB et OpenCV cherche les boutons dans ses images, comme
pyautogui.locateCenterOnScreen. Aucune coordonnée de bouton n'est écrite en dur.
Le serveur de scrcpy (outils/scrcpy-server-v5.0.1) simule les doigts (vrais gestes à deux
doigts sans root, touches très rapides) et envoie l'écran en vidéo : une image fraîche en
~50 ms au lieu de ~1,5 s par « adb screencap » (il faut PyAV : .venv/bin/pip install av ;
sans lui, le bot reprend les captures ADB). scrcpy lui-même n'est pas nécessaire : ferme-le
pour économiser la batterie du PC, le script garde l'écran allumé.
La taille et la place de chaque bouton trouvé sont retenues dans templates/boutons.json.

  python bot_coc.py                     village principal, boucle sans fin (Ctrl+C pour arrêter)
  python bot_coc.py --nuit              base des ouvriers (base de nuit), boucle sans fin
  python bot_coc.py --attaques 1        une seule attaque, pour vérifier (marche avec --nuit)
  python bot_coc.py --duree 90          au bout de 90 s de combat : Capituler / Terminer la bataille
  python bot_coc.py --butin 3M          village principal : attaque si or + élixir + 120 × noir ≥ 3 millions
  python bot_coc.py --debug             enregistre l'analyse de chaque combat et les temps ADB
  python bot_coc.py vues [--nuit]       pendant l'observation d'un village : dézoome et enregistre
                                        les points de pose dans debug/ (ne pose rien)
  python bot_coc.py test [--nuit]       analyse l'écran actuel du téléphone -> debug/
  python bot_coc.py test --image F.png  même analyse sur une capture enregistrée
  python bot_coc.py capture NOM         crée templates/NOM.png (tu encadres le bouton)
  python bot_coc.py chiffres            si le butin s'affiche « illisible » : réapprend les
                                        chiffres sur ton écran (pendant l'observation d'un village)
  python bot_coc.py recalibrer          les bords de la carte seront recherchés au prochain combat
  python bot_coc.py resolution 50|100   change la résolution du jeu à la main (le jeu redémarre)

Résolution : le bot ne reconnaît le jeu qu'affiché en 50 %. Au lancement, il le passe en 50 %
s'il ne l'est pas (le jeu redémarre) et l'y laisse (REMETTRE_A_LA_FIN pour remettre 100 %).

Village principal : Combat → Trouver une partie → Attaquer → butin (Suivant si or + élixir +
120 × élixir noir < 2,2 millions) → dézoom au maximum → vue haut (caméra en butée en haut à
gauche) : moitié des troupes, une partie des héros sur les deux bords du haut → vue bas
(en butée en bas à droite) : le reste → vérifications et reposes (héros et engin compris)
jusqu'à ce que tout soit sorti → compétences des héros → sorts sur les groupes de troupes
les plus nombreux (vue bas puis vue haut) → vitesse ×4 dès que le bouton de
vitesse apparaît (au bout de 2 minutes) → Rentrer dès la fin.
Au tout premier combat, le bot cherche les bords de la carte (une troupe à la fois, de
l'extérieur vers l'intérieur) puis les garde dans templates/carte.json.

Base des ouvriers (--nuit) : Combat → Trouver ! → dézoom → repère la base adverse → héros
(carte de gauche) et tous les camps tout autour de la base, espacés → vérification →
compétence de chaque camp 5 s après sa pose, au moins 2 s entre deux → compétence du héros
chaque fois que sa jauge est pleine (3 barres) → vitesse ×4 dès que le bouton apparaît, une
fois les compétences des camps lancées → 2e village (étape 2) : pareil (les camps survivants
sont reposés ; une compétence déjà lancée ne l'est pas deux fois) → Rentrer.

Templates (dossier templates/) :
  combat.png             bouton « Combat » du village
  trouver_partie.png     bouton orange « Trouver une partie »
  attaquer.png           bouton vert « Attaquer » de l'écran d'armée
  terminer_bataille.png  bouton rouge « Terminer la bataille » (= écran de combat)
  suivant.png            bouton « Suivant » (village suivant si le butin est trop faible)
  rentrer_village.png    bouton « Rentrer » de l'écran de fin
  nuit/combat.png        bouton « Combat » (haches) de la base des ouvriers
  nuit/trouver.png       bouton « Trouver ! » de la fenêtre « Lancer l'attaque »
  vitesse_1x.png, vitesse_4x.png   bouton de vitesse du combat, dans ses deux états
  nuit/*.png             facultatif : nuit/trouver_partie.png, nuit/rentrer.png si ces écrans
                         diffèrent du village principal (python bot_coc.py capture nuit/rentrer)
  confirmer.png          facultatif : bouton de la fenêtre « Capituler ? » (sinon : le bouton vert)
  sorts/precipitation.png, sorts/rage.png   icônes des sorts dans la barre
  cartes/*.png           dessin de chaque carte de ton armée : retrouvées quel que soit le décor
                         derrière la barre (les nouvelles cartes sont mémorisées toutes seules)
  glyphes/*.png          chiffres du jeu, pour lire le butin et les compteurs « x50 »
  popups/*.png           facultatif : boutons qui ferment une fenêtre imprévue (croix, OK…)
"""

import argparse
import collections
import copy
import json
import os
import random
import re
import shutil
import signal
import socket
import struct
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path


# Cœurs du processeur où tourne le bot. None : les cœurs économes (4 à 7 sur ce PC : moins de
# batterie, ~0,1 s de plus par attaque), jamais le premier, déjà le plus chargé. Pour aller un
# peu plus vite sur les cœurs performants sans le premier : COEURS = {1, 2, 3}
COEURS = None


def coeurs_du_bot():
    """Les cœurs choisis : COEURS, sinon les cœurs économes s'il y en a (Intel hybride), sinon
    tous ; jamais le premier."""
    tous = os.sched_getaffinity(0)
    if COEURS:
        return (set(COEURS) & tous) or tous
    econes = set()
    try:
        for morceau in Path("/sys/devices/cpu_atom/cpus").read_text().strip().split(","):
            a, _, b = morceau.partition("-")
            econes.update(range(int(a), int(b or a) + 1))
    except (OSError, ValueError):
        pass
    return (econes & tous) - {0} or tous - {0} or tous


# Avant numpy et OpenCV : leurs fils de calcul, et ceux du décodage vidéo, héritent du réglage.
try:
    os.sched_setaffinity(0, coeurs_du_bot())
except (AttributeError, OSError):
    pass

import cv2
import numpy as np

try:
    import av            # décodeur vidéo (PyAV) : images de l'écran par le serveur scrcpy
except ImportError:
    av = None

cv2.setNumThreads(2)     # moins de cœurs sollicités en même temps : meilleur pour la batterie

# ─── Réglages ────────────────────────────────────────────────────────────────

DOSSIER = Path(__file__).resolve().parent
TEMPLATES = DOSSIER / "templates"
GLYPHES = TEMPLATES / "glyphes"
SORTS = TEMPLATES / "sorts"
CARTES = TEMPLATES / "cartes"     # dessin de chaque carte de ton armée (appris tout seul)
DEBUG = DOSSIER / "debug"

ADB_SERIAL = None            # None : le câble s'il est branché, sinon le téléphone connecté en Wi-Fi (adb connect) ; ou son numéro (adb devices)
GARDER_ECRAN_ALLUME = True   # écran du téléphone toujours allumé pendant le bot (rétabli à la fin)
SEUIL = 0.80                 # confiance minimale pour reconnaître un bouton (0 à 1)
SEUIL_POPUP = 0.92           # popups/*.png : touchés dès qu'ils sont vus, donc plus exigeant
                             # (un « OK » vert ressemble à 0,80 à bien d'autres boutons)
REDUCTION = 0.5              # la recherche se fait en demi-résolution : 4× moins de calcul
ECHELLES = (1.0, 0.95, 1.05, 0.9, 1.1, 0.85, 1.15, 0.8, 1.2, 0.75, 1.25, 0.7, 1.3)

# Choix du village : il attaque si or + élixir + POIDS_NOIR × élixir noir ≥ BUTIN_MIN_TOTAL,
# ou, avec --or / --elixir / --noir, si chaque ressource choisie atteint son minimum.
# Sinon il appuie sur « Suivant » (qui coûte de l'or). Modifiable au lancement : --butin 3M
BUTIN_MIN_TOTAL = 2_200_000
POIDS_NOIR = 120             # 1 élixir noir compte comme 120 or ou élixir
BUTIN_MIN = {}               # par ressource, ex. {"or_": 1_000_000, "noir": 8_000}
RESSOURCES = {"or_": "or", "elixir": "élixir", "noir": "noir"}
SUIVANTS_MAX = 30            # au-delà de 30 villages passés d'affilée, il attaque quand même

# Doigts simulés par le serveur de scrcpy (gestes à deux doigts sans root, touches rapides).
# Si le serveur ne démarre pas, le bot repasse par « adb shell input » (plus lent, sans dézoom).
SCRCPY_SERVEUR = DOSSIER / "outils" / "scrcpy-server-v5.0.1"
SCRCPY_VERSION = "5.0.1"
DOIGTS_SCRCPY = True
APPUI = 0.018                # durée d'une touche (s)
ECART_TOUCHES = 0.022        # pause entre deux touches (s)
PINCEMENTS = 2               # pincements pour dézoomer à fond (mesuré : un seul suffit déjà)
PINCEMENT_DUREE = 0.14       # s par pincement (4 × 0,28 s avant : 2,0 s de dézoom, 0,6 s maintenant)

# Images de l'écran : vidéo du serveur scrcpy (une image fraîche en ~50 ms, ~5 % d'un cœur du PC
# à 10 images/s) au lieu de « adb screencap » (~1,5 s). Il faut PyAV : .venv/bin/pip install av
# (Mesuré : le décodage matériel VAAPI ne coûte pas moins à cette résolution, 2,1 contre 2,2 ms
# par image, à cause de la copie de chaque image vers la mémoire du processeur.)
VIDEO_SCRCPY = True
VIDEO_IPS = 10               # images par seconde (20 : 8 % d'un cœur au lieu de 5 %, pour 25 ms de gagnées)
VIDEO_DEBIT = 8_000_000      # bits/s : assez pour que les images valent une capture d'écran
VIDEO_DEBIT_SANS_FIL = 4_000_000   # en Wi-Fi : moitié moins (radio du téléphone), encore large à 1170×540

# Résolution du jeu. Tout le bot (boutons, chiffres, cartes, bords de la carte) est réglé sur le
# jeu affiché en 50 % : en 100 %, le jeu dessine les boutons et les textes autrement (plus nets,
# texte ~5 % plus grand) et plus rien n'est reconnu. Le bot passe donc le jeu en 50 % quand il
# démarre (commande Android « cmd game », le jeu redémarre) et le remet comme avant quand il
# s'arrête, même par Ctrl+C. Tu peux jouer toi-même en 100 %.
JEU = "com.supercell.clashofclans"
RESOLUTION_BOT = 0.5         # None : le bot ne touche jamais à la résolution du jeu
REMETTRE_A_LA_FIN = False    # True : à l'arrêt, remet la résolution d'avant (le jeu redémarre)
MEMO_RESOLUTION = DOSSIER / "resolution_a_remettre.txt"   # présent = le bot doit remettre 100 %

# Caméra : au début de chaque attaque, dézoom au maximum (pincement à deux doigts), puis
# vue « haut » (caméra en butée en haut à gauche) et vue « bas » (en butée en bas à droite).
# Dans ces deux vues la carte est toujours au même endroit. Ses bords sont trouvés au premier
# combat (le bot pose une troupe de plus en plus vers l'intérieur jusqu'à ce que le jeu
# l'accepte), puis gardés dans templates/carte.json. Les troupes sont posées sur la rangée de
# cases du bord, qu'aucune zone rouge n'atteint. « python bot_coc.py vues » montre les points.
CARTE_JSON = TEMPLATES / "carte.json"
BARRE = (0.80, 1.0)          # bande verticale où se trouve la barre des troupes

# Sorts : une fois toutes les troupes posées, là où il y en a le plus. Le bot compare trois
# images rapprochées : ce qui bouge, ce sont les troupes (les bâtiments restent immobiles). La
# moitié des sorts part dans la vue bas, l'autre dans la vue haut, chacun sur un groupe
# différent. Ça se passe pendant le combat, quand le bot attend de toute façon.
SORTS_APRES = 2.5            # s après la dernière troupe posée : le temps qu'elles arrivent à la base
SORT_RAYON = 3.5             # rayon pris en compte autour de chaque sort (cases)
SORT_DENSITE_MIN = 0.02      # part minimale de pixels qui bougent pour viser un groupe
RAFALE = 40                  # touches envoyées d'un coup, entre deux vérifications rapides
EXTRA = 2                    # touches en plus quand on repose des troupes, pour être sûr qu'elles sortent

# Affichages du jeu où l'on ne pose jamais rien (fractions de l'écran : x0, y0, x1, y1)
UI_FIXE = [(0.00, 0.00, 0.18, 0.30),     # butin disponible, nom du joueur
           (0.80, 0.00, 1.00, 0.24),     # ressources
           (0.42, 0.00, 0.58, 0.14),     # chrono
           (0.84, 0.48, 1.00, 0.80)]     # « Suivant », dégâts, bouton de vitesse

OBLIGATOIRES = ("combat", "trouver_partie", "terminer_bataille", "suivant", "rentrer_village")
FACULTATIFS = ("attaquer",)

# Bouton de vitesse du combat (« 1x » / « 4x », à droite) : il apparaît au bout de 2 minutes,
# dans les deux villages. Le bot le touche quand il affiche 1x (le combat passe en ×4), jamais
# quand il affiche 4x (ce serait revenir en ×1).
VITESSE_APRES = 100.0        # s de combat avant de commencer à le guetter

# Capitulation quand plus rien n'avance : si les « Dégâts généraux » n'ont pas bougé depuis
# CAPITULER_SI_FIGE s (et pas avant CAPITULER_APRES_MIN s après le début de la pose), le bot
# capitule sans attendre la fin du combat ni --duree. None : jamais.
CAPITULER_SI_FIGE = 6.0
CAPITULER_APRES_MIN = 20.0
# Bonus de victoire : à SEUIL_ETOILE % ou plus sans aucune étoile, on laisse plus de temps
# (délai sans destruction doublé, durée max + BONUS_ETOILE s) tant que l'étoile n'est pas là.
SEUIL_ETOILE = 46
BONUS_ETOILE = 15.0
VITESSE_PERIODE = 3.0        # s entre deux coups d'œil tant qu'il n'est pas sur 4x

# Base des ouvriers (--nuit)
COMPETENCE_TROUPES_APRES = 5.0   # compétence de chaque camp 5 s après sa pose…
COMPETENCE_ECART = 2.0           # … et au moins 2 s entre deux compétences de camps
MARGES_NUIT = (1.5, 2.6, 3.7)    # pose tout autour de la base, à 1,5 case ; plus loin si c'est refusé
PAS_CARTE_NUIT = 152 / 1080      # disposition des cartes de la barre (en fraction de la hauteur)
LARGEUR_CARTE_NUIT = 132 / 1080
DECALAGE_HEROS_NUIT = 170 / 1080  # du bord gauche de la 1re carte de camp au bord gauche du héros
UI_NUIT = [(0.00, 0.00, 0.18, 0.14),     # défenseur
           (0.39, 0.00, 0.61, 0.13),     # chrono
           (0.79, 0.00, 1.00, 0.14),     # attaquant
           (0.00, 0.58, 0.16, 0.79),     # bouton rouge
           (0.89, 0.46, 1.00, 0.67),     # bouton de vitesse (1x / 4x)
           (0.82, 0.64, 1.00, 0.80)]     # dégâts
OBLIGATOIRES_NUIT = ("nuit/combat",)


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


# ─── Structures ──────────────────────────────────────────────────────────────

@dataclass
class Trouve:
    nom: str
    x: int            # centre, en pixels du téléphone
    y: int
    boite: tuple      # (x, y, largeur, hauteur)
    score: float
    valide: bool = True   # False : bon score en gris, mais couleur ou contraste différents


@dataclass
class Carte:
    x0: int
    y0: int
    x1: int
    y1: int
    couleur0: float = 0.0                # taux de couleur au début du combat
    nombre: int = None                   # compteur « x50 » lu au début ; None = unité unique
    sort: str = None                     # « precipitation », « rage »… ; None = pas un sort connu
    heros: bool = False                  # unité unique au cadre bleu foncé = héros
    connue: str = None                   # nom du modèle de templates/cartes/ qui l'a reconnue
    bande0: np.ndarray = field(default=None, repr=False)     # au-dessus de la carte, avant la pose
    motif0: bool = False                 # le décor au-dessus imitait déjà une barre de vie
    points: list = field(default_factory=list, repr=False)   # où elle a été posée dans la rafale

    @property
    def point(self):
        """Où toucher la carte : haut-centre, pour éviter les icônes du bas."""
        return (self.x0 + self.x1) // 2, self.y0 + (self.y1 - self.y0) * 2 // 5

    def decrire(self):
        if self.sort:
            return f"{self.sort} x{self.nombre or '?'}"
        if self.nombre:
            return f"x{self.nombre}"
        return "héros" if self.heros else "unité"


@dataclass
class Butin:
    or_: int
    elixir: int
    noir: int

    @property
    def total(self):
        return self.or_ + self.elixir + POIDS_NOIR * self.noir

    def suffisant(self):
        return (self.total >= BUTIN_MIN_TOTAL
                and all(getattr(self, r) >= m for r, m in BUTIN_MIN.items()))

    def __str__(self):
        texte = f"{self.or_:,} or · {self.elixir:,} élixir · {self.noir:,} noir"
        if not BUTIN_MIN:
            texte += f" = {self.total:,} au total"
        return texte.replace(",", " ")


def regle_butin():
    if BUTIN_MIN:
        texte = "attaque si " + " et ".join(f"{RESSOURCES[r]} ≥ {m:,}" for r, m in BUTIN_MIN.items())
        if BUTIN_MIN_TOTAL:
            texte += f" et or + élixir + {POIDS_NOIR} × noir ≥ {BUTIN_MIN_TOTAL:,}"
    elif BUTIN_MIN_TOTAL:
        texte = f"attaque si or + élixir + {POIDS_NOIR} × noir ≥ {BUTIN_MIN_TOTAL:,}"
    else:
        texte = "attaque le premier village trouvé (pas de butin minimum)"
    return texte.replace(",", " ")


class Ecran:
    """Une capture. Celle de la vidéo arrive à la taille où le jeu dessine (la moitié de
    l'écran) : l'image pleine taille n'est fabriquée qu'au premier accès à `img`, la version
    grise réduite en sort directement, et `zone()` n'agrandit que le morceau demandé."""

    def __init__(self, img=None, partielle=False, petite=None, taille=None):
        self._img = img
        self._petite = petite
        self.w, self.h = (img.shape[1], img.shape[0]) if img is not None else taille
        self.partielle = partielle     # True : seules quelques lignes ont été capturées
        self._gris = None

    @property
    def img(self):
        if self._img is None:
            self._img = cv2.resize(self._petite, (self.w, self.h), interpolation=cv2.INTER_LINEAR)
        return self._img

    @property
    def gris(self):
        if self._gris is None:
            p = self._petite
            if p is not None and p.shape[1] == round(self.w * REDUCTION) and p.shape[0] == round(self.h * REDUCTION):
                self._gris = cv2.cvtColor(p, cv2.COLOR_BGR2GRAY)
            else:
                g = cv2.cvtColor(self.img, cv2.COLOR_BGR2GRAY)
                self._gris = cv2.resize(g, None, fx=REDUCTION, fy=REDUCTION, interpolation=cv2.INTER_AREA)
        return self._gris

    def zone(self, x0, y0, x1, y1):
        """Le morceau [y0:y1, x0:x1] de l'image pleine taille, sans l'agrandir en entier."""
        x0, y0, x1, y1 = max(0, int(x0)), max(0, int(y0)), min(self.w, int(x1)), min(self.h, int(y1))
        if self._img is not None or self._petite is None or x1 <= x0 or y1 <= y0:
            return self.img[y0:y1, x0:x1]
        kx, ky = self._petite.shape[1] / self.w, self._petite.shape[0] / self.h
        a, b = max(0, int(x0 * kx) - 1), max(0, int(y0 * ky) - 1)
        c = min(self._petite.shape[1], int(np.ceil(x1 * kx)) + 1)
        d = min(self._petite.shape[0], int(np.ceil(y1 * ky)) + 1)
        grand = cv2.resize(self._petite[b:d, a:c], (round((c - a) / kx), round((d - b) / ky)),
                           interpolation=cv2.INTER_LINEAR)
        ox, oy = x0 - round(a / kx), y0 - round(b / ky)
        return grand[oy:oy + (y1 - y0), ox:ox + (x1 - x0)]


class Bloque(Exception):
    """L'écran attendu n'est pas venu : le cycle repart de zéro."""

    def __init__(self, message, ecran=None):
        super().__init__(message)
        self.ecran = ecran


# ─── Doigts simulés : le serveur de scrcpy ───────────────────────────────────

class CanalScrcpy:
    """Doigts virtuels et images de l'écran. Le serveur de scrcpy tourne sur le téléphone avec
    les droits d'ADB : il injecte de vrais gestes tactiles, à plusieurs doigts, sans root (un
    message de 32 octets par doigt posé, déplacé ou levé), et, avec video=True, envoie l'écran
    en vidéo H.264 à 1170×540 (la résolution à laquelle le jeu dessine en 50 %) : une image
    toute fraîche coûte ~5 ms au lieu de ~1,5 s pour « adb screencap »."""

    CHEMIN_TEL = "/data/local/tmp/bot_coc-scrcpy-server.jar"
    BAS, HAUT, BOUGE = 0, 1, 2                 # doigt posé, levé, déplacé (actions Android)
    DOIGT1, DOIGT2 = 0x0B070001, 0x0B070002

    def __init__(self, adb, largeur, hauteur, video=False, debit=VIDEO_DEBIT):
        self.adb = list(adb)
        self.w, self.h = int(largeur), int(hauteur)
        self.proc = self.sock = self.port = None
        self.sortie = collections.deque(maxlen=30)
        self.video = video and av is not None
        self.debit = debit
        self.sock_video = None
        self.vw = self.vh = 0                  # taille de la vidéo (les touches s'y rapportent)
        self.image = None                      # dernière image décodée (av.VideoFrame)
        self.t_image = 0.0                     # quand elle est arrivée (time.monotonic)
        self.cond = threading.Condition()
        self.video_ok = False                  # True dès la première image décodée
        self.fini = not self.video             # True quand la vidéo s'est arrêtée

    @staticmethod
    def serveur():
        """(fichier du serveur, version) : celui livré avec le bot, sinon celui de scrcpy
        installé sur le PC (version 3.1 ou plus). None si aucun."""
        if SCRCPY_SERVEUR.is_file():
            return SCRCPY_SERVEUR, SCRCPY_VERSION
        exe = shutil.which("scrcpy")
        if not exe:
            return None
        try:
            texte = subprocess.run([exe, "--version"], capture_output=True, text=True, timeout=10).stdout
        except (OSError, subprocess.TimeoutExpired):
            return None
        m = re.search(r"scrcpy v?(\d+)\.(\d+)(\.\d+)?", texte)
        if not m or (int(m.group(1)), int(m.group(2))) < (3, 1):
            return None
        version = f"{m.group(1)}.{m.group(2)}{m.group(3) or ''}"
        for c in (os.environ.get("SCRCPY_SERVER_PATH"), Path(exe).resolve().parent / "scrcpy-server",
                  "/usr/share/scrcpy/scrcpy-server", "/usr/local/share/scrcpy/scrcpy-server"):
            if c and Path(c).is_file():
                return Path(c), version
        return None

    def _adb(self, *args, timeout=20):
        return subprocess.run(self.adb + [str(a) for a in args], capture_output=True, text=True, timeout=timeout)

    def _lire_sortie(self, proc):
        for ligne in iter(proc.stdout.readline, b""):
            self.sortie.append(ligne.decode(errors="replace").strip())

    def demarrer(self):
        """Envoie le serveur, le lance et s'y connecte. Renvoie "" si tout va bien, sinon
        la raison de l'échec."""
        trouve = self.serveur()
        if trouve is None:
            return f"serveur scrcpy introuvable ({SCRCPY_SERVEUR.relative_to(DOSSIER)} absent)"
        fichier, version = trouve
        try:
            r = self._adb("push", fichier, self.CHEMIN_TEL, timeout=60)
            if r.returncode != 0:
                return "envoi du serveur refusé : " + (r.stderr or r.stdout).strip()[:120]
        except (OSError, subprocess.TimeoutExpired) as e:
            return f"ADB : {e}"
        raison = ""
        for _ in range(2):                      # 2e essai si le serveur annonce une autre version
            raison = self._lancer(version)
            if not raison:
                return ""
            m = re.search(r"server version \(([\w.\-]+)\) does not match", raison)
            if not m:
                return raison
            version = m.group(1)
        return raison

    def _lancer(self, version):
        scid = random.randrange(0x100000, 0x7FFFFFFF)
        try:
            r = self._adb("forward", "tcp:0", f"localabstract:scrcpy_{scid:08x}")
        except (OSError, subprocess.TimeoutExpired) as e:
            return f"ADB : {e}"
        if r.returncode != 0 or not r.stdout.strip().isdigit():
            return "tunnel ADB refusé : " + (r.stderr or r.stdout).strip()[:120]
        self.port = int(r.stdout.strip())
        self.sortie.clear()
        if self.video:
            cote = int(round(max(self.w, self.h) * (RESOLUTION_BOT or 1.0)))
            options = ["video=true", "video_codec=h264", f"max_size={cote}", f"max_fps={VIDEO_IPS}",
                       f"video_bit_rate={self.debit}"]
        else:
            options = ["video=false"]
        self.proc = subprocess.Popen(
            self.adb + ["shell", f"CLASSPATH={self.CHEMIN_TEL}", "app_process", "/", "com.genymobile.scrcpy.Server",
                        version, f"scid={scid:08x}", "log_level=warn", *options, "audio=false",
                        "control=true", "tunnel_forward=true", "cleanup=false", "clipboard_autosync=false",
                        "power_on=false", "send_device_meta=false"],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        threading.Thread(target=self._lire_sortie, args=(self.proc,), daemon=True).start()
        # comme scrcpy : on se connecte en boucle jusqu'à recevoir l'octet témoin du serveur, sur
        # la première connexion (la vidéo s'il y en a une) ; la commande des doigts vient ensuite
        premier = None
        fin = time.monotonic() + 10
        while time.monotonic() < fin and self.proc.poll() is None:
            s = None
            try:
                s = socket.create_connection(("127.0.0.1", self.port), timeout=2)
                s.settimeout(2)
                if s.recv(1) == b"\x00":
                    premier, s = s, None
                    break
            except OSError:
                pass
            finally:
                if s is not None:
                    s.close()
            time.sleep(0.1)
        if premier is not None and self.video:
            try:
                self.sock = socket.create_connection(("127.0.0.1", self.port), timeout=5)
                self.sock_video = premier
            except OSError:
                premier.close()
        elif premier is not None:
            self.sock = premier
        if self.sock is not None:
            self.sock.settimeout(None)
            self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            threading.Thread(target=self._vider, args=(self.sock,), daemon=True).start()
            if self.sock_video is not None:
                self.sock_video.settimeout(None)
                threading.Thread(target=self._recevoir_video, args=(self.sock_video,), daemon=True).start()
            return ""
        time.sleep(0.2)
        texte = " ".join(self.sortie)
        self.fermer()
        return ("le serveur s'est arrêté : " + texte[:200]) if texte else "le serveur ne répond pas"

    @staticmethod
    def _vider(sock):
        """Le serveur peut renvoyer des messages (presse-papiers…) : lus et jetés."""
        try:
            while sock.recv(4096):
                pass
        except OSError:
            pass

    @staticmethod
    def _lire_n(sock, n):
        tampon = bytearray()
        while len(tampon) < n:
            morceau = sock.recv(n - len(tampon))
            if not morceau:
                raise EOFError("vidéo coupée")
            tampon += morceau
        return bytes(tampon)

    def _recevoir_video(self, sock):
        """Fil de fond : reçoit la vidéo et garde la dernière image décodée. Chaque paquet a un
        en-tête de 12 octets : bit 63 = paquet de session (largeur, hauteur de la vidéo), bit 62
        = configuration (SPS/PPS, collée au paquet suivant), sinon une image et sa taille."""
        config = b""
        try:
            if self._lire_n(sock, 4) != b"h264":
                raise ValueError("codec inattendu")
            decodeur = av.CodecContext.create("h264", "r")
            while True:
                entete = self._lire_n(sock, 12)
                if entete[0] & 0x80:
                    with self.cond:
                        self.vw, self.vh = struct.unpack(">II", entete[4:])
                    continue
                donnees = self._lire_n(sock, struct.unpack(">I", entete[8:])[0])
                if entete[0] & 0x40:
                    config = donnees
                    continue
                for image in decodeur.decode(av.Packet(config + donnees)):
                    with self.cond:
                        self.image, self.t_image = image, time.monotonic()
                        self.video_ok = True
                        self.cond.notify_all()
                config = b""
        except (OSError, EOFError, ValueError, struct.error, av.error.FFmpegError) as e:
            if self.sock_video is not None:          # pas une fermeture voulue
                self.sortie.append(f"vidéo : {e}")
        with self.cond:
            self.video_ok = False
            self.fini = True
            self.cond.notify_all()

    def image_apres(self, t, delai=0.6):
        """Première image arrivée après l'instant t (time.monotonic), en BGR à la taille de
        l'écran du téléphone ; None si la vidéo ne marche pas."""
        with self.cond:
            fin = time.monotonic() + delai
            while not self.fini and self.t_image <= t and time.monotonic() < fin:
                self.cond.wait(max(0.0, fin - time.monotonic()))
            if not self.video_ok or self.image is None or self.t_image <= t:
                return None                  # pas d'image neuve (écran éteint ?) : capture ADB
            image = self.image
        return image.to_ndarray(format="bgr24")         # à la taille de la vidéo (Ecran agrandit au besoin)

    def doigt(self, action, ident, x, y):
        pression = 0 if action == self.HAUT else 0xFFFF
        w, h = self.w, self.h
        if self.vw and self.vh:              # avec la vidéo, le serveur attend ses coordonnées
            x, y, w, h = x * self.vw / self.w, y * self.vh / self.h, self.vw, self.vh
        self.sock.sendall(struct.pack(">BBqiiHHHII", 2, action, ident, int(round(x)), int(round(y)),
                                      w & 0xFFFF, h & 0xFFFF, pression, 0, 0))

    def toucher(self, points, appui=APPUI, ecart=ECART_TOUCHES):
        for x, y in points:
            self.doigt(self.BAS, self.DOIGT1, x, y)
            time.sleep(appui)
            self.doigt(self.HAUT, self.DOIGT1, x, y)
            time.sleep(ecart)

    def glisser(self, x1, y1, x2, y2, duree=0.22, pas=12, tenir=0.0):
        self.doigt(self.BAS, self.DOIGT1, x1, y1)
        for k in range(1, pas + 1):
            time.sleep(duree / pas)
            self.doigt(self.BOUGE, self.DOIGT1, x1 + (x2 - x1) * k / pas, y1 + (y2 - y1) * k / pas)
        if tenir:
            time.sleep(tenir)
        self.doigt(self.HAUT, self.DOIGT1, x2, y2)

    def pincer(self, fixe, debut, fin, duree=0.28, pas=12):
        """Deux doigts posés au même instant : le premier reste en `fixe`, le second va de
        `debut` à `fin`. Le rapprocher dézoome, l'éloigner zoome."""
        self.doigt(self.BAS, self.DOIGT1, *fixe)
        self.doigt(self.BAS, self.DOIGT2, *debut)
        for k in range(1, pas + 1):
            time.sleep(duree / pas)
            self.doigt(self.BOUGE, self.DOIGT2, debut[0] + (fin[0] - debut[0]) * k / pas,
                       debut[1] + (fin[1] - debut[1]) * k / pas)
        time.sleep(0.05)
        self.doigt(self.HAUT, self.DOIGT2, *fin)
        self.doigt(self.HAUT, self.DOIGT1, *fixe)

    def fermer(self):
        video, self.sock_video = self.sock_video, None
        for s in (self.sock, video):
            if s is not None:
                try:
                    s.close()
                except OSError:
                    pass
        self.sock = None
        if self.proc is not None:
            try:
                self.proc.terminate()
                self.proc.wait(timeout=3)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    self.proc.kill()
                except OSError:
                    pass
            self.proc = None
        if self.port:
            try:
                self._adb("forward", "--remove", f"tcp:{self.port}", timeout=10)
            except (OSError, subprocess.TimeoutExpired):
                pass
            self.port = None


# ─── Téléphone (ADB) ─────────────────────────────────────────────────────────

def est_sans_fil(serie):
    return ":" in serie or "_adb-tls-connect" in serie


def appareils():
    """[(numéro, en Wi-Fi ?)] des téléphones prêts (état « device »)."""
    try:
        sortie = subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=20).stdout
    except subprocess.TimeoutExpired:
        return []
    champs = (ligne.split() for ligne in sortie.splitlines()[1:])
    return [(c[0], est_sans_fil(c[0])) for c in champs if len(c) >= 2 and c[1] == "device"]


def choisir_appareil():
    """Le câble d'abord (plus rapide, et il recharge le téléphone), sinon le Wi-Fi."""
    liste = sorted(appareils(), key=lambda a: a[1])
    return liste[0] if liste else (None, False)


def connecter_sans_fil():
    """Lance adb-a54, s'il existe (ton script qui allume le débogage sans fil et fait adb connect) :
    l'adresse ip:port, ou None."""
    try:
        r = subprocess.run(["adb-a54"], capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.TimeoutExpired):
        return None
    m = re.search(r"(\d+\.\d+\.\d+\.\d+:\d+)", r.stdout)
    if r.returncode != 0 or not m:
        lignes = r.stdout.strip().splitlines()
        log("  " + (lignes[-1] if lignes else "adb-a54 n'a rien répondu"))
        return None
    return m.group(1) if any(s == m.group(1) for s, _ in appareils()) else None


class Telephone:
    def __init__(self, serial=ADB_SERIAL):
        if shutil.which("adb") is None:
            sys.exit("adb introuvable. Sur CachyOS : sudo pacman -S android-tools")
        self.connecte_par_moi = False
        if serial is None:
            serial, sans_fil = choisir_appareil()
            if serial is None and shutil.which("adb-a54"):
                log("Pas de câble : je connecte le téléphone en Wi-Fi (adb-a54)")
                serial, sans_fil = connecter_sans_fil(), True
                self.connecte_par_moi = serial is not None
        else:
            sans_fil = est_sans_fil(serial)
        if serial is None:
            sys.exit("Téléphone introuvable : ni câble USB, ni Wi-Fi (branche le câble ou fais adb connect IP:PORT).")
        self.serie, self.sans_fil = serial, sans_fil
        self.adb = ["adb", "-s", serial]
        log("Téléphone en Wi-Fi" if sans_fil else "Téléphone en USB")
        self.png = False          # passe à True si la capture brute n'est pas lisible
        self.geo = None           # (largeur, hauteur, taille de l'en-tête) de la capture brute
        self.dims = None          # (largeur, hauteur) de la dernière capture complète
        self.bandes = True        # passe à False si le téléphone ne sait pas couper la capture
        self.temps = {"video": [0.0, 0], "capture": [0.0, 0], "bande": [0.0, 0], "touches": [0.0, 0]}
        self.ranger_serveur_adb()
        self.relances = 0         # redémarrages du serveur scrcpy après une coupure
        self.tactile = None       # écran tactile (ancien geste sendevent), lu au premier besoin
        self.canal = None         # doigts simulés par le serveur de scrcpy, lancés au premier besoin
        self.canal_essaye = False
        self.dezoom_verifie = False   # True dès qu'un pincement a fait bouger l'image
        self.remettre_resolution = False   # True : à l'arrêt, remettre le jeu en 100 %
        self.ancien_reglage = None
        if GARDER_ECRAN_ALLUME:
            valeur = self.shell("settings get global stay_on_while_plugged_in").strip()
            if valeur.isdigit():
                self.ancien_reglage = valeur
                self.shell("svc power stayon true")
            self.shell("input keyevent KEYCODE_WAKEUP")

    @staticmethod
    def ranger_serveur_adb():
        """Le serveur adb (il transporte la vidéo) sur les mêmes cœurs que le bot. Lancé par le
        bot, il en hérite ; déjà lancé avant, on le déplace."""
        try:
            pids = subprocess.run(["pgrep", "-f", "adb .*fork-server server"], capture_output=True,
                                  text=True).stdout.split()
            for pid in pids:
                os.sched_setaffinity(int(pid), os.sched_getaffinity(0))
        except (OSError, ValueError):
            pass

    def liaison(self):
        """En Wi-Fi, la liaison tombe quand le téléphone se verrouille ou change de réseau (le
        port change) : on le retrouve avec adb-a54. True si le téléphone répond."""
        if not self.sans_fil or any(s == self.serie for s, _ in appareils()):
            return True
        log("  liaison Wi-Fi perdue : je reconnecte le téléphone (adb-a54)")
        serie = connecter_sans_fil()
        if serie is None:
            log("  reconnexion impossible (téléphone verrouillé ou hors du Wi-Fi ?)")
            return False
        if self.canal is not None:
            self.canal.fermer()
        self.serie, self.adb = serie, ["adb", "-s", serie]
        self.canal, self.canal_essaye, self.relances, self.geo = None, False, 0, None
        return True

    def shell(self, commande):
        try:
            return subprocess.run(self.adb + ["shell", commande], capture_output=True,
                                  text=True, timeout=60).stdout
        except subprocess.TimeoutExpired:
            raise Bloque("ADB ne répond plus")

    def _exec(self, commande):
        try:
            return subprocess.run(self.adb + ["exec-out", commande], capture_output=True, timeout=20).stdout
        except subprocess.TimeoutExpired:
            raise Bloque("la capture d'écran ne répond plus")

    def _chrono(self, cle, t0, n=1):
        self.temps[cle][0] += time.perf_counter() - t0
        self.temps[cle][1] += n

    def video(self):
        """Le canal scrcpy si sa vidéo marche, sinon None."""
        canal = self.doigts() if VIDEO_SCRCPY else None
        return canal if canal is not None and canal.video_ok else None

    def capture(self):
        """Écran complet : une image de la vidéo arrivée après l'appel (~5 ms), sinon
        « adb screencap » (~1,5 s : 10 Mo à faire passer par le câble)."""
        canal = self.video()
        if canal is not None:
            t0 = time.perf_counter()
            petite = canal.image_apres(time.monotonic())
            if petite is not None:
                self.dims = (canal.w, canal.h)
                self._chrono("video", t0)
                return Ecran(petite=petite, taille=self.dims)
        return self._screencap()

    def _screencap(self):
        t0 = time.perf_counter()
        d, img = self._exec("screencap -p" if self.png else "screencap"), None
        if self.png:
            img = cv2.imdecode(np.frombuffer(d, np.uint8), cv2.IMREAD_COLOR) if d else None
        elif len(d) > 16:
            # format brut : largeur, hauteur, format (+ espace colorimétrique), puis les pixels RGBA
            w, h, fmt = struct.unpack_from("<3I", d)
            entete = len(d) - w * h * 4
            if fmt in (1, 2) and 12 <= entete <= 64:
                px = np.frombuffer(d, np.uint8, w * h * 4, entete).reshape(h, w, 4)
                img = cv2.cvtColor(px, cv2.COLOR_RGBA2BGR)
                self.geo = (w, h, entete)
            else:
                self.png = True                      # téléphone exotique : on passe au PNG
                return self._screencap()
        if img is None:
            raise Bloque("capture d'écran illisible (téléphone débranché ou verrouillé ?)")
        self.dims = (img.shape[1], img.shape[0])      # brute ou PNG : on retient la taille
        self._chrono("capture", t0)
        return Ecran(img)

    def capture_bande(self, y0, y1=None):
        """Seulement les lignes y0 à y1 : le téléphone coupe lui-même la capture, il n'en
        passe qu'une petite partie par le câble. Le reste de l'image est laissé noir.
        Avec la vidéo, l'écran entier ne coûte rien de plus : on le prend."""
        if self.video() is not None or self.geo is None or self.png or not self.bandes:
            return self.capture()
        w, h, entete = self.geo
        y0, y1 = max(0, int(y0)), h if y1 is None else min(h, int(y1))
        if y1 <= y0:
            return self.capture()
        t0 = time.perf_counter()
        taille = (y1 - y0) * w * 4
        d = self._exec(f"screencap | tail -c +{entete + y0 * w * 4 + 1} | head -c {taille}")
        if len(d) != taille:
            self.bandes = False                      # pas pris en charge : captures complètes
            return self.capture()
        img = np.zeros((h, w, 3), np.uint8)
        img[y0:y1] = cv2.cvtColor(np.frombuffer(d, np.uint8).reshape(y1 - y0, w, 4), cv2.COLOR_RGBA2BGR)
        self._chrono("bande", t0)
        return Ecran(img, partielle=True)

    def taille(self):
        """(largeur, hauteur) de l'écran tel que le jeu l'affiche (paysage), d'après la
        dernière capture, ou de « wm size » (le jeu est toujours en paysage)."""
        if self.dims is None:
            tailles = re.findall(r"(\d+)x(\d+)", self.shell("wm size"))
            if tailles:
                a, b = (int(v) for v in tailles[-1])
                self.dims = (max(a, b), min(a, b))
            else:
                self._screencap()
        return self.dims

    def doigts(self):
        """Les doigts simulés et la vidéo (serveur de scrcpy), démarrés au premier appel. None
        s'ils ne marchent pas : on passe alors par « adb shell input » et « screencap »."""
        if self.canal is None and not self.canal_essaye and DOIGTS_SCRCPY:
            self.canal_essaye = True
            w, h = self.taille()
            canal = CanalScrcpy(self.adb, w, h, video=VIDEO_SCRCPY,
                                debit=VIDEO_DEBIT_SANS_FIL if self.sans_fil else VIDEO_DEBIT)
            raison = canal.demarrer()
            if raison:
                log(f"  doigts simulés indisponibles ({raison}) : touches par « input », sans dézoom automatique")
            else:
                self.canal = canal
                if canal.video and canal.image_apres(0.0, delai=4.0) is not None:
                    log(f"  doigts simulés et vidéo prêts (serveur scrcpy, {canal.vw}×{canal.vh})")
                else:
                    log("  doigts simulés prêts (serveur scrcpy)" + (
                        "" if not VIDEO_SCRCPY else " ; vidéo indisponible : captures par ADB"
                        + ("" if av is not None else " (installe PyAV : .venv/bin/pip install av)")))
        return self.canal

    def _canal_perdu(self, e):
        log(f"  doigts simulés perdus ({e}) : je repasse par « input »")
        try:
            self.canal.fermer()
        except OSError:
            pass
        self.canal = None
        self.relances += 1
        self.canal_essaye = self.relances > 3        # on le relance (3 fois au plus)

    def toucher(self, *points, ecart=None):
        """Touche un ou plusieurs points, dans l'ordre (doigts simulés : ~40 ms par touche ;
        sinon un seul appel « input tap » enchaîné)."""
        if not points:
            return
        t0 = time.perf_counter()
        canal = self.doigts()
        if canal is not None:
            try:
                canal.toucher(points, ecart=ECART_TOUCHES if ecart is None else ecart)
                self._chrono("touches", t0, len(points))
                return
            except OSError as e:
                self._canal_perdu(e)
        self.shell("; ".join(f"input tap {int(x)} {int(y)}" for x, y in points))
        self._chrono("touches", t0, len(points))

    def glisser(self, x1, y1, x2, y2, ms=220, tenir=0.0):
        """Un doigt qui glisse : déplace la caméra (ne pose rien)."""
        canal = self.doigts()
        if canal is not None:
            try:
                canal.glisser(x1, y1, x2, y2, ms / 1000, tenir=tenir)
                return
            except OSError as e:
                self._canal_perdu(e)
        self.shell(f"input swipe {int(x1)} {int(y1)} {int(x2)} {int(y2)} {int(ms + 1000 * tenir)}")

    def pincer(self, dezoom=True):
        """Pincement à deux doigts : le premier posé au centre de l'écran (sur la base
        adverse, où rien ne peut être posé), le second qui s'en rapproche (dézoom) ou s'en
        éloigne (zoom). Renvoie False si aucun moyen de faire deux doigts ne marche."""
        canal = self.doigts()
        if canal is not None:
            w, h = self.taille()
            fixe, loin, pres = (0.50 * w, 0.45 * h), (0.88 * w, 0.45 * h), (0.56 * w, 0.45 * h)
            debut, fin = (loin, pres) if dezoom else (pres, loin)
            try:
                canal.pincer(fixe, debut, fin, duree=PINCEMENT_DUREE, pas=6)
                return True
            except OSError as e:
                self._canal_perdu(e)
        return dezoom and self._pincer_sendevent()

    def _pincer_sendevent(self):
        """Ancien geste : doigts écrits directement dans l'écran tactile (sendevent).
        Beaucoup de téléphones (dont les Samsung) le refusent sans root."""
        if self.tactile is None:
            self.tactile = analyser_getevent(self.shell("getevent -pl")) or False
            if self.tactile:
                log(f"  écran tactile : {self.tactile['dev']} ({self.tactile['nom']})")
        if not self.tactile:
            return False
        try:
            r = subprocess.run(self.adb + ["shell", "sh"], input=script_pincement(self.tactile),
                               capture_output=True, text=True, timeout=30)
        except subprocess.TimeoutExpired:
            return False
        erreur = (r.stdout + r.stderr).lower()
        if "denied" in erreur or "could not" in erreur or "not found" in erreur:
            log("  le téléphone refuse le geste à deux doigts : " + (r.stdout + r.stderr).strip()[:120])
            self.tactile = False
            return False
        return True

    def bilan_temps(self):
        def moy(cle):
            t, n = self.temps[cle]
            return f"{1000 * t / n:.0f} ms" if n else "—"
        return (f"image vidéo {moy('video')}, capture ADB {moy('capture')}, capture partielle "
                f"{moy('bande')}, touche {moy('touches')} (moyennes)")

    # ── Résolution du jeu (50 % pendant le bot, 100 % pour toi) ──

    def echelle_jeu(self):
        """Résolution à laquelle le jeu dessine vraiment (0.5 = 50 %), d'après la taille de son
        image dans le téléphone. None si le jeu n'est pas affiché."""
        sortie = self.shell(f"dumpsys window 2>/dev/null | grep -m1 mCurrentFocus; dumpsys SurfaceFlinger "
                            f"| grep 'SurfaceView.{JEU}' | grep -o 'w/h:[0-9]*x[0-9]*'")
        if JEU not in sortie.split("\n", 1)[0]:
            return None             # caché derrière l'accueil : sa surface reste dans SurfaceFlinger
        tailles = re.findall(r"w/h:(\d+)x(\d+)", sortie)
        ecran = re.findall(r"(\d+)x(\d+)", self.shell("wm size"))
        if not tailles or not ecran:
            return None
        w, h = collections.Counter(tailles).most_common(1)[0][0]
        return min(int(w), int(h)) / min(int(v) for v in ecran[-1])

    def _attendre_echelle(self, voulue, delai=45):
        fin = time.monotonic() + delai
        while time.monotonic() < fin:
            e = self.echelle_jeu()
            if e is not None and abs(e - voulue) < 0.05:
                return True
            time.sleep(1)
        return False

    def passer_en(self, echelle):
        """Règle la résolution du jeu (0.5 ou 1.0) et le redémarre pour qu'elle prenne effet.
        Renvoie (réglage accepté, le jeu dessine bien à cette résolution)."""
        if echelle >= 0.99:
            self.shell(f"cmd game reset {JEU}")              # réglage normal du téléphone
            accepte = "current mode: custom" not in self.shell(f"cmd game list-modes {JEU}")
        else:
            # deux fois : venant du mode « standard », Android passe en mode « custom » au 1er
            # appel sans appliquer la résolution ; le 2e l'applique.
            for _ in range(2):
                accepte = f"downscaling-ratio: {echelle:g}" in self.shell(
                    f"cmd game set --downscale {echelle:g} {JEU}")
        if not accepte:
            return False, False
        for _ in range(3):              # le réglage met un instant à s'appliquer : on relance
            time.sleep(2)               # le jeu jusqu'à ce qu'il dessine à la bonne résolution
            if self.relancer_jeu(echelle, delai=20):
                return True, True
        return True, False

    def relancer_jeu(self, echelle=None, delai=30):
        """Ferme et relance Clash of Clans (connexion perdue, « Recharger le jeu », écran
        inconnu). True dès que le jeu s'affiche (à la résolution voulue, si elle est donnée)."""
        self.shell(f"am force-stop {JEU}")
        self.shell(f"monkey -p {JEU} -c android.intent.category.LAUNCHER 1")
        fin = time.monotonic() + delai
        while time.monotonic() < fin:
            e = self.echelle_jeu()
            if e is not None and (echelle is None or abs(e - echelle) < 0.05):
                return True
            time.sleep(1)
        return False

    def resolution_du_bot(self):
        """Au lancement du bot : met le jeu en 50 % s'il n'y est pas déjà."""
        if RESOLUTION_BOT is None:
            return
        e = self.echelle_jeu()
        if e is None:
            log("Clash of Clans n'est pas affiché : je le lance")
            self.shell(f"monkey -p {JEU} -c android.intent.category.LAUNCHER 1")
            for _ in range(30):
                time.sleep(1)
                e = self.echelle_jeu()
                if e is not None:
                    break
        if MEMO_RESOLUTION.exists():
            self.remettre_resolution = REMETTRE_A_LA_FIN    # laissé en 50 % par un arrêt brutal
        if e is not None and abs(e - RESOLUTION_BOT) < 0.05:
            return                                          # déjà bon : rien à redémarrer
        log(f"Jeu affiché en {e:.0%} : je le passe en {RESOLUTION_BOT:.0%} le temps du bot "
            f"(il redémarre, ~15 s)" if e else f"Je passe le jeu en {RESOLUTION_BOT:.0%} (il redémarre)")
        MEMO_RESOLUTION.write_text(f"{JEU} : remettre la résolution normale (cmd game reset)\n")
        self.remettre_resolution = REMETTRE_A_LA_FIN      # même si Ctrl+C pendant le changement
        accepte, effectif = self.passer_en(RESOLUTION_BOT)
        if not accepte:
            MEMO_RESOLUTION.unlink(missing_ok=True)
            self.remettre_resolution = False
            log(f"  le téléphone refuse de changer la résolution : mets le jeu en "
                f"{RESOLUTION_BOT:.0%} à la main, sinon les boutons ne seront pas reconnus")
            return
        log(f"  jeu en {RESOLUTION_BOT:.0%}" if effectif else
            "  réglage fait, mais je ne vois pas encore le jeu (écran verrouillé ?)")

    def avertir_resolution(self):
        """Modes test, capture… : prévient si le jeu n'est pas dans la résolution du bot."""
        e = self.echelle_jeu() if RESOLUTION_BOT else None
        if e is not None and abs(e - RESOLUTION_BOT) >= 0.05:
            print(f"Attention : le jeu est affiché en {e:.0%}, le bot est réglé pour "
                  f"{RESOLUTION_BOT:.0%} : rien ne sera reconnu. Lance d'abord :\n"
                  f"  .venv/bin/python bot_coc.py resolution {RESOLUTION_BOT * 100:.0f}\n")

    def fermer(self):
        if self.canal is not None:
            self.canal.fermer()
            self.canal = None
        if self.connecte_par_moi:
            subprocess.run(["adb", "disconnect", self.serie], capture_output=True, timeout=20)
        if self.remettre_resolution and MEMO_RESOLUTION.exists():
            log("Je remets le jeu en résolution normale (il redémarre, ~15 s)…")
            try:
                accepte, effectif = self.passer_en(1.0)
                if effectif:
                    log("  jeu en 100 %")
                elif accepte:
                    log("  réglage remis : le jeu sera en 100 % à son prochain lancement")
                else:
                    log("  échec : relance « .venv/bin/python bot_coc.py resolution 100 »")
                if accepte:
                    MEMO_RESOLUTION.unlink(missing_ok=True)
            except Bloque:
                log("  ADB ne répond plus : relance « bot_coc.py resolution 100 »")
        if self.ancien_reglage is not None:
            try:
                self.shell(f"settings put global stay_on_while_plugged_in {self.ancien_reglage}")
            except Bloque:
                pass


def analyser_getevent(texte):
    """L'écran tactile multipoint, d'après la sortie de « getevent -pl » : son fichier
    /dev/input/eventN, l'étendue de ses coordonnées et les événements qu'il comprend."""
    meilleur = None
    for bloc in re.split(r"add device \d+: ", texte)[1:]:
        def etendue(code):
            m = re.search(code + r"\s*:\s*value -?\d+, min (-?\d+), max (-?\d+)", bloc)
            return (int(m.group(1)), int(m.group(2))) if m else None
        x, y = etendue("ABS_MT_POSITION_X"), etendue("ABS_MT_POSITION_Y")
        if not x or not y:
            continue
        nom = re.search(r'name:\s+"([^"]*)"', bloc)
        info = {"dev": bloc.splitlines()[0].strip(), "nom": nom.group(1) if nom else "?", "x": x, "y": y,
                "slot": "ABS_MT_SLOT" in bloc, "pression": etendue("ABS_MT_PRESSURE"),
                "taille": etendue("ABS_MT_TOUCH_MAJOR"), "btn_touch": "BTN_TOUCH" in bloc,
                "btn_doigt": "BTN_TOOL_FINGER" in bloc}
        score = 2 * ("INPUT_PROP_DIRECT" in bloc) + ("touch" in info["nom"].lower())
        if meilleur is None or score > meilleur[0]:
            meilleur = (score, info)
    return meilleur[1] if meilleur else None


def script_pincement(t, pas=12):
    """Script shell (sendevent) d'un pincement : deux doigts posés ensemble au centre de la
    dalle, écartés le long de son grand côté, qui se rapprochent. Le geste est le même quelle
    que soit l'orientation de l'écran, donc pas besoin de la connaître."""
    (xa, xb), (ya, yb) = t["x"], t["y"]
    cx, cy = (xa + xb) // 2, (ya + yb) // 2
    long_y = (yb - ya) >= (xb - xa)
    longueur = (yb - ya) if long_y else (xb - xa)
    lignes = []

    def ev(type_, code, valeur):
        lignes.append(f"sendevent {t['dev']} {type_} {code} {int(valeur)}")

    def doigt(slot, d, ident=None):
        if t["slot"]:
            ev(3, 47, slot)                                   # ABS_MT_SLOT
        if ident is not None:
            ev(3, 57, ident)                                  # ABS_MT_TRACKING_ID
        ev(3, 53, cx if long_y else cx + d)                   # ABS_MT_POSITION_X
        ev(3, 54, cy + d if long_y else cy)                   # ABS_MT_POSITION_Y
        if ident is not None and t["pression"]:
            ev(3, 58, max(1, t["pression"][1] // 2))          # ABS_MT_PRESSURE
        if ident is not None and t["taille"]:
            ev(3, 48, max(1, min(8, t["taille"][1])))         # ABS_MT_TOUCH_MAJOR
        if not t["slot"]:
            ev(0, 2, 0)                                       # SYN_MT_REPORT (ancien protocole)

    debut, fin = 0.30 * longueur, 0.04 * longueur
    doigt(0, -debut, 4801)
    doigt(1, debut, 4802)
    if t["btn_touch"]:
        ev(1, 330, 1)                                         # BTN_TOUCH
    if t["btn_doigt"]:
        ev(1, 325, 1)                                         # BTN_TOOL_FINGER
    ev(0, 0, 0)                                               # SYN_REPORT
    for k in range(1, pas + 1):
        d = debut + (fin - debut) * k / pas
        doigt(0, -d)
        doigt(1, d)
        ev(0, 0, 0)
        lignes.append("sleep 0.015")
    for slot in (0, 1):
        if t["slot"]:
            ev(3, 47, slot)
        ev(3, 57, -1)                                         # doigt levé
    if t["btn_touch"]:
        ev(1, 330, 0)
    if t["btn_doigt"]:
        ev(1, 325, 0)
    ev(0, 0, 0)
    return "\n".join(lignes) + "\n"


class ImageFixe:
    """Remplace le téléphone par une capture enregistrée (modes test, capture, chiffres)."""

    def __init__(self, chemin):
        img = cv2.imread(str(chemin))
        if img is None:
            sys.exit(f"Image illisible : {chemin}")
        self.ecran = Ecran(img)
        self.dezoom_verifie = True

    def capture(self):
        return self.ecran

    def capture_bande(self, y0, y1=None):
        return self.ecran

    def taille(self):
        return self.ecran.w, self.ecran.h

    def toucher(self, *points, ecart=None):
        pass

    def glisser(self, *args, **kwargs):
        pass

    def pincer(self, dezoom=True):
        return True

    def fermer(self):
        pass


# ─── Reconnaissance des boutons ──────────────────────────────────────────────

_templates = {}
_echelle_connue = {}
_zone_connue = {}        # dernière position de chaque bouton : on y regarde d'abord
_derniere_recherche = {}  # quand chaque bouton a été cherché sur tout l'écran pour la dernière fois
_derniere_complete = {}   # … et à toutes les tailles
MEMO_BOUTONS = TEMPLATES / "boutons.json"   # taille et place de chaque bouton, d'une session à l'autre
_memo_charge = False


def _signature(nom):
    """Taille et date du fichier du template : si tu le recaptures, son ancienne taille et
    sa place retenues sont oubliées."""
    try:
        st = (TEMPLATES / f"{nom}.png").stat()
        return [st.st_size, int(st.st_mtime)]
    except OSError:
        return None


def _charger_memo():
    """Reprend la taille et la place des boutons trouvés lors des sessions précédentes : plus
    besoin d'essayer 13 tailles sur tout l'écran (~100 ms) pour chacun."""
    global _memo_charge
    if _memo_charge:
        return
    _memo_charge = True
    try:
        d = json.loads(MEMO_BOUTONS.read_text())
        if d.get("reduction") != REDUCTION:
            return
        for nom, v in d.get("boutons", {}).items():
            if v.get("fichier") == _signature(nom):
                _echelle_connue.setdefault(nom, float(v["echelle"]))
                _zone_connue.setdefault(nom, tuple(int(a) for a in v["zone"]))
    except (OSError, ValueError, KeyError, TypeError):
        pass


def _retenir(nom, e, zone):
    """Mémorise la taille et la place d'un bouton ; écrit le fichier si elles ont changé."""
    ancienne = _zone_connue.get(nom)
    _echelle_connue[nom], _zone_connue[nom] = e, tuple(int(a) for a in zone)
    if ancienne is not None and max(abs(a - b) for a, b in zip(ancienne, zone)) <= 3:
        return
    try:
        MEMO_BOUTONS.write_text(json.dumps({"reduction": REDUCTION, "boutons": {
            n: {"echelle": _echelle_connue[n], "zone": list(z), "fichier": _signature(n)}
            for n, z in sorted(_zone_connue.items()) if n in _echelle_connue}}, indent=1))
    except OSError:
        pass


def _charger(nom):
    """(gris réduit pour la recherche, couleur moyenne, contraste) d'un template, ou None."""
    if nom not in _templates:
        chemin = TEMPLATES / f"{nom}.png"
        img = cv2.imread(str(chemin), cv2.IMREAD_COLOR) if chemin.exists() else None
        if img is None:
            _templates[nom] = None
        else:
            gris = cv2.resize(cv2.cvtColor(img, cv2.COLOR_BGR2GRAY), None, fx=REDUCTION,
                              fy=REDUCTION, interpolation=cv2.INTER_AREA)
            _templates[nom] = (gris, img.reshape(-1, 3).mean(axis=0), float(img.std()))
    return _templates[nom]


def template(nom):
    t = _charger(nom)
    return None if t is None else t[0]


def _ressemble(ecran, nom, boite):
    """Contrôle après la recherche en gris : même couleur moyenne et même contraste que le
    template. Écarte les faux positifs sur les zones unies ou d'une autre couleur."""
    _, couleur, contraste = _charger(nom)
    x, y, w, h = boite
    zone = ecran.img[max(0, y):y + h, max(0, x):x + w]
    if zone.size == 0:
        return False
    ecart = float(np.abs(zone.reshape(-1, 3).mean(axis=0) - couleur).max())
    return ecart < 40 and float(zone.std()) > 0.5 * contraste


def popups():
    dossier = TEMPLATES / "popups"
    return sorted(f"popups/{p.stem}" for p in dossier.glob("*.png")) if dossier.is_dir() else []


def _comparer(gris, tpl, e, pics=1):
    """matchTemplate du template mis à l'échelle e : les `pics` meilleurs emplacements,
    [(score, x, y, largeur, hauteur, e)], du plus fort au plus faible."""
    t = tpl if e == 1.0 else cv2.resize(
        tpl, None, fx=e, fy=e, interpolation=cv2.INTER_AREA if e < 1 else cv2.INTER_LINEAR)
    th, tw = t.shape
    if min(th, tw) < 6 or th > gris.shape[0] or tw > gris.shape[1]:
        return []
    res = cv2.matchTemplate(gris, t, cv2.TM_CCOEFF_NORMED)
    sortie = []
    for _ in range(pics):
        _, score, _, (x, y) = cv2.minMaxLoc(res)
        sortie.append((float(score), x, y, tw, th, e))
        cv2.rectangle(res, (x - tw // 2, y - th // 2), (x + tw // 2, y + th // 2), -1.0, -1)
    return sortie


def _trouve(nom, score, x, y, tw, th):
    k = 1 / REDUCTION
    return Trouve(nom, int((x + tw / 2) * k), int((y + th / 2) * k),
                  (int(x * k), int(y * k), int(tw * k), int(th * k)), float(score))


def seuil_de(nom):
    return SEUIL_POPUP if nom.startswith("popups/") else SEUIL


def chercher(ecran, nom):
    """Meilleure correspondance d'un template (None si pas de fichier), même sous le seuil.
    Regarde d'abord là où le bouton était la dernière fois (très rapide), puis tout l'écran,
    à plusieurs tailles tant que la bonne n'est pas connue. Un emplacement qui dépasse le
    seuil n'est accepté que si sa couleur et son contraste ressemblent au template."""
    if _charger(nom) is None:
        return None
    _charger_memo()
    tpl, g, seuil = _charger(nom)[0], ecran.gris, seuil_de(nom)
    proche = None
    if nom in _zone_connue and nom in _echelle_connue:
        x, y, tw, th = _zone_connue[nom]
        mx, my = tw // 2 + 4, th // 2 + 4
        xa, ya = max(0, x - mx), max(0, y - my)
        for r in _comparer(g[ya:y + th + my, xa:x + tw + mx], tpl, _echelle_connue[nom]):
            t = _trouve(nom, r[0], r[1] + xa, r[2] + ya, r[3], r[4])
            if t.score >= seuil:
                t.valide = _ressemble(ecran, nom, t.boite)
                if t.valide:
                    _retenir(nom, _echelle_connue[nom], (r[1] + xa, r[2] + ya, r[3], r[4]))
                    return t
            proche = t
    # Pas à sa place habituelle : tout l'écran, mais pas plus d'une fois toutes les 0,6 s
    # (toutes les 2 s pour un bouton jamais vu, qu'il faut chercher à 13 tailles) : un bouton
    # absent qu'on guette en boucle ne coûte alors presque plus rien.
    maintenant = time.monotonic()
    if maintenant - _derniere_recherche.get(nom, -99.0) < (0.6 if nom in _echelle_connue else 3.0):
        return proche or Trouve(nom, 0, 0, (0, 0, 0, 0), 0.0, False)
    _derniere_recherche[nom] = maintenant
    if nom in _echelle_connue:
        # la taille retenue, puis la taille normale ; et toutes les 6 s, toutes les tailles : une
        # taille mal retenue (image prise pendant l'animation d'ouverture d'un menu, où tout est
        # 5 % plus gros) se répare toute seule au lieu de bloquer le bot
        e0 = _echelle_connue[nom]
        echelles = [e0] + ([1.0] if e0 != 1.0 else [])
        if maintenant - _derniere_complete.get(nom, -99.0) > 6.0:
            _derniere_complete[nom] = maintenant
            echelles += [e for e in ECHELLES if e not in echelles]
    else:
        echelles = list(ECHELLES)
    meilleur, meilleure_e, affine = None, None, False
    while echelles:
        e = echelles.pop(0)
        for r in _comparer(g, tpl, e, pics=3):
            t = _trouve(nom, *r[:5])
            if t.score >= seuil:
                t.valide = _ressemble(ecran, nom, t.boite)
                if t.valide:
                    _retenir(nom, e, r[1:5])
                    return t
            if meilleur is None or (t.valide, t.score) > (meilleur.valide, meilleur.score):
                meilleur, meilleure_e = t, e
        if not echelles and not affine and meilleur and meilleur.score >= 0.6:
            affine = True                                   # affinage autour de la meilleure taille
            echelles = [meilleure_e - 0.025, meilleure_e + 0.025]
            if nom in _echelle_connue:                      # taille retenue qui ne colle plus tout à fait
                echelles += [meilleure_e - 0.05, meilleure_e + 0.05]
    return meilleur


def localiser(ecran, nom):
    """Équivalent de locateCenterOnScreen : le bouton s'il est à l'écran, sinon None."""
    t = chercher(ecran, nom)
    return t if t and t.valide and t.score >= seuil_de(nom) else None


def lignes_de(nom, marge=12):
    """Lignes d'écran (y0, y1) où se trouvait ce bouton la dernière fois, ou None."""
    if nom not in _zone_connue:
        return None
    _, y, _, th = _zone_connue[nom]
    return int(y / REDUCTION) - marge, int((y + th) / REDUCTION) + marge


def attendre(tel, cibles, delai, en_route=(), pas=0.2, patients=None):
    """Capture en boucle jusqu'à voir l'un des templates `cibles`. En attendant,
    touche les popups connus puis les boutons `en_route` (dans cet ordre de priorité), une
    fois l'écran posé (bouton vu deux fois de suite au même endroit). `patients` : boutons
    touchés seulement s'ils restent affichés tant de secondes ({nom: secondes}).
    Quand on sait déjà où sont tous ces boutons, on ne capture que leurs lignes
    (beaucoup plus rapide), avec une capture complète de temps en temps par sécurité."""
    fin = time.monotonic() + delai
    derniere_touche = {}
    ecran, tour = None, 0
    noms = [*cibles, *en_route, *popups()]
    vus = {}                    # boutons vus au tour précédent (pour ne pas toucher pendant une animation)
    depuis = {}                 # boutons « patients » : affichés sans interruption depuis quand
    while time.monotonic() < fin:
        lignes = [lignes_de(n) for n in noms]
        if tour % 5 and all(lignes):
            ecran = tel.capture_bande(min(l[0] for l in lignes), max(l[1] for l in lignes))
        else:
            ecran = tel.capture()
        tour += 1
        for nom in cibles:
            t = localiser(ecran, nom)
            if t:
                return ecran, t
        nouveaux = {}
        for nom in [*popups(), *en_route]:            # une fenêtre imprévue passe devant tout
            t = localiser(ecran, nom)
            if not t:
                continue
            nouveaux[nom] = (t.x, t.y)
            # le bouton le plus prioritaire à l'écran, s'il était déjà là au tour d'avant (sinon
            # c'est peut-être une image d'animation : on attend le tour suivant)
            if pose(vus, nom, t) and time.monotonic() - derniere_touche.get(nom, 0) > 2.5:
                log(f"  → {nom}")
                tel.toucher((t.x, t.y))
                derniere_touche[nom] = time.monotonic()
            break
        vus = nouveaux
        for nom, duree in (patients or {}).items():
            t = localiser(ecran, nom)
            if not t:
                depuis.pop(nom, None)
            elif time.monotonic() - depuis.setdefault(nom, time.monotonic()) >= duree:
                log(f"  → {nom} (affiché depuis {duree:g} s)")
                tel.toucher((t.x, t.y))
                depuis.pop(nom)
        time.sleep(pas)
    raise Bloque(f"« {' / '.join(cibles)} » n'est pas apparu en {delai} s", ecran)


def pose(vus, nom, t, tolerance=12):
    """Le bouton était-il déjà au même endroit au tour précédent (écran immobile) ?"""
    p = vus.get(nom)
    return p is not None and abs(p[0] - t.x) <= tolerance and abs(p[1] - t.y) <= tolerance


# ─── Lecture des chiffres (butin, compteurs « x50 ») ─────────────────────────

_glyphes = None


def modeles_glyphes():
    global _glyphes
    if _glyphes is None:
        _glyphes = {}
        for f in sorted(GLYPHES.glob("*.png")):
            img = cv2.imread(str(f), cv2.IMREAD_GRAYSCALE)
            if img is not None:
                _glyphes.setdefault(f.stem.split("_")[0], []).append(img.astype(np.float32) / 255)
    return _glyphes


def glyphes(roi, h_ref):
    """Caractères d'une ligne de texte du jeu (clairs, cernés de noir), de gauche à
    droite, découpés s'ils se touchent. Le décor derrière n'a pas d'importance."""
    hsv = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
    m = ((hsv[..., 2] > 165) & (hsv[..., 1] < 90)).astype(np.uint8)
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, 8)
    cands = [(i, st[i]) for i in range(1, n)
             if 0.4 * h_ref <= st[i][3] <= 0.85 * h_ref and st[i][4] >= 0.035 * h_ref * h_ref]
    if not cands:
        return []
    haut = np.median([s[1] for _, s in cands])
    hh = np.median([s[3] for _, s in cands])
    cands = sorted([(i, s) for i, s in cands
                    if abs(s[1] - haut) < 0.3 * hh and abs(s[3] - hh) < 0.3 * hh], key=lambda c: c[1][0])
    larges = [s[2] for _, s in cands if s[2] > 0.45 * hh]
    lt = float(np.median(larges)) if larges else 0.75 * hh
    sortie, fin_prec = [], None
    for i, (x, y, w, h, _) in cands:
        if fin_prec is not None and x - fin_prec > 1.6 * lt:
            break                                        # grand trou : fin du nombre
        mk = (lab[y:y + h, x:x + w] == i).astype(np.uint8)
        k = int(round(w / lt)) if w > 1.45 * lt else 1
        if k == 1:
            sortie.append(mk)
        else:                                            # chiffres collés : découpe aux creux
            cols = mk.sum(axis=0).astype(float)
            coupes = [0]
            for j in range(1, k):
                c = int(w * j / k)
                a, b = max(1, c - int(0.25 * lt)), min(w - 1, c + int(0.25 * lt))
                coupes.append(a + int(np.argmin(cols[a:b])) if b > a else c)
            coupes.append(w)
            for a, b in zip(coupes, coupes[1:]):
                part = mk[:, a:b]
                lignes = np.flatnonzero(part.any(axis=1))
                if len(lignes):
                    sortie.append(part[lignes[0]:lignes[-1] + 1])
        fin_prec = x + w
    return sortie


def normaliser(mk):
    """Masque d'un caractère centré dans un cadre fixe de 24×28 (proportions conservées)."""
    h, w = mk.shape
    largeur = max(w, int(round(0.9 * h)))
    boite = np.zeros((h, largeur), np.float32)
    boite[:, (largeur - w) // 2:(largeur - w) // 2 + w] = mk
    return cv2.resize(boite, (24, 28), interpolation=cv2.INTER_AREA)


def reconnaitre(mk, alphabet=None):
    """(caractère, score de 0 à 1) le plus ressemblant parmi les modèles de glyphes/."""
    v = normaliser(mk).ravel()
    v = v - v.mean()
    nv = np.sqrt((v * v).sum())
    meilleur = (None, -1.0)
    for c, liste in modeles_glyphes().items():
        if alphabet and c not in alphabet:
            continue
        for t in liste:
            t = t.ravel() - t.mean()
            d = nv * np.sqrt((t * t).sum())
            s = float((v * t).sum() / d) if d else 0.0
            if s > meilleur[1]:
                meilleur = (c, s)
    return meilleur


def tache(masque, aire_min):
    """Plus grande tache pleine et à peu près ronde d'un masque : (x, y, w, h) ou None."""
    n, _, st, _ = cv2.connectedComponentsWithStats(masque.astype(np.uint8), 8)
    choix = None
    for i in range(1, n):
        x, y, w, h, a = st[i]
        if a >= aire_min and 0.7 <= w / h <= 1.4 and a >= 0.55 * w * h and (choix is None or a > choix[4]):
            choix = st[i]
    return None if choix is None else tuple(int(v) for v in choix[:4])


def lignes_butin(ecran):
    """Chiffres (masques) des trois lignes du butin disponible : or, élixir, élixir noir.
    Repère la pièce d'or et la goutte rose par leur couleur, puis prend les chiffres à
    droite. None si l'écran n'affiche pas de butin."""
    zone = ecran.img[:int(0.40 * ecran.h), :int(0.25 * ecran.w)]
    hsv = cv2.cvtColor(zone, cv2.COLOR_BGR2HSV)
    t, s, v = (hsv[..., k].astype(np.int16) for k in range(3))
    aire_min = 0.002 * zone.shape[0] * zone.shape[1]
    piece = tache((t >= 15) & (t <= 35) & (s > 140) & (v > 170), aire_min)
    if piece is None:
        return None
    x, y, w, h = piece
    pas = 1.48 * h                                       # écart entre deux lignes
    goutte = tache((t >= 135) & (t <= 170) & (s > 110) & (v > 120), 0.6 * aire_min)
    if goutte:
        dy = goutte[1] + goutte[3] / 2 - (y + h / 2)
        if abs(goutte[0] + goutte[2] / 2 - (x + w / 2)) < w and 1.1 * h < dy < 2.0 * h:
            pas = dy
    lignes = []
    for k in range(3):
        cy = y + h / 2 + k * pas
        roi = zone[max(0, int(cy - 0.62 * h)):int(cy + 0.62 * h), int(x + 1.1 * w):int(x + 7.5 * w)]
        lignes.append(glyphes(roi, h) if roi.size else [])
    return lignes


def rogner(mk):
    """Masque débarrassé de ses lignes et colonnes vides."""
    lignes, colonnes = np.flatnonzero(mk.any(axis=1)), np.flatnonzero(mk.any(axis=0))
    if not len(lignes) or not len(colonnes):
        return mk
    return mk[lignes[0]:lignes[-1] + 1, colonnes[0]:colonnes[-1] + 1]


def lire_chiffres(masques):
    """[(chiffre, score)] des masques de glyphes(). Un glyphe large et mal reconnu est essayé
    coupé en deux à chaque colonne : deux chiffres collés, comme le « 19 » de « 194 048 » (un
    « 1 » est si fin que la paire ne paraît pas assez large pour être coupée d'office)."""
    sortie = []
    for m in masques:
        lu = reconnaitre(m, "0123456789")
        h, w = m.shape
        if lu[1] < 0.65 and w > 0.9 * h:
            meilleure = None
            for x in range(int(0.2 * w), int(0.8 * w) + 1):
                g, d = rogner(m[:, :x]), rogner(m[:, x:])
                if g.size and d.size:
                    paire = [reconnaitre(g, "0123456789"), reconnaitre(d, "0123456789")]
                    if meilleure is None or min(sc for _, sc in paire) > min(sc for _, sc in meilleure):
                        meilleure = paire
            if meilleure and min(sc for _, sc in meilleure) >= 0.65:
                sortie += meilleure
                continue
        sortie.append(lu)
    return sortie


def lire_butin(ecran):
    """Butin disponible affiché en haut à gauche pendant l'observation, ou None."""
    lignes = lignes_butin(ecran)
    if lignes is None:
        return None
    valeurs = []
    for k, masques in enumerate(lignes):
        lus = lire_chiffres(masques)
        if lus and min(sc for _, sc in lus) >= 0.65:
            valeurs.append(int("".join(c for c, _ in lus)))
        elif k == 2:
            valeurs.append(0)                            # pas de ligne d'élixir noir
        else:
            return None
    return Butin(*valeurs)


def lire_degats(ecran):
    """« Dégâts généraux » (0 à 100) affichés en bas à droite pendant un combat, ou None."""
    w, h = ecran.w, ecran.h
    roi = ecran.zone(0.885 * w, 0.722 * h, 0.956 * w, 0.780 * h)
    lus = lire_chiffres(glyphes(roi, 0.042 * h))
    if not lus or min(s for _, s in lus) < 0.65:
        return None
    valeur = int("".join(c for c, _ in lus))
    return valeur if valeur <= 100 else None


def etoiles(ecran):
    """Étoiles gagnées (0 à 3), à gauche des « Dégâts généraux » : sombres tant qu'elles ne
    sont pas gagnées, claires ensuite."""
    w, h = ecran.w, ecran.h
    n = 0
    for fx in (0.8573, 0.8782, 0.8983):
        z = ecran.zone((fx - 0.005) * w, 0.737 * h, (fx + 0.005) * w, 0.759 * h)
        if z.size and float((cv2.cvtColor(z, cv2.COLOR_BGR2HSV)[..., 2] > 170).mean()) > 0.3:
            n += 1
    return n


def lire_compteur(ecran, carte):
    """Nombre affiché en haut d'une carte (« x50 » → 50), ou None (héros, engin de siège…).
    La zone lue déborde au-dessus de la carte : une carte sélectionnée remonte un peu."""
    w, h = carte.x1 - carte.x0, carte.y1 - carte.y0
    roi = ecran.img[max(0, carte.y0 - int(0.2 * h)):carte.y0 + int(0.3 * h),
                    carte.x0 + int(0.25 * w):carte.x1]
    lus = [reconnaitre(m) for m in glyphes(roi, 0.2 * h)]
    for i, (c, s) in enumerate(lus):                     # le « x » puis les chiffres qui suivent
        if c == "x" and s >= 0.75:
            chiffres = lus[i + 1:]
            if chiffres and all(d.isdigit() and sd >= 0.65 for d, sd in chiffres):
                return int("".join(d for d, _ in chiffres))
            break
    return None


# ─── Combat : barre des troupes, état du combat ──────────────────────────────

def en_combat(ecran, boite):
    """Vrai tant que le bouton rouge « Terminer la bataille » / « Abandonner » est là.
    Dès qu'il disparaît (écran de fin, village…), on ne touche plus rien au hasard."""
    x, y, w, h = boite
    hsv = cv2.cvtColor(ecran.zone(x, y, x + w, y + h), cv2.COLOR_BGR2HSV)
    teinte, sat, val = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    return float((((teinte < 10) | (teinte > 170)) & (sat > 120) & (val > 90)).mean()) > 0.2


def bande_rouge(boite):
    return boite[1] - 4, boite[1] + boite[3] + 4


def taux_couleur(img):
    """Part de pixels vifs et colorés : tombe presque à zéro quand une carte grise."""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    return float(((hsv[..., 1] > 90) & (hsv[..., 2] > 90)).mean())


def segments(masque):
    """Plages [début, fin) où un masque 1D est vrai."""
    bords = np.flatnonzero(np.diff(np.concatenate(([0], masque.astype(np.int8), [0]))))
    return list(zip(bords[::2], bords[1::2]))


_modeles_cartes = None


def art(img):
    """Partie centrale d'une carte (le dessin) : sans le compteur en haut, sans le niveau
    en bas, sans les bords. C'est la seule partie qui ne change jamais."""
    h, w = img.shape[:2]
    return img[int(0.30 * h):int(0.74 * h), int(0.10 * w):int(0.90 * w)], int(0.10 * w), int(0.30 * h)


def modeles_cartes():
    global _modeles_cartes
    if _modeles_cartes is None:
        _modeles_cartes = []
        for f in sorted(CARTES.glob("*.png")):
            img = cv2.imread(str(f))
            if img is None:
                continue
            dessin, dx, dy = art(img)
            gris = cv2.resize(cv2.cvtColor(dessin, cv2.COLOR_BGR2GRAY), None, fx=REDUCTION,
                              fy=REDUCTION, interpolation=cv2.INTER_AREA)
            _modeles_cartes.append({"nom": f.stem, "gris": gris, "taille": img.shape[1::-1],
                                    "decalage": (dx, dy), "couleur": dessin.reshape(-1, 3).mean(axis=0)})
    return _modeles_cartes


def cartes_connues(ecran):
    """Cartes reconnues par leur dessin (templates/cartes/) : marche quel que soit le décor
    derrière la barre, car les cartes sont opaques."""
    ya = int(ecran.h * BARRE[0])
    bande = ecran.gris[int(ya * REDUCTION):]
    trouvees = []
    for m in modeles_cartes():
        if m["gris"].shape[0] > bande.shape[0] or m["gris"].shape[1] > bande.shape[1]:
            continue
        _, score, _, (x, y) = cv2.minMaxLoc(cv2.matchTemplate(bande, m["gris"], cv2.TM_CCOEFF_NORMED))
        if score < 0.80:
            continue
        x0 = int(x / REDUCTION) - m["decalage"][0]
        y0 = int(y / REDUCTION) + ya - m["decalage"][1]
        w, h = m["taille"]
        dessin = art(ecran.img[max(0, y0):y0 + h, max(0, x0):x0 + w])[0]
        if dessin.size == 0 or np.abs(dessin.reshape(-1, 3).mean(axis=0) - m["couleur"]).max() > 45:
            continue                                  # même forme mais pas la même couleur
        trouvees.append((score, Carte(x0, y0, x0 + w, y0 + h, connue=m["nom"])))
    cartes = []
    for score, c in sorted(trouvees, key=lambda t: -t[0]):        # une carte par emplacement
        if all(c.x1 <= d.x0 or c.x0 >= d.x1 for d in cartes):
            cartes.append(c)
    return cartes


def cartes_par_luminosite(ecran):
    """Cartes vues comme des blocs clairs sur le fond sombre de la barre (décor sombre)."""
    h, w = ecran.h, ecran.w
    ya = int(h * BARRE[0])
    clair = cv2.cvtColor(ecran.img[ya:int(h * BARRE[1])], cv2.COLOR_BGR2HSV)[..., 2] > 120
    k = max(3, int(0.002 * w) | 1)
    profil = np.convolve(clair.mean(axis=0), np.ones(k) / k, mode="same")
    blocs = []
    for a, b in segments(profil > 0.28):
        # un dessin sombre peut couper une carte : on recolle les morceaux très proches
        if blocs and a - blocs[-1][1] < 0.004 * w and b - blocs[-1][0] <= 0.07 * w:
            blocs[-1] = (blocs[-1][0], b)
        else:
            blocs.append((a, b))
    cartes = []
    for a, b in blocs:
        if not 0.035 * w <= b - a <= 0.09 * w:
            continue
        lignes = segments(clair[:, a:b].mean(axis=1) > 0.3)
        if not lignes:
            continue
        haut, bas = max(lignes, key=lambda s: s[1] - s[0])
        if bas - haut >= 0.10 * h:
            cartes.append(Carte(int(a), ya + int(haut), int(b), ya + int(bas)))
    return cartes


def traits_sombres(ecran, y0, y1):
    """Colonnes où passe un trait vertical plus foncé que ses deux voisins (le contour
    d'une carte), sur la plupart des lignes de la barre. Renvoie (positions, image grise)."""
    g = cv2.blur(cv2.cvtColor(ecran.img[y0 + 8:y1 - 8], cv2.COLOR_BGR2GRAY).astype(np.int16), (1, 5))
    vallee = np.zeros(g.shape[1])
    for d in (2, 3, 4, 5):
        gauche, droite = np.roll(g, d, axis=1), np.roll(g, -d, axis=1)
        vallee = np.maximum(vallee, ((np.minimum(gauche, droite) - g) > 18).mean(axis=0))
    return [(a + b - 1) / 2 for a, b in segments(vallee >= 0.6)], g


def vide_entre(traits, a, b):
    """Aucun autre contour entre a et b : sinon ce n'est pas une carte mais l'écart entre
    le bord d'une carte et le même bord de la suivante."""
    return not any(a + 4 < t < b - 4 for t in traits)


def largeur_par_contours(traits, h):
    """Largeur de carte la plus fréquente entre deux contours (None si rien de net)."""
    ecarts = [b - a for i, a in enumerate(traits) for b in traits[i + 1:]
              if 0.105 * h <= b - a <= 0.14 * h and vide_entre(traits, a, b)]
    if not ecarts:
        return None
    votes = [sum(abs(e - f) <= 0.02 * e for f in ecarts) for e in ecarts]
    return float(np.median([e for e in ecarts if abs(e - ecarts[int(np.argmax(votes))]) <= 0.02 * e]))


def cartes_par_contours(ecran, largeur, y0, y1, traits=None):
    """Cartes vues par leur contour sombre (décor clair) : deux traits verticaux plus
    foncés que ce qui les entoure, écartés d'une largeur de carte, et un dessin entre les deux."""
    if traits is None:
        traits, g = traits_sombres(ecran, y0, y1)
    else:
        g = traits_sombres(ecran, y0, y1)[1]
    cartes, fin = [], -1
    for i, xa in enumerate(traits):
        if xa <= fin:
            continue
        for xb in traits[i + 1:]:
            if abs(xb - xa - largeur) <= 0.06 * largeur and vide_entre(traits, xa, xb):
                interieur = g[:, int(xa) + 6:int(xb) - 6]
                if interieur.size and np.abs(np.diff(interieur, axis=0)).mean() >= 3.5:
                    cartes.append(Carte(int(round(xa)), y0, int(round(xb)), y1))
                    fin = xb
                break
            if xb - xa > 1.1 * largeur:
                break
    return cartes


def detecter_cartes(ecran):
    """Repère les cartes (troupes, engin, héros, sorts) de la barre du bas, de gauche à
    droite. D'abord celles dont on connaît le dessin, puis les autres, vues comme des blocs
    clairs (décor sombre) ou par leur contour sombre (décor clair). Toutes ont la même
    taille et sont alignées : on écarte ce qui ne colle pas."""
    connues = cartes_connues(ecran)
    autres = cartes_par_luminosite(ecran)
    ref = connues or autres
    if ref:
        y0, y1 = int(np.median([c.y0 for c in ref])), int(np.median([c.y1 for c in ref]))
    else:
        y0, y1 = int(0.83 * ecran.h), int(0.983 * ecran.h)
    traits = traits_sombres(ecran, y0, y1)[0]
    if connues:
        largeur = float(np.median([c.x1 - c.x0 for c in connues]))
    else:                                   # sans carte connue : la largeur vue sur les contours
        largeur = largeur_par_contours(traits, ecran.h) or (
            float(np.median([c.x1 - c.x0 for c in autres])) if len(autres) >= 3 else 0.123 * ecran.h)
    autres += cartes_par_contours(ecran, largeur, y0, y1, traits)
    cartes = list(connues)
    for c in autres:
        if abs((c.x1 - c.x0) - largeur) <= 0.15 * largeur and abs(c.y1 - y1) <= 0.06 * ecran.h \
                and all(c.x1 <= d.x0 + 0.1 * largeur or c.x0 >= d.x1 - 0.1 * largeur for d in cartes):
            cartes.append(c)
    for c in cartes:                         # toutes à la même hauteur (une carte sélectionnée
        c.y0, c.y1 = y0, y1                  # au début du combat est un peu remontée)
        c.couleur0 = taux_couleur(ecran.img[c.y0:c.y1, c.x0:c.x1])
    return sorted(cartes, key=lambda c: c.x0)


def apprendre_cartes(ecran, cartes):
    """Retient le dessin des nouvelles cartes sûres (compteur lu, cadre de héros, sort
    reconnu) pour les retrouver ensuite quel que soit le décor."""
    nouvelles = 0
    for c in cartes:
        if c.connue or not (c.nombre or c.heros or c.sort):
            continue
        if len(list(CARTES.glob("*.png"))) >= 60:
            break
        CARTES.mkdir(parents=True, exist_ok=True)
        nom = f"appris_{time.strftime('%Y%m%d_%H%M%S')}_{c.x0}"
        cv2.imwrite(str(CARTES / f"{nom}.png"), ecran.img[c.y0:c.y1, c.x0:c.x1])
        c.connue = nom
        nouvelles += 1
    if nouvelles:
        global _modeles_cartes
        _modeles_cartes = None
        log(f"  {nouvelles} nouvelle(s) carte(s) mémorisée(s)")


def carte_epuisee(ecran, carte):
    vignette_couleur = ecran.img[carte.y0:carte.y1, carte.x0:carte.x1]
    return taux_couleur(vignette_couleur) < 0.4 * carte.couleur0


_sorts = None


def identifier_sort(ecran, carte):
    """Nom du sort d'une carte d'après les icônes de templates/sorts/ (« precipitation »,
    « rage »…), ou None si ce n'est pas un sort connu."""
    global _sorts
    if _sorts is None:
        _sorts = {f.stem: cv2.imread(str(f)) for f in sorted(SORTS.glob("*.png"))}
    case = ecran.img[carte.y0:carte.y1, carte.x0:carte.x1]
    meilleur = (None, 0.6)
    for nom, icone in _sorts.items():
        if icone is None or icone.shape[0] > case.shape[0] or icone.shape[1] > case.shape[1]:
            continue
        score = cv2.minMaxLoc(cv2.matchTemplate(case, icone, cv2.TM_CCOEFF_NORMED))[1]
        if score > meilleur[1]:
            meilleur = (nom, score)
    return meilleur[0]


def cadre_heros(ecran, carte):
    """Les cartes de héros ont un épais cadre bleu foncé (l'engin de siège, non)."""
    w, h = carte.x1 - carte.x0, carte.y1 - carte.y0
    bord = ecran.img[carte.y0:carte.y0 + h // 2, carte.x0:carte.x0 + max(4, int(0.08 * w))]
    hsv = cv2.cvtColor(bord, cv2.COLOR_BGR2HSV)
    t, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    return float(((t >= 105) & (t <= 140) & (s > 120) & (v < 170)).mean()) >= 0.15


def bande_dessus(ecran, carte):
    """Bande juste au-dessus d'une carte : c'est là qu'apparaît la barre de vie quand
    l'unité est posée (la carte grandit vers le haut). Une carte simplement sélectionnée
    ne remonte pas jusque-là."""
    hc, m = carte.y1 - carte.y0, (carte.x1 - carte.x0) // 20
    return ecran.img[max(0, carte.y0 - int(0.27 * hc)):carte.y0 - int(0.09 * hc), carte.x0 + m:carte.x1 - m]


def barre_de_vie(zone):
    """Une ligne de la zone porte-t-elle une barre de vie : un remplissage uni vert, jaune,
    orange ou rouge (jamais le vert-jaune de l'herbe), en retrait des deux bords de la carte
    et long d'au moins un quart de sa largeur ?"""
    hsv = cv2.cvtColor(zone, cv2.COLOR_BGR2HSV)
    t, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    rempli = (s > 120) & (v > 110) & ((t <= 28) | ((t >= 48) & (t <= 82)) | (t >= 168))
    largeur = zone.shape[1]
    for r, ligne in enumerate(rempli):
        idx = np.flatnonzero(ligne[:largeur // 4])
        if not len(idx) or idx[0] < 2:
            continue
        a = int(idx[0])
        reste_ligne = ligne[a:]
        b = a + (int(np.argmin(reste_ligne)) if not reste_ligne.all() else len(reste_ligne))
        if b - a >= 0.25 * largeur and b <= largeur - 3 and zone[r, a:b].std(axis=0).max() < 30:
            return True
    return False


def cadre_au_dessus(ecran, carte):
    """Le cadre d'une carte posée monte haut au-dessus d'elle : au moins deux bords
    horizontaux nets qui s'arrêtent aux bords de la carte (le long trait du haut de la barre
    traverse tout l'écran, lui). Une carte sélectionnée ne remonte pas jusque-là."""
    hc, w = carte.y1 - carte.y0, carte.x1 - carte.x0
    ya, yb = carte.y0 - int(0.29 * hc), carte.y0 - int(0.15 * hc)
    xa, xb = carte.x0 - int(0.11 * w), carte.x1 + int(0.11 * w)
    if ya < 2 or xa < 0 or xb > ecran.w:
        return False
    g = cv2.blur(cv2.cvtColor(ecran.img[ya - 2:yb + 2, xa:xb], cv2.COLOR_BGR2GRAY).astype(np.int16), (5, 1))
    gy = np.abs(g[4:] - g[:-4]) > 25
    m, d = int(0.09 * w), carte.x0 - xa
    dedans = gy[:, d + m:d + w - m].mean(axis=1)
    dehors = np.maximum(gy[:, :d - 3].mean(axis=1), gy[:, d + w + 3:].mean(axis=1))
    return int(((dedans >= 0.7) & (dehors < 0.5)).sum()) >= 2


def motif_pose(ecran, carte):
    return barre_de_vie(bande_dessus(ecran, carte)) or cadre_au_dessus(ecran, carte)


def deploye(ecran, carte):
    """Unité unique (héros, engin de siège) posée ? Oui si sa carte est grise (posée puis
    morte, ou compétence déjà lancée) ou si sa barre de vie est apparue au-dessus d'elle,
    dans un cadre. Sinon non, et on la repose. (Avant, le bot comparait avec le décor vu
    pendant l'observation ; mais le dézoom et les vues changent ce décor, et il croyait à
    tort un héros ou l'engin posé.)"""
    if carte_epuisee(ecran, carte):
        return True
    return bande_dessus(ecran, carte).size > 0 and motif_pose(ecran, carte)


def analyser_cartes(ecran, cartes):
    """Avant toute touche : compteur de chaque carte, sorts, héros, aspect de départ."""
    for c in cartes:
        c.nombre = lire_compteur(ecran, c)
        c.sort = identifier_sort(ecran, c)       # même sans compteur lisible : jamais une unité
        c.heros = c.nombre is None and not c.sort and cadre_heros(ecran, c)
        c.bande0 = bande_dessus(ecran, c).astype(np.int16)
        c.motif0 = c.nombre is None and motif_pose(ecran, c)   # le décor imite déjà une barre de vie


# ─── La carte : dézoom, deux vues fixes, bords de la carte ───────────────────

def zones_interdites(ecran, cartes, boite_rouge, boite_suivant=None):
    """Rectangles où le déploiement ne doit jamais toucher (boutons et affichages du jeu)."""
    w, h = ecran.w, ecran.h
    m = int(0.015 * w)
    haut_barre = min((c.y0 for c in cartes), default=int(h * BARRE[0]))
    zones = [(0, haut_barre - m, w, h),        # la barre des troupes elle-même
             (0, 0, w, int(0.05 * h))]         # le tout haut de l'écran
    zones += [(x0 * w, y0 * h, x1 * w, y1 * h) for x0, y0, x1, y1 in UI_FIXE]
    if boite_suivant is None:
        t = localiser(ecran, "suivant")
        boite_suivant = t.boite if t else None
    for x, y, bw, bh in [boite_rouge] + ([boite_suivant] if boite_suivant else []):
        zones.append((x - m, y - m, x + bw + m, y + bh + m))
    return zones


def pas_de(debut, fin, pas):
    """debut, debut + pas, … sans dépasser fin (pas positif ou négatif)."""
    n = int(np.floor((fin - debut) / pas + 1e-9)) + 1
    return [debut + k * pas for k in range(max(0, n))]


NOMS_BORDS = {"i0": "haut-droite", "j0": "haut-gauche", "i1": "bas-gauche", "j1": "bas-droite"}


class Plan:
    """La carte vue dans les deux positions fixes de la caméra (dézoom maximal, en butée).
    Une case (i, j) : i va du bord haut-droite (i0) au bord bas-gauche (i1), j du bord
    haut-gauche (j0) au bord bas-droite (j1) ; la carte fait 44 cases de côté. À l'écran,
    en vue haut : point = sommet + i·(-a, b) + j·(a, b). La vue bas est la vue haut décalée.
    Valeurs mesurées sur tes captures (écran 2340×1080) ; les bords sont confirmés au
    premier combat puis gardés dans templates/carte.json."""

    DEFAUT = {"ecran": [2340, 1080], "sommet": [1170.0, 115.0], "case": [21.2, 15.84],
              "phase": [0.0, 0.43], "decalage_bas": [-70.0, -614.0],
              "bords": {"i0": -1.5, "j0": -2.07, "i1": 42.5, "j1": 41.93},
              "trouves": {"i0": False, "j0": False, "i1": False, "j1": False}, "decalages_mesures": []}

    def __init__(self, w, h):
        self.w, self.h = w, h
        self.d = copy.deepcopy(self.DEFAUT)
        if CARTE_JSON.exists():
            try:
                lu = json.loads(CARTE_JSON.read_text())
                for cle, defaut in self.d.items():
                    if cle in lu:
                        self.d[cle] = {**defaut, **lu[cle]} if isinstance(defaut, dict) else lu[cle]
            except (ValueError, OSError):
                log("  templates/carte.json illisible : je repars des valeurs mesurées")
        ew, eh = self.d["ecran"]
        self.kx, self.ky = w / ew, h / eh
        self.decalage = list(self.d["decalage_bas"])     # corrigé si la vue bas est mesurée

    def sauver(self):
        CARTE_JSON.write_text(json.dumps(self.d, indent=2, ensure_ascii=False))

    def noter_decalage(self, mesure):
        """Une mesure de la vue bas : utilisée pour ce combat, et gardée (les 9 dernières)
        pour que la position habituelle, prise quand la mesure échoue, soit leur médiane."""
        self.decalage = [round(mesure[0], 1), round(mesure[1], 1)]
        liste = (self.d.get("decalages_mesures") or [])[-8:] + [self.decalage]
        self.d["decalages_mesures"] = liste
        self.d["decalage_bas"] = [float(np.median([m[0] for m in liste])), float(np.median([m[1] for m in liste]))]
        try:
            self.sauver()
        except OSError:
            pass

    def ecran(self, vue, i, j):
        sx, sy = self.d["sommet"]
        a, b = self.d["case"]
        x, y = sx - a * i + a * j, sy + b * i + b * j
        if vue == "bas":
            x, y = x + self.decalage[0], y + self.decalage[1]
        return x * self.kx, y * self.ky

    def libre(self, zones, x, y):
        return (0.01 * self.w < x < 0.99 * self.w and 0 < y < self.h
                and not any(x0 <= x <= x1 and y0 <= y <= y1 for x0, y0, x1, y1 in zones))

    def centre(self):
        b = self.d["bords"]
        return (b["i0"] + b["i1"]) / 2, (b["j0"] + b["j1"]) / 2

    def rangee(self, vue, retrait=0.5):
        """Cases le long des deux bords visibles dans cette vue, à « retrait » cases du bord,
        dans l'ordre du chemin (haut : gauche → sommet → droite ; bas : gauche → pointe → droite)."""
        b = self.d["bords"]
        if vue == "haut":
            j = b["j0"] + retrait
            cases = [(i, j) for i in pas_de(b["i1"] - 0.5, b["i0"] + retrait, -1)]
            i = b["i0"] + retrait
            cases += [(i, jj) for jj in pas_de(b["j0"] + retrait + 1, b["j1"] - 0.5, 1)]
        else:
            i = b["i1"] - retrait
            cases = [(i, jj) for jj in pas_de(b["j0"] + 0.5, b["j1"] - retrait, 1)]
            j = b["j1"] - retrait
            cases += [(ii, j) for ii in pas_de(b["i1"] - retrait - 1, b["i0"] + 0.5, -1)]
        return cases

    def chemin(self, vue, zones, retrait=0.5):
        """Points d'écran de la rangée du bord, hors boutons et affichages."""
        points = []
        for i, j in self.rangee(vue, retrait):
            x, y = self.ecran(vue, i, j)
            if self.libre(zones, x, y):
                points.append((int(round(x)), int(round(y))))
        return points

    def sondes(self, cle, position=20):
        """Cases à essayer pour trouver un bord : à « position » le long de ce bord (le milieu
        d'abord), de l'extérieur de la carte vers l'intérieur."""
        pi, pj = self.d["phase"]
        if cle == "i0":
            return [(pi + k, pj + position) for k in range(-4, 2)]
        if cle == "j0":
            return [(pi + position, pj + k) for k in range(-4, 2)]
        if cle == "i1":
            return [(pi + k, pj + position) for k in range(46, 40, -1)]
        return [(pi + position, pj + k) for k in range(46, 40, -1)]

    def fixer_bord(self, cle, case):
        """La première case acceptée (en venant de l'extérieur) est la rangée du bord."""
        b, trouves = self.d["bords"], self.d["trouves"]
        valeur = case[0] if cle[0] == "i" else case[1]
        b[cle] = valeur - 0.5 if cle.endswith("0") else valeur + 0.5
        trouves[cle] = True
        for a0, a1 in (("i0", "i1"), ("j0", "j1")):     # la carte fait exactement 44 cases
            if trouves[a0] and trouves[a1]:
                if b[a1] - b[a0] < 43.5:                   # un bord vu trop à l'intérieur
                    b[a0], b[a1] = min(b[a0], b[a1] - 44), max(b[a1], b[a0] + 44)
            elif trouves[a0]:
                b[a1] = b[a0] + 44
            elif trouves[a1]:
                b[a0] = b[a1] - 44

    def completer_bords(self):
        """Un bord trouvé donne le bord opposé (44 cases plus loin). True si tout est connu."""
        trouves = self.d["trouves"]
        for a0, a1 in (("i0", "i1"), ("j0", "j1")):
            if trouves[a0] or trouves[a1]:
                trouves[a0] = trouves[a1] = True
        return all(trouves.values())


def vignette(tel):
    """Petite image grise et floue du milieu de l'écran : pour voir si la caméra a bougé."""
    w, h = tel.taille()
    ya, yb = int(0.15 * h), int(0.75 * h)
    e = tel.capture_bande(ya, yb)
    g = e.gris[int(ya * REDUCTION):int(yb * REDUCTION)]
    g = cv2.resize(g, (max(1, w // 8), max(1, (yb - ya) // 8)), interpolation=cv2.INTER_AREA)
    return cv2.GaussianBlur(g.astype(np.float32), (0, 0), 1.5)


def attendre_arret(tel, maxi, mini=0.06, seuil=1.2):
    """Attend que l'image se fige (fin du glissement de la caméra ou du dézoom), maxi
    secondes au plus. Sans la vidéo, les images sont trop lentes : on attend maxi."""
    video = getattr(tel, "video", lambda: None)()
    if video is None:
        time.sleep(maxi)
        return
    time.sleep(mini)
    fin = time.monotonic() + maxi
    prec, calmes = vignette(tel), 0
    while time.monotonic() < fin:
        v = vignette(tel)
        calmes = calmes + 1 if prec.shape == v.shape and float(np.abs(v - prec).mean()) < seuil else 0
        if calmes >= 2:
            return
        prec = v


def a_bouge(a, b):
    """Écart moyen > 7 niveaux de gris : la caméra a bougé (les animations du jeu, elles,
    restent sous 4 même après 30 s de combat)."""
    return a.shape == b.shape and float(np.abs(a - b).mean()) > 7.0


def _pincements(tel, n):
    """n pincements à la suite, puis le temps que l'image se pose."""
    ok = True
    for k in range(n):
        ok = tel.pincer() and ok
        if k < n - 1:
            time.sleep(0.05)
    attendre_arret(tel, 0.45)
    return ok


def dezoomer(tel):
    """Dézoom au maximum (PINCEMENTS pincements : le jeu ne descend jamais plus bas, et un
    ou deux ne suffisent pas toujours). La première fois, on vérifie que l'image bouge
    vraiment ; si elle ne bouge pas, soit on était déjà au maximum, soit le geste est
    ignoré : un zoom avant tranche. Renvoie False si le téléphone ignore les gestes à deux
    doigts."""
    if tel.dezoom_verifie:
        return _pincements(tel, PINCEMENTS)
    avant = vignette(tel)
    if not tel.pincer():
        return False
    attendre_arret(tel, 0.5)
    apres = vignette(tel)
    if a_bouge(avant, apres):
        tel.dezoom_verifie = True
        _pincements(tel, PINCEMENTS - 1)
        return True
    tel.pincer(dezoom=False)          # rien n'a bougé : déjà au maximum ? un zoom avant tranche
    attendre_arret(tel, 0.5)
    if not a_bouge(apres, vignette(tel)):
        log("  le pincement ne fait rien bouger à l'écran")
        return False
    tel.dezoom_verifie = True
    _pincements(tel, PINCEMENTS)
    return True


def aller_vue(tel, vue, w, h):
    """Caméra en butée : « haut » = tout en haut à gauche, « bas » = tout en bas à droite.
    Le doigt glisse sans s'arrêter : le jeu déplace la caméra et ne pose rien."""
    if vue == "haut":
        a, b = (0.22 * w, 0.34 * h), (0.70 * w, 0.76 * h)
    else:
        a, b = (0.80 * w, 0.66 * h), (0.30 * w, 0.12 * h)
    for _ in range(3):
        tel.glisser(a[0], a[1], b[0], b[1], 200)
    attendre_arret(tel, 0.5)                         # la caméra finit de glisser


def mesurer_decalage(ecran_haut, ecran_bas, plan, zones=None):
    """Décalage réel entre la vue haut et la vue bas (en pixels de l'étalonnage), ou None.
    La vue bas n'est pas toujours au même endroit : d'une attaque à l'autre elle glisse le
    long du bord de la carte, jusqu'à ~180 px. Les deux vues ne se recouvrent que sur une
    bande d'environ 200 px, en partie cachée par les affichages du jeu : on y prend une
    vingtaine de petits morceaux (110×70) hors affichages, on les cherche dans la vue bas
    autour du décalage attendu (en demi-résolution, ~10 ms en tout), et on garde la valeur
    sur laquelle la plupart s'accordent à 10 px près."""
    w, h = ecran_haut.w, ecran_haut.h
    if zones is None:
        zones = [(x0 * w, y0 * h, x1 * w, y1 * h) for x0, y0, x1, y1 in UI_FIXE] + [(0, BARRE[0] * h, w, h)]
    # la bande tout en haut n'est interdite qu'à la pose : l'image y montre bien la carte
    zones = [z for z in zones if not (z[1] <= 0 and z[3] <= 0.06 * h and z[2] - z[0] >= 0.9 * w)]
    k = 1 / REDUCTION
    gh, gb = ecran_haut.gris, ecran_bas.gris
    ax, ay = plan.decalage[0] * plan.kx, plan.decalage[1] * plan.ky
    tw, th, mx, my = 110, 70, 280, 180

    def touche(x, y, ww, hh):
        return any(not (x + ww < x0 or x > x1 or y + hh < y0 or y > y1) for x0, y0, x1, y1 in zones)

    mesures = []
    for y0 in range(int(0.52 * h), int(0.79 * h) - th, 40):
        for x0 in range(int(0.12 * w), int(0.88 * w) - tw, 90):
            xb, yb = x0 + ax, y0 + ay                   # où le morceau devrait tomber dans la vue bas
            if yb + th < 0:
                continue                                # tombe au-dessus de l'écran
            if touche(x0, y0, tw, th) or touche(xb - 20, yb - 20, tw + 40, th + 40):
                continue
            tpl = gh[int(y0 / k):int((y0 + th) / k), int(x0 / k):int((x0 + tw) / k)]
            if tpl.size == 0 or tpl.std() < 12:
                continue                                # morceau trop uni : pas fiable
            xa, ya = max(0, int((xb - mx) / k)), max(0, int((yb - my) / k))
            zone = gb[ya:max(ya, int((yb + th + my) / k)), xa:max(xa, int((xb + tw + mx) / k))]
            if zone.shape[0] <= tpl.shape[0] or zone.shape[1] <= tpl.shape[1]:
                continue
            _, score, _, (x, y) = cv2.minMaxLoc(cv2.matchTemplate(zone, tpl, cv2.TM_CCOEFF_NORMED))
            if score >= 0.6:
                mesures.append(((xa + x) * k - x0, (ya + y) * k - y0))
    if len(mesures) < 2:
        return None
    m = np.array(mesures, float)
    proches = m[np.hypot(*(m - np.median(m, axis=0)).T) <= 10]
    if len(proches) < max(2, 0.4 * len(m)):
        return None                                     # les morceaux ne s'accordent pas
    dx, dy = np.median(proches, axis=0)
    return float(dx / plan.kx), float(dy / plan.ky)


def repartir(chemin, k, decalage=0.0):
    """k points régulièrement espacés le long du chemin, décalés d'une fraction du chemin."""
    n = len(chemin)
    if not n or k <= 0:
        return []
    return [chemin[int((decalage + (q + 0.5) / k) * n) % n] for q in range(k)]


# ─── Déploiement : vue haut, vue bas, puis on complète ───────────────────────

class FinDuCombat(Exception):
    """Le bouton rouge a disparu : on ne touche plus rien."""


def foyers_troupes(tel, plan, vue, zones, n, evites=()):
    """(dernier écran, jusqu'à n points d'écran) où bougent le plus de troupes, du groupe le
    plus chargé au moins chargé, espacés d'au moins un rayon et demi de sort. Trois images à
    0,12 s d'écart, comparées en quart de résolution (~3 ms de calcul) : les troupes qui
    marchent et se battent changent l'image, les bâtiments non. Seulement sur la carte, hors
    affichages du jeu et hors des endroits `evites` (sorts déjà lancés, qui scintillent)."""
    k = 2 / REDUCTION                                   # pixels d'écran par pixel du quart d'image
    images, ecran = [], None
    for i in range(3):
        if i:
            time.sleep(0.12)
        ecran = tel.capture()
        images.append(cv2.resize(ecran.gris, None, fx=0.5, fy=0.5, interpolation=cv2.INTER_AREA).astype(np.int16))
    bouge = ((np.abs(images[1] - images[0]) > 12) | (np.abs(images[2] - images[1]) > 12)).astype(np.float32)
    h, w = bouge.shape
    masque = np.zeros((h, w), np.float32)
    b = plan.d["bords"]
    coins = [plan.ecran(vue, i, j) for i, j in ((b["i0"], b["j0"]), (b["i0"], b["j1"]), (b["i1"], b["j1"]), (b["i1"], b["j0"]))]
    cv2.fillPoly(masque, [np.array([(x / k, y / k) for x, y in coins], np.int32)], 1.0)
    for x0, y0, x1, y1 in zones:
        masque[max(0, int(y0 / k)):max(0, int(y1 / k) + 1), max(0, int(x0 / k)):max(0, int(x1 / k) + 1)] = 0
    a, bb = plan.d["case"]
    rx = max(2, int(SORT_RAYON * a * 1.41 * plan.kx / k))
    ry = max(2, int(SORT_RAYON * bb * 1.41 * plan.ky / k))
    densite = cv2.boxFilter(bouge * masque, -1, (2 * rx + 1, 2 * ry + 1)) * masque
    for x, y in evites:
        cv2.ellipse(densite, (int(x / k), int(y / k)), (rx, ry), 0, 0, 360, 0, -1)
    foyers = []
    while len(foyers) < n:
        _, v, _, (x, y) = cv2.minMaxLoc(densite)
        if v < SORT_DENSITE_MIN:
            break
        foyers.append((int(x * k), int(y * k)))
        cv2.ellipse(densite, (x, y), (int(1.5 * rx), int(1.5 * ry)), 0, 0, 360, 0, -1)
    return ecran, foyers


def rafale(tel, touches, boite):
    """Envoie les touches par paquets de RAFALE. Entre deux paquets, vérifie que le combat
    continue en ne capturant que les lignes du bouton rouge."""
    for k in range(0, len(touches), RAFALE):
        if k and not en_combat(tel.capture_bande(*bande_rouge(boite)), boite):
            raise FinDuCombat
        tel.toucher(*touches[k:k + RAFALE])


def reste(ecran, carte):
    """Combien d'unités de cette carte ne sont pas encore sorties (0 = tout est posé).
    Un compteur illisible compte pour 0 : dans le doute, on ne relance rien."""
    if carte.nombre == 0 or not carte.points:
        return 0
    if carte.nombre is None:                         # héros, engin : barre de vie visible ?
        return 0 if deploye(ecran, carte) else 1
    if carte_epuisee(ecran, carte):
        return 0
    return lire_compteur(ecran, carte) or 0


class Deploiement:
    """Dézoom, vue haut (moitié des troupes, une partie des héros sur les deux bords du haut),
    vue bas (le reste sur les deux bords du bas), vérifications, compétences, puis les sorts
    sur les groupes de troupes."""

    def __init__(self, tel, cartes, zones, boite, plan, debug=False, numero=0):
        self.tel, self.cartes, self.zones, self.boite, self.plan = tel, cartes, zones, boite, plan
        self.debug, self.numero = debug, numero
        hauteur = cartes[0].y1 - cartes[0].y0
        self.haut = min(min(c.y0 for c in cartes) - int(0.32 * hauteur), boite[1]) - 4
        self.sorts = [c for c in cartes if c.sort]
        self.troupes = [c for c in cartes if c.nombre and not c.sort]
        self.uniques = [c for c in cartes if c.nombre is None and not c.sort]
        self.mauvais = set()
        self.poses = {}              # vue -> points où les troupes ont été posées
        self.fin_pose = time.monotonic()
        for c in cartes:
            c.points = []

    # ── lectures de la barre ──

    def capture_barre(self):
        # le temps que la barre se mette à jour (la vidéo, elle, arrive en 50 ms au plus)
        time.sleep(0.12 if getattr(self.tel, "video", lambda: None)() else 0.25)
        ecran = self.tel.capture_bande(self.haut)
        if not en_combat(ecran, self.boite):
            raise FinDuCombat
        return ecran

    def compter(self, carte):
        """Compteur actuel d'une carte de troupes (None si illisible deux fois de suite)."""
        for _ in range(2):
            e = self.capture_barre()
            if carte_epuisee(e, carte):
                return 0
            n = lire_compteur(e, carte)
            if n is not None:
                return n
        return None

    def manque(self, ecran):
        return {id(c): k for c in self.cartes if (k := reste(ecran, c))}

    def decrire_manque(self, manque):
        return ", ".join(f"carte {self.cartes.index(c) + 1} ({c.decrire()}) : {manque[id(c)]}"
                         for c in self.cartes if id(c) in manque)

    # ── bords de la carte ──

    def sonder(self, vue):
        """Trouve les bords de cette vue s'ils ne sont pas encore connus : une troupe à la
        fois, posée de plus en plus vers l'intérieur ; la première case acceptée est la
        rangée du bord. Une seule fois : ensuite c'est gardé dans templates/carte.json."""
        cles = [k for k in (("i0", "j0") if vue == "haut" else ("i1", "j1")) if not self.plan.d["trouves"][k]]
        sonde = max(self.troupes, key=lambda c: c.nombre, default=None)
        if not cles or sonde is None:
            return
        log(f"  je cherche le bord de la carte ({', '.join(NOMS_BORDS[k] for k in cles)}) : une troupe à la fois")
        n = self.compter(sonde)
        if n is None:
            log("  compteur illisible : je garde les bords estimés")
            return
        self.tel.toucher(sonde.point)
        for cle in cles:
            trouve = False
            for position in (20, 8, 32):              # le milieu du bord, puis ailleurs s'il est bloqué
                for case in self.plan.sondes(cle, position):
                    x, y = self.plan.ecran(vue, *case)
                    if not self.plan.libre(self.zones, x, y):
                        continue
                    self.tel.toucher((x, y))
                    apres = self.compter(sonde)
                    if apres is not None and apres < n:
                        self.plan.fixer_bord(cle, case)
                        sonde.points.append((int(x), int(y)))
                        log(f"  bord {NOMS_BORDS[cle]} trouvé")
                        n, trouve = apres, True
                        break
                    n = apres if apres is not None else n
                if trouve:
                    break
            if not trouve:
                log(f"  bord {NOMS_BORDS[cle]} pas trouvé : je le déduis du bord opposé (la carte fait 44 cases)")
        if vue == "bas" and self.plan.completer_bords():
            self.plan.sauver()
            log("  bords de la carte enregistrés (templates/carte.json)")

    # ── pose ──

    def touches(self, c, points):
        c.points += points
        return [c.point] + points if points else []

    def poser_vue(self, vue, ecran):
        """Pose dans la vue courante : troupes, puis héros/engin (les sorts viennent après)."""
        chemin = self.plan.chemin(vue, self.zones)
        if not chemin:
            raise Bloque(f"aucun point de pose dans la vue {vue} : vérifie avec « python bot_coc.py vues »", ecran)
        touches = []
        for q, c in enumerate(self.troupes):
            if vue == "haut":
                k = (c.nombre - len(c.points) + 1) // 2           # la moitié (moins les sondes)
            else:                                               # tout ce qui reste
                n = 0 if carte_epuisee(ecran, c) else lire_compteur(ecran, c)
                k = n if n is not None else max(0, c.nombre - len(c.points))
            touches += self.touches(c, repartir(chemin, k, 0.5 * q / max(1, len(self.troupes))))
        moitie = (len(self.uniques) + 1) // 2
        uniques = self.uniques[:moitie] if vue == "haut" else self.uniques[moitie:]
        for q, c in enumerate(uniques):
            touches += self.touches(c, repartir(chemin, 1, (q + 0.5) / max(1, len(uniques)) - 0.5))
        log(f"  vue {vue} : {len(touches)} touches")
        rafale(self.tel, touches, self.boite)
        self.poses[vue] = [p for p in touches if p not in {c.point for c in self.cartes}]
        self.fin_pose = time.monotonic()
        return chemin

    def completer(self, chemin):
        """Contrôles dans la vue bas : on repose ce qui n'est pas sorti (héros et engin
        d'abord, troupes avec EXTRA touches en plus), jusqu'à 3 fois, en décalant d'une case
        vers l'intérieur au deuxième essai."""
        for passe in range(4):
            manque = self.manque(self.capture_barre())
            if not manque:
                log("  vérifié : tout est posé")
                return
            if passe == 3:
                log("  impossible de tout poser : il reste " + self.decrire_manque(manque))
                return
            log("  il reste " + self.decrire_manque(manque) + " : je repose")
            chemin_p = self.plan.chemin("bas", self.zones, 1.5) if passe == 1 else chemin
            touches = []
            for q, c in enumerate(sorted(self.cartes, key=lambda c: c.nombre is not None)):
                k = manque.get(id(c))
                if not k:
                    continue
                if c.nombre is None:                    # héros/engin : ailleurs que là où c'était refusé
                    self.mauvais.update(c.points[-1:])
                    libres = [p for p in chemin_p if p not in self.mauvais]
                    pts = repartir(libres or chemin_p, 1, 0.13 * passe + 0.29 * q)
                else:
                    pts = repartir(chemin_p, k + EXTRA, 0.17 * passe + 0.07 * q)
                touches += self.touches(c, pts)
            rafale(self.tel, touches, self.boite)

    def mesurer_vue_bas(self, ecran_haut, ecran_bas):
        """Mesure la vue bas ; si elle échoue (caméra pas tout à fait arrêtée ?), une seconde
        fois 0,2 s plus tard. Renvoie l'écran utilisé."""
        mesure = mesurer_decalage(ecran_haut, ecran_bas, self.plan, self.zones)
        if mesure is None:
            time.sleep(0.2)
            ecran_bas = self.tel.capture()
            mesure = mesurer_decalage(ecran_haut, ecran_bas, self.plan, self.zones)
        if mesure is None:
            log(f"  vue bas non mesurée : je garde le décalage "
                f"{self.plan.decalage[0]:+.0f}, {self.plan.decalage[1]:+.0f} px")
        else:
            self.plan.noter_decalage(mesure)
        return ecran_bas

    def lancer(self):
        w, h = self.plan.w, self.plan.h
        if not dezoomer(self.tel):
            log("  je ne peux pas dézoomer moi-même : dézoome à fond à la main une fois (le jeu garde le zoom)")
        aller_vue(self.tel, "haut", w, h)
        ecran_haut = self.tel.capture()
        self.sonder("haut")
        log("  armée : " + ", ".join(c.decrire() for c in self.cartes))
        chemin = self.poser_vue("haut", ecran_haut)
        if self.debug:
            sauver(annoter(ecran_haut, self.cartes, self.zones, chemin), f"combat_{self.numero}_haut")

        aller_vue(self.tel, "bas", w, h)
        ecran_bas = self.tel.capture()
        if not en_combat(ecran_bas, self.boite):
            raise FinDuCombat
        ecran_bas = self.mesurer_vue_bas(ecran_haut, ecran_bas)
        self.sonder("bas")
        chemin = self.poser_vue("bas", ecran_bas)
        if self.debug:
            sauver(annoter(ecran_bas, self.cartes, self.zones, chemin), f"combat_{self.numero}_bas")

        self.completer(chemin)
        heros = [c for c in self.uniques if c.heros]
        if heros:
            self.tel.toucher(*[c.point for c in heros])
            log(f"  → compétences des {len(heros)} héros")
        self.lancer_sorts()

    def points_secours(self, vue, m):
        """Moins de groupes de troupes visibles que de sorts : m autres points, différents,
        entre les endroits où les troupes ont été posées dans cette vue et le centre de la
        carte (là où elles vont)."""
        cx, cy = self.plan.ecran(vue, *self.plan.centre())
        points = []
        for px, py in repartir(self.poses.get(vue) or [(cx, cy)], m):
            for t in (0.4, 0.7, 0.0):
                x, y = px + (cx - px) * t, py + (cy - py) * t
                if self.plan.libre(self.zones, x, y):
                    points.append((int(x), int(y)))
                    break
        return points

    def lancer_sorts(self):
        """Sorts une fois toutes les troupes posées, sur les groupes de troupes les plus
        nombreux (foyers_troupes) : la moitié dans la vue bas (on y est), le reste dans la vue
        haut. Le combat tourne pendant ce temps : l'attaque n'en dure pas plus longtemps."""
        attente = self.fin_pose + SORTS_APRES - time.monotonic()
        if self.sorts and attente > 0:                  # que les troupes arrivent au contact
            time.sleep(attente)
        if any(c.nombre is None for c in self.sorts):   # compteur illisible au début : relu
            ecran = self.capture_barre()
            for c in self.sorts:
                if c.nombre is None:
                    c.nombre = lire_compteur(ecran, c) or 1
        sorts = [c for c in self.sorts if c.nombre]
        if not sorts:
            return
        lances = []                                     # points de la vue bas, déjà couverts
        for vue in ("bas", "haut"):
            parts = {id(c): c.nombre // 2 if vue == "bas" else c.nombre - c.nombre // 2 for c in sorts}
            n = max(parts.values())
            if not n:
                continue
            if vue == "haut":
                aller_vue(self.tel, "haut", self.plan.w, self.plan.h)
            dx, dy = self.plan.decalage[0] * self.plan.kx, self.plan.decalage[1] * self.plan.ky
            evites = [(x - dx, y - dy) for x, y in lances] if vue == "haut" else []
            ecran, foyers = foyers_troupes(self.tel, self.plan, vue, self.zones, n, evites)
            if not en_combat(ecran, self.boite):
                raise FinDuCombat
            vus = len(foyers)
            if vus < n:
                foyers += self.points_secours(vue, n - vus)
            if not foyers:
                continue
            touches, places = [], {}
            for c in sorts:
                pts = [foyers[q % len(foyers)] for q in range(parts[id(c)])]
                touches += self.touches(c, pts)
                places.setdefault(c.sort, []).extend((vue, p) for p in pts)
            rafale(self.tel, touches, self.boite)
            log(f"  vue {vue} : sorts sur {vus} groupe(s) de troupes" if vus else
                f"  vue {vue} : pas de troupes visibles, sorts entre les poses et le centre")
            if vue == "bas":
                lances = [p for _, liste in places.items() for _, p in liste]
            if self.debug:
                sauver(annoter(ecran, self.cartes, self.zones, [], emplacements=places, vue=vue),
                       f"combat_{self.numero}_sorts_{vue}")


def deployer(tel, cartes, zones, boite, plan, debug=False, numero=0):
    """Pose toute l'armée. Renvoie False si le combat s'est terminé avant la fin."""
    try:
        Deploiement(tel, cartes, zones, boite, plan, debug, numero).lancer()
    except FinDuCombat:
        return False
    return True


# ─── Fin anticipée (--duree) ─────────────────────────────────────────────────

def bouton_rouge(ecran):
    """Le bouton rouge « Capituler » / « Terminer la bataille », à gauche de l'écran :
    (x, y, largeur, hauteur) ou None. Repéré à sa couleur : marche quel que soit son texte."""
    h, w = ecran.h, ecran.w
    ya, xb, yb = int(0.55 * h), int(0.22 * w), int(0.82 * h)
    hsv = cv2.cvtColor(ecran.img[ya:yb, :xb], cv2.COLOR_BGR2HSV)
    t, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    m = cv2.morphologyEx((((t < 10) | (t > 170)) & (s > 130) & (v > 110)).astype(np.uint8),
                         cv2.MORPH_CLOSE, np.ones((7, 7), np.uint8))
    n, _, st, _ = cv2.connectedComponentsWithStats(m, 8)
    choix = None
    for i in range(1, n):
        x, y, bw, bh, a = st[i]
        if a >= 0.002 * w * h and 1.5 <= bw / max(1, bh) <= 5 and (choix is None or a > choix[4]):
            choix = st[i]
    return None if choix is None else (int(choix[0]), int(choix[1]) + ya, int(choix[2]), int(choix[3]))


def bouton_vert(ecran, zone=(0.20, 0.30, 0.80, 0.94)):
    """Centre du plus gros bouton vert vif de la zone (fractions de l'écran), ou None :
    le « OK » d'une fenêtre de confirmation, le « Rentrer » d'un écran de fin…"""
    h, w = ecran.h, ecran.w
    xa, ya, xb, yb = int(zone[0] * w), int(zone[1] * h), int(zone[2] * w), int(zone[3] * h)
    hsv = cv2.cvtColor(ecran.img[ya:yb, xa:xb], cv2.COLOR_BGR2HSV)
    t, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    m = cv2.morphologyEx(((t >= 35) & (t <= 75) & (s > 140) & (v > 150)).astype(np.uint8),
                         cv2.MORPH_CLOSE, np.ones((9, 9), np.uint8))      # le texte blanc coupe le bouton
    n, _, st, _ = cv2.connectedComponentsWithStats(m, 8)
    choix = None
    for i in range(1, n):
        x, y, bw, bh, a = st[i]
        if a >= 0.004 * w * h and 1.3 <= bw / max(1, bh) <= 5.5 and a >= 0.5 * bw * bh \
                and (choix is None or a > choix[4]):
            choix = st[i]
    return None if choix is None else (int(xa + choix[0] + choix[2] / 2), int(ya + choix[1] + choix[3] / 2))


def abandonner(tel, boite=None, raison="durée atteinte"):
    """Appuie sur « Capituler » / « Terminer la bataille » puis confirme (templates/
    confirmer.png s'il existe, sinon le bouton vert de la fenêtre). La fenêtre est guettée en
    continu (elle s'ouvre en ~0,3 s) ; son bouton n'est touché que s'il vient d'apparaître (pas
    un bout d'herbe vert vif déjà là avant) et qu'il est immobile (vu deux fois au même endroit)."""
    ecran = tel.capture()
    b = bouton_rouge(ecran) or boite
    if b is None:
        log(f"  {raison}, mais je ne vois pas le bouton Capituler / Terminer la bataille")
        return False
    deja = bouton_vert(ecran)
    x, y, bw, bh = b
    log(f"  → {raison} : capituler / terminer la bataille")
    tel.toucher((x + bw // 2, y + bh // 2))
    rapide = getattr(tel, "video", lambda: None)() is not None
    fin = time.monotonic() + (2.5 if rapide else 4.0)
    time.sleep(0.15 if rapide else 0.9)
    avant = None
    while time.monotonic() < fin:
        ecran = tel.capture()
        t = localiser(ecran, "confirmer")
        p = (t.x, t.y) if t else bouton_vert(ecran)
        if p and deja and abs(p[0] - deja[0]) <= 12 and abs(p[1] - deja[1]) <= 12:
            p = None                                    # déjà là avant : pas la fenêtre
        if p and avant and abs(p[0] - avant[0]) <= 12 and abs(p[1] - avant[1]) <= 12:
            tel.toucher(p)
            log("  → confirmer")
            return True
        avant = p
        time.sleep(0.1 if rapide else 0.4)
    log("  pas de fenêtre de confirmation (fin directe ?)")
    return True


# ─── Vitesse du combat (bouton 1x / 4x) ──────────────────────────────────────

def lire_vitesse(ecran):
    """Bouton de vitesse : ("1x" ou "4x", (x, y)), ou (None, None) s'il n'est pas à l'écran
    ou si la lecture est douteuse. Les deux images ne diffèrent que par le chiffre : chacune
    ressemble aussi un peu à l'autre, on garde la plus ressemblante, avec de la marge."""
    t1, t4 = chercher(ecran, "vitesse_1x"), chercher(ecran, "vitesse_4x")
    s1 = t1.score if t1 is not None and t1.valide else 0.0
    s4 = t4.score if t4 is not None and t4.valide else 0.0
    if max(s1, s4) < SEUIL or abs(s1 - s4) < 0.06:
        return None, None
    t = t1 if s1 > s4 else t4
    return ("1x" if t is t1 else "4x"), (t.x, t.y)


class Vitesse:
    """Guette le bouton de vitesse pendant un combat (un coup d'œil toutes les 3 s à partir
    de 100 s de combat ; toutes les 15 s une fois sur 4x) et le touche quand il affiche 1x.
    Après une touche, on regarde vite s'il est bien passé sur 4x."""

    def __init__(self, tel):
        self.tel = tel
        self.prochain = 0.0
        self.touche = 0.0
        self.etat = None

    def guetter(self, debut):
        t = time.monotonic()
        if debut is None or t - debut < VITESSE_APRES or t < self.prochain:
            return self.etat
        lignes = lignes_de("vitesse_1x", 20) or lignes_de("vitesse_4x", 20)
        ecran = self.tel.capture_bande(*lignes) if lignes else self.tel.capture()
        etat, point = lire_vitesse(ecran)
        if etat == "1x" and time.monotonic() - self.touche > 2.0:
            self.tel.toucher(point)
            self.touche = time.monotonic()
            log("  → vitesse ×4")
            self.prochain = time.monotonic() + 1.5
        else:
            self.prochain = time.monotonic() + (15.0 if etat == "4x" else VITESSE_PERIODE)
        self.etat = etat
        return etat


# ─── Le cycle ────────────────────────────────────────────────────────────────

def attendre_village_suivant(tel, bouton):
    """Après « Suivant » : guette les nuages puis le nouveau village, en ne capturant
    que les lignes du bouton Suivant (rapide), puis renvoie un écran complet."""
    y0, y1 = bouton.boite[1] - 12, bouton.boite[1] + bouton.boite[3] + 12
    debut = time.monotonic()
    parti = False
    while time.monotonic() - debut < 45:
        visible = localiser(tel.capture_bande(y0, y1), "suivant") is not None
        if not visible:
            parti = True                          # nuages : recherche en cours
        elif parti or time.monotonic() - debut > 6:
            time.sleep(0.3)                       # laisse le butin s'afficher en entier
            return tel.capture()
        time.sleep(0.1)
    raise Bloque("le village suivant n'est pas arrivé en 45 s")


def lire_village(tel, ecran, delai=2.5):
    """(écran, butin, bouton Suivant) une fois l'écran du village posé : butin lisible, bouton
    Suivant visible, et la même lecture deux fois de suite (les images arrivent si vite qu'on
    verrait sinon le village en train d'apparaître). Au bout de `delai`, la dernière lecture."""
    fin = time.monotonic() + delai
    avant = None
    while True:
        butin, bouton = lire_butin(ecran), localiser(ecran, "suivant")
        lecture = (str(butin), bouton is not None)
        if butin is not None and bouton is not None and lecture == avant:
            return ecran, butin, bouton
        if time.monotonic() > fin:
            return ecran, butin, bouton
        avant = lecture
        time.sleep(0.15)
        ecran = tel.capture()


def choisir_village(tel, ecran):
    """Lit le butin et passe au village suivant tant qu'il est trop faible.
    Renvoie (écran du village choisi, bouton Suivant)."""
    bouton = None
    for sautes in range(SUIVANTS_MAX + 1):
        ecran, butin, vu = lire_village(tel, ecran)
        bouton = vu or bouton
        if butin is None:
            log("  butin illisible : j'attaque ce village")
            return ecran, bouton
        log(f"  butin : {butin}")
        if butin.suffisant():
            return ecran, bouton
        if sautes == SUIVANTS_MAX:
            log(f"  {SUIVANTS_MAX} villages passés d'affilée : j'attaque celui-ci")
            return ecran, bouton
        if bouton is None:
            log("  bouton Suivant introuvable : j'attaque ce village")
            return ecran, bouton
        log("  → suivant (pas assez de butin)")
        tel.toucher((bouton.x, bouton.y))
        ecran = attendre_village_suivant(tel, bouton)
    return ecran, bouton


def attendre_fin(tel, boite, delai=300, duree=None, debut=None):
    """Attend l'écran de fin et renvoie le bouton « Rentrer ». Pendant le combat, on surveille
    le bouton rouge et les « Dégâts généraux » : on capitule quand le temps --duree est écoulé,
    ou quand les dégâts n'ont pas bougé depuis CAPITULER_SI_FIGE s (troupes mortes ou bloquées).
    Dès que le bouton rouge disparaît, on cherche « Rentrer », touché une fois immobile."""
    fin = time.monotonic() + delai
    ecran, essais, abandon = None, 0, False
    vitesse = Vitesse(tel)
    degats, change, vu, prolonge = None, time.monotonic(), None, False
    while time.monotonic() < fin:
        ecran = tel.capture_bande(*bande_rouge(boite))
        maintenant = time.monotonic()
        if en_combat(ecran, boite):
            if debut and not abandon:
                d = lire_degats(ecran)
                if d is not None and d != degats:
                    degats, change = d, maintenant
                presque = (degats is not None and degats >= SEUIL_ETOILE and not ecran.partielle
                           and etoiles(ecran) == 0)
                if presque and not prolonge:
                    log(f"  {degats} % sans étoile : je laisse plus de temps pour le bonus de victoire")
                elif prolonge and not presque:
                    log(f"  étoile obtenue ({degats} %)")
                prolonge = presque
                limite_fige = CAPITULER_SI_FIGE and CAPITULER_SI_FIGE * (2 if presque else 1)
                limite = duree and duree + (BONUS_ETOILE if presque else 0)
                fige = (limite_fige and d is not None and d == degats
                        and maintenant - change >= limite_fige
                        and maintenant - debut >= CAPITULER_APRES_MIN)
                if fige or (limite and maintenant - debut >= limite):
                    abandon = True
                    abandonner(tel, boite, f"dégâts figés à {degats} % depuis {maintenant - change:.0f} s"
                               if fige else f"durée atteinte ({degats} %)" if degats is not None
                               else "durée atteinte")
                    continue
            if not abandon:
                vitesse.guetter(debut)
            time.sleep(0.4)
            continue
        lignes = lignes_de("rentrer_village")
        complet = not lignes or essais % 4 == 3       # de temps en temps, tout l'écran
        ecran = tel.capture() if complet else tel.capture_bande(*lignes)
        essais += 1
        t = localiser(ecran, "rentrer_village")
        if t:
            if vu and abs(vu[0] - t.x) <= 12 and abs(vu[1] - t.y) <= 12:
                return t                             # immobile : la fenêtre a fini de s'ouvrir
            vu = (t.x, t.y)
            time.sleep(0.1)
            continue
        vu = None
        if complet:
            if localiser(ecran, "combat"):
                return None                          # déjà revenu au village
            for nom in popups():
                p = localiser(ecran, nom)
                if p:
                    tel.toucher((p.x, p.y))
                    break
        time.sleep(0.2)
    raise Bloque(f"pas d'écran de fin après {delai} s", ecran)


def une_attaque(tel, numero, debug=False, duree=None):
    log(f"Attaque n°{numero}")

    # 1. Lancement : « Combat », « Trouver une partie », « Attaquer »… jusqu'au combat
    # « Rentrer » de l'écran de recherche : il est aussi affiché pendant toute recherche normale
    # (quelques secondes) ; on ne le touche que si l'écran reste figé (« Impossible d'attaquer
    # ce village »)
    ecran, rouge = attendre(tel, ["terminer_bataille"], 120,
                            en_route=["trouver_partie", "attaquer", "combat"],
                            patients={"recherche_rentrer": 25.0})
    boite = rouge.boite
    if ecran.partielle:
        ecran = tel.capture()                     # l'écran entier pour le butin et la barre

    # 2. Butin : « Suivant » tant qu'il n'y en a pas assez
    ecran, suivant = choisir_village(tel, ecran)

    # 3. La barre : cartes, compteurs, sorts. Sans aucune carte de troupes, c'est louche
    #    (barre pas encore affichée ?) : on regarde à nouveau, deux fois au plus.
    #    Même lecture sur deux images de suite : la barre peut encore être en train d'apparaître
    #    (une carte de sort lue sans son compteur passerait sinon pour une unité).
    avant = None
    for essai in range(4):
        cartes = detecter_cartes(ecran)
        analyser_cartes(ecran, cartes)
        lecture = [c.decrire() for c in cartes]
        complete = any(c.nombre and not c.sort for c in cartes) and \
            not any(c.sort and c.nombre is None for c in cartes)
        if (complete and lecture == avant) or essai == 3:
            break
        avant = lecture
        time.sleep(0.15)
        ecran = tel.capture()
    if not any(c.nombre and not c.sort for c in cartes):
        log("  aucune carte de troupes lisible dans la barre")
    apprendre_cartes(ecran, cartes)
    log(f"  barre : {len(cartes)} cartes, dont {sum(1 for c in cartes if c.connue)} reconnues par leur dessin")
    zones = zones_interdites(ecran, cartes, boite, suivant.boite if suivant else None)
    if debug or not cartes:
        log(f"  analyse : {sauver(annoter(ecran, cartes, zones, []), f'combat_{numero}')}")
    if not cartes:
        tel.toucher((rouge.x, rouge.y))
        sys.exit("Aucune carte détectée dans la barre : armée vide ? J'arrête.")

    # 4. Dézoom, vue haut puis vue bas : tout est posé sur la rangée du bord de la carte
    plan = Plan(ecran.w, ecran.h)
    debut = time.monotonic()
    if not deployer(tel, cartes, zones, boite, plan, debug, numero):
        log("  combat terminé avant la fin du déploiement")

    # 5. Écran de fin : « Rentrer » dès qu'il apparaît (ou capituler au bout de --duree)
    bouton = attendre_fin(tel, boite, duree=duree, debut=debut)
    if bouton:
        tel.toucher((bouton.x, bouton.y))
        log("  → rentrer")
    else:
        log("  déjà revenu au village")
    if debug and isinstance(tel, Telephone):
        log(f"  temps ADB : {tel.bilan_temps()}")


def recuperer(tel, nuit=False):
    """Après un blocage : tente de revenir à un écran connu."""
    try:
        ecran = tel.capture()
        if nuit and localiser(ecran, "nuit/combat"):
            return                                # déjà au village de la base des ouvriers
        for nom in [*(["nuit/rentrer"] if nuit else []), "rentrer_village", *popups()]:
            t = localiser(ecran, nom)
            if t:
                log(f"  → {nom}")
                tel.toucher((t.x, t.y))
                time.sleep(2)
                return
    except Bloque:
        pass


def detecter_village(tel, delai=30):
    """--auto : village principal (False) ou base des ouvriers (True), d'après les boutons à
    l'écran au lancement ; None si rien de reconnu en `delai` secondes."""
    principal = ("combat", "trouver_partie", "terminer_bataille", "suivant")
    ouvriers = ("nuit/combat", "nuit/trouver")
    fin = time.monotonic() + delai
    while time.monotonic() < fin:
        e = tel.capture()
        scores = {}
        for nom in (*principal, *ouvriers):
            t = localiser(e, nom)
            if t:
                scores[nom] = t.score
        if scores:
            return max(scores, key=scores.get) in ouvriers
        if lire_barre_nuit(e).valide:
            return True
        for nom in popups():                      # une fenêtre imprévue cache le village
            p = localiser(e, nom)
            if p:
                tel.toucher((p.x, p.y))
                break
        time.sleep(0.5)
    return None


def boucle(tel, max_attaques, debug, nuit=False, duree=None):
    if nuit:
        manquants = [f"templates/{n}.png" for n in OBLIGATOIRES_NUIT if template(n) is None]
    else:
        manquants = [f"templates/{n}.png" for n in OBLIGATOIRES if template(n) is None]
        if not modeles_glyphes():
            manquants.append("templates/glyphes/")
    if manquants:
        sys.exit("Il manque : " + ", ".join(manquants) + "\nRe-extrais le zip, ou crée un "
                 "bouton avec : python bot_coc.py capture NOM")
    if nuit:
        log("Base des ouvriers" + (f", capitule au bout de {duree:g} s" if duree else ""))
    else:
        log(f"Village principal : {regle_butin()}" + (f", capitule au bout de {duree:g} s" if duree else ""))
    faites = echecs = 0
    while not max_attaques or faites < max_attaques:
        try:
            if nuit:
                une_attaque_nuit(tel, faites + 1, debug, duree)
            else:
                une_attaque(tel, faites + 1, debug, duree)
            faites += 1
            echecs = 0
        except Bloque as e:
            echecs += 1
            chemin = sauver(e.ecran.img, "bloque") if e.ecran is not None else None
            log(f"Bloqué : {e}" + (f" (capture : {chemin})" if chemin else ""))
            if echecs >= 5:
                log("5 blocages d'affilée, j'arrête.")
                break
            if isinstance(tel, Telephone) and not tel.liaison():
                continue
            if echecs >= 2 and isinstance(tel, Telephone):
                log("  2 blocages de suite : je relance le jeu")   # connexion perdue, inactivité…
                if not tel.relancer_jeu():
                    log("  le jeu ne s'affiche pas (écran verrouillé ?)")
                continue
            recuperer(tel, nuit)
    log(f"Fin : {faites} attaque(s) terminée(s).")


# ─── Base des ouvriers (--nuit) : la barre ───────────────────────────────────
#
# La barre : la carte du héros à gauche, puis une carte par camp (numérotées 1, 2, 3…),
# toutes à la même distance les unes des autres. Le fond de chaque carte dit où en est le
# camp : bleu = à poser, vert = posé (troupes en vie), gris = mort ou utilisé.

@dataclass
class CarteNuit:
    x0: int
    x1: int
    etat: str                 # "dispo" (bleue, à poser), "posee" (verte), "grise", "?"
    rang: int = 0             # 0 = héros, puis 1, 2… comme les numéros des camps
    heros: bool = False
    jauge: str = None         # héros posé : "pleine" (3 barres violettes), "charge" ou None
    y_barre: int = None       # héros : haut de sa barre de vie

    def point(self, h):
        """Où toucher la carte : sur le dessin, au-dessus du bouton d'échange du bas."""
        return (self.x0 + self.x1) // 2, int(0.865 * h)

    def decrire(self):
        return "héros" if self.heros else f"camp {self.rang}"


@dataclass
class BarreNuit:
    cartes: list
    bords: int = 0            # cartes dont on voit le bord gauche à sa place
    places: tuple = None      # (x du héros, [x de chaque place de camp]) : fixes tout le combat

    @property
    def heros(self):
        return next((c for c in self.cartes if c.heros), None)

    @property
    def camps(self):
        return [c for c in self.cartes if not c.heros]

    @property
    def valide(self):
        """Vraie barre de combat ? (le village ou l'écran de fin, eux, n'ont pas de cartes
        bleues, vertes ou grises à ces places précises)"""
        return self.bords >= 2 and (len(self.camps) >= 2 or (self.heros is not None and len(self.camps) >= 1))

    def carte(self, rang):
        return next((c for c in self.cartes if c.rang == rang), None)


def _remplissage(zone):
    """Pixels d'une barre de vie : vert, jaune, orange ou rouge vifs."""
    hsv = cv2.cvtColor(zone, cv2.COLOR_BGR2HSV)
    t, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]
    return (s > 120) & (v > 110) & ((t <= 30) | ((t >= 35) & (t <= 80)) | (t >= 170))


def _magenta(zone):
    b, g, r = (zone[..., k].astype(np.int16) for k in range(3))
    return (b > 170) & (r > 130) & (g < 0.75 * r) & (b - g > 70)


def _cyan(zone):
    b, g, r = (zone[..., k].astype(np.int16) for k in range(3))
    return (b > 170) & (g > 140) & (r < 120)


def _bords_cartes(img):
    """Pour chaque colonne : (début de carte probable, fond de carte à droite). Le bord
    gauche d'une carte est clair (fond de carte) et précédé d'un petit vide sombre."""
    h, w = img.shape[:2]
    ya, yb = int(0.86 * h), int(0.97 * h)
    hsv = cv2.cvtColor(img[ya:yb], cv2.COLOR_BGR2HSV)
    s, v = hsv[..., 1], hsv[..., 2]
    profil = (((s > 90) & (v > 100)) | ((s < 45) & (v > 95))).mean(axis=0)
    larg = LARGEUR_CARTE_NUIT * h
    mg, tr = max(4, int(0.17 * larg)), max(3, int(0.12 * larg))
    cum = np.concatenate(([0.0], np.cumsum(profil)))
    xs = np.arange(w)

    def moy(a, b):
        a, b = np.clip(a, 0, w), np.clip(b, 0, w)
        return np.where(b > a, (cum[b] - cum[a]) / np.maximum(1, b - a), 0.0)
    gauche = moy(xs + 2, xs + mg)
    return gauche - moy(xs - tr, xs - 2), gauche, profil


def _score_grille(bord, p, phi, larg):
    pos = np.arange(phi, len(bord) - larg * 0.9, p).round().astype(int)
    sc = np.clip(bord[pos], 0, None)
    return float(sc[sc > 0.25].sum())


def _entete_camp(img, x0, larg, h):
    """Une carte de camp a un bandeau en haut (numéro + barre violette, ou gris quand le
    camp est mort) sur une trentaine de lignes. La carte du héros, une case vide ou un
    détail du décor n'en ont pas."""
    zone = img[int(0.80 * h):int(0.855 * h), int(x0 + 0.40 * larg):int(x0 + 0.92 * larg)]
    if zone.size == 0:
        return False
    hsv = cv2.cvtColor(zone, cv2.COLOR_BGR2HSV)
    gris = ((hsv[..., 1] < 30) & (hsv[..., 2] > 90) & (hsv[..., 2] < 210)).mean(axis=1)
    lignes = int((_magenta(zone).mean(axis=1) > 0.6).sum()) + int((gris > 0.6).sum())
    return lignes >= max(6, 0.012 * h)


def _etat_camp(img, x0, larg, h):
    """État d'une carte de camp, d'après le fond de sa marge gauche et les couleurs du dessin :
    « dispo » (fond bleu, à poser), « posee » (fond vert : en vie, compétence prête),
    « utilisee » (fond gris mais dessin en couleurs : en vie, compétence déjà lancée),
    « grise » (tout gris : mort), « ? » (pas une carte : case vide, autre écran)."""
    zone = img[int(0.87 * h):int(0.95 * h), int(x0 + 0.03 * larg):int(x0 + 0.15 * larg)]
    corps = img[int(0.86 * h):int(0.97 * h), int(x0 + 0.05 * larg):int(x0 + 0.95 * larg)]
    if zone.size == 0 or corps.size == 0:
        return "?"
    hsv = cv2.cvtColor(zone, cv2.COLOR_BGR2HSV).reshape(-1, 3)
    t, s, v = (float(np.median(hsv[:, k])) for k in range(3))
    if s >= 80 and 92 <= t <= 130:
        return "dispo"
    if s >= 80 and 25 <= t <= 80:
        return "posee"
    if s < 45 and v >= 120:
        hc = cv2.cvtColor(corps, cv2.COLOR_BGR2HSV)
        vif = float(((hc[..., 1] > 90) & (hc[..., 2] > 90)).mean())
        return "utilisee" if vif > 0.03 else "grise"
    return "?"


def _lire_heros(img, x0, larg, h):
    """État du héros : « pose » si sa barre de vie est remontée au-dessus des cartes, « mort »
    si sa carte est grise, sinon « dispo ». Sa jauge de compétence (3 barres de plus en plus
    hautes au-dessus de la barre de vie) est « pleine » quand la 3e, à droite, est violette."""
    xa, xb = int(x0 + 0.08 * larg), int(x0 + 0.92 * larg)
    zone = img[int(0.70 * h):int(0.86 * h), xa:xb]
    lignes = np.flatnonzero(_remplissage(zone).mean(axis=1) >= 0.25) if zone.size else []
    y_barre = int(0.70 * h) + int(lignes[0]) if len(lignes) else None
    corps = img[int(0.88 * h):int(0.96 * h), xa:xb]
    gris = corps.size and float((cv2.cvtColor(corps, cv2.COLOR_BGR2HSV)[..., 1] > 70).mean()) < 0.12
    if y_barre is not None and y_barre < 0.815 * h:
        etat = "pose"
    elif gris:
        etat = "mort"
    else:
        etat = "dispo"
    jauge = None
    if etat == "pose":
        ya, yb = max(0, int(y_barre - 0.27 * larg)), int(y_barre - 0.06 * larg)
        barre3 = img[ya:yb, int(x0 + 0.60 * larg):int(x0 + 0.88 * larg)]
        toute = img[ya:yb, xa:xb]
        if barre3.size and float(_magenta(barre3).mean()) >= 0.35:
            jauge = "pleine"
        elif toute.size and (_magenta(toute).any() or _cyan(toute).any()):
            jauge = "charge"
    return etat, jauge, y_barre


def _places_nuit(img, bord, gauche, h, w):
    """Places des cartes, apprises sur la barre du début du combat (toutes les cartes y ont
    leur bandeau numéroté) : la rangée régulière des cartes de camp, prolongée jusqu'à 10
    places pour les renforts de l'étape 2, et la place du héros à gauche. None si rien."""
    larg = LARGEUR_CARTE_NUIT * h
    meilleur = (0.0, None, None)
    for p in np.arange(0.97, 1.03, 0.004) * PAS_CARTE_NUIT * h:
        for phi in range(int(p)):
            sc = _score_grille(bord, p, phi, larg)
            if sc > meilleur[0]:
                meilleur = (sc, float(p), phi)
    _, p, phi = meilleur
    if p is None:
        return None
    vus = []
    marge = max(3, int(0.06 * p))               # chaque carte est cherchée autour de sa place
    for x in np.arange(phi, w - larg * 0.9, p):
        a, z = max(0, int(round(x)) - marge), min(w - 1, int(round(x)) + marge)
        x = a + int(np.argmax(bord[a:z + 1]))
        if bord[x] > 0.25 and gauche[x] > 0.55 and _entete_camp(img, x, larg, h):
            vus.append(x)
    if not vus:
        return None
    if len(vus) >= 2:                           # écart exact, mesuré de la 1re à la dernière carte
        p = (vus[-1] - vus[0]) / round((vus[-1] - vus[0]) / p)
    places = [vus[0] + int(round(k * p)) for k in range(10) if vus[0] + k * p + 0.9 * larg <= w]
    for x in vus:                               # les cartes vues gardent leur position exacte
        k = int(round((x - vus[0]) / p))
        if k < len(places):
            places[k] = x
    return int(round(vus[0] - DECALAGE_HEROS_NUIT * h)), places


def lire_barre_nuit(ecran, places=None):
    """Cartes de la barre (héros à gauche, puis les camps) et leur état. `places` : la
    disposition apprise au début du combat. Chaque carte garde ainsi son numéro (sa place)
    tout le combat, même quand son aspect change (compétence lancée, camp mort)."""
    img, h, w = ecran.img, ecran.h, ecran.w
    bord, gauche, profil = _bords_cartes(img)
    larg = LARGEUR_CARTE_NUIT * h
    if places is None:
        places = _places_nuit(img, bord, gauche, h, w)
        if places is None:
            return BarreNuit([])
    xh, xs = places
    camps, bords = [], 0
    for k, x in enumerate(xs, 1):
        # une carte à cette place ? (une place vide n'est qu'un cadre en pointillés sur le décor)
        a, z = x + int(0.05 * larg), min(w, x + int(0.95 * larg))
        if z <= a or float(profil[a:z].mean()) < 0.12 or float(bord[max(0, x - 4):x + 5].max()) < 0.25:
            continue
        etat = _etat_camp(img, x, larg, h)
        if etat == "?":
            continue                            # pas une barre de combat
        camps.append(CarteNuit(x, int(x + larg), etat, k))
        bords += 1
    cartes = []
    if 0 <= xh and float(profil[xh + int(0.05 * larg):xh + int(0.95 * larg)].mean()) > 0.35:
        etat, jauge, yb = _lire_heros(img, xh, larg, h)
        cartes.append(CarteNuit(xh, int(xh + larg), etat, 0, True, jauge, yb))
    return BarreNuit(cartes + camps, bords, places)


# ─── Base des ouvriers : où poser ────────────────────────────────────────────
#
# Le jeu est vu en perspective isométrique : les côtés de la carte et des bâtiments suivent
# deux directions de pente ±R. Avec U = y + R·x et V = y − R·x, la base adverse tient dans un
# « losange » U0 ≤ U ≤ U1, V0 ≤ V ≤ V1. On le trouve en partant du centre des bâtiments et en
# poussant chaque côté tant qu'il reste des bâtiments derrière. Les troupes sont posées un peu
# plus loin, sur le sol nu (l'anneau qui sépare la base du bord de la carte), tout autour.

R_ISO = 15.84 / 21.2


def zones_nuit(ecran, barre):
    """Rectangles où l'on ne touche jamais : affichages du jeu, bouton rouge, barre."""
    w, h = ecran.w, ecran.h
    zones = [(x0 * w, y0 * h, x1 * w, y1 * h) for x0, y0, x1, y1 in UI_NUIT]
    zones.append((0, 0.79 * h, w, h))                              # la barre des cartes
    if barre is not None and barre.heros is not None:              # la jauge du héros dépasse
        zones.append((barre.heros.x0 - 15, 0.72 * h, barre.heros.x1 + 15, h))
    rouge = bouton_rouge(ecran)
    if rouge:
        x, y, bw, bh = rouge
        zones.append((x - 20, y - 20, x + bw + 20, y + bh + 20))
    return zones


def _texture(img, k):
    h, w = img.shape[:2]
    s = cv2.resize(img, (w // k, h // k), interpolation=cv2.INTER_AREA)
    lab = cv2.GaussianBlur(cv2.cvtColor(s, cv2.COLOR_BGR2LAB).astype(np.float32), (0, 0), 1.0)
    gx = cv2.Sobel(lab, cv2.CV_32F, 1, 0, ksize=3) / 8
    gy = cv2.Sobel(lab, cv2.CV_32F, 0, 1, ksize=3) / 8
    return np.sqrt((gx ** 2 + gy ** 2).sum(axis=2))


def taille_case_nuit(img, lisse):
    """Demi-hauteur b d'une case à l'écran, mesurée sur le damier du sol (sa période
    verticale vaut 2b), là où le sol est nu. None si le motif n'est pas net."""
    h, w = img.shape[:2]
    g = cv2.cvtColor(cv2.resize(img, (w // 2, h // 2), interpolation=cv2.INTER_AREA), cv2.COLOR_BGR2GRAY)
    g = g.astype(np.float32)
    g -= cv2.GaussianBlur(g, (0, 0), 6)
    m = cv2.resize(lisse.astype(np.uint8), (w // 2, h // 2), interpolation=cv2.INTER_NEAREST).astype(bool)
    if m.sum() < 3000:
        return None
    g[~m] = 0
    c0 = float((g[m] ** 2).mean()) or 1.0
    corr = []
    for d in range(7, 50):
        mm = m[:-d] & m[d:]
        corr.append(float((g[:-d] * g[d:])[mm].mean()) / c0 if mm.sum() > 1500 else 0.0)
    for i in range(1, len(corr) - 1):
        if corr[i] > corr[i - 1] and corr[i] >= corr[i + 1] and corr[i] > 0.04:
            periode = 2 * (i + 7)               # en pixels pleine taille (image réduite de moitié)
            if 0.006 * h <= periode / 2 <= 0.045 * h:
                return periode / 2
    return None


def losange_base_nuit(ecran, zones, k=3, seuil=8.0, tau=0.12):
    """Losange de la base adverse : dict U0, U1, V0, V1 (pixels), b (demi-hauteur d'une case),
    plus les masques utiles. None si aucun bâtiment n'est visible au centre."""
    img, h = ecran.img, ecran.h
    gm = _texture(img, k)
    sh, sw = gm.shape
    occupe = gm > seuil
    visible = np.ones((sh, sw), bool)
    for x0, y0, x1, y1 in zones:
        visible[max(0, int(y0 / k)):max(0, int(y1 / k) + 1), max(0, int(x0 / k)):max(0, int(x1 / k) + 1)] = False
    dens = cv2.blur(occupe.astype(np.float32), (7, 7))
    lisse = (dens < 0.08) & visible
    b = taille_case_nuit(img, lisse)
    if b is None:
        b = 15.84 * h / 1080                   # taille au dézoom maximal du village principal
    ys, xs = np.mgrid[0:sh, 0:sw]
    U = (ys * k + R_ISO * xs * k).astype(np.float32)
    V = (ys * k - R_ISO * xs * k).astype(np.float32)
    centre = visible & occupe & (np.abs(xs - sw / 2) < 0.25 * sw) & (ys > 0.12 * sh) & (ys < 0.75 * sh)
    if centre.sum() < 50:
        return None
    uc, vc = float(np.median(U[centre])), float(np.median(V[centre]))
    d = 2 * b                                  # une case, le long de U ou de V
    L = {"U0": uc - 2 * d, "U1": uc + 2 * d, "V0": vc - 2 * d, "V1": vc + 2 * d}

    def bande(cote, decal):
        """Part de pixels occupés dans la bande d'une case derrière ce côté (+ decal cases)."""
        if cote[0] == "U":
            a = L["U0"] - (decal + 1) * d if cote == "U0" else L["U1"] + decal * d
            m = (U >= a) & (U < a + d) & (V >= L["V0"]) & (V <= L["V1"])
        else:
            a = L["V0"] - (decal + 1) * d if cote == "V0" else L["V1"] + decal * d
            m = (V >= a) & (V < a + d) & (U >= L["U0"]) & (U <= L["U1"])
        m &= visible
        return float(occupe[m].mean()) if m.sum() > 30 else None

    actifs = set(L)
    for _ in range(80):
        if not actifs:
            break
        for cote in list(actifs):
            p1 = bande(cote, 0)
            if p1 is None:                     # le côté sort de l'écran ou passe sous la barre
                actifs.discard(cote)
                continue
            p2 = bande(cote, 1)
            if p1 < tau and (p2 is None or p2 < tau):
                actifs.discard(cote)           # deux cases de sol nu : c'est le bord de la base
                continue
            L[cote] += -d if cote.endswith("0") else d
    base = {**L, "b": b, "centre": (uc, vc), "dens": dens, "visible": visible, "k": k}
    base["permis"] = bords_carte_nuit(img, base, U, V)
    return base


def bords_carte_nuit(img, base, U, V):
    """Jusqu'où l'on peut poser derrière chaque côté de la base, en cases : on avance par
    demi-cases et on s'arrête au bord de la carte, là où le sol change franchement de
    couleur (herbe, forêt, rochers de la grotte) ou se couvre de décor sur deux bandes de
    suite. Les variations d'éclairage du sol, elles, restent sous le seuil."""
    k, dens, vis, b = base["k"], base["dens"], base["visible"], base["b"]
    h, w = img.shape[:2]
    lab = cv2.GaussianBlur(cv2.cvtColor(cv2.resize(img, (w // k, h // k), interpolation=cv2.INTER_AREA),
                                        cv2.COLOR_BGR2LAB).astype(np.float32), (0, 0), 1.5)
    profils = {}
    for cote in ("U0", "U1", "V0", "V1"):
        liste = []
        for s in range(24):                     # 24 demi-cases = 12 cases au plus
            if cote[0] == "U":
                a = base["U0"] - (s + 1) * b if cote == "U0" else base["U1"] + s * b
                m = (U >= a) & (U < a + b) & (V >= base["V0"]) & (V <= base["V1"])
            else:
                a = base["V0"] - (s + 1) * b if cote == "V0" else base["V1"] + s * b
                m = (V >= a) & (V < a + b) & (U >= base["U0"]) & (U <= base["U1"])
            m &= vis
            liste.append((lab[m].mean(axis=0), float(dens[m].mean())) if m.sum() >= 15 else None)
        profils[cote] = liste
    refs = [p[0] for liste in profils.values() for p in liste[1:4] if p and p[1] < 0.15]
    permis = {}
    for cote, liste in profils.items():
        permis[cote] = 99.0
        if not refs:
            continue
        ref = np.median(np.array(refs), axis=0)
        hors = [p is not None and (float(np.linalg.norm(p[0] - ref)) > 22 or p[1] > 0.30) for p in liste]
        for s in range(len(hors) - 1):
            if hors[s] and hors[s + 1]:
                permis[cote] = 0.5 * s - 0.5    # une demi-case avant le bord de la carte
                break
    return permis


def _vers_xy(U, V):
    return (U - V) / (2 * R_ISO), (U + V) / 2


def anneau_nuit(base, marge, zones, w, h, sol_nu=True):
    """Points où poser, tout autour de la base, à `marge` cases de son losange (moins si le
    bord de la carte est plus près ; aucun point le long d'un côté collé au bord), dans
    l'ordre : coin gauche → haut → droit → bas. Seulement ceux qui sont visibles, hors des
    affichages et, si sol_nu, sur du sol sans bâtiment ni décor."""
    permis = base.get("permis", {})
    m = {c: min(marge, permis.get(c, 99.0)) for c in ("U0", "U1", "V0", "V1")}
    d = {c: 2 * base["b"] * max(0.0, v) for c, v in m.items()}
    U0, U1, V0, V1 = base["U0"] - d["U0"], base["U1"] + d["U1"], base["V0"] - d["V0"], base["V1"] + d["V1"]
    coins = [(U0, V1), (U0, V0), (U1, V0), (U1, V1), (U0, V1)]
    cotes = ("U0", "V0", "U1", "V1")            # le côté parcouru entre deux coins
    pas = 1.6 * base["b"]
    k, dens = base["k"], base["dens"]
    r = max(1, int(0.8 * base["b"] / k))
    points = []
    for cote, (ua, va), (ub, vb) in zip(cotes, coins, coins[1:]):
        if m[cote] < 0.9:
            continue                            # bord de la carte trop près de la base
        xa, ya = _vers_xy(ua, va)
        xb, yb = _vers_xy(ub, vb)
        n = max(1, int(np.hypot(xb - xa, yb - ya) / pas))
        for t in range(n):
            x, y = xa + (xb - xa) * t / n, ya + (yb - ya) * t / n
            if not (0.01 * w < x < 0.99 * w and 0.02 * h < y < h) or \
                    any(x0 <= x <= x1 and y0 <= y <= y1 for x0, y0, x1, y1 in zones):
                continue
            if sol_nu:
                cx, cy = int(x / k), int(y / k)
                if float(dens[max(0, cy - r):cy + r + 1, max(0, cx - r):cx + r + 1].max()) > 0.25:
                    continue
            points.append((int(round(x)), int(round(y))))
    return points


# ─── Base des ouvriers : le combat ───────────────────────────────────────────

class CombatNuit:
    """Un combat de la base des ouvriers : une ou deux étapes (2e village dans la grotte).
    À chaque étape : dézoom, losange de la base, héros et camps posés tout autour de la base,
    espacés, vérifiés et reposés plus loin si besoin. Ensuite : compétence de chaque camp 5 s
    après sa pose (2 s au moins entre deux), compétence du héros dès que sa jauge est pleine,
    vitesse ×4 une fois les compétences des camps lancées."""

    def __init__(self, tel, debug=False, numero=0, duree=None):
        self.tel, self.debug, self.numero, self.duree = tel, debug, numero, duree
        self.w, self.h = tel.taille()
        self.places = None           # places des cartes, apprises au début du combat
        self.etape = 0
        self.debut = None            # première pose : point de départ de --duree
        self.abandon = False
        self.poses = {}              # rang du camp -> heure de sa pose (étape en cours)
        self.competences = []        # [(heure, rang)] encore à lancer
        self.faites = set()          # camps dont la compétence est lancée ou prévue (étape en cours)
        self.abandonnees = set()     # cartes impossibles à poser dans cette étape
        self.derniere_competence = 0.0
        self.lancees = set()         # camps dont la compétence est lancée (tout le combat : un
                                     # camp qui survit à l'étape 1 est reposé, sans compétence)
        self.heros_tape = 0.0
        self.vitesse = Vitesse(tel)
        self.facteur = 1.0           # 4 si le combat tourne déjà en ×4 quand on pose
        # lignes capturées pour suivre la barre : de la jauge du héros au bas des cartes
        self.y_haut, self.y_bas = int(0.745 * self.h), int(0.975 * self.h)

    def lire(self, ecran=None):
        if ecran is None:
            ecran = self.tel.capture_bande(self.y_haut, self.y_bas)
        barre = lire_barre_nuit(ecran, self.places)
        if barre.valide and self.places is None:
            self.places = barre.places
        return ecran, barre

    # ── déploiement d'une étape ──

    def nouvelle_etape(self, ecran, barre):
        if self.places is None and barre.valide:
            self.places = barre.places
        self.etape += 1
        self.poses, self.faites, self.abandonnees = {}, set(), set()
        self.competences = []
        a_poser = [c for c in barre.cartes if c.etat == "dispo"]
        log(f"  étape {self.etape} : " + (", ".join(c.decrire() for c in a_poser) or "rien à poser"))
        if not a_poser:
            return
        if not dezoomer(self.tel):
            log("  je ne peux pas dézoomer moi-même : dézoome à fond à la main (le jeu garde le zoom)")
        ancien = self.places
        if self.etape == 1:
            self.places = None                 # places relues sur la barre immobile d'après le dézoom
        ecran, barre = self.lire(self.tel.capture())
        if not barre.valide and ancien is not None:
            self.places = ancien
            barre = lire_barre_nuit(ecran, ancien)
        if not barre.valide:
            raise FinDuCombat
        vitesse = lire_vitesse(ecran)[0] if self.etape >= 2 else None   # absent au début du combat
        self.facteur = 4.0 if vitesse == "4x" else 1.0
        if self.facteur > 1:
            log("  combat déjà en ×4 : compétences 4 fois plus rapprochées (même rythme dans le jeu)")
        a_poser = [c for c in barre.cartes if c.etat == "dispo"]
        zones = zones_nuit(ecran, barre)
        base = losange_base_nuit(ecran, zones)
        if base is None:
            log("  base adverse introuvable : je pose autour du centre de l'écran")
            c = (0.42 * self.h + R_ISO * 0.5 * self.w, 0.42 * self.h - R_ISO * 0.5 * self.w)
            base = {"U0": c[0] - 150, "U1": c[0] + 150, "V0": c[1] - 150, "V1": c[1] + 150,
                    "b": 15.84 * self.h / 1080, "dens": np.zeros((self.h // 3, self.w // 3), np.float32), "k": 3}
        self.poser(ecran, base, zones, a_poser)

    def poser(self, ecran, base, zones, a_poser):
        """Pose les cartes tout autour de la base, espacées ; vérifie ; repose plus loin ce
        qui a été refusé (3 essais), en évitant les endroits déjà refusés."""
        dessin, refuses = None, []
        for passe, marge in enumerate(MARGES_NUIT):
            anneau = anneau_nuit(base, marge, zones, self.w, self.h) or \
                anneau_nuit(base, marge, zones, self.w, self.h, sol_nu=False)
            if not anneau:
                log("  aucun point de pose visible autour de la base")
                break
            loin = [p for p in anneau if all(np.hypot(p[0] - r[0], p[1] - r[1]) > 1.5 * base["b"] for r in refuses)]
            points = loin or anneau
            if dessin is None:
                dessin = (points, list(a_poser))
            n = len(a_poser)
            if self.debut is None:
                self.debut = time.monotonic()
            log(f"  pose ({'tout autour de la base' if passe == 0 else f'{marge:.1f} cases plus loin'}) : "
                + ", ".join(c.decrire() for c in a_poser))
            heures, ou_pose = {}, {}
            for q, c in enumerate(a_poser):         # la carte, puis l'endroit où la poser
                p = points[int(((q + 0.5) / n + 0.37 * passe) * len(points)) % len(points)]
                self.tel.toucher(c.point(self.h), p, ecart=0.05)
                heures[c.rang], ou_pose[c.rang] = time.monotonic(), p
            time.sleep(0.7)
            _, barre = self.lire()
            if not barre.valide:
                raise FinDuCombat
            restant = []
            for c in a_poser:
                d = barre.carte(c.rang)
                if d is not None and d.etat == "dispo":
                    restant.append(d)
                    refuses.append(ou_pose[c.rang])
                elif not c.heros and c.rang not in self.lancees and (d is None or d.etat != "utilisee"):
                    self.poses.setdefault(c.rang, heures[c.rang])      # compétence encore à lancer
            if not restant:
                log("  vérifié : tout est posé")
                break
            a_poser = restant
        else:
            log("  impossible de poser : " + ", ".join(c.decrire() for c in a_poser))
            self.abandonnees |= {c.rang for c in a_poser}
        self.planifier()
        if self.debug and dessin:
            sauver(annoter_nuit(ecran, lire_barre_nuit(ecran, self.places), zones, base, dessin[0]),
                   f"nuit_{self.numero}_etape{self.etape}")

    def planifier(self):
        """Compétence de chaque camp posé : 5 s après sa pose, et 2 s au moins après la
        précédente (pour étaler les nuées de chauves-souris des sorcières). Temps du jeu :
        divisés par 4 si le combat tourne en ×4."""
        derniere = max([t for t, _ in self.competences], default=0.0)
        for rang, t_pose in sorted(self.poses.items(), key=lambda kv: (kv[1], kv[0])):
            if rang in self.faites:
                continue
            t = max(t_pose + COMPETENCE_TROUPES_APRES / self.facteur, derniere + COMPETENCE_ECART / self.facteur)
            self.competences.append((t, rang))
            self.faites.add(rang)
            derniere = t
        self.competences.sort()

    def competences_dues(self, barre, avance=0.0):
        """Lance les compétences prévues d'ici `avance` secondes (en attendant leur heure
        exacte) : la capture suivante prend ~1 s, elles ne seront pas en retard. Seulement
        sur une carte vue verte (compétence prête) à la dernière lecture : jamais sur une carte
        déjà utilisée, morte, ni sur un autre écran. Les 2 s d'écart comptent à partir de la
        compétence réellement lancée juste avant."""
        while self.competences:
            t_prevu, rang = self.competences[0]
            t = max(t_prevu, self.derniere_competence + COMPETENCE_ECART / self.facteur)
            if t > time.monotonic() + avance:
                break
            c = barre.carte(rang)
            if c is None and time.monotonic() - t_prevu < 3.0:
                break                          # carte mal lue cette fois : on la relira au tour suivant
            attente = t - time.monotonic()
            if attente > 0:
                time.sleep(attente)
            self.competences.pop(0)
            if c is None or c.etat != "posee":
                continue                       # camp mort, ou compétence déjà lancée : rien à faire
            self.tel.toucher(c.point(self.h))
            self.derniere_competence = time.monotonic()
            self.lancees.add(rang)
            log(f"  → compétence du camp {rang}")

    # ── surveillance jusqu'à la fin (ou jusqu'à l'étape suivante) ──

    def surveiller(self):
        """Lance les compétences à l'heure et surveille la barre. Rend la main quand la
        barre disparaît (fin du combat ou passage à l'étape suivante)."""
        absent = nouveau = 0
        fin = time.monotonic() + 420
        video = getattr(self.tel, "video", lambda: None)() is not None
        tour = 0.0
        while time.monotonic() < fin:
            if video:                          # images instantanées : ~4 coups d'œil par seconde suffisent
                time.sleep(max(0.0, tour + 0.25 - time.monotonic()))
                tour = time.monotonic()
            if self.duree and self.debut and not self.abandon and time.monotonic() - self.debut >= self.duree:
                self.abandon = True
                self.competences = []
                abandonner(self.tel)
                return
            _, barre = self.lire()
            if not barre.valide:
                absent += 1
                if absent >= 2:
                    return
                continue
            absent = 0
            a_poser = [c for c in barre.cartes if c.etat == "dispo" and c.rang not in self.abandonnees]
            nouveau = nouveau + 1 if a_poser else 0
            if nouveau >= 2:                   # des cartes à poser à nouveau : 2e village
                nouveau = 0
                time.sleep(1.0)                # le temps que l'arrivée dans la grotte se termine
                ecran, barre = self.lire(self.tel.capture())
                if barre.valide:
                    self.nouvelle_etape(ecran, barre)
                continue
            hz = barre.heros
            if hz and hz.etat == "pose" and hz.jauge == "pleine" and time.monotonic() - self.heros_tape > 5:
                self.tel.toucher(hz.point(self.h))
                self.heros_tape = time.monotonic()
                log("  → compétence du héros (jauge pleine)")
            self.competences_dues(barre, avance=0.3 if video else 1.3)
            if not self.competences and not self.abandon:
                self.vitesse.guetter(self.debut)      # ×4 seulement quand plus rien n'est à l'heure
        raise Bloque("combat de plus de 7 minutes ?")


def annoter_nuit(ecran, barre, zones, base, anneau):
    """Rouge = zones interdites, jaune = losange de la base, vert = points de pose,
    cartes : bleu = à poser, vert = posée, gris = morte ; violet = jauge du héros."""
    img = ecran.img.copy()
    ep = max(2, ecran.w // 800)
    for x0, y0, x1, y1 in zones:
        cv2.rectangle(img, (int(x0), int(y0)), (int(x1), int(y1)), (0, 0, 255), ep)
    if base is not None:
        coins = [(base["U0"], base["V1"]), (base["U0"], base["V0"]), (base["U1"], base["V0"]), (base["U1"], base["V1"])]
        pts = np.array([_vers_xy(u, v) for u, v in coins], np.int32)
        cv2.polylines(img, [pts], True, (0, 220, 255), ep)
    for x, y in anneau:
        cv2.circle(img, (x, y), 9, (0, 230, 0), -1)
        cv2.circle(img, (x, y), 9, (0, 0, 0), 2)
    couleurs = {"dispo": (255, 140, 0), "posee": (0, 200, 0), "utilisee": (0, 120, 0), "grise": (160, 160, 160),
                "?": (0, 0, 255), "pose": (0, 200, 0), "mort": (160, 160, 160)}
    for c in (barre.cartes if barre else []):
        y0 = int(0.82 * ecran.h)
        cv2.rectangle(img, (c.x0, y0), (c.x1, int(0.98 * ecran.h)), couleurs.get(c.etat, (0, 0, 255)), ep)
        texte = f"{c.decrire()} {c.etat}" + (f" {c.jauge}" if c.jauge else "")
        cv2.putText(img, texte, (c.x0, y0 - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.55, couleurs.get(c.etat, (0, 0, 255)), 2)
        cv2.circle(img, c.point(ecran.h), 8, couleurs.get(c.etat, (0, 0, 255)), -1)
    return img


# ─── Base des ouvriers : le cycle ────────────────────────────────────────────

def attendre_combat_nuit(tel, delai=120):
    """Du village jusqu'au combat : touche « Combat » (haches), puis « Trouver ! » dans la
    fenêtre « Lancer l'attaque », jusqu'à voir la barre des cartes. Les boutons de la fenêtre
    passent avant « Combat », qui reste visible (assombri) derrière elle."""
    fin = time.monotonic() + delai
    derniere = {}
    ecran = None
    noms = [*popups(), "nuit/trouver", "nuit/trouver_partie", "trouver_partie", "nuit/attaquer", "nuit/combat"]
    vus = {}
    while time.monotonic() < fin:
        ecran = tel.capture()
        barre = lire_barre_nuit(ecran)
        if barre.valide:
            return ecran, barre
        nouveaux = {}
        for nom in noms:                          # le plus prioritaire à l'écran, une fois posé
            t = localiser(ecran, nom)
            if not t:
                continue
            nouveaux[nom] = (t.x, t.y)
            if pose(vus, nom, t) and time.monotonic() - derniere.get(nom, 0) > 2.5:
                log(f"  → {nom.replace('nuit/', '')}")
                tel.toucher((t.x, t.y))
                derniere[nom] = time.monotonic()
                if "trouver" in nom:              # recherche lancée : on ne retouche pas « Combat »
                    derniere["nuit/combat"] = time.monotonic() + 10
            break
        vus = nouveaux
        time.sleep(0.3)
    raise Bloque("le combat n'a pas commencé : s'il faut toucher un autre bouton après « Combat », "
                 "crée-le avec « python bot_coc.py capture nuit/trouver »", ecran)


def attendre_fin_nuit(tel, delai=90):
    """Après le combat : « Rentrer » (templates nuit/rentrer puis rentrer_village, ou à
    défaut le gros bouton vert du bas). Renvoie "combat" si la barre revient (2e village)."""
    fin = time.monotonic() + delai
    debut = time.monotonic()
    ecran = None
    while time.monotonic() < fin:
        ecran = tel.capture()
        if lire_barre_nuit(ecran).valide:
            return "combat"
        if localiser(ecran, "nuit/combat"):       # d'abord : déjà au village ? (le « Rentrer »
            return "village"                      # du village principal y ressemble à 0,76)
        for nom in ("nuit/rentrer", "rentrer_village"):
            t = localiser(ecran, nom)
            if t:
                tel.toucher((t.x, t.y))
                log("  → rentrer")
                return "rentre"
        for nom in popups():
            p = localiser(ecran, nom)
            if p:
                tel.toucher((p.x, p.y))
                break
        if time.monotonic() - debut > 8:
            p = bouton_vert(ecran, (0.25, 0.70, 0.75, 0.98))
            if p:
                tel.toucher(p)
                log("  → rentrer (bouton vert du bas ; « capture nuit/rentrer » pour le reconnaître)")
                return "rentre"
        time.sleep(0.4)
    raise Bloque(f"pas d'écran de fin après {delai} s", ecran)


def une_attaque_nuit(tel, numero, debug=False, duree=None):
    log(f"Attaque n°{numero} (base des ouvriers)")
    ecran, barre = attendre_combat_nuit(tel)
    log("  barre : " + ", ".join(f"{c.decrire()} ({c.etat})" for c in barre.cartes))
    if debug:
        log(f"  analyse : {sauver(annoter_nuit(ecran, barre, zones_nuit(ecran, barre), None, []), f'nuit_{numero}')}")
    combat = CombatNuit(tel, debug, numero, duree)
    suite, premiere = "combat", True
    while suite == "combat":                  # 1er village, puis le 2e (étape 2) s'il y en a un
        try:
            if premiere:
                premiere = False
                combat.nouvelle_etape(ecran, barre)
            combat.surveiller()
        except FinDuCombat:
            pass                              # barre disparue en pleine pose : on regarde pourquoi
        suite = attendre_fin_nuit(tel)
    if suite == "village":
        log("  déjà revenu au village")
    if debug and isinstance(tel, Telephone):
        log(f"  temps ADB : {tel.bilan_temps()}")


# ─── Outils : capture de templates, test, images de debug ────────────────────

def sauver(img, nom, horodatage=True):
    DEBUG.mkdir(exist_ok=True)
    chemin = DEBUG / (f"{nom}_{time.strftime('%H%M%S')}.png" if horodatage else f"{nom}.png")
    cv2.imwrite(str(chemin), img)
    return chemin


def annoter(ecran, cartes, zones, chemin, trouves=(), emplacements=None, vue=None):
    """Rouge = zones interdites, jaune = boutons, bleu = cartes, vert = rangée du bord où
    les troupes sont posées, rose = précipitation, violet = rage (ceux de cette vue)."""
    img = ecran.img.copy()
    ep = max(2, ecran.w // 800)
    for x0, y0, x1, y1 in zones:
        cv2.rectangle(img, (int(x0), int(y0)), (int(x1), int(y1)), (0, 0, 255), ep)
    for t in trouves:
        x, y, bw, bh = t.boite
        cv2.rectangle(img, (x, y), (x + bw, y + bh), (0, 255, 255), ep)
        cv2.putText(img, f"{t.nom} {t.score:.2f}", (x, max(30, y - 10)),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.9, (0, 255, 255), ep)
    for i, c in enumerate(cartes):
        cv2.rectangle(img, (c.x0, c.y0), (c.x1, c.y1), (255, 140, 0), ep)
        cv2.putText(img, f"{i + 1} {c.decrire()}", (c.x0 + 2, c.y0 - 10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255, 140, 0), 2)
        cv2.circle(img, c.point, 9, (255, 140, 0), -1)
    for x, y in chemin:
        cv2.circle(img, (x, y), 8, (0, 230, 0), -1)
        cv2.circle(img, (x, y), 8, (0, 0, 0), 2)
    for nom, places in (emplacements or {}).items():
        couleur = (200, 40, 150) if nom == "rage" else (190, 80, 255)
        for v, (x, y) in places:
            if v == vue:
                cv2.circle(img, (x, y), 34, couleur, 5)
                cv2.putText(img, nom[0].upper(), (x - 9, y + 10), cv2.FONT_HERSHEY_SIMPLEX, 0.9, couleur, 3)
    return img


def mode_capture(tel, nom):
    ecran = tel.capture()
    f = min(1.0, 1500 / ecran.w)
    apercu = cv2.resize(ecran.img, None, fx=f, fy=f, interpolation=cv2.INTER_AREA)
    print("Encadre le bouton à la souris, puis Entrée (touche c pour annuler).")
    x, y, w, h = cv2.selectROI("Encadre le bouton puis ENTREE (c = annuler)", apercu,
                               showCrosshair=False)
    cv2.destroyAllWindows()
    if w == 0 or h == 0:
        sys.exit("Annulé, rien n'a été enregistré.")
    x, y, w, h = (int(round(v / f)) for v in (x, y, w, h))
    chemin = TEMPLATES / f"{nom}.png"
    chemin.parent.mkdir(parents=True, exist_ok=True)
    cv2.imwrite(str(chemin), ecran.img[y:y + h, x:x + w])
    print(f"Enregistré : {chemin} ({w}×{h} px)")


def mode_chiffres(tel):
    """Apprend les chiffres sur ton propre écran : tu tapes le butin que tu vois."""
    lignes = lignes_butin(tel.capture())
    if lignes is None:
        sys.exit("Pas de butin à l'écran : lance cette commande pendant l'observation d'un village.")
    GLYPHES.mkdir(parents=True, exist_ok=True)
    ajoutes, marque = 0, time.strftime("%H%M%S")
    for k, (nom, masques) in enumerate(zip(("Or", "Élixir", "Élixir noir"), lignes)):
        lu = "".join(reconnaitre(m, "0123456789")[0] or "?" for m in masques)
        saisie = input(f"{nom} : je lis « {lu or 'rien'} ». Tape le nombre affiché "
                       "(Entrée s'il est juste) : ").replace(" ", "")
        vrai = saisie or lu
        if not vrai.isdigit() or len(vrai) != len(masques):
            print(f"  {len(masques)} chiffres repérés, {len(vrai)} tapés : ligne ignorée")
            continue
        for j, (c, m) in enumerate(zip(vrai, masques)):
            cv2.imwrite(str(GLYPHES / f"{c}_u{marque}{k}{j}.png"),
                        (normaliser(m) * 255).clip(0, 255).astype(np.uint8))
            ajoutes += 1
    print(f"{ajoutes} chiffres ajoutés à templates/glyphes/.")


def mode_test(tel):
    ecran = tel.capture()
    print(f"Écran : {ecran.w}×{ecran.h}")
    trouves = []
    for nom in (*OBLIGATOIRES, *FACULTATIFS, *popups()):
        t = chercher(ecran, nom)
        if t is None:
            print(f"  {nom:<18} pas de fichier templates/{nom}.png")
            continue
        vu = t.score >= seuil_de(nom) and t.valide
        etat = "VU    " if vu else ("écarté" if t.score >= seuil_de(nom) else "absent")
        print(f"  {nom:<18} {etat}  score {t.score:.2f}"
              + ("  (forme proche mais mauvaise couleur)" if etat == "écarté" else ""))
        if vu:
            trouves.append(t)
    rouge = next((t for t in trouves if t.nom == "terminer_bataille"), None)
    cartes, zones = [], []
    if rouge:
        butin = lire_butin(ecran)
        print(f"Butin : {butin if butin else 'illisible'}"
              + (f"  → {'attaque' if butin.suffisant() else 'Suivant'}" if butin else ""))
        cartes = detecter_cartes(ecran)
        zones = zones_interdites(ecran, cartes, rouge.boite)
        analyser_cartes(ecran, cartes)
        print(f"Barre : {len(cartes)} cartes : " + ", ".join(c.decrire() for c in cartes)
              + f"  ({sum(1 for c in cartes if c.connue)} reconnues par leur dessin)")
    else:
        print("Pas sur l'écran d'observation d'un village : butin et barre non analysés.")
    print(f"Bouton de vitesse : {lire_vitesse(ecran)[0] or 'absent'}")
    image = annoter(ecran, cartes, zones, [], trouves)
    print(f"Image annotée : {sauver(image, 'test', False)}")


def mode_vues(tel, vue_image=None):
    """Dézoome, va en vue haut puis en vue bas, et enregistre chaque vue avec les points de
    pose (debug/vue_haut.png, debug/vue_bas.png). Ne pose rien. Avec --image et --vue,
    dessine les points sur une capture enregistrée."""
    ecran = tel.capture()
    rouge = localiser(ecran, "terminer_bataille")
    cartes = detecter_cartes(ecran) if rouge else []
    boite = rouge.boite if rouge else (int(0.03 * ecran.w), int(0.72 * ecran.h), int(0.09 * ecran.w), int(0.067 * ecran.h))
    zones = zones_interdites(ecran, cartes, boite)
    plan = Plan(ecran.w, ecran.h)
    b, trouves = plan.d["bords"], plan.d["trouves"]
    print("Bords de la carte : " + ", ".join(f"{NOMS_BORDS[k]} {b[k]:+.2f} ({'trouvé' if trouves[k] else 'estimé'})"
                                             for k in ("i0", "j0", "i1", "j1")))
    captures = {}
    if vue_image:
        captures[vue_image] = ecran
    else:
        print("Dézoom : " + ("fait" if dezoomer(tel) else
                             "impossible tout seul (dézoome à fond à la main, puis relance)"))
        for vue in ("haut", "bas"):
            aller_vue(tel, vue, ecran.w, ecran.h)
            captures[vue] = tel.capture()
        mesure = mesurer_decalage(captures["haut"], captures["bas"], plan, zones)
        attendu = plan.d["decalage_bas"]
        if mesure is None:
            print("Décalage entre les vues : introuvable (dézoom pas au maximum ?)")
        else:
            print(f"Décalage entre les vues : {mesure[0]:+.0f}, {mesure[1]:+.0f} px "
                  f"(attendu {attendu[0]:+.0f}, {attendu[1]:+.0f})")
            plan.decalage = list(mesure)
    for vue, e in captures.items():
        chemin = plan.chemin(vue, zones)
        image = annoter(e, cartes, zones, chemin)
        print(f"Vue {vue} : {len(chemin)} points de pose  → {sauver(image, f'vue_{vue}', False)}")


def mode_test_nuit(tel):
    """Base des ouvriers : boutons reconnus, état des cartes, base adverse et points de
    pose sur l'écran actuel (sans dézoomer) -> debug/test_nuit.png."""
    ecran = tel.capture()
    print(f"Écran : {ecran.w}×{ecran.h}")
    for nom in ("nuit/combat", "nuit/trouver", "nuit/trouver_partie", "trouver_partie", "nuit/rentrer",
                "rentrer_village", "confirmer", *popups()):
        t = chercher(ecran, nom)
        if t is not None:
            vu = t.score >= seuil_de(nom) and t.valide
            print(f"  {nom:<20} {'VU    ' if vu else 'absent'}  score {t.score:.2f}")
    barre = lire_barre_nuit(ecran)
    zones, base, anneau = zones_nuit(ecran, barre), None, []
    if barre.valide:
        print("Barre : " + ", ".join(f"{c.decrire()} {c.etat}" + (f" (jauge {c.jauge})" if c.jauge else "")
                                     for c in barre.cartes))
        base = losange_base_nuit(ecran, zones)
        if base:
            anneau = anneau_nuit(base, MARGES_NUIT[0], zones, ecran.w, ecran.h)
            print(f"Base adverse trouvée (case ≈ {base['b']:.0f} px) : {len(anneau)} points de pose visibles")
        else:
            print("Base adverse introuvable")
    else:
        print("Pas de barre de combat de la base des ouvriers à l'écran.")
    print(f"Bouton de vitesse : {lire_vitesse(ecran)[0] or 'absent'}")
    print(f"Image annotée : {sauver(annoter_nuit(ecran, barre, zones, base, anneau), 'test_nuit', False)}")


def mode_vues_nuit(tel):
    """Base des ouvriers, pendant l'observation : dézoome, repère la base et enregistre les
    points de pose dans debug/nuit_vue.png. Ne pose rien."""
    if not lire_barre_nuit(tel.capture()).valide:
        print("Attention : je ne vois pas la barre d'un combat de la base des ouvriers.")
    print("Dézoom : " + ("fait" if dezoomer(tel) else
                         "impossible tout seul (dézoome à fond à la main, puis relance)"))
    ecran = tel.capture()
    barre = lire_barre_nuit(ecran)
    zones = zones_nuit(ecran, barre)
    base = losange_base_nuit(ecran, zones)
    anneau = anneau_nuit(base, MARGES_NUIT[0], zones, ecran.w, ecran.h) if base else []
    print(f"Base adverse : {'trouvée' if base else 'introuvable'}"
          + (f" (case ≈ {base['b']:.0f} px), {len(anneau)} points de pose visibles" if base else ""))
    print(f"Image : {sauver(annoter_nuit(ecran, barre, zones, base, anneau), 'nuit_vue', False)}")


def mode_resolution(tel, pourcent):
    """Change la résolution du jeu à la main (le jeu redémarre). Le bot ne la remettra pas
    tout seul : c'est ton choix."""
    print(f"Je passe le jeu en {pourcent} % (il redémarre, ~15 s)…")
    accepte, effectif = tel.passer_en(pourcent / 100)
    if accepte:
        MEMO_RESOLUTION.unlink(missing_ok=True)
    print(f"Jeu en {pourcent} %." if effectif else
          "Réglage fait ; il s'appliquera dès que le jeu sera affiché." if accepte else
          "Le téléphone refuse le changement : règle-le à la main dans les paramètres du jeu.")


def mode_recalibrer():
    """Oublie les bords trouvés : ils seront recherchés au prochain combat."""
    if CARTE_JSON.exists():
        d = json.loads(CARTE_JSON.read_text())
        d["trouves"] = {k: False for k in ("i0", "j0", "i1", "j1")}
        CARTE_JSON.write_text(json.dumps(d, indent=2, ensure_ascii=False))
    print("Les bords de la carte seront recherchés au prochain combat.")


def lire_nombre(texte):
    """« 2.2M », « 2,2 M », « 800k », « 2200000 » → entier."""
    t = texte.strip().lower().replace(" ", "").replace(",", ".")
    m = re.fullmatch(r"(\d+(?:\.\d+)?)([km]?)", t)
    if not m:
        raise argparse.ArgumentTypeError(f"nombre illisible : {texte} (exemples : 2200000, 2.2M, 800k)")
    return int(round(float(m.group(1)) * {"": 1, "k": 1_000, "m": 1_000_000}[m.group(2)]))


def arret_demande(*_):
    raise KeyboardInterrupt


def main():
    global BUTIN_MIN_TOTAL, CAPITULER_SI_FIGE
    # Ctrl+C, l'appli Bot CoC ou la fin de la session KDE : arrêt propre dans tous les cas, même
    # si le programme qui a lancé le bot ignorait Ctrl+C
    signal.signal(signal.SIGINT, arret_demande)
    signal.signal(signal.SIGTERM, arret_demande)
    p = argparse.ArgumentParser(description="Boucle d'attaques Clash of Clans (ADB + OpenCV).")
    p.add_argument("mode", nargs="?", default="run",
                   choices=["run", "test", "capture", "chiffres", "vues", "recalibrer", "resolution"])
    p.add_argument("nom", nargs="?", help="nom du template à créer (mode capture), ou 50 / 100 (mode resolution)")
    p.add_argument("--nuit", action="store_true", help="base des ouvriers (base de nuit) au lieu du village principal")
    p.add_argument("--auto", action="store_true", help="village principal ou base des ouvriers : vu sur l'écran au lancement")
    p.add_argument("--fige", type=float, default=None, metavar="SECONDES",
                   help=f"capitule quand les dégâts n'ont pas bougé depuis ce temps (0 = jamais ; défaut {CAPITULER_SI_FIGE:g})")
    p.add_argument("--attaques", type=int, default=0, help="nombre d'attaques puis arrêt (0 = sans fin)")
    p.add_argument("--duree", type=float, default=None, metavar="SECONDES",
                   help="au bout de ce temps de combat, appuie sur Capituler / Terminer la bataille")
    p.add_argument("--butin", type=lire_nombre, default=None, metavar="TOTAL",
                   help=f"butin minimum or + élixir + {POIDS_NOIR} × noir (défaut 2.2M, 0 = aucun)")
    for r, nom in RESSOURCES.items():
        p.add_argument(f"--{nom.replace('é', 'e')}", dest=r, type=lire_nombre, default=None, metavar="N",
                       help=f"{nom} minimum, à la place du total (plusieurs : tous doivent être atteints)")
    p.add_argument("--image", help="utiliser une capture enregistrée au lieu du téléphone")
    p.add_argument("--vue", choices=["haut", "bas"], help="avec --image : la vue de cette capture (mode vues)")
    p.add_argument("--debug", action="store_true", help="enregistre l'analyse de chaque combat")
    a = p.parse_args()
    if a.mode == "capture" and not a.nom:
        p.error("indique le nom du template, par ex. : python bot_coc.py capture combat")
    if a.mode == "resolution" and a.nom not in ("50", "100"):
        p.error("indique 50 ou 100, par ex. : python bot_coc.py resolution 100")
    if a.mode in ("run", "resolution") and a.image:
        p.error("--image ne sert qu'aux modes test, capture, chiffres et vues")
    if a.mode == "vues" and a.image and not a.vue and not a.nuit:
        p.error("avec --image, précise la vue de la capture : --vue haut ou --vue bas")
    if a.duree is not None and a.duree <= 0:
        p.error("--duree doit être un nombre de secondes positif, par ex. --duree 90")
    BUTIN_MIN.update({r: getattr(a, r) for r in RESSOURCES if getattr(a, r) is not None})
    if a.butin is not None:
        BUTIN_MIN_TOTAL = a.butin
    elif BUTIN_MIN:
        BUTIN_MIN_TOTAL = 0
    if a.fige is not None:
        CAPITULER_SI_FIGE = a.fige or None
    if a.mode == "recalibrer":
        mode_recalibrer()
        return
    tel = ImageFixe(a.image) if a.image else Telephone()
    try:
        if a.mode in ("test", "capture", "chiffres", "vues") and not a.image:
            tel.avertir_resolution()
        if a.mode == "resolution":
            mode_resolution(tel, int(a.nom))
        elif a.mode == "capture":
            mode_capture(tel, a.nom)
        elif a.mode == "chiffres":
            mode_chiffres(tel)
        elif a.mode == "test":
            if a.nuit:
                mode_test_nuit(tel)
            else:
                mode_test(tel)
        elif a.mode == "vues":
            if a.nuit:
                mode_vues_nuit(tel)
            else:
                mode_vues(tel, a.vue if a.image else None)
        else:
            tel.resolution_du_bot()
            nuit = a.nuit
            if a.auto:
                nuit = detecter_village(tel)
                if nuit is None:
                    sys.exit("Village pas reconnu : ouvre le village principal ou la base des ouvriers, puis relance.")
                log("Village détecté : " + ("base des ouvriers" if nuit else "village principal"))
            boucle(tel, a.attaques, a.debug, nuit, a.duree)
    except KeyboardInterrupt:
        print()
        log("Arrêt demandé (Ctrl+C).")
    finally:
        tel.fermer()


if __name__ == "__main__":
    main()

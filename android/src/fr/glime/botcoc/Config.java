package fr.glime.botcoc;

import java.io.File;

/** Réglages du bot : les mêmes valeurs que bot_coc.py sur le PC. */
final class Config {
    private Config() {}

    static final String JEU = "com.supercell.clashofclans";

    static final double SEUIL = 0.80;            // confiance minimale pour reconnaître un bouton
    static final double SEUIL_POPUP = 0.92;      // popups/*.png : touchés dès qu'ils sont vus
    static final double REDUCTION = 0.5;         // recherche en demi-résolution
    static final double[] ECHELLES = {1.0, 0.95, 1.05, 0.9, 1.1, 0.85, 1.15, 0.8, 1.2, 0.75, 1.25, 0.7, 1.3};

    static final int POIDS_NOIR = 120;
    static final int SUIVANTS_MAX = 30;

    static final long APPUI_MS = 20;             // durée d'une touche
    static final long ECART_MS = 22;             // pause entre deux touches
    static final int PINCEMENTS = 2;
    static final long PINCEMENT_MS = 140;

    static final double[] BARRE = {0.80, 1.0};
    static final double SORTS_APRES = 2.5;
    static final double SORT_RAYON = 3.5;
    static final double SORT_DENSITE_MIN = 0.02;
    static final int RAFALE = 40;
    static final int EXTRA = 2;

    static final double[][] UI_FIXE = {
            {0.00, 0.00, 0.18, 0.30},     // butin disponible, nom du joueur
            {0.80, 0.00, 1.00, 0.24},     // ressources
            {0.42, 0.00, 0.58, 0.14},     // chrono
            {0.84, 0.48, 1.00, 0.80}};    // « Suivant », dégâts, bouton de vitesse

    static final String[] OBLIGATOIRES = {"combat", "trouver_partie", "terminer_bataille", "suivant", "rentrer_village"};

    static final double VITESSE_APRES = 100.0;
    static final double VITESSE_PERIODE = 3.0;
    static final double CAPITULER_APRES_MIN = 20.0;
    static final int SEUIL_ETOILE = 46;
    static final double BONUS_ETOILE = 15.0;

    // réglés par l'appli à chaque lancement
    static long butinMinTotal = 2_200_000;
    static long minOr = -1, minElixir = -1, minNoir = -1;     // -1 : pas de minimum pour cette ressource
    static double capitulerSiFige = 6.0;                      // 0 : jamais
    static int attaques = 0;                                  // 0 : sans fin
    static double duree = 0;                                  // 0 : pas de limite
    static String village = "auto";                           // auto, principal, ouvriers
    static boolean debug = false;

    static File racine = new File("/data/local/tmp/botcoc");

    private static boolean opencv = false;

    /** Charge OpenCV (une fois). */
    static synchronized void opencv() {
        if (opencv) return;
        System.loadLibrary("opencv_java4");
        opencv = true;
    }

    static File templates() { return new File(racine, "templates"); }

    static File debugDossier() { return new File(racine, "debug"); }
}

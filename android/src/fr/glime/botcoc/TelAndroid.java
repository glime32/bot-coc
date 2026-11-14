package fr.glime.botcoc;

/** Le téléphone lui-même : images du partage d'écran, doigts du service d'accessibilité. Tant
 *  que Clash of Clans n'est pas au premier plan, le bot se met en pause (il ne touche jamais une
 *  autre appli). */
final class TelAndroid extends Tel {
    private int w, h;

    void preparer() {
        BotService s = service();
        int[] t = CaptureService.tailleEcran(s);
        w = t[0];
        h = t[1];
        if (!s.jeuAuPremierPlan()) {
            Journal.log("Clash of Clans n'est pas affiché : je le lance");
            s.ouvrirJeu();
        }
        attendreJeu();
    }

    private static BotService service() {
        BotService s = BotService.instance;
        if (s == null) throw new Combat.Fin("Le service d'accessibilité Bot CoC est coupé : rallume-le dans les réglages.");
        return s;
    }

    private static CaptureService partage() {
        CaptureService c = CaptureService.instance;
        if (c == null || !CaptureService.actif()) throw new Combat.Fin("Le partage d'écran est arrêté.");
        return c;
    }

    /** Attend que le jeu revienne au premier plan (notification ouverte, autre appli, écran
     *  verrouillé…). */
    private void attendreJeu() {
        BotService s = service();
        if (s.jeuAuPremierPlan() && !Overlay.panneauVisible) return;
        Controle.Etat avant = Controle.etat;
        Controle.etat(Controle.Etat.PAUSE);
        boolean cache = !s.jeuAuPremierPlan();
        if (cache) Journal.log("  jeu caché : pause");
        while (!s.jeuAuPremierPlan() || Overlay.panneauVisible) {
            super.dormir(0.3);
            s = service();
        }
        if (cache) Journal.log("  jeu revenu : je reprends");
        Controle.etat(avant == Controle.Etat.PAUSE ? Controle.Etat.MARCHE : avant);
        if (cache) super.dormir(0.8);
    }

    @Override
    Ecran capture() {
        Controle.verifier();
        attendreJeu();
        Img petite = partage().image(250);
        if (petite == null) petite = partage().image(1500);
        if (petite == null) throw new Bloque("aucune image du partage d'écran (écran éteint ?)");
        if (Controle.etat == Controle.Etat.BLOQUE) Controle.etat(Controle.Etat.MARCHE);
        return new Ecran(petite, w, h);
    }

    @Override
    void toucher(int[]... points) {
        if (points.length == 0) return;
        attendreJeu();
        service().toucher(points, w, h);
    }

    @Override
    void toucherLent(long ecartMs, int[]... points) {
        if (points.length == 0) return;
        attendreJeu();
        service().toucher(points, w, h, ecartMs);
    }

    @Override
    void glisser(double x1, double y1, double x2, double y2, long ms, long tenirMs) {
        attendreJeu();
        service().glisser(x1, y1, x2, y2, ms, tenirMs, w, h);
    }

    @Override
    boolean pincer(boolean dezoom) {
        attendreJeu();
        return service().pincer(dezoom, w, h);
    }

    @Override
    int[] taille() { return new int[]{w, h}; }

    @Override
    boolean relancerJeu() {
        BotService s = service();
        s.accueil();
        dormir(1.5);
        if (!s.ouvrirJeu()) return false;
        double fin = maintenant() + 30;
        while (maintenant() < fin) {
            dormir(1);
            if (s.jeuAuPremierPlan()) {
                dormir(2);
                return true;
            }
        }
        return false;
    }
}

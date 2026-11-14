package fr.glime.botcoc;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/** Lance et arrête le bot dans son propre fil ; tient les compteurs affichés par la pastille. */
final class Controle {
    private Controle() {}

    enum Etat { ARRETE, MARCHE, PAUSE, BLOQUE }

    interface Ecouteur { void change(); }

    static volatile boolean arret = false;
    static volatile Etat etat = Etat.ARRETE;
    static volatile int attaque = 0, total = 0, faites = 0, suivants = 0, blocages = 0;
    static volatile boolean arretDemande = false;
    static volatile String derniereFin = null;
    private static Thread fil;
    private static final List<Ecouteur> ecouteurs = new ArrayList<>();

    static synchronized void ecouter(Ecouteur e) { ecouteurs.add(e); }

    static synchronized void oublier(Ecouteur e) { ecouteurs.remove(e); }

    static void prevenir() {
        List<Ecouteur> copie;
        synchronized (Controle.class) { copie = new ArrayList<>(ecouteurs); }
        for (Ecouteur e : copie) e.change();
    }

    static void verifier() {
        if (arret || Thread.currentThread().isInterrupted()) throw new Arret();
    }

    static synchronized boolean enMarche() { return fil != null && fil.isAlive(); }

    static void etat(Etat e) {
        if (etat != e) {
            etat = e;
            prevenir();
        }
    }

    static void attaque(int n) {
        attaque = n;
        if (etat != Etat.PAUSE) etat = Etat.MARCHE;
        prevenir();
    }

    static void faite(int n) { faites = n; prevenir(); }

    static void suivant() { suivants++; prevenir(); }

    static void bloque() {
        blocages++;
        etat = Etat.BLOQUE;
        prevenir();
    }

    /** Lit les réglages de l'appli dans Config. */
    static void appliquer(Context c) {
        SharedPreferences p = Reglages.prefs(c);
        Config.village = p.getString("village", "auto");
        Config.attaques = p.getInt("attaques", 14);
        Config.duree = p.getInt("duree", 29);
        Config.capitulerSiFige = p.getInt("fige", 6);
        boolean parRessource = p.getBoolean("parRessource", false);
        Config.minOr = Config.minElixir = Config.minNoir = -1;
        if (parRessource) {
            Config.butinMinTotal = 0;
            if (p.getBoolean("avecOr", true)) Config.minOr = Math.round(p.getFloat("butinOr", 1.0f) * 1_000_000);
            if (p.getBoolean("avecElixir", false)) Config.minElixir = Math.round(p.getFloat("butinElixir", 1.0f) * 1_000_000);
            if (p.getBoolean("avecNoir", true)) Config.minNoir = p.getInt("butinNoir", 8000);
        } else {
            Config.butinMinTotal = Math.round(p.getFloat("butin", 2.2f) * 1_000_000);
        }
        Config.debug = p.getBoolean("debug", false);
    }

    /** Démarre le bot (le partage d'écran et le service d'accessibilité doivent être prêts). */
    static synchronized boolean lancer(Context c) { return lancer(c, null); }

    /** `essai` : réglages à changer pour ce lancement seulement (commande adb). */
    static synchronized boolean lancer(Context c, android.os.Bundle essai) {
        if (enMarche()) return false;
        appliquer(c);
        if (essai != null) {
            if (essai.containsKey("attaques")) Config.attaques = essai.getInt("attaques");
            if (essai.containsKey("duree")) Config.duree = essai.getInt("duree");
            if (essai.containsKey("debug")) Config.debug = essai.getBoolean("debug");
            if (essai.containsKey("village")) Config.village = essai.getString("village");
        }
        arret = false;
        arretDemande = false;
        derniereFin = null;
        attaque = faites = suivants = blocages = 0;
        total = Config.attaques;
        etat = Etat.MARCHE;
        Journal.ouvrir(new File(Config.debugDossier(), "journal.txt"));
        fil = new Thread(Controle::tourner, "bot");
        fil.start();
        prevenir();
        return true;
    }

    static synchronized void arreter() {
        if (fil == null || !fil.isAlive()) return;
        arret = true;
        arretDemande = true;
        fil.interrupt();
    }

    private static void tourner() {
        String fin = null;
        try {
            TelAndroid tel = new TelAndroid();
            tel.preparer();
            boolean nuit = Config.village.equals("ouvriers");
            if (Config.village.equals("auto")) {
                Boolean n = Combat.detecterVillage(tel, 30);
                if (n == null) {
                    fin = "Village pas reconnu : ouvre le village principal, puis relance.";
                    Journal.log(fin);
                    return;
                }
                Journal.log("Village détecté : " + (n ? "base des ouvriers" : "village principal"));
                nuit = n;
            }
            double duree = nuit ? 0 : Config.duree;     // base des ouvriers : combat jusqu'au bout
            Combat.boucle(tel, Config.attaques, Config.debug, nuit, duree);
            fin = "Fin : " + faites + " attaque(s) terminée(s).";
        } catch (Arret a) {
            Journal.log("Arrêt demandé.");
        } catch (Combat.Fin f) {
            fin = f.getMessage();
            Journal.log(fin);
        } catch (Bloque b) {
            fin = "Bloqué : " + b.getMessage();
            Journal.log(fin);
        } catch (Throwable t) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            fin = "Erreur : " + t;
            Journal.log(fin);
            for (String l : sw.toString().split("\n")) {
                if (l.contains("fr.glime")) Journal.log("    " + l.trim());
            }
        } finally {
            derniereFin = arretDemande ? null : fin;
            synchronized (Controle.class) { fil = null; }
            etat = Etat.ARRETE;
            prevenir();
        }
    }
}

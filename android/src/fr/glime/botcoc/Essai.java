package fr.glime.botcoc;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Essais sans toucher au jeu, lancés par adb (app_process) sur des captures enregistrées :
 *  CLASSPATH=bot-coc.apk app_process / fr.glime.botcoc.Essai DOSSIER test IMAGE… */
public final class Essai {
    private Essai() {}

    public static void main(String[] a) {
        String dossier = a[0];
        System.load(dossier + "/libc++_shared.so");
        System.load(dossier + "/libopencv_java4.so");
        Config.racine = new File(dossier);
        Journal.console = true;
        String mode = a[1];
        for (int i = 2; i < a.length; i++) {
            File f = new File(a[i]);
            Img img = Img.lire(f, true);
            if (img == null) {
                System.out.println("Image illisible : " + f);
                continue;
            }
            System.out.println("== " + f.getName());
            Ecran e = img.w > 1500 ? new Ecran(img) : new Ecran(img, img.w * 2, img.h * 2);
            if (mode.equals("test")) test(e, f.getName());
            if (mode.equals("chrono")) chrono(e);
            if (mode.equals("attente")) attente(e);
            if (mode.equals("nuit")) nuit(e);
        }
    }

    private static double ms(long t0) { return (System.nanoTime() - t0) / 1e6; }

    private static void chrono(Ecran ecran) {
        long t0 = System.nanoTime();
        Img g = ecran.gris();
        System.out.println(String.format(Locale.US, "gris : %.1f ms", ms(t0)));
        Img tpl = Img.lire(new java.io.File(Config.templates(), "trouver_partie.png"), true).gris()
                .facteur(Config.REDUCTION, org.opencv.imgproc.Imgproc.INTER_AREA);
        System.out.println("modèle " + tpl.w + "×" + tpl.h + ", écran gris " + g.w + "×" + g.h
                + ", threads OpenCV " + org.opencv.core.Core.getNumThreads());
        for (int k = 0; k < 3; k++) {
            t0 = System.nanoTime();
            org.opencv.core.Mat mg = g.mat(), mt = tpl.mat(), res = new org.opencv.core.Mat();
            double copie = ms(t0);
            t0 = System.nanoTime();
            org.opencv.imgproc.Imgproc.matchTemplate(mg, mt, res, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            double mt1 = ms(t0);
            t0 = System.nanoTime();
            org.opencv.core.Core.minMaxLoc(res);
            double mm = ms(t0);
            Img g4 = ecran.gris4(), t4 = tpl.facteur(0.5, org.opencv.imgproc.Imgproc.INTER_AREA);
            org.opencv.core.Mat m4 = g4.mat(), mt4 = t4.mat(), r4 = new org.opencv.core.Mat();
            t0 = System.nanoTime();
            org.opencv.imgproc.Imgproc.matchTemplate(m4, mt4, r4, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            double mt4ms = ms(t0);
            System.out.println(String.format(Locale.US, "copie %.1f ms · matchTemplate demi %.1f ms · minMaxLoc %.1f ms · quart %.1f ms",
                    copie, mt1, mm, mt4ms));
            mg.release(); mt.release(); res.release(); m4.release(); mt4.release(); r4.release();
        }
        for (int fils : new int[]{1, 4, 8}) {
            org.opencv.core.Core.setNumThreads(fils);
            org.opencv.core.Mat mg = g.mat(), mt = tpl.mat(), res = new org.opencv.core.Mat();
            org.opencv.imgproc.Imgproc.matchTemplate(mg, mt, res, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            t0 = System.nanoTime();
            for (int k = 0; k < 3; k++) org.opencv.imgproc.Imgproc.matchTemplate(mg, mt, res, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            System.out.println(String.format(Locale.US, "%d fil(s) : %.1f ms", fils, ms(t0) / 3));
            mg.release(); mt.release(); res.release();
        }
        java.util.concurrent.ExecutorService ex = java.util.concurrent.Executors.newFixedThreadPool(4);
        org.opencv.core.Core.setNumThreads(1);
        t0 = System.nanoTime();
        java.util.List<java.util.concurrent.Future<?>> fs = new java.util.ArrayList<>();
        for (int k = 0; k < 8; k++) fs.add(ex.submit(() -> {
            org.opencv.core.Mat mg = g.mat(), mt = tpl.mat(), res = new org.opencv.core.Mat();
            org.opencv.imgproc.Imgproc.matchTemplate(mg, mt, res, org.opencv.imgproc.Imgproc.TM_CCOEFF_NORMED);
            mg.release(); mt.release(); res.release();
        }));
        for (java.util.concurrent.Future<?> f : fs) try { f.get(); } catch (Exception x) { }
        System.out.println(String.format(Locale.US, "8 comparaisons sur 4 fils d'un seul cœur chacun : %.1f ms au total", ms(t0)));
        ex.shutdown();
        org.opencv.core.Core.setNumThreads(8);
        for (String n : new String[]{"trouver_partie", "suivant", "rentrer_village", "popups/ok"}) {
            Gabarits.oublier();
            t0 = System.nanoTime();
            Trouve t = Gabarits.chercher(ecran, n);
            System.out.println(String.format(Locale.US, "chercher %s (sans mémo) : %.0f ms, score %.2f", n, ms(t0), t.score));
        }
    }

    /** Ce que coûte la boucle d'attente d'un écran (attendre()) quand les boutons sont absents. */
    private static void attente(Ecran ecran) {
        Gabarits.oublier();
        List<String> noms = new ArrayList<>(List.of("terminer_bataille", "trouver_partie", "attaquer", "combat", "recherche_rentrer"));
        noms.addAll(1, Gabarits.popups());
        double total = 0, pire = 0;
        long debut = System.nanoTime();
        int tours = 0;
        while ((System.nanoTime() - debut) / 1e9 < 35) {
            long t0 = System.nanoTime();
            for (String n : noms) Gabarits.localiser(ecran, n);
            double d = ms(t0);
            total += d;
            pire = Math.max(pire, d);
            tours++;
            try { Thread.sleep(200); } catch (InterruptedException x) { return; }
        }
        System.out.println(String.format(Locale.US, "%d tours en 35 s : %.0f ms par tour en moyenne, %.0f ms au pire, calcul %.0f %% du temps",
                tours, total / tours, pire, 100 * total / ((System.nanoTime() - debut) / 1e6)));
    }

    private static void nuit(Ecran ecran) {
        long t0 = System.nanoTime();
        for (String n : new String[]{"nuit/combat", "nuit/trouver", "rentrer_village"}) {
            Trouve t = Gabarits.chercher(ecran, n);
            System.out.println(String.format(Locale.US, "  %-18s %s", n, t == null ? "pas de fichier" : String.format(Locale.US, "score %.2f", t.score)));
        }
        Nuit.Barre barre = Nuit.lireBarre(ecran);
        List<String> l = new ArrayList<>();
        for (Nuit.CarteNuit c : barre.cartes) l.add(c.decrire() + " " + c.etat + (c.jauge != null ? " " + c.jauge : "") + " x=" + c.x0);
        System.out.println("Barre : " + (barre.valide() ? "valide" : "pas une barre de combat") + " : " + String.join(", ", l));
        if (barre.valide()) {
            List<double[]> zones = Nuit.zones(ecran, barre);
            long t1 = System.nanoTime();
            Nuit.Base base = Nuit.losange(ecran, zones);
            System.out.println(String.format(Locale.US, "losange en %.0f ms", ms(t1)));
            if (base == null) {
                System.out.println("Base adverse introuvable");
            } else {
                List<int[]> anneau = Nuit.anneau(base, Nuit.MARGES[0], zones, ecran.w, ecran.h, true);
                System.out.println(String.format(Locale.US, "Base : U %.0f…%.0f V %.0f…%.0f, case %.1f px, bords %s, %d points de pose",
                        base.U0, base.U1, base.V0, base.V1, base.b, base.permis, anneau.size()));
                Combat.sauver(Nuit.annoter(ecran, barre, zones, base, anneau), "test_nuit");
            }
        }
        System.out.println(String.format(Locale.US, "(%.0f ms)", ms(t0)));
    }

    private static void test(Ecran ecran, String nom) {
        long t0 = System.nanoTime();
        System.out.println("Écran : " + ecran.w + "×" + ecran.h);
        List<Trouve> trouves = new ArrayList<>();
        List<String> noms = new ArrayList<>(List.of(Config.OBLIGATOIRES));
        noms.add("attaquer");
        noms.addAll(Gabarits.popups());
        for (String n : noms) {
            Trouve t = Gabarits.chercher(ecran, n);
            if (t == null) {
                System.out.println(String.format("  %-18s pas de fichier templates/%s.png", n, n));
                continue;
            }
            boolean vu = t.score >= Gabarits.seuil(n) && t.valide;
            String etat = vu ? "VU    " : t.score >= Gabarits.seuil(n) ? "écarté" : "absent";
            System.out.println(String.format(Locale.US, "  %-18s %s  score %.2f", n, etat, t.score)
                    + (etat.equals("écarté") ? "  (forme proche mais mauvaise couleur)" : ""));
            if (vu) trouves.add(t);
        }
        Trouve rouge = null;
        for (Trouve t : trouves) if (t.nom.equals("terminer_bataille")) rouge = t;
        if (rouge != null) {
            Chiffres.Butin butin = Chiffres.lireButin(ecran);
            System.out.println("Butin : " + (butin != null ? butin + "  → " + (butin.suffisant() ? "attaque" : "Suivant") : "illisible"));
            List<Carte> cartes = Cartes.detecter(ecran);
            Cartes.analyser(ecran, cartes);
            List<String> d = new ArrayList<>();
            int connues = 0;
            for (Carte c : cartes) {
                d.add(c.decrire());
                if (c.connue != null) connues++;
            }
            System.out.println("Barre : " + cartes.size() + " cartes : " + String.join(", ", d) + "  (" + connues + " reconnues par leur dessin)");
            Integer deg = Chiffres.lireDegats(ecran);
            System.out.println("Dégâts : " + deg + " · étoiles : " + Chiffres.etoiles(ecran)
                    + " · bouton rouge : " + java.util.Arrays.toString(Cartes.boutonRouge(ecran)));
        } else {
            System.out.println("Pas sur l'écran d'observation d'un village : butin et barre non analysés.");
        }
        Object[] v = Combat.lireVitesse(ecran);
        System.out.println("Bouton de vitesse : " + (v != null ? v[0] : "absent"));
        int[] vert = Cartes.boutonVert(ecran);
        System.out.println("Bouton vert : " + java.util.Arrays.toString(vert));
        System.out.println(String.format(Locale.US, "(%.0f ms)", (System.nanoTime() - t0) / 1e6));
    }
}

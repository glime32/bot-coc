package fr.glime.botcoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reconnaissance des boutons (templates/*.png), comme locateCenterOnScreen. La taille et la
 *  place de chaque bouton trouvé sont retenues dans templates/boutons.json. */
final class Gabarits {
    private Gabarits() {}

    private static final class Modele {
        final Img gris;
        final double[] couleur;
        final double contraste;
        final long taille;

        Modele(Img gris, double[] couleur, double contraste, long taille) {
            this.gris = gris;
            this.couleur = couleur;
            this.contraste = contraste;
            this.taille = taille;
        }
    }

    private static final Map<String, Modele> modeles = new HashMap<>();
    private static final Map<String, Boolean> absents = new HashMap<>();
    private static final Map<String, Double> echelleConnue = new HashMap<>();
    private static final Map<String, int[]> zoneConnue = new HashMap<>();
    private static final Map<String, Double> derniereRecherche = new HashMap<>();
    private static final Map<String, Double> derniereComplete = new HashMap<>();
    private static final Map<String, Double> derniereToutes = new HashMap<>();
    private static boolean memoCharge = false;
    private static List<String> popups;

    static synchronized void oublier() {
        modeles.clear();
        absents.clear();
        echelleConnue.clear();
        zoneConnue.clear();
        derniereRecherche.clear();
        derniereComplete.clear();
        derniereToutes.clear();
        memoCharge = false;
        popups = null;
    }

    private static File fichier(String nom) { return new File(Config.templates(), nom + ".png"); }

    private static File memo() { return new File(Config.templates(), "boutons.json"); }

    private static synchronized Modele charger(String nom) {
        if (modeles.containsKey(nom)) return modeles.get(nom);
        if (absents.containsKey(nom)) return null;
        File f = fichier(nom);
        Img img = Img.lire(f, true);
        if (img == null) {
            absents.put(nom, true);
            return null;
        }
        Img gris = img.gris().facteur(Config.REDUCTION, Imgproc.INTER_AREA);
        Modele m = new Modele(gris, img.moyenne(), img.ecartType(), f.length());
        modeles.put(nom, m);
        return m;
    }

    static boolean existe(String nom) { return charger(nom) != null; }

    /** On sait déjà où se trouve ce bouton (vu lors d'une session précédente). */
    static synchronized boolean connu(String nom) {
        chargerMemo();
        return zoneConnue.containsKey(nom);
    }

    /** Reprend la taille et la place des boutons des sessions précédentes (le fichier du PC
     *  marche aussi : seule la taille du template doit correspondre). */
    private static synchronized void chargerMemo() {
        if (memoCharge) return;
        memoCharge = true;
        try {
            JSONObject d = new JSONObject(new String(Files.readAllBytes(memo().toPath()), StandardCharsets.UTF_8));
            if (d.optDouble("reduction", -1) != Config.REDUCTION) return;
            JSONObject b = d.getJSONObject("boutons");
            for (java.util.Iterator<String> it = b.keys(); it.hasNext(); ) {
                String nom = it.next();
                JSONObject v = b.getJSONObject(nom);
                JSONArray sig = v.optJSONArray("fichier");
                File f = fichier(nom);
                if (sig == null || !f.isFile() || sig.getLong(0) != f.length()) continue;
                JSONArray z = v.getJSONArray("zone");
                echelleConnue.putIfAbsent(nom, v.getDouble("echelle"));
                zoneConnue.putIfAbsent(nom, new int[]{z.getInt(0), z.getInt(1), z.getInt(2), z.getInt(3)});
            }
        } catch (Exception ignored) {
        }
    }

    private static synchronized void retenir(String nom, double e, int[] zone) {
        int[] ancienne = zoneConnue.get(nom);
        echelleConnue.put(nom, e);
        zoneConnue.put(nom, zone.clone());
        if (ancienne != null) {
            int ecart = 0;
            for (int i = 0; i < 4; i++) ecart = Math.max(ecart, Math.abs(ancienne[i] - zone[i]));
            if (ecart <= 3) return;
        }
        try {
            JSONObject boutons = new JSONObject();
            for (Map.Entry<String, int[]> z : new TreeMap<>(zoneConnue).entrySet()) {
                String n = z.getKey();
                if (!echelleConnue.containsKey(n)) continue;
                JSONObject v = new JSONObject();
                v.put("echelle", echelleConnue.get(n));
                v.put("zone", new JSONArray(Arrays.stream(z.getValue()).boxed().toArray()));
                File f = fichier(n);
                v.put("fichier", new JSONArray(new long[]{f.length(), f.lastModified() / 1000}));
                boutons.put(n, v);
            }
            JSONObject d = new JSONObject();
            d.put("reduction", Config.REDUCTION);
            d.put("boutons", boutons);
            Files.write(memo().toPath(), d.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Contrôle après la recherche en gris : même couleur moyenne et même contraste. */
    private static boolean ressemble(Ecran ecran, String nom, int[] boite) {
        Modele m = charger(nom);
        int x = boite[0], y = boite[1], w = boite[2], h = boite[3];
        Img zone = ecran.zone(Math.max(0, x), Math.max(0, y), x + w, y + h);
        if (zone.vide()) return false;
        double[] moy = zone.moyenne();
        double ecart = 0;
        for (int k = 0; k < 3; k++) ecart = Math.max(ecart, Math.abs(moy[k] - m.couleur[k]));
        return ecart < 40 && zone.ecartType() > 0.5 * m.contraste;
    }

    static synchronized List<String> popups() {
        if (popups == null) {
            popups = new ArrayList<>();
            File[] fs = new File(Config.templates(), "popups").listFiles();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    if (f.getName().endsWith(".png")) popups.add("popups/" + f.getName().substring(0, f.getName().length() - 4));
                }
            }
        }
        return popups;
    }

    /** matchTemplate du template à l'échelle e : les `pics` meilleurs emplacements
     *  {score, x, y, largeur, hauteur} (en demi-résolution), du plus fort au plus faible. */
    private static List<double[]> comparer(Img gris, Img tpl, double e, int pics) {
        List<double[]> sortie = new ArrayList<>();
        Mat g = gris.mat(), t0 = tpl.mat(), t = t0, res = new Mat();
        try {
            if (e != 1.0) {
                t = new Mat();
                Imgproc.resize(t0, t, new Size(0, 0), e, e, e < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
            }
            int th = t.rows(), tw = t.cols();
            if (Math.min(th, tw) < 6 || th > g.rows() || tw > g.cols()) return sortie;
            Imgproc.matchTemplate(g, t, res, Imgproc.TM_CCOEFF_NORMED);
            for (int i = 0; i < pics; i++) {
                Core.MinMaxLocResult r = Core.minMaxLoc(res);
                int x = (int) r.maxLoc.x, y = (int) r.maxLoc.y;
                sortie.add(new double[]{r.maxVal, x, y, tw, th, e});
                Imgproc.rectangle(res, new Point(x - tw / 2, y - th / 2), new Point(x + tw / 2, y + th / 2),
                        new Scalar(-1.0), -1);
            }
            return sortie;
        } finally {
            g.release();
            t0.release();
            if (t != t0) t.release();
            res.release();
        }
    }

    /** Même résultat que comparer() sur tout l'écran, ~5 fois plus vite : premier passage au
     *  quart de la résolution, puis le score exact en demi-résolution autour des meilleurs
     *  endroits seulement. */
    private static List<double[]> comparerRapide(Ecran ecran, Img tpl, double e, int pics) {
        Img g = ecran.gris();
        Mat t0 = tpl.mat(), t = new Mat(), t4 = new Mat(), res = new Mat(), g4 = null;
        try {
            if (e != 1.0) Imgproc.resize(t0, t, new Size(0, 0), e, e, e < 1 ? Imgproc.INTER_AREA : Imgproc.INTER_LINEAR);
            else t0.copyTo(t);
            int th = t.rows(), tw = t.cols();
            if (Math.min(th, tw) < 6 || th > g.h || tw > g.w) return new ArrayList<>();
            if (Math.min(th, tw) < 20) return comparer(g, tpl, e, pics);
            Imgproc.resize(t, t4, new Size(0, 0), 0.5, 0.5, Imgproc.INTER_AREA);
            g4 = ecran.gris4().mat();
            if (t4.rows() > g4.rows() || t4.cols() > g4.cols()) return comparer(g, tpl, e, pics);
            Imgproc.matchTemplate(g4, t4, res, Imgproc.TM_CCOEFF_NORMED);
            int tw4 = t4.cols(), th4 = t4.rows(), m = 5;
            List<double[]> sortie = new ArrayList<>();
            for (int i = 0; i < pics + 1; i++) {
                Core.MinMaxLocResult r = Core.minMaxLoc(res);
                int x4 = (int) r.maxLoc.x, y4 = (int) r.maxLoc.y;
                Imgproc.rectangle(res, new Point(x4 - tw4 / 2, y4 - th4 / 2), new Point(x4 + tw4 / 2, y4 + th4 / 2),
                        new Scalar(-1.0), -1);
                int xa = Math.max(0, 2 * x4 - m), ya = Math.max(0, 2 * y4 - m);
                Img fen = g.crop(xa, ya, Math.min(g.w, 2 * x4 + tw + m), Math.min(g.h, 2 * y4 + th + m));
                if (fen.w < tw || fen.h < th) continue;
                Mat mf = fen.mat(), rf = new Mat();
                try {
                    Imgproc.matchTemplate(mf, t, rf, Imgproc.TM_CCOEFF_NORMED);
                    Core.MinMaxLocResult r2 = Core.minMaxLoc(rf);
                    sortie.add(new double[]{r2.maxVal, xa + r2.maxLoc.x, ya + r2.maxLoc.y, tw, th, e});
                } finally {
                    mf.release();
                    rf.release();
                }
            }
            sortie.sort((a, b) -> Double.compare(b[0], a[0]));
            List<double[]> distincts = new ArrayList<>();
            for (double[] c : sortie) {
                boolean double_ = false;
                for (double[] d : distincts) {
                    if (Math.abs(c[1] - d[1]) < tw / 2.0 && Math.abs(c[2] - d[2]) < th / 2.0) double_ = true;
                }
                if (!double_ && distincts.size() < pics) distincts.add(c);
            }
            return distincts;
        } finally {
            t0.release();
            t.release();
            t4.release();
            res.release();
            if (g4 != null) g4.release();
        }
    }

    private static Trouve trouve(String nom, double score, double x, double y, double tw, double th) {
        double k = 1 / Config.REDUCTION;
        return new Trouve(nom, (int) ((x + tw / 2) * k), (int) ((y + th / 2) * k),
                new int[]{(int) (x * k), (int) (y * k), (int) (tw * k), (int) (th * k)}, score);
    }

    static double seuil(String nom) { return nom.startsWith("popups/") ? Config.SEUIL_POPUP : Config.SEUIL; }

    /** Meilleure correspondance d'un template (null si pas de fichier), même sous le seuil.
     *  D'abord là où le bouton était la dernière fois, puis tout l'écran, à plusieurs tailles
     *  tant que la bonne n'est pas connue. */
    static Trouve chercher(Ecran ecran, String nom) {
        Modele m = charger(nom);
        if (m == null) return null;
        chargerMemo();
        Img tpl = m.gris, g = ecran.gris();
        double seuil = seuil(nom);
        Trouve proche = null;
        int[] connue;
        Double eConnue;
        synchronized (Gabarits.class) {
            connue = zoneConnue.get(nom);
            eConnue = echelleConnue.get(nom);
        }
        if (connue != null && eConnue != null) {
            int x = connue[0], y = connue[1], tw = connue[2], th = connue[3];
            int mx = tw / 2 + 4, my = th / 2 + 4;
            int xa = Math.max(0, x - mx), ya = Math.max(0, y - my);
            for (double[] r : comparer(g.crop(xa, ya, x + tw + mx, y + th + my), tpl, eConnue, 1)) {
                Trouve t = trouve(nom, r[0], r[1] + xa, r[2] + ya, r[3], r[4]);
                if (t.score >= seuil) {
                    t.valide = ressemble(ecran, nom, t.boite);
                    if (t.valide) {
                        retenir(nom, eConnue, new int[]{(int) r[1] + xa, (int) r[2] + ya, (int) r[3], (int) r[4]});
                        return t;
                    }
                }
                proche = t;
            }
        }
        double maintenant = Tel.maintenant();
        List<Double> echelles = new ArrayList<>();
        synchronized (Gabarits.class) {
            double derniere = derniereRecherche.getOrDefault(nom, -99.0);
            if (maintenant - derniere < 3.0) {
                if (proche != null) return proche;
                Trouve rien = new Trouve(nom, 0, 0, new int[]{0, 0, 0, 0}, 0.0);
                rien.valide = false;
                return rien;
            }
            derniereRecherche.put(nom, maintenant);
            if (echelleConnue.containsKey(nom)) {
                // un bouton connu apparaît à sa place (vérifiée à chaque fois, quelques ms) : sur tout
                // l'écran, la taille retenue et la normale toutes les 3 s, les tailles voisines toutes
                // les 15 s (une image prise pendant l'animation d'ouverture d'un menu est 5 % plus
                // grosse), toutes les tailles toutes les 60 s
                double e0 = echelleConnue.get(nom);
                echelles.add(e0);
                if (e0 != 1.0) echelles.add(1.0);
                if (maintenant - derniereToutes.getOrDefault(nom, -99.0) > 60.0) {
                    derniereToutes.put(nom, maintenant);
                    derniereComplete.put(nom, maintenant);
                    for (double e : Config.ECHELLES) if (!echelles.contains(e)) echelles.add(e);
                } else if (maintenant - derniereComplete.getOrDefault(nom, -99.0) > 15.0) {
                    derniereComplete.put(nom, maintenant);
                    for (double d : new double[]{-0.05, -0.025, 0.025, 0.05}) echelles.add(e0 + d);
                }
            } else {
                for (double e : Config.ECHELLES) echelles.add(e);
            }
        }
        Trouve meilleur = null;
        double meilleureE = 0;
        boolean affine = false;
        boolean avecConnue = eConnue != null || echelleConnue.containsKey(nom);
        boolean affinable = echelles.size() >= Config.ECHELLES.length;
        boolean premier = avecConnue;
        while (!echelles.isEmpty()) {
            List<Double> lot = new ArrayList<>(premier ? echelles.subList(0, 1) : echelles);
            echelles.removeAll(lot);
            premier = false;
            List<List<double[]>> resultats = enParallele(ecran, tpl, lot);
            for (int q = 0; q < lot.size(); q++) {
                double e = lot.get(q);
                for (double[] r : resultats.get(q)) {
                    Trouve t = trouve(nom, r[0], r[1], r[2], r[3], r[4]);
                    if (t.score >= seuil) {
                        t.valide = ressemble(ecran, nom, t.boite);
                        if (t.valide) {
                            retenir(nom, e, new int[]{(int) r[1], (int) r[2], (int) r[3], (int) r[4]});
                            return t;
                        }
                    }
                    if (meilleur == null || plusFort(t, meilleur)) {
                        meilleur = t;
                        meilleureE = e;
                    }
                }
            }
            if (echelles.isEmpty() && affinable && !affine && meilleur != null && meilleur.score >= 0.6) {
                affine = true;
                echelles.add(meilleureE - 0.025);
                echelles.add(meilleureE + 0.025);
                if (avecConnue) {
                    echelles.add(meilleureE - 0.05);
                    echelles.add(meilleureE + 0.05);
                }
            }
        }
        return meilleur;
    }

    /** OpenCV ne compare que sur un cœur à la fois : les tailles sont essayées sur 4 cœurs. */
    private static final java.util.concurrent.ExecutorService FILS = java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "recherche");
        t.setDaemon(true);
        return t;
    });

    private static List<List<double[]>> enParallele(Ecran ecran, Img tpl, List<Double> echelles) {
        ecran.gris4();
        List<java.util.concurrent.Future<List<double[]>>> fs = new ArrayList<>();
        for (double e : echelles) fs.add(FILS.submit(() -> comparerRapide(ecran, tpl, e, 3)));
        List<List<double[]>> r = new ArrayList<>();
        try {
            for (java.util.concurrent.Future<List<double[]>> f : fs) r.add(f.get());
        } catch (InterruptedException e) {
            for (java.util.concurrent.Future<List<double[]>> f : fs) f.cancel(false);
            Thread.currentThread().interrupt();
            throw new Arret();
        } catch (java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
        return r;
    }

    private static boolean plusFort(Trouve a, Trouve b) {
        if (a.valide != b.valide) return a.valide;
        return a.score > b.score;
    }

    /** Le bouton s'il est à l'écran, sinon null. */
    static Trouve localiser(Ecran ecran, String nom) {
        Trouve t = chercher(ecran, nom);
        return t != null && t.valide && t.score >= seuil(nom) ? t : null;
    }

    /** Capture en boucle jusqu'à voir l'un des `cibles`. En attendant, touche les popups puis les
     *  boutons `enRoute` (dans cet ordre), une fois l'écran posé (bouton vu deux fois de suite au
     *  même endroit). `patients` : boutons touchés seulement s'ils restent affichés tant de
     *  secondes. Renvoie {écran, bouton trouvé}. */
    static Object[] attendre(Tel tel, String[] cibles, double delai, String[] enRoute, double pas,
                             Map<String, Double> patients) {
        double fin = Tel.maintenant() + delai;
        Map<String, Double> derniereTouche = new HashMap<>();
        Ecran ecran = null;
        Map<String, int[]> vus = new HashMap<>();
        Map<String, Double> depuis = new HashMap<>();
        while (Tel.maintenant() < fin) {
            ecran = tel.capture();
            for (String nom : cibles) {
                Trouve t = localiser(ecran, nom);
                if (t != null) return new Object[]{ecran, t};
            }
            Map<String, int[]> nouveaux = new HashMap<>();
            List<String> ordre = new ArrayList<>(popups());
            if (enRoute != null) ordre.addAll(Arrays.asList(enRoute));
            for (String nom : ordre) {
                Trouve t = localiser(ecran, nom);
                if (t == null) continue;
                nouveaux.put(nom, new int[]{t.x, t.y});
                if (pose(vus, nom, t, 12) && Tel.maintenant() - derniereTouche.getOrDefault(nom, 0.0) > 2.5) {
                    Journal.log("  → " + nom);
                    tel.toucher(t.point());
                    derniereTouche.put(nom, Tel.maintenant());
                }
                break;
            }
            vus = nouveaux;
            if (patients != null) {
                for (Map.Entry<String, Double> p : patients.entrySet()) {
                    String nom = p.getKey();
                    Trouve t = localiser(ecran, nom);
                    if (t == null) {
                        depuis.remove(nom);
                    } else {
                        depuis.putIfAbsent(nom, Tel.maintenant());
                        if (Tel.maintenant() - depuis.get(nom) >= p.getValue()) {
                            Journal.log("  → " + nom + " (affiché depuis " + Math.round(p.getValue()) + " s)");
                            tel.toucher(t.point());
                            depuis.remove(nom);
                        }
                    }
                }
            }
            tel.dormir(pas);
        }
        throw new Bloque("« " + String.join(" / ", cibles) + " » n'est pas apparu en " + Math.round(delai) + " s", ecran);
    }

    static Map<String, Double> patients(String nom, double secondes) {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put(nom, secondes);
        return m;
    }

    /** Le bouton était-il déjà au même endroit au tour précédent (écran immobile) ? */
    static boolean pose(Map<String, int[]> vus, String nom, Trouve t, int tolerance) {
        int[] p = vus.get(nom);
        return p != null && Math.abs(p[0] - t.x) <= tolerance && Math.abs(p[1] - t.y) <= tolerance;
    }
}

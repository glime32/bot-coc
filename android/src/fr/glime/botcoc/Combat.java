package fr.glime.botcoc;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Le village principal : caméra, pose des troupes et des sorts, fin du combat, le cycle. */
final class Combat {
    private Combat() {}

    /** Le bot ne peut pas continuer (armée vide…) : arrêt avec ce message. */
    static final class Fin extends RuntimeException {
        Fin(String message) { super(message); }
    }

    static void log(String m) { Journal.log(m); }

    // ── caméra ──

    /** Petite image grise et floue du milieu de l'écran : pour voir si la caméra a bougé. */
    static final class Vignette {
        final float[] p;
        final int w, h;

        Vignette(float[] p, int w, int h) {
            this.p = p;
            this.w = w;
            this.h = h;
        }

        double ecart(Vignette o) {
            if (o == null || o.w != w || o.h != h || p.length == 0) return Double.NaN;
            double s = 0;
            for (int i = 0; i < p.length; i++) s += Math.abs(p[i] - o.p[i]);
            return s / p.length;
        }
    }

    static Vignette vignette(Tel tel) {
        int[] t = tel.taille();
        int w = t[0], h = t[1];
        int ya = (int) (0.15 * h), yb = (int) (0.75 * h);
        Ecran e = tel.capture();
        Img g = e.gris();
        Img bande = g.crop(0, (int) (ya * Config.REDUCTION), g.w, (int) (yb * Config.REDUCTION));
        Img petite = bande.redim(Math.max(1, w / 8), Math.max(1, (yb - ya) / 8), Imgproc.INTER_AREA);
        Mat a = petite.mat(), f = new Mat(), b = new Mat();
        try {
            a.convertTo(f, CvType.CV_32F);
            Imgproc.GaussianBlur(f, b, new Size(0, 0), 1.5);
            float[] r = new float[petite.w * petite.h];
            b.get(0, 0, r);
            return new Vignette(r, petite.w, petite.h);
        } finally {
            a.release();
            f.release();
            b.release();
        }
    }

    /** Attend que l'image se fige (fin du glissement de la caméra ou du dézoom). */
    static void attendreArret(Tel tel, double maxi) { attendreArret(tel, maxi, 0.06, 1.2); }

    static void attendreArret(Tel tel, double maxi, double mini, double seuil) {
        if (!tel.video()) {
            tel.dormir(maxi);
            return;
        }
        tel.dormir(mini);
        double fin = Tel.maintenant() + maxi;
        Vignette prec = vignette(tel);
        int calmes = 0;
        while (Tel.maintenant() < fin) {
            Vignette v = vignette(tel);
            double e = prec.ecart(v);
            calmes = !Double.isNaN(e) && e < seuil ? calmes + 1 : 0;
            if (calmes >= 2) return;
            prec = v;
        }
    }

    static boolean aBouge(Vignette a, Vignette b) {
        double e = a.ecart(b);
        return !Double.isNaN(e) && e > 7.0;
    }

    private static boolean pincements(Tel tel, int n) {
        boolean ok = true;
        for (int k = 0; k < n; k++) {
            ok = tel.pincer(true) && ok;
            if (k < n - 1) tel.dormir(0.05);
        }
        attendreArret(tel, 0.45);
        return ok;
    }

    /** Dézoom au maximum. La première fois, on vérifie que l'image bouge vraiment. */
    static boolean dezoomer(Tel tel) {
        if (tel.dezoomVerifie) return pincements(tel, Config.PINCEMENTS);
        Vignette avant = vignette(tel);
        if (!tel.pincer(true)) return false;
        attendreArret(tel, 0.5);
        Vignette apres = vignette(tel);
        if (aBouge(avant, apres)) {
            tel.dezoomVerifie = true;
            pincements(tel, Config.PINCEMENTS - 1);
            return true;
        }
        tel.pincer(false);
        attendreArret(tel, 0.5);
        if (!aBouge(apres, vignette(tel))) {
            log("  le pincement ne fait rien bouger à l'écran");
            return false;
        }
        tel.dezoomVerifie = true;
        pincements(tel, Config.PINCEMENTS);
        return true;
    }

    /** Caméra en butée : « haut » = tout en haut à gauche, « bas » = tout en bas à droite. */
    static void allerVue(Tel tel, String vue, int w, int h) {
        double[] a, b;
        if (vue.equals("haut")) {
            a = new double[]{0.22 * w, 0.34 * h};
            b = new double[]{0.70 * w, 0.76 * h};
        } else {
            a = new double[]{0.80 * w, 0.66 * h};
            b = new double[]{0.30 * w, 0.12 * h};
        }
        for (int k = 0; k < 3; k++) tel.glisser(a[0], a[1], b[0], b[1], 200, 0);
        attendreArret(tel, 0.5);
    }

    /** Décalage réel entre la vue haut et la vue bas (en pixels de l'étalonnage), ou null. */
    static double[] mesurerDecalage(Ecran haut, Ecran bas, Plan plan, List<double[]> zones) {
        int w = haut.w, h = haut.h;
        List<double[]> zs = new ArrayList<>();
        if (zones == null) {
            for (double[] u : Config.UI_FIXE) zs.add(new double[]{u[0] * w, u[1] * h, u[2] * w, u[3] * h});
            zs.add(new double[]{0, Config.BARRE[0] * h, w, h});
        } else {
            for (double[] z : zones) {
                if (!(z[1] <= 0 && z[3] <= 0.06 * h && z[2] - z[0] >= 0.9 * w)) zs.add(z);
            }
        }
        double k = 1 / Config.REDUCTION;
        Img gh = haut.gris(), gb = bas.gris();
        double ax = plan.decalage[0] * plan.kx, ay = plan.decalage[1] * plan.ky;
        int tw = 110, th = 70, mx = 280, my = 180;
        List<double[]> mesures = new ArrayList<>();
        for (int y0 = (int) (0.52 * h); y0 < (int) (0.79 * h) - th; y0 += 40) {
            for (int x0 = (int) (0.12 * w); x0 < (int) (0.88 * w) - tw; x0 += 90) {
                double xb = x0 + ax, yb = y0 + ay;
                if (yb + th < 0) continue;
                if (touche(zs, x0, y0, tw, th) || touche(zs, xb - 20, yb - 20, tw + 40, th + 40)) continue;
                Img tpl = gh.crop((int) (x0 / k), (int) (y0 / k), (int) ((x0 + tw) / k), (int) ((y0 + th) / k));
                if (tpl.vide() || tpl.ecartType() < 12) continue;
                int xa = Math.max(0, (int) ((xb - mx) / k)), ya = Math.max(0, (int) ((yb - my) / k));
                Img zone = gb.crop(xa, ya, Math.max(xa, (int) ((xb + tw + mx) / k)), Math.max(ya, (int) ((yb + th + my) / k)));
                if (zone.h <= tpl.h || zone.w <= tpl.w) continue;
                Mat mz = zone.mat(), mt = tpl.mat(), res = new Mat();
                try {
                    Imgproc.matchTemplate(mz, mt, res, Imgproc.TM_CCOEFF_NORMED);
                    Core.MinMaxLocResult r = Core.minMaxLoc(res);
                    if (r.maxVal >= 0.6) mesures.add(new double[]{(xa + r.maxLoc.x) * k - x0, (ya + r.maxLoc.y) * k - y0});
                } finally {
                    mz.release();
                    mt.release();
                    res.release();
                }
            }
        }
        if (mesures.size() < 2) return null;
        double[] xs = new double[mesures.size()], ys = new double[mesures.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = mesures.get(i)[0];
            ys[i] = mesures.get(i)[1];
        }
        double mxm = Img.mediane(xs), mym = Img.mediane(ys);
        List<Double> px = new ArrayList<>(), py = new ArrayList<>();
        for (double[] m : mesures) {
            if (Math.hypot(m[0] - mxm, m[1] - mym) <= 10) {
                px.add(m[0]);
                py.add(m[1]);
            }
        }
        if (px.size() < Math.max(2, 0.4 * mesures.size())) return null;
        return new double[]{Img.mediane(px) / plan.kx, Img.mediane(py) / plan.ky};
    }

    private static boolean touche(List<double[]> zones, double x, double y, double ww, double hh) {
        for (double[] z : zones) if (!(x + ww < z[0] || x > z[2] || y + hh < z[1] || y > z[3])) return true;
        return false;
    }

    /** k points régulièrement espacés le long du chemin, décalés d'une fraction du chemin. */
    static List<int[]> repartir(List<int[]> chemin, int k, double decalage) {
        int n = chemin.size();
        List<int[]> r = new ArrayList<>();
        if (n == 0 || k <= 0) return r;
        for (int q = 0; q < k; q++) r.add(chemin.get(Math.floorMod((int) ((decalage + (q + 0.5) / k) * n), n)));
        return r;
    }

    /** (dernier écran, jusqu'à n points) où bougent le plus de troupes : trois images à 0,12 s
     *  d'écart comparées en quart de résolution, sur la carte seulement. */
    static Object[] foyersTroupes(Tel tel, Plan plan, String vue, List<double[]> zones, int n, List<double[]> evites) {
        double k = 2 / Config.REDUCTION;
        Img[] images = new Img[3];
        Ecran ecran = null;
        for (int i = 0; i < 3; i++) {
            if (i > 0) tel.dormir(0.12);
            ecran = tel.capture();
            images[i] = ecran.gris().facteur(0.5, Imgproc.INTER_AREA);
        }
        int w = images[0].w, h = images[0].h;
        float[] bouge = new float[w * h];
        for (int i = 0; i < w * h; i++) {
            int a = images[0].p[i] & 0xff, b = images[1].p[i] & 0xff, c = images[2].p[i] & 0xff;
            if (Math.abs(b - a) > 12 || Math.abs(c - b) > 12) bouge[i] = 1f;
        }
        Mat masque = Mat.zeros(h, w, CvType.CV_32F), mb = new Mat(h, w, CvType.CV_32F), prod = new Mat(), densite = new Mat();
        List<int[]> foyers = new ArrayList<>();
        try {
            double[][] coins = {
                    plan.ecran(vue, plan.bord("i0"), plan.bord("j0")), plan.ecran(vue, plan.bord("i0"), plan.bord("j1")),
                    plan.ecran(vue, plan.bord("i1"), plan.bord("j1")), plan.ecran(vue, plan.bord("i1"), plan.bord("j0"))};
            Point[] pts = new Point[4];
            for (int q = 0; q < 4; q++) pts[q] = new Point((int) (coins[q][0] / k), (int) (coins[q][1] / k));
            MatOfPoint poly = new MatOfPoint(pts);
            Imgproc.fillPoly(masque, Collections.singletonList(poly), new Scalar(1.0));
            poly.release();
            for (double[] z : zones) {
                int y0 = Math.max(0, (int) (z[1] / k)), y1 = Math.min(h, Math.max(0, (int) (z[3] / k) + 1));
                int x0 = Math.max(0, (int) (z[0] / k)), x1 = Math.min(w, Math.max(0, (int) (z[2] / k) + 1));
                if (x1 > x0 && y1 > y0) {
                    Mat sous = masque.submat(new Rect(x0, y0, x1 - x0, y1 - y0));
                    sous.setTo(new Scalar(0));
                    sous.release();
                }
            }
            double a = plan.cas[0], bb = plan.cas[1];
            int rx = Math.max(2, (int) (Config.SORT_RAYON * a * 1.41 * plan.kx / k));
            int ry = Math.max(2, (int) (Config.SORT_RAYON * bb * 1.41 * plan.ky / k));
            mb.put(0, 0, bouge);
            Core.multiply(mb, masque, prod);
            Imgproc.boxFilter(prod, densite, -1, new Size(2 * rx + 1, 2 * ry + 1));
            Core.multiply(densite, masque, densite);
            for (double[] p : evites) {
                Imgproc.ellipse(densite, new Point((int) (p[0] / k), (int) (p[1] / k)), new Size(rx, ry), 0, 0, 360, new Scalar(0), -1);
            }
            while (foyers.size() < n) {
                Core.MinMaxLocResult r = Core.minMaxLoc(densite);
                if (r.maxVal < Config.SORT_DENSITE_MIN) break;
                int x = (int) r.maxLoc.x, y = (int) r.maxLoc.y;
                foyers.add(new int[]{(int) (x * k), (int) (y * k)});
                Imgproc.ellipse(densite, new Point(x, y), new Size((int) (1.5 * rx), (int) (1.5 * ry)), 0, 0, 360, new Scalar(0), -1);
            }
        } finally {
            masque.release();
            mb.release();
            prod.release();
            densite.release();
        }
        return new Object[]{ecran, foyers};
    }

    static int[] bandeRouge(int[] boite) { return new int[]{boite[1] - 4, boite[1] + boite[3] + 4}; }

    /** Envoie les touches par paquets de RAFALE ; entre deux paquets, vérifie que le combat continue. */
    static void rafale(Tel tel, List<int[]> touches, int[] boite) {
        for (int k = 0; k < touches.size(); k += Config.RAFALE) {
            if (k > 0 && !Cartes.enCombat(tel.capture(), boite)) throw new FinDuCombat();
            List<int[]> paquet = touches.subList(k, Math.min(touches.size(), k + Config.RAFALE));
            tel.toucher(paquet.toArray(new int[0][]));
        }
    }

    /** Combien d'unités de cette carte ne sont pas encore sorties (0 = tout est posé). */
    static int reste(Ecran ecran, Carte c) {
        if ((c.nombre != null && c.nombre == 0) || c.points.isEmpty()) return 0;
        if (c.nombre == null) return Cartes.deploye(ecran, c) ? 0 : 1;
        if (Cartes.epuisee(ecran, c)) return 0;
        Integer n = Chiffres.lireCompteur(ecran, c);
        return n != null ? n : 0;
    }

    // ── déploiement ──

    static final class Deploiement {
        final Tel tel;
        final List<Carte> cartes;
        final List<double[]> zones;
        final int[] boite;
        final Plan plan;
        final boolean debug;
        final int numero;
        final List<Carte> sorts = new ArrayList<>(), troupes = new ArrayList<>(), uniques = new ArrayList<>();
        final List<int[]> mauvais = new ArrayList<>();
        final Map<String, List<int[]>> poses = new HashMap<>();
        double finPose = Tel.maintenant();
        Ecran derniereBarre;
        static int reposesSauvees = 0;

        Deploiement(Tel tel, List<Carte> cartes, List<double[]> zones, int[] boite, Plan plan, boolean debug, int numero) {
            this.tel = tel;
            this.cartes = cartes;
            this.zones = zones;
            this.boite = boite;
            this.plan = plan;
            this.debug = debug;
            this.numero = numero;
            for (Carte c : cartes) {
                if (c.sort != null) sorts.add(c);
                else if (Cartes.aNombre(c)) troupes.add(c);
                else if (c.nombre == null) uniques.add(c);
                c.points.clear();
            }
        }

        Ecran captureBarre() {
            tel.dormir(tel.video() ? 0.12 : 0.25);
            Ecran e = tel.capture();
            if (!Cartes.enCombat(e, boite)) throw new FinDuCombat();
            return e;
        }

        Integer compter(Carte c) {
            for (int i = 0; i < 2; i++) {
                Ecran e = captureBarre();
                if (Cartes.epuisee(e, c)) return 0;
                Integer n = Chiffres.lireCompteur(e, c);
                if (n != null) return n;
            }
            return null;
        }

        Map<Carte, Integer> manque(Ecran ecran) {
            Map<Carte, Integer> m = new LinkedHashMap<>();
            for (Carte c : cartes) {
                int k = reste(ecran, c);
                if (k != 0) m.put(c, k);
            }
            return m;
        }

        String decrireManque(Map<Carte, Integer> manque) {
            List<String> parts = new ArrayList<>();
            for (Carte c : cartes) {
                if (manque.containsKey(c)) parts.add("carte " + (cartes.indexOf(c) + 1) + " (" + c.decrire() + ") : " + manque.get(c));
            }
            return String.join(", ", parts);
        }

        /** Trouve les bords de cette vue s'ils ne sont pas encore connus : une troupe à la fois. */
        void sonder(String vue) {
            List<String> cles = new ArrayList<>();
            for (String k : vue.equals("haut") ? new String[]{"i0", "j0"} : new String[]{"i1", "j1"}) {
                if (!plan.trouve(k)) cles.add(k);
            }
            Carte sonde = null;
            for (Carte c : troupes) if (sonde == null || c.nombre > sonde.nombre) sonde = c;
            if (cles.isEmpty() || sonde == null) return;
            List<String> noms = new ArrayList<>();
            for (String k : cles) noms.add(Plan.nomBord(k));
            log("  je cherche le bord de la carte (" + String.join(", ", noms) + ") : une troupe à la fois");
            Integer n = compter(sonde);
            if (n == null) {
                log("  compteur illisible : je garde les bords estimés");
                return;
            }
            tel.toucher(sonde.point());
            for (String cle : cles) {
                boolean trouve = false;
                for (int position : new int[]{20, 8, 32}) {
                    for (double[] cas : plan.sondes(cle, position)) {
                        double[] p = plan.ecran(vue, cas[0], cas[1]);
                        if (!plan.libre(zones, p[0], p[1])) continue;
                        tel.toucher(new int[]{(int) p[0], (int) p[1]});
                        Integer apres = compter(sonde);
                        if (apres != null && apres < n) {
                            plan.fixerBord(cle, cas);
                            sonde.points.add(new int[]{(int) p[0], (int) p[1]});
                            log("  bord " + Plan.nomBord(cle) + " trouvé");
                            n = apres;
                            trouve = true;
                            break;
                        }
                        if (apres != null) n = apres;
                    }
                    if (trouve) break;
                }
                if (!trouve) log("  bord " + Plan.nomBord(cle) + " pas trouvé : je le déduis du bord opposé (la carte fait 44 cases)");
            }
            if (vue.equals("bas") && plan.completerBords()) {
                plan.sauver();
                log("  bords de la carte enregistrés (templates/carte.json)");
            }
        }

        List<int[]> touches(Carte c, List<int[]> points) {
            c.points.addAll(points);
            List<int[]> r = new ArrayList<>();
            if (!points.isEmpty()) {
                r.add(c.point());
                r.addAll(points);
            }
            return r;
        }

        /** Pose dans la vue courante : troupes, puis héros/engin (les sorts viennent après). */
        List<int[]> poserVue(String vue, Ecran ecran) {
            List<int[]> chemin = plan.chemin(vue, zones, 0.5);
            if (chemin.isEmpty()) throw new Bloque("aucun point de pose dans la vue " + vue, ecran);
            List<int[]> touches = new ArrayList<>();
            for (int q = 0; q < troupes.size(); q++) {
                Carte c = troupes.get(q);
                int k;
                if (vue.equals("haut")) {
                    k = Math.floorDiv(c.nombre - c.points.size() + 1, 2);
                } else {
                    Integer n = Cartes.epuisee(ecran, c) ? Integer.valueOf(0) : Chiffres.lireCompteur(ecran, c);
                    k = n != null ? n : Math.max(0, c.nombre - c.points.size());
                }
                touches.addAll(touches(c, repartir(chemin, k, 0.5 * q / Math.max(1, troupes.size()))));
            }
            int moitie = (uniques.size() + 1) / 2;
            List<Carte> lesquels = vue.equals("haut") ? uniques.subList(0, moitie) : uniques.subList(moitie, uniques.size());
            for (int q = 0; q < lesquels.size(); q++) {
                touches.addAll(touches(lesquels.get(q), repartir(chemin, 1, (q + 0.5) / Math.max(1, lesquels.size()) - 0.5)));
            }
            log("  vue " + vue + " : " + touches.size() + " touches");
            rafale(tel, touches, boite);
            List<int[]> posees = new ArrayList<>();
            for (int[] p : touches) {
                boolean carte = false;
                for (Carte c : cartes) if (Arrays.equals(c.point(), p)) carte = true;
                if (!carte) posees.add(p);
            }
            poses.put(vue, posees);
            finPose = Tel.maintenant();
            return chemin;
        }

        /** Contrôles dans la vue bas : on repose ce qui n'est pas sorti, jusqu'à 3 fois. */
        void completer(List<int[]> chemin) {
            for (int passe = 0; passe < 4; passe++) {
                derniereBarre = captureBarre();
                Map<Carte, Integer> manque = manque(derniereBarre);
                if (manque.isEmpty()) {
                    log("  vérifié : tout est posé");
                    return;
                }
                if (passe == 3) {
                    log("  impossible de tout poser : il reste " + decrireManque(manque));
                    return;
                }
                log("  il reste " + decrireManque(manque) + " : je repose");
                if (passe == 0 && reposesSauvees < 3) {
                    for (Carte c : manque.keySet()) {
                        if (c.nombre == null) {
                            reposesSauvees++;
                            sauver(derniereBarre.img(), "reste_carte" + (cartes.indexOf(c) + 1));
                            break;
                        }
                    }
                }
                List<int[]> cheminP = passe == 1 ? plan.chemin("bas", zones, 1.5) : chemin;
                List<int[]> touches = new ArrayList<>();
                List<Carte> ordre = new ArrayList<>(cartes);
                ordre.sort((a, b) -> Boolean.compare(a.nombre != null, b.nombre != null));
                for (int q = 0; q < ordre.size(); q++) {
                    Carte c = ordre.get(q);
                    Integer k = manque.get(c);
                    if (k == null || k == 0) continue;
                    List<int[]> pts;
                    if (c.nombre == null) {
                        if (!c.points.isEmpty()) mauvais.add(c.points.get(c.points.size() - 1));
                        List<int[]> libres = new ArrayList<>();
                        for (int[] p : cheminP) {
                            boolean refuse = false;
                            for (int[] m : mauvais) if (Arrays.equals(m, p)) refuse = true;
                            if (!refuse) libres.add(p);
                        }
                        pts = repartir(libres.isEmpty() ? cheminP : libres, 1, 0.13 * passe + 0.29 * q);
                    } else {
                        pts = repartir(cheminP, k + Config.EXTRA, 0.17 * passe + 0.07 * q);
                    }
                    touches.addAll(touches(c, pts));
                }
                rafale(tel, touches, boite);
            }
        }

        Ecran mesurerVueBas(Ecran haut, Ecran bas) {
            double[] mesure = mesurerDecalage(haut, bas, plan, zones);
            if (mesure == null) {
                tel.dormir(0.2);
                bas = tel.capture();
                mesure = mesurerDecalage(haut, bas, plan, zones);
            }
            if (mesure == null) {
                log(String.format(Locale.FRANCE, "  vue bas non mesurée : je garde le décalage %+.0f, %+.0f px", plan.decalage[0], plan.decalage[1]));
            } else {
                plan.noterDecalage(mesure);
            }
            return bas;
        }

        void lancer() {
            int w = plan.w, h = plan.h;
            if (!dezoomer(tel)) log("  je ne peux pas dézoomer moi-même : dézoome à fond à la main une fois (le jeu garde le zoom)");
            allerVue(tel, "haut", w, h);
            Ecran ecranHaut = tel.capture();
            sonder("haut");
            List<String> armee = new ArrayList<>();
            for (Carte c : cartes) armee.add(c.decrire());
            log("  armée : " + String.join(", ", armee));
            List<int[]> chemin = poserVue("haut", ecranHaut);
            if (debug) sauver(annoter(ecranHaut, cartes, zones, chemin, null), "combat_" + numero + "_haut");

            allerVue(tel, "bas", w, h);
            Ecran ecranBas = tel.capture();
            if (!Cartes.enCombat(ecranBas, boite)) throw new FinDuCombat();
            ecranBas = mesurerVueBas(ecranHaut, ecranBas);
            sonder("bas");
            chemin = poserVue("bas", ecranBas);
            if (debug) sauver(annoter(ecranBas, cartes, zones, chemin, null), "combat_" + numero + "_bas");

            completer(chemin);
            List<int[]> heros = new ArrayList<>();
            for (Carte c : uniques) if (c.heros) heros.add(c.point());
            if (!heros.isEmpty()) {
                tel.toucher(heros.toArray(new int[0][]));
                log("  → compétences des " + heros.size() + " héros");
            }
            lancerSorts();
        }

        /** Moins de groupes visibles que de sorts : m autres points entre les poses et le centre. */
        List<int[]> pointsSecours(String vue, int m) {
            double[] c = plan.centre();
            double[] centre = plan.ecran(vue, c[0], c[1]);
            double cx = centre[0], cy = centre[1];
            List<int[]> base = poses.get(vue);
            if (base == null || base.isEmpty()) base = Collections.singletonList(new int[]{(int) cx, (int) cy});
            List<int[]> points = new ArrayList<>();
            for (int[] p : repartir(base, m, 0.0)) {
                for (double t : new double[]{0.4, 0.7, 0.0}) {
                    double x = p[0] + (cx - p[0]) * t, y = p[1] + (cy - p[1]) * t;
                    if (plan.libre(zones, x, y)) {
                        points.add(new int[]{(int) x, (int) y});
                        break;
                    }
                }
            }
            return points;
        }

        /** Sorts une fois toutes les troupes posées, sur les groupes les plus nombreux : la moitié
         *  dans la vue bas, le reste dans la vue haut. */
        @SuppressWarnings("unchecked")
        void lancerSorts() {
            double attente = finPose + Config.SORTS_APRES - Tel.maintenant();
            if (!sorts.isEmpty() && attente > 0) tel.dormir(attente);
            boolean illisible = false;
            for (Carte c : sorts) if (c.nombre == null) illisible = true;
            if (illisible) {
                Ecran e = captureBarre();
                for (Carte c : sorts) {
                    if (c.nombre == null) {
                        Integer n = Chiffres.lireCompteur(e, c);
                        c.nombre = n != null && n != 0 ? n : 1;
                    }
                }
            }
            List<Carte> actifs = new ArrayList<>();
            for (Carte c : sorts) if (Cartes.aNombre(c)) actifs.add(c);
            if (actifs.isEmpty()) return;
            List<int[]> lances = new ArrayList<>();
            for (String vue : new String[]{"bas", "haut"}) {
                Map<Carte, Integer> parts = new HashMap<>();
                int n = 0;
                for (Carte c : actifs) {
                    int p = vue.equals("bas") ? c.nombre / 2 : c.nombre - c.nombre / 2;
                    parts.put(c, p);
                    n = Math.max(n, p);
                }
                if (n == 0) continue;
                if (vue.equals("haut")) allerVue(tel, "haut", plan.w, plan.h);
                double dx = plan.decalage[0] * plan.kx, dy = plan.decalage[1] * plan.ky;
                List<double[]> evites = new ArrayList<>();
                if (vue.equals("haut")) for (int[] p : lances) evites.add(new double[]{p[0] - dx, p[1] - dy});
                Object[] r = foyersTroupes(tel, plan, vue, zones, n, evites);
                Ecran ecran = (Ecran) r[0];
                List<int[]> foyers = new ArrayList<>((List<int[]>) r[1]);
                if (!Cartes.enCombat(ecran, boite)) throw new FinDuCombat();
                int vus = foyers.size();
                if (vus < n) foyers.addAll(pointsSecours(vue, n - vus));
                if (foyers.isEmpty()) continue;
                List<int[]> touches = new ArrayList<>();
                Map<String, List<int[]>> places = new LinkedHashMap<>();
                for (Carte c : actifs) {
                    List<int[]> pts = new ArrayList<>();
                    for (int q = 0; q < parts.get(c); q++) pts.add(foyers.get(q % foyers.size()));
                    touches.addAll(touches(c, pts));
                    places.computeIfAbsent(c.sort, x -> new ArrayList<>()).addAll(pts);
                }
                rafale(tel, touches, boite);
                log(vus > 0 ? "  vue " + vue + " : sorts sur " + vus + " groupe(s) de troupes"
                        : "  vue " + vue + " : pas de troupes visibles, sorts entre les poses et le centre");
                if (vue.equals("bas")) {
                    lances.clear();
                    for (List<int[]> l : places.values()) lances.addAll(l);
                }
                if (debug) sauver(annoter(ecran, cartes, zones, new ArrayList<>(), places), "combat_" + numero + "_sorts_" + vue);
            }
        }
    }

    /** Pose toute l'armée. False si le combat s'est terminé avant la fin. */
    static boolean deployer(Tel tel, List<Carte> cartes, List<double[]> zones, int[] boite, Plan plan, boolean debug, int numero) {
        try {
            new Deploiement(tel, cartes, zones, boite, plan, debug, numero).lancer();
        } catch (FinDuCombat e) {
            return false;
        }
        return true;
    }

    // ── fin anticipée ──

    /** Appuie sur « Capituler » / « Terminer la bataille » puis confirme. */
    static boolean abandonner(Tel tel, int[] boite, String raison) { return abandonner(tel, boite, raison, false); }

    /** `reessai` : la fenêtre de confirmation est peut-être déjà ouverte (pas reconnue la première
     *  fois) : son bouton vert, s'il est là et immobile, est touché sans retoucher le rouge. */
    static boolean abandonner(Tel tel, int[] boite, String raison, boolean reessai) {
        Ecran ecran = tel.capture();
        if (reessai) {
            int[] a = Cartes.boutonVert(ecran);
            if (a != null) {
                tel.dormir(0.15);
                ecran = tel.capture();
                int[] b = Cartes.boutonVert(ecran);
                if (b != null && Math.abs(a[0] - b[0]) <= 12 && Math.abs(a[1] - b[1]) <= 12) {
                    tel.toucher(b);
                    log("  → confirmer (" + raison + ")");
                    return true;
                }
            }
        }
        int[] b = Cartes.boutonRouge(ecran);
        if (b == null) b = boite;
        if (b == null) {
            log("  " + raison + ", mais je ne vois pas le bouton Capituler / Terminer la bataille");
            return false;
        }
        int[] deja = Cartes.boutonVert(ecran);
        log("  → " + raison + " : capituler / terminer la bataille");
        tel.toucher(new int[]{b[0] + b[2] / 2, b[1] + b[3] / 2});
        boolean rapide = tel.video();
        double fin = Tel.maintenant() + (rapide ? 2.5 : 4.0);
        tel.dormir(rapide ? 0.15 : 0.9);
        int[] avant = null;
        while (Tel.maintenant() < fin) {
            ecran = tel.capture();
            Trouve t = Gabarits.localiser(ecran, "confirmer");
            int[] p = t != null ? t.point() : Cartes.boutonVert(ecran);
            if (p != null && deja != null && Math.abs(p[0] - deja[0]) <= 12 && Math.abs(p[1] - deja[1]) <= 12) p = null;
            if (p != null && avant != null && Math.abs(p[0] - avant[0]) <= 12 && Math.abs(p[1] - avant[1]) <= 12) {
                tel.toucher(p);
                log("  → confirmer");
                return true;
            }
            avant = p;
            tel.dormir(rapide ? 0.1 : 0.4);
        }
        String chemin = ecran != null ? sauver(ecran.img(), "sans_confirmation") : null;
        log("  pas de fenêtre de confirmation (fin directe ?)" + (chemin != null ? " (capture : " + chemin + ")" : ""));
        return true;
    }

    // ── vitesse du combat ──

    /** {"1x" ou "4x", Trouve} ou null si le bouton n'est pas à l'écran ou si la lecture est douteuse. */
    static Object[] lireVitesse(Ecran ecran) {
        Trouve t1 = Gabarits.chercher(ecran, "vitesse_1x"), t4 = Gabarits.chercher(ecran, "vitesse_4x");
        double s1 = t1 != null && t1.valide ? t1.score : 0.0;
        double s4 = t4 != null && t4.valide ? t4.score : 0.0;
        if (Math.max(s1, s4) < Config.SEUIL || Math.abs(s1 - s4) < 0.06) return null;
        return s1 > s4 ? new Object[]{"1x", t1} : new Object[]{"4x", t4};
    }

    static final class Vitesse {
        final Tel tel;
        double prochain = 0, touche = 0;
        String etat;

        Vitesse(Tel tel) { this.tel = tel; }

        String guetter(double debut) {
            double t = Tel.maintenant();
            if (debut == 0 || t - debut < Config.VITESSE_APRES || t < prochain) return etat;
            Object[] lu = lireVitesse(tel.capture());
            String e = lu == null ? null : (String) lu[0];
            if ("1x".equals(e) && Tel.maintenant() - touche > 2.0) {
                tel.toucher(((Trouve) lu[1]).point());
                touche = Tel.maintenant();
                log("  → vitesse ×4");
                prochain = Tel.maintenant() + 1.5;
            } else {
                prochain = Tel.maintenant() + ("4x".equals(e) ? 15.0 : Config.VITESSE_PERIODE);
            }
            etat = e;
            return e;
        }
    }

    // ── le cycle ──

    static Ecran attendreVillageSuivant(Tel tel, Trouve bouton) {
        double debut = Tel.maintenant();
        boolean parti = false;
        while (Tel.maintenant() - debut < 45) {
            boolean visible = Gabarits.localiser(tel.capture(), "suivant") != null;
            if (!visible) {
                parti = true;
            } else if (parti || Tel.maintenant() - debut > 6) {
                tel.dormir(0.3);
                return tel.capture();
            }
            tel.dormir(0.1);
        }
        throw new Bloque("le village suivant n'est pas arrivé en 45 s");
    }

    /** {écran, butin, bouton Suivant} une fois l'écran du village posé (même lecture deux fois). */
    static Object[] lireVillage(Tel tel, Ecran ecran, double delai) {
        double fin = Tel.maintenant() + delai;
        String avant = null;
        while (true) {
            Chiffres.Butin butin = Chiffres.lireButin(ecran);
            Trouve bouton = Gabarits.localiser(ecran, "suivant");
            String lecture = butin + "|" + (bouton != null);
            if (butin != null && bouton != null && lecture.equals(avant)) return new Object[]{ecran, butin, bouton};
            if (Tel.maintenant() > fin) return new Object[]{ecran, butin, bouton};
            avant = lecture;
            tel.dormir(0.15);
            ecran = tel.capture();
        }
    }

    /** Passe au village suivant tant que le butin est trop faible. {écran, bouton Suivant}. */
    static Object[] choisirVillage(Tel tel, Ecran ecran) {
        Trouve bouton = null;
        for (int sautes = 0; sautes <= Config.SUIVANTS_MAX; sautes++) {
            Object[] lu = lireVillage(tel, ecran, 2.5);
            ecran = (Ecran) lu[0];
            Chiffres.Butin butin = (Chiffres.Butin) lu[1];
            if (lu[2] != null) bouton = (Trouve) lu[2];
            if (butin == null) {
                log("  butin illisible : j'attaque ce village");
                return new Object[]{ecran, bouton};
            }
            log("  butin : " + butin);
            if (butin.suffisant()) return new Object[]{ecran, bouton};
            if (sautes == Config.SUIVANTS_MAX) {
                log("  " + Config.SUIVANTS_MAX + " villages passés d'affilée : j'attaque celui-ci");
                return new Object[]{ecran, bouton};
            }
            if (bouton == null) {
                log("  bouton Suivant introuvable : j'attaque ce village");
                return new Object[]{ecran, bouton};
            }
            log("  → suivant (pas assez de butin)");
            Controle.suivant();
            tel.toucher(bouton.point());
            ecran = attendreVillageSuivant(tel, bouton);
        }
        return new Object[]{ecran, bouton};
    }

    /** Attend l'écran de fin et renvoie le bouton « Rentrer » (null : déjà revenu au village).
     *  Capitule au bout de la durée max, ou quand les dégâts ne bougent plus. */
    static Trouve attendreFin(Tel tel, int[] boite, double delai, double duree, double debut) {
        double fin = Tel.maintenant() + delai;
        Ecran ecran = null;
        int essais = 0;
        boolean abandon = false, prolonge = false;
        double abandonA = 0;
        int abandons = 0;
        Vitesse vitesse = new Vitesse(tel);
        Integer degats = null;
        double change = Tel.maintenant();
        int[] vu = null;
        while (Tel.maintenant() < fin) {
            ecran = tel.capture();
            double maintenant = Tel.maintenant();
            if (Cartes.enCombat(ecran, boite)) {
                if (abandon && abandons < 3 && maintenant - abandonA > 8) {
                    abandons++;
                    abandonA = maintenant;
                    abandonner(tel, boite, "toujours en combat 8 s après la capitulation", true);
                    continue;
                }
                if (debut > 0 && !abandon) {
                    Integer d = Chiffres.lireDegats(ecran);
                    if (d != null && !d.equals(degats)) {
                        degats = d;
                        change = maintenant;
                    }
                    boolean presque = degats != null && degats >= Config.SEUIL_ETOILE && Chiffres.etoiles(ecran) == 0;
                    if (presque && !prolonge) log("  " + degats + " % sans étoile : je laisse plus de temps pour le bonus de victoire");
                    else if (prolonge && !presque) log("  étoile obtenue (" + degats + " %)");
                    prolonge = presque;
                    double limiteFige = Config.capitulerSiFige * (presque ? 2 : 1);
                    double limite = Config.duree > 0 ? Config.duree + (presque ? Config.BONUS_ETOILE : 0) : 0;
                    boolean fige = limiteFige > 0 && d != null && d.equals(degats) && maintenant - change >= limiteFige
                            && maintenant - debut >= Config.CAPITULER_APRES_MIN;
                    if (fige || (limite > 0 && maintenant - debut >= limite)) {
                        abandon = true;
                        abandons = 1;
                        abandonA = maintenant;
                        abandonner(tel, boite, fige ? "dégâts figés à " + degats + " % depuis " + Math.round(maintenant - change) + " s"
                                : degats != null ? "durée atteinte (" + degats + " %)" : "durée atteinte");
                        continue;
                    }
                }
                if (!abandon) vitesse.guetter(debut);
                tel.dormir(0.4);
                continue;
            }
            boolean complet = !Gabarits.connu("rentrer_village") || essais % 4 == 3;
            essais++;
            Trouve t = Gabarits.localiser(ecran, "rentrer_village");
            if (t != null) {
                if (vu != null && Math.abs(vu[0] - t.x) <= 12 && Math.abs(vu[1] - t.y) <= 12) return t;
                vu = new int[]{t.x, t.y};
                tel.dormir(0.1);
                continue;
            }
            vu = null;
            if (complet) {
                if (Gabarits.localiser(ecran, "combat") != null) return null;
                for (String nom : Gabarits.popups()) {
                    Trouve p = Gabarits.localiser(ecran, nom);
                    if (p != null) {
                        tel.toucher(p.point());
                        break;
                    }
                }
            }
            tel.dormir(0.2);
        }
        throw new Bloque("pas d'écran de fin après " + Math.round(delai) + " s", ecran);
    }

    static void uneAttaque(Tel tel, int numero, boolean debug) {
        log("Attaque n°" + numero);
        Controle.attaque(numero);

        Object[] r = Gabarits.attendre(tel, new String[]{"terminer_bataille"}, 120,
                new String[]{"trouver_partie", "attaquer", "combat"}, 0.2, Gabarits.patients("recherche_rentrer", 25.0));
        Ecran ecran = (Ecran) r[0];
        Trouve rouge = (Trouve) r[1];
        int[] boite = rouge.boite;

        Object[] choix = choisirVillage(tel, ecran);
        ecran = (Ecran) choix[0];
        Trouve suivant = (Trouve) choix[1];

        List<Carte> cartes = new ArrayList<>();
        String avant = null;
        for (int essai = 0; essai < 4; essai++) {
            cartes = Cartes.detecter(ecran);
            Cartes.analyser(ecran, cartes);
            List<String> l = new ArrayList<>();
            for (Carte c : cartes) l.add(c.decrire());
            String lecture = String.join(",", l);
            boolean troupe = false, sortSansNombre = false;
            for (Carte c : cartes) {
                if (Cartes.aNombre(c) && c.sort == null) troupe = true;
                if (c.sort != null && c.nombre == null) sortSansNombre = true;
            }
            boolean complete = troupe && !sortSansNombre;
            if ((complete && lecture.equals(avant)) || essai == 3) break;
            avant = lecture;
            tel.dormir(0.15);
            ecran = tel.capture();
        }
        boolean troupe = false;
        for (Carte c : cartes) if (Cartes.aNombre(c) && c.sort == null) troupe = true;
        if (!troupe) log("  aucune carte de troupes lisible dans la barre");
        Cartes.apprendre(ecran, cartes);
        int connues = 0;
        for (Carte c : cartes) if (c.connue != null) connues++;
        log("  barre : " + cartes.size() + " cartes, dont " + connues + " reconnues par leur dessin");
        List<double[]> zones = Cartes.zonesInterdites(ecran, cartes, boite, suivant != null ? suivant.boite : null);
        if (debug || cartes.isEmpty()) log("  analyse : " + sauver(annoter(ecran, cartes, zones, new ArrayList<>(), null), "combat_" + numero));
        if (cartes.isEmpty()) {
            tel.toucher(rouge.point());
            throw new Fin("Aucune carte détectée dans la barre : armée vide ? J'arrête.");
        }

        Plan plan = new Plan(ecran.w, ecran.h);
        double debut = Tel.maintenant();
        if (!deployer(tel, cartes, zones, boite, plan, debug, numero)) log("  combat terminé avant la fin du déploiement");

        Trouve bouton = attendreFin(tel, boite, 300, Config.duree, debut);
        if (bouton != null) {
            tel.toucher(bouton.point());
            log("  → rentrer");
        } else {
            log("  déjà revenu au village");
        }
    }

    /** Après un blocage : tente de revenir à un écran connu. */
    static void recuperer(Tel tel, boolean nuit) {
        try {
            Ecran ecran = tel.capture();
            if (nuit && Gabarits.localiser(ecran, "nuit/combat") != null) return;
            List<String> noms = new ArrayList<>();
            if (nuit) noms.add("nuit/rentrer");
            noms.add("rentrer_village");
            noms.addAll(Gabarits.popups());
            for (String nom : noms) {
                Trouve t = Gabarits.localiser(ecran, nom);
                if (t != null) {
                    log("  → " + nom);
                    tel.toucher(t.point());
                    tel.dormir(2);
                    return;
                }
            }
        } catch (Bloque ignored) {
        }
    }

    /** Village principal (false) ou base des ouvriers (true), d'après les boutons à l'écran ;
     *  null si rien de reconnu en `delai` secondes. */
    static Boolean detecterVillage(Tel tel, double delai) {
        String[] principal = {"combat", "trouver_partie", "terminer_bataille", "suivant"};
        String[] ouvriers = {"nuit/combat", "nuit/trouver"};
        double fin = Tel.maintenant() + delai;
        while (Tel.maintenant() < fin) {
            Ecran e = tel.capture();
            String meilleur = null;
            double score = -1;
            for (String[] groupe : new String[][]{principal, ouvriers}) {
                for (String nom : groupe) {
                    Trouve t = Gabarits.localiser(e, nom);
                    if (t != null && t.score > score) {
                        score = t.score;
                        meilleur = nom;
                    }
                }
            }
            if (meilleur != null) return meilleur.startsWith("nuit/");
            if (Nuit.lireBarre(e).valide()) return true;
            for (String nom : Gabarits.popups()) {
                Trouve p = Gabarits.localiser(e, nom);
                if (p != null) {
                    tel.toucher(p.point());
                    break;
                }
            }
            tel.dormir(0.5);
        }
        return null;
    }

    /** `duree` : capitulation au bout de ce temps (0 : jamais ; toujours 0 en base des ouvriers). */
    static void boucle(Tel tel, int maxAttaques, boolean debug, boolean nuit, double duree) {
        List<String> manquants = new ArrayList<>();
        for (String n : nuit ? new String[]{"nuit/combat"} : Config.OBLIGATOIRES) {
            if (!Gabarits.existe(n)) manquants.add("templates/" + n + ".png");
        }
        if (!nuit && Chiffres.modeles().isEmpty()) manquants.add("templates/glyphes/");
        if (!manquants.isEmpty()) throw new Fin("Il manque : " + String.join(", ", manquants));
        String capitule = duree > 0 ? ", capitule au bout de " + Math.round(duree) + " s" : "";
        log(nuit ? "Base des ouvriers" + capitule : "Village principal : " + Chiffres.regleButin() + capitule);
        int faites = 0, echecs = 0;
        while (maxAttaques == 0 || faites < maxAttaques) {
            try {
                if (nuit) Nuit.uneAttaque(tel, faites + 1, debug, duree);
                else uneAttaque(tel, faites + 1, debug);
                faites++;
                Controle.faite(faites);
                echecs = 0;
            } catch (Bloque e) {
                echecs++;
                Controle.bloque();
                String chemin = e.ecran != null ? sauver(e.ecran.img(), "bloque") : null;
                log("Bloqué : " + e.getMessage() + (chemin != null ? " (capture : " + chemin + ")" : ""));
                if (echecs >= 5) {
                    log("5 blocages d'affilée, j'arrête.");
                    break;
                }
                if (echecs >= 2) {
                    log("  2 blocages de suite : je remets le jeu au premier plan");
                    if (!tel.relancerJeu()) log("  le jeu ne s'affiche pas (écran verrouillé ?)");
                    continue;
                }
                recuperer(tel, nuit);
            }
        }
        if (Config.debug && CaptureService.instance != null) log("  partage d'écran : " + CaptureService.instance.bilan());
        log("Fin : " + faites + " attaque(s) terminée(s).");
    }

    // ── images de débogage ──

    static String sauver(Img img, String nom) {
        String heure = new SimpleDateFormat("HHmmss", Locale.FRANCE).format(new Date());
        File f = new File(Config.debugDossier(), nom + "_" + heure + ".png");
        return img.ecrire(f) ? f.getPath() : null;
    }

    /** Rouge = zones interdites, bleu = cartes, vert = rangée du bord, rose/violet = sorts. */
    static Img annoter(Ecran ecran, List<Carte> cartes, List<double[]> zones, List<int[]> chemin, Map<String, List<int[]>> sorts) {
        Mat img = ecran.img().mat();
        try {
            int ep = Math.max(2, ecran.w / 800);
            for (double[] z : zones) Imgproc.rectangle(img, new Point((int) z[0], (int) z[1]), new Point((int) z[2], (int) z[3]), new Scalar(0, 0, 255), ep);
            for (int i = 0; i < cartes.size(); i++) {
                Carte c = cartes.get(i);
                Imgproc.rectangle(img, new Point(c.x0, c.y0), new Point(c.x1, c.y1), new Scalar(255, 140, 0), ep);
                Imgproc.putText(img, (i + 1) + " " + ascii(c.decrire()), new Point(c.x0 + 2, c.y0 - 10), Imgproc.FONT_HERSHEY_SIMPLEX, 0.6, new Scalar(255, 140, 0), 2);
                int[] p = c.point();
                Imgproc.circle(img, new Point(p[0], p[1]), 9, new Scalar(255, 140, 0), -1);
            }
            for (int[] p : chemin) {
                Imgproc.circle(img, new Point(p[0], p[1]), 8, new Scalar(0, 230, 0), -1);
                Imgproc.circle(img, new Point(p[0], p[1]), 8, new Scalar(0, 0, 0), 2);
            }
            if (sorts != null) for (Map.Entry<String, List<int[]>> e : sorts.entrySet()) {
                Scalar couleur = e.getKey().equals("rage") ? new Scalar(200, 40, 150) : new Scalar(190, 80, 255);
                for (int[] p : e.getValue()) Imgproc.circle(img, new Point(p[0], p[1]), 34, couleur, 5);
            }
            return Img.de(img);
        } finally {
            img.release();
        }
    }

    private static String ascii(String s) {
        return java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("[^\\p{ASCII}]", "");
    }
}

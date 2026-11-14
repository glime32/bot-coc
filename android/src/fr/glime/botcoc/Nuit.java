package fr.glime.botcoc;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Base des ouvriers : la barre (héros puis camps), la base adverse vue comme un losange,
 *  la pose tout autour, les compétences, le 2e village. Même logique que bot_coc.py --nuit. */
final class Nuit {
    private Nuit() {}

    static final double COMPETENCE_TROUPES_APRES = 5.0;
    static final double COMPETENCE_ECART = 2.0;
    static final double[] MARGES = {1.5, 2.6, 3.7};
    static final double PAS_CARTE = 152.0 / 1080;
    static final double LARGEUR_CARTE = 132.0 / 1080;
    static final double DECALAGE_HEROS = 170.0 / 1080;
    static final double R_ISO = 15.84 / 21.2;
    static final double[][] UI = {
            {0.00, 0.00, 0.18, 0.14},     // défenseur
            {0.39, 0.00, 0.61, 0.13},     // chrono
            {0.79, 0.00, 1.00, 0.14},     // attaquant
            {0.00, 0.58, 0.16, 0.79},     // bouton rouge
            {0.89, 0.46, 1.00, 0.67},     // bouton de vitesse
            {0.82, 0.64, 1.00, 0.80}};    // dégâts

    static void log(String m) { Journal.log(m); }

    // ── la barre ──

    static final class CarteNuit {
        final int x0, x1, rang;
        final String etat;          // dispo, posee, utilisee, grise, ? ; héros : dispo, pose, mort
        final boolean heros;
        final String jauge;         // héros posé : « pleine », « charge » ou null

        CarteNuit(int x0, int x1, String etat, int rang, boolean heros, String jauge) {
            this.x0 = x0;
            this.x1 = x1;
            this.etat = etat;
            this.rang = rang;
            this.heros = heros;
            this.jauge = jauge;
        }

        int[] point(int h) { return new int[]{(x0 + x1) / 2, (int) (0.865 * h)}; }

        String decrire() { return heros ? "héros" : "camp " + rang; }
    }

    /** Places des cartes apprises au début du combat : x du héros, x de chaque place de camp. */
    static final class Places {
        final int heros;
        final int[] camps;

        Places(int heros, int[] camps) {
            this.heros = heros;
            this.camps = camps;
        }
    }

    static final class Barre {
        final List<CarteNuit> cartes;
        final int bords;
        final Places places;

        Barre(List<CarteNuit> cartes, int bords, Places places) {
            this.cartes = cartes;
            this.bords = bords;
            this.places = places;
        }

        CarteNuit heros() {
            for (CarteNuit c : cartes) if (c.heros) return c;
            return null;
        }

        List<CarteNuit> camps() {
            List<CarteNuit> r = new ArrayList<>();
            for (CarteNuit c : cartes) if (!c.heros) r.add(c);
            return r;
        }

        /** Vraie barre de combat ? (le village ou l'écran de fin n'ont pas de cartes à ces places) */
        boolean valide() {
            int n = camps().size();
            return bords >= 2 && (n >= 2 || (heros() != null && n >= 1));
        }

        CarteNuit carte(int rang) {
            for (CarteNuit c : cartes) if (c.rang == rang) return c;
            return null;
        }
    }

    /** Le bas de l'écran (pleine taille) : tout ce que lit la barre, sans agrandir l'image entière. */
    static final class Bas {
        final Img img;
        final int y0, w, h;

        Bas(Ecran e) {
            y0 = (int) (0.60 * e.h);
            img = e.zone(0, y0, e.w, e.h);
            w = e.w;
            h = e.h;
        }

        /** img[ya:yb, xa:xb] en coordonnées de l'écran. */
        Img z(double xa, double ya, double xb, double yb) {
            return img.crop((int) xa, (int) ya - y0, (int) xb, (int) yb - y0);
        }
    }

    private static boolean remplissage(int t, int s, int v) {
        return s > 120 && v > 110 && (t <= 30 || (t >= 35 && t <= 80) || t >= 170);
    }

    private static boolean magenta(Img z, int i) {
        int b = z.p[i * 3] & 0xff, g = z.p[i * 3 + 1] & 0xff, r = z.p[i * 3 + 2] & 0xff;
        return b > 170 && r > 130 && g < 0.75 * r && b - g > 70;
    }

    private static boolean cyan(Img z, int i) {
        int b = z.p[i * 3] & 0xff, g = z.p[i * 3 + 1] & 0xff, r = z.p[i * 3 + 2] & 0xff;
        return b > 170 && g > 140 && r < 120;
    }

    private static double partMagenta(Img z) {
        if (z.vide()) return Double.NaN;
        int n = 0;
        for (int i = 0; i < z.w * z.h; i++) if (magenta(z, i)) n++;
        return (double) n / (z.w * z.h);
    }

    /** Pour chaque colonne : {bord (début de carte probable), gauche (fond de carte à droite),
     *  profil}. Le bord gauche d'une carte est clair et précédé d'un petit vide sombre. */
    static double[][] bordsCartes(Bas bas) {
        int h = bas.h, w = bas.w;
        Img z = bas.z(0, (int) (0.86 * h), w, (int) (0.97 * h));
        Img hsv = z.hsv();
        double[] profil = new double[w];
        for (int y = 0; y < z.h; y++) {
            for (int x = 0; x < z.w; x++) {
                int i = (y * z.w + x) * 3;
                int s = hsv.p[i + 1] & 0xff, v = hsv.p[i + 2] & 0xff;
                if ((s > 90 && v > 100) || (s < 45 && v > 95)) profil[x]++;
            }
        }
        for (int x = 0; x < w; x++) profil[x] = z.h > 0 ? profil[x] / z.h : Double.NaN;
        double larg = LARGEUR_CARTE * h;
        int mg = Math.max(4, (int) (0.17 * larg)), tr = Math.max(3, (int) (0.12 * larg));
        double[] cum = new double[w + 1];
        for (int x = 0; x < w; x++) cum[x + 1] = cum[x] + profil[x];
        double[] bord = new double[w], gauche = new double[w];
        for (int x = 0; x < w; x++) {
            gauche[x] = moy(cum, x + 2, x + mg, w);
            bord[x] = gauche[x] - moy(cum, x - tr, x - 2, w);
        }
        return new double[][]{bord, gauche, profil};
    }

    private static double moy(double[] cum, int a, int b, int w) {
        a = Math.max(0, Math.min(w, a));
        b = Math.max(0, Math.min(w, b));
        return b > a ? (cum[b] - cum[a]) / Math.max(1, b - a) : 0.0;
    }

    private static double scoreGrille(double[] bord, double p, int phi, double larg) {
        double s = 0;
        int n = (int) Math.ceil((bord.length - larg * 0.9 - phi) / p);
        for (int k = 0; k < n; k++) {
            int pos = (int) Math.rint(phi + k * p);
            if (pos < 0 || pos >= bord.length) continue;
            double v = Math.max(0, bord[pos]);
            if (v > 0.25) s += v;
        }
        return s;
    }

    /** Une carte de camp a un bandeau en haut (numéro + barre violette, ou gris si mort). */
    static boolean enteteCamp(Bas bas, double x0, double larg) {
        int h = bas.h;
        Img z = bas.z((int) (x0 + 0.40 * larg), (int) (0.80 * h), (int) (x0 + 0.92 * larg), (int) (0.855 * h));
        if (z.vide()) return false;
        Img hsv = z.hsv();
        int lignes = 0;
        for (int y = 0; y < z.h; y++) {
            int gris = 0, mag = 0;
            for (int x = 0; x < z.w; x++) {
                int i = y * z.w + x;
                int s = hsv.p[i * 3 + 1] & 0xff, v = hsv.p[i * 3 + 2] & 0xff;
                if (s < 30 && v > 90 && v < 210) gris++;
                if (magenta(z, i)) mag++;
            }
            if ((double) mag / z.w > 0.6) lignes++;
            if ((double) gris / z.w > 0.6) lignes++;
        }
        return lignes >= Math.max(6, 0.012 * h);
    }

    private static double medianeCanal(Img hsv, int k) {
        double[] v = new double[hsv.w * hsv.h];
        for (int i = 0; i < v.length; i++) v[i] = hsv.p[i * 3 + k] & 0xff;
        return Img.mediane(v);
    }

    /** État d'une carte de camp d'après le fond de sa marge gauche et les couleurs du dessin. */
    static String etatCamp(Bas bas, double x0, double larg) {
        int h = bas.h;
        Img zone = bas.z((int) (x0 + 0.03 * larg), (int) (0.87 * h), (int) (x0 + 0.15 * larg), (int) (0.95 * h));
        Img corps = bas.z((int) (x0 + 0.05 * larg), (int) (0.86 * h), (int) (x0 + 0.95 * larg), (int) (0.97 * h));
        if (zone.vide() || corps.vide()) return "?";
        Img hsv = zone.hsv();
        double t = medianeCanal(hsv, 0), s = medianeCanal(hsv, 1), v = medianeCanal(hsv, 2);
        if (s >= 80 && t >= 92 && t <= 130) return "dispo";
        if (s >= 80 && t >= 25 && t <= 80) return "posee";
        if (s < 45 && v >= 120) {
            Img hc = corps.hsv();
            int n = 0;
            for (int i = 0; i < corps.w * corps.h; i++) {
                if ((hc.p[i * 3 + 1] & 0xff) > 90 && (hc.p[i * 3 + 2] & 0xff) > 90) n++;
            }
            return (double) n / (corps.w * corps.h) > 0.03 ? "utilisee" : "grise";
        }
        return "?";
    }

    /** État du héros (« pose », « mort », « dispo ») et sa jauge de compétence. */
    static String[] lireHeros(Bas bas, double x0, double larg) {
        int h = bas.h;
        int xa = (int) (x0 + 0.08 * larg), xb = (int) (x0 + 0.92 * larg);
        Img zone = bas.z(xa, (int) (0.70 * h), xb, (int) (0.86 * h));
        Integer yBarre = null;
        if (!zone.vide()) {
            Img hsv = zone.hsv();
            for (int y = 0; y < zone.h && yBarre == null; y++) {
                int n = 0;
                for (int x = 0; x < zone.w; x++) {
                    int i = (y * zone.w + x) * 3;
                    if (remplissage(hsv.p[i] & 0xff, hsv.p[i + 1] & 0xff, hsv.p[i + 2] & 0xff)) n++;
                }
                if ((double) n / zone.w >= 0.25) yBarre = (int) (0.70 * h) + y;
            }
        }
        Img corps = bas.z(xa, (int) (0.88 * h), xb, (int) (0.96 * h));
        boolean gris = false;
        if (!corps.vide()) {
            Img hc = corps.hsv();
            int n = 0;
            for (int i = 0; i < corps.w * corps.h; i++) if ((hc.p[i * 3 + 1] & 0xff) > 70) n++;
            gris = (double) n / (corps.w * corps.h) < 0.12;
        }
        String etat = yBarre != null && yBarre < 0.815 * h ? "pose" : gris ? "mort" : "dispo";
        String jauge = null;
        if (etat.equals("pose")) {
            int ya = Math.max(0, (int) (yBarre - 0.27 * larg)), yb = (int) (yBarre - 0.06 * larg);
            Img barre3 = bas.z((int) (x0 + 0.60 * larg), ya, (int) (x0 + 0.88 * larg), yb);
            Img toute = bas.z(xa, ya, xb, yb);
            if (!barre3.vide() && partMagenta(barre3) >= 0.35) {
                jauge = "pleine";
            } else if (!toute.vide()) {
                for (int i = 0; i < toute.w * toute.h && jauge == null; i++) {
                    if (magenta(toute, i) || cyan(toute, i)) jauge = "charge";
                }
            }
        }
        return new String[]{etat, jauge};
    }

    /** Places des cartes, apprises sur la barre du début du combat : la rangée régulière des
     *  camps, prolongée jusqu'à 10 places pour les renforts de l'étape 2, et le héros à gauche. */
    static Places places(Bas bas, double[] bord, double[] gauche) {
        int h = bas.h, w = bas.w;
        double larg = LARGEUR_CARTE * h;
        double meilleurS = 0.0, p = -1;
        int phiM = 0;
        int n = (int) Math.ceil((1.03 - 0.97) / 0.004);
        for (int i = 0; i < n; i++) {
            double pp = (0.97 + i * 0.004) * PAS_CARTE * h;
            for (int phi = 0; phi < (int) pp; phi++) {
                double sc = scoreGrille(bord, pp, phi, larg);
                if (sc > meilleurS) {
                    meilleurS = sc;
                    p = pp;
                    phiM = phi;
                }
            }
        }
        if (p < 0) return null;
        List<Integer> vus = new ArrayList<>();
        int marge = Math.max(3, (int) (0.06 * p));
        int nb = (int) Math.ceil((w - larg * 0.9 - phiM) / p);
        for (int q = 0; q < nb; q++) {
            double xf = phiM + q * p;
            int a = Math.max(0, (int) Math.rint(xf) - marge), z = Math.min(w - 1, (int) Math.rint(xf) + marge);
            int x = a;
            for (int t = a; t <= z; t++) if (bord[t] > bord[x]) x = t;
            if (bord[x] > 0.25 && gauche[x] > 0.55 && enteteCamp(bas, x, larg)) vus.add(x);
        }
        if (vus.isEmpty()) return null;
        int v0 = vus.get(0), vn = vus.get(vus.size() - 1);
        if (vus.size() >= 2) p = (vn - v0) / Math.rint((vn - v0) / p);
        List<Integer> pl = new ArrayList<>();
        for (int k = 0; k < 10; k++) if (v0 + k * p + 0.9 * larg <= w) pl.add(v0 + (int) Math.rint(k * p));
        for (int x : vus) {
            int k = (int) Math.rint((x - v0) / p);
            if (k < pl.size()) pl.set(k, x);
        }
        int[] camps = new int[pl.size()];
        for (int i = 0; i < camps.length; i++) camps[i] = pl.get(i);
        return new Places((int) Math.rint(v0 - DECALAGE_HEROS * h), camps);
    }

    private static double moyenne(double[] t, int a, int b) {
        a = Math.max(0, a);
        b = Math.min(t.length, b);
        if (b <= a) return Double.NaN;
        double s = 0;
        for (int i = a; i < b; i++) s += t[i];
        return s / (b - a);
    }

    /** Cartes de la barre et leur état ; chaque carte garde sa place (son numéro) tout le combat. */
    static Barre lireBarre(Ecran ecran, Places places) {
        Bas bas = new Bas(ecran);
        int h = bas.h, w = bas.w;
        double[][] bg = bordsCartes(bas);
        double[] bord = bg[0], gauche = bg[1], profil = bg[2];
        double larg = LARGEUR_CARTE * h;
        if (places == null) {
            places = places(bas, bord, gauche);
            if (places == null) return new Barre(new ArrayList<>(), 0, null);
        }
        List<CarteNuit> camps = new ArrayList<>();
        int bords = 0;
        for (int k = 1; k <= places.camps.length; k++) {
            int x = places.camps[k - 1];
            int a = x + (int) (0.05 * larg), z = Math.min(w, x + (int) (0.95 * larg));
            double maxBord = -Double.MAX_VALUE;
            for (int t = Math.max(0, x - 4); t < Math.min(w, x + 5); t++) maxBord = Math.max(maxBord, bord[t]);
            double pm = moyenne(profil, a, z);
            if (z <= a || !(pm >= 0.12) || maxBord < 0.25) continue;
            String etat = etatCamp(bas, x, larg);
            if (etat.equals("?")) continue;
            camps.add(new CarteNuit(x, (int) (x + larg), etat, k, false, null));
            bords++;
        }
        List<CarteNuit> cartes = new ArrayList<>();
        int xh = places.heros;
        if (xh >= 0 && moyenne(profil, xh + (int) (0.05 * larg), xh + (int) (0.95 * larg)) > 0.35) {
            String[] eh = lireHeros(bas, xh, larg);
            cartes.add(new CarteNuit(xh, (int) (xh + larg), eh[0], 0, true, eh[1]));
        }
        cartes.addAll(camps);
        return new Barre(cartes, bords, places);
    }

    static Barre lireBarre(Ecran ecran) { return lireBarre(ecran, null); }

    // ── où poser ──

    static List<double[]> zones(Ecran ecran, Barre barre) {
        int w = ecran.w, h = ecran.h;
        List<double[]> z = new ArrayList<>();
        for (double[] u : UI) z.add(new double[]{u[0] * w, u[1] * h, u[2] * w, u[3] * h});
        z.add(new double[]{0, 0.79 * h, w, h});
        if (barre != null && barre.heros() != null) {
            CarteNuit hz = barre.heros();
            z.add(new double[]{hz.x0 - 15, 0.72 * h, hz.x1 + 15, h});
        }
        int[] r = Cartes.boutonRouge(ecran);
        if (r != null) z.add(new double[]{r[0] - 20, r[1] - 20, r[0] + r[2] + 20, r[1] + r[3] + 20});
        return z;
    }

    /** Le losange de la base adverse (en pixels : U = y + R·x, V = y − R·x) et ce qu'il faut pour
     *  poser autour. */
    static final class Base {
        double U0, U1, V0, V1, b;
        int k, sw, sh;
        float[] dens;
        boolean[] visible;
        final Map<String, Double> permis = new LinkedHashMap<>();

        double get(String c) {
            switch (c) {
                case "U0": return U0;
                case "U1": return U1;
                case "V0": return V0;
                default: return V1;
            }
        }

        void set(String c, double v) {
            switch (c) {
                case "U0": U0 = v; break;
                case "U1": U1 = v; break;
                case "V0": V0 = v; break;
                default: V1 = v;
            }
        }

        float dens(int x, int y) { return dens[y * sw + x]; }
    }

    /** Netteté (gradient de couleur) de l'image réduite k fois. */
    private static float[] texture(Img img, int k, int[] dims) {
        int sw = img.w / k, sh = img.h / k;
        dims[0] = sw;
        dims[1] = sh;
        Mat m = img.mat(), s = new Mat(), lab8 = new Mat(), lab = new Mat(), flou = new Mat(), gx = new Mat(), gy = new Mat();
        try {
            Imgproc.resize(m, s, new Size(sw, sh), 0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(s, lab8, Imgproc.COLOR_BGR2Lab);
            lab8.convertTo(lab, CvType.CV_32F);
            Imgproc.GaussianBlur(lab, flou, new Size(0, 0), 1.0);
            Imgproc.Sobel(flou, gx, CvType.CV_32F, 1, 0, 3);
            Imgproc.Sobel(flou, gy, CvType.CV_32F, 0, 1, 3);
            float[] ax = new float[sw * sh * 3], ay = new float[sw * sh * 3];
            gx.get(0, 0, ax);
            gy.get(0, 0, ay);
            float[] r = new float[sw * sh];
            for (int i = 0; i < r.length; i++) {
                double t = 0;
                for (int c = 0; c < 3; c++) {
                    double a = ax[i * 3 + c] / 8.0, b = ay[i * 3 + c] / 8.0;
                    t += a * a + b * b;
                }
                r[i] = (float) Math.sqrt(t);
            }
            return r;
        } finally {
            m.release();
            s.release();
            lab8.release();
            lab.release();
            flou.release();
            gx.release();
            gy.release();
        }
    }

    /** Demi-hauteur b d'une case, mesurée sur le damier du sol nu (période verticale 2b). */
    static Double tailleCase(Img img, boolean[] lisse, int lw, int lh) {
        int w2 = img.w / 2, h2 = img.h / 2, h = img.h;
        Mat m = img.mat(), s = new Mat(), g8 = new Mat(), g = new Mat(), flou = new Mat();
        float[] gf = new float[w2 * h2];
        try {
            Imgproc.resize(m, s, new Size(w2, h2), 0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(s, g8, Imgproc.COLOR_BGR2GRAY);
            g8.convertTo(g, CvType.CV_32F);
            Imgproc.GaussianBlur(g, flou, new Size(0, 0), 6);
            Core.subtract(g, flou, g);
            g.get(0, 0, gf);
        } finally {
            m.release();
            s.release();
            g8.release();
            g.release();
            flou.release();
        }
        boolean[] mm = new boolean[w2 * h2];
        int total = 0;
        for (int y = 0; y < h2; y++) {
            int sy = Math.min(lh - 1, (int) Math.floor(y * (double) lh / h2));
            for (int x = 0; x < w2; x++) {
                int sx = Math.min(lw - 1, (int) Math.floor(x * (double) lw / w2));
                if (lisse[sy * lw + sx]) {
                    mm[y * w2 + x] = true;
                    total++;
                }
            }
        }
        if (total < 3000) return null;
        double c0 = 0;
        for (int i = 0; i < gf.length; i++) {
            if (!mm[i]) gf[i] = 0;
            else c0 += (double) gf[i] * gf[i];
        }
        c0 = c0 / total;
        if (c0 == 0) c0 = 1.0;
        double[] corr = new double[43];
        for (int d = 7; d < 50; d++) {
            double s2 = 0;
            int n = 0;
            for (int i = 0; i < (h2 - d) * w2; i++) {
                if (mm[i] && mm[i + d * w2]) {
                    s2 += (double) gf[i] * gf[i + d * w2];
                    n++;
                }
            }
            corr[d - 7] = n > 1500 ? s2 / n / c0 : 0.0;
        }
        for (int i = 1; i < corr.length - 1; i++) {
            if (corr[i] > corr[i - 1] && corr[i] >= corr[i + 1] && corr[i] > 0.04) {
                double periode = 2 * (i + 7);
                if (0.006 * h <= periode / 2 && periode / 2 <= 0.045 * h) return periode / 2;
            }
        }
        return null;
    }

    /** Le losange de la base adverse, ou null si aucun bâtiment n'est visible au centre. */
    static Base losange(Ecran ecran, List<double[]> zones) {
        int k = 3;
        double seuil = 8.0, tau = 0.12;
        Img img = ecran.img();
        int h = ecran.h;
        int[] dims = new int[2];
        float[] gm = texture(img, k, dims);
        int sw = dims[0], sh = dims[1], n = sw * sh;
        boolean[] occupe = new boolean[n], visible = new boolean[n];
        Arrays.fill(visible, true);
        for (int i = 0; i < n; i++) occupe[i] = gm[i] > seuil;
        for (double[] z : zones) {
            int y0 = Math.max(0, (int) (z[1] / k)), y1 = Math.min(sh, Math.max(0, (int) (z[3] / k) + 1));
            int x0 = Math.max(0, (int) (z[0] / k)), x1 = Math.min(sw, Math.max(0, (int) (z[2] / k) + 1));
            for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) visible[y * sw + x] = false;
        }
        float[] dens = new float[n];
        Mat mo = new Mat(sh, sw, CvType.CV_32F), md = new Mat();
        try {
            float[] o = new float[n];
            for (int i = 0; i < n; i++) o[i] = occupe[i] ? 1f : 0f;
            mo.put(0, 0, o);
            Imgproc.blur(mo, md, new Size(7, 7));
            md.get(0, 0, dens);
        } finally {
            mo.release();
            md.release();
        }
        boolean[] lisse = new boolean[n];
        for (int i = 0; i < n; i++) lisse[i] = dens[i] < 0.08 && visible[i];
        Double b = tailleCase(img, lisse, sw, sh);
        double bb = b != null ? b : 15.84 * h / 1080;
        float[] U = new float[n], V = new float[n];
        List<Float> us = new ArrayList<>(), vs = new ArrayList<>();
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int i = y * sw + x;
                U[i] = (float) (y * k + R_ISO * x * k);
                V[i] = (float) (y * k - R_ISO * x * k);
                if (visible[i] && occupe[i] && Math.abs(x - sw / 2.0) < 0.25 * sw && y > 0.12 * sh && y < 0.75 * sh) {
                    us.add(U[i]);
                    vs.add(V[i]);
                }
            }
        }
        if (us.size() < 50) return null;
        double uc = Img.mediane(us), vc = Img.mediane(vs);
        double d = 2 * bb;
        Base base = new Base();
        base.U0 = uc - 2 * d;
        base.U1 = uc + 2 * d;
        base.V0 = vc - 2 * d;
        base.V1 = vc + 2 * d;
        base.b = bb;
        base.k = k;
        base.sw = sw;
        base.sh = sh;
        base.dens = dens;
        base.visible = visible;
        List<String> actifs = new ArrayList<>(List.of("U0", "U1", "V0", "V1"));
        for (int tour = 0; tour < 80 && !actifs.isEmpty(); tour++) {
            for (String cote : new ArrayList<>(actifs)) {
                Double p1 = bande(base, U, V, occupe, cote, 0, d);
                if (p1 == null) {
                    actifs.remove(cote);
                    continue;
                }
                Double p2 = bande(base, U, V, occupe, cote, 1, d);
                if (p1 < tau && (p2 == null || p2 < tau)) {
                    actifs.remove(cote);
                    continue;
                }
                base.set(cote, base.get(cote) + (cote.endsWith("0") ? -d : d));
            }
        }
        bordsCarte(img, base, U, V);
        return base;
    }

    /** Part de pixels occupés dans la bande d'une case derrière ce côté (+ decal cases). */
    private static Double bande(Base L, float[] U, float[] V, boolean[] occupe, String cote, int decal, double d) {
        int tot = 0, occ = 0;
        boolean surU = cote.charAt(0) == 'U';
        double a = cote.equals("U0") ? L.U0 - (decal + 1) * d : cote.equals("U1") ? L.U1 + decal * d
                : cote.equals("V0") ? L.V0 - (decal + 1) * d : L.V1 + decal * d;
        for (int i = 0; i < U.length; i++) {
            if (!L.visible[i]) continue;
            boolean m = surU ? U[i] >= a && U[i] < a + d && V[i] >= L.V0 && V[i] <= L.V1
                    : V[i] >= a && V[i] < a + d && U[i] >= L.U0 && U[i] <= L.U1;
            if (m) {
                tot++;
                if (occupe[i]) occ++;
            }
        }
        return tot > 30 ? (double) occ / tot : null;
    }

    /** Jusqu'où l'on peut poser derrière chaque côté (en cases) : jusqu'au bord de la carte, là
     *  où le sol change franchement de couleur ou se couvre de décor sur deux bandes de suite. */
    private static void bordsCarte(Img img, Base base, float[] U, float[] V) {
        int k = base.k, sw = base.sw, sh = base.sh;
        float[] lab = new float[sw * sh * 3];
        Mat m = img.mat(), s = new Mat(), l8 = new Mat(), lf = new Mat(), flou = new Mat();
        try {
            Imgproc.resize(m, s, new Size(sw, sh), 0, 0, Imgproc.INTER_AREA);
            Imgproc.cvtColor(s, l8, Imgproc.COLOR_BGR2Lab);
            l8.convertTo(lf, CvType.CV_32F);
            Imgproc.GaussianBlur(lf, flou, new Size(0, 0), 1.5);
            flou.get(0, 0, lab);
        } finally {
            m.release();
            s.release();
            l8.release();
            lf.release();
            flou.release();
        }
        double b = base.b;
        Map<String, List<double[]>> profils = new LinkedHashMap<>();
        for (String cote : new String[]{"U0", "U1", "V0", "V1"}) {
            List<double[]> liste = new ArrayList<>();
            for (int st = 0; st < 24; st++) {
                double a = cote.equals("U0") ? base.U0 - (st + 1) * b : cote.equals("U1") ? base.U1 + st * b
                        : cote.equals("V0") ? base.V0 - (st + 1) * b : base.V1 + st * b;
                boolean surU = cote.charAt(0) == 'U';
                double l0 = 0, l1 = 0, l2 = 0, dsum = 0;
                int n = 0;
                for (int i = 0; i < U.length; i++) {
                    if (!base.visible[i]) continue;
                    boolean dedans = surU ? U[i] >= a && U[i] < a + b && V[i] >= base.V0 && V[i] <= base.V1
                            : V[i] >= a && V[i] < a + b && U[i] >= base.U0 && U[i] <= base.U1;
                    if (dedans) {
                        l0 += lab[i * 3];
                        l1 += lab[i * 3 + 1];
                        l2 += lab[i * 3 + 2];
                        dsum += base.dens[i];
                        n++;
                    }
                }
                liste.add(n >= 15 ? new double[]{l0 / n, l1 / n, l2 / n, dsum / n} : null);
            }
            profils.put(cote, liste);
        }
        List<double[]> refs = new ArrayList<>();
        for (List<double[]> liste : profils.values()) {
            for (int i = 1; i < 4; i++) {
                double[] p = liste.get(i);
                if (p != null && p[3] < 0.15) refs.add(p);
            }
        }
        for (Map.Entry<String, List<double[]>> e : profils.entrySet()) {
            base.permis.put(e.getKey(), 99.0);
            if (refs.isEmpty()) continue;
            double[] ref = new double[3];
            for (int c = 0; c < 3; c++) {
                double[] col = new double[refs.size()];
                for (int i = 0; i < col.length; i++) col[i] = refs.get(i)[c];
                ref[c] = Img.mediane(col);
            }
            List<double[]> liste = e.getValue();
            boolean[] hors = new boolean[liste.size()];
            for (int i = 0; i < hors.length; i++) {
                double[] p = liste.get(i);
                if (p == null) continue;
                double dist = Math.sqrt(Math.pow(p[0] - ref[0], 2) + Math.pow(p[1] - ref[1], 2) + Math.pow(p[2] - ref[2], 2));
                hors[i] = dist > 22 || p[3] > 0.30;
            }
            for (int st = 0; st < hors.length - 1; st++) {
                if (hors[st] && hors[st + 1]) {
                    base.permis.put(e.getKey(), 0.5 * st - 0.5);
                    break;
                }
            }
        }
    }

    private static double[] versXY(double u, double v) { return new double[]{(u - v) / (2 * R_ISO), (u + v) / 2}; }

    /** Points où poser, tout autour de la base, à `marge` cases de son losange (coin gauche → haut
     *  → droit → bas), visibles, hors des affichages et, si solNu, sur du sol sans décor. */
    static List<int[]> anneau(Base base, double marge, List<double[]> zones, int w, int h, boolean solNu) {
        Map<String, Double> m = new LinkedHashMap<>(), d = new LinkedHashMap<>();
        for (String c : new String[]{"U0", "U1", "V0", "V1"}) {
            m.put(c, Math.min(marge, base.permis.getOrDefault(c, 99.0)));
            d.put(c, 2 * base.b * Math.max(0.0, m.get(c)));
        }
        double U0 = base.U0 - d.get("U0"), U1 = base.U1 + d.get("U1"), V0 = base.V0 - d.get("V0"), V1 = base.V1 + d.get("V1");
        double[][] coins = {{U0, V1}, {U0, V0}, {U1, V0}, {U1, V1}, {U0, V1}};
        String[] cotes = {"U0", "V0", "U1", "V1"};
        double pas = 1.6 * base.b;
        int k = base.k;
        int r = Math.max(1, (int) (0.8 * base.b / k));
        List<int[]> points = new ArrayList<>();
        for (int q = 0; q < 4; q++) {
            if (m.get(cotes[q]) < 0.9) continue;
            double[] a = versXY(coins[q][0], coins[q][1]), b2 = versXY(coins[q + 1][0], coins[q + 1][1]);
            int n = Math.max(1, (int) (Math.hypot(b2[0] - a[0], b2[1] - a[1]) / pas));
            for (int t = 0; t < n; t++) {
                double x = a[0] + (b2[0] - a[0]) * t / n, y = a[1] + (b2[1] - a[1]) * t / n;
                if (!(0.01 * w < x && x < 0.99 * w && 0.02 * h < y && y < h)) continue;
                boolean interdit = false;
                for (double[] z : zones) if (z[0] <= x && x <= z[2] && z[1] <= y && y <= z[3]) interdit = true;
                if (interdit) continue;
                if (solNu) {
                    int cx = (int) (x / k), cy = (int) (y / k);
                    float mx = 0;
                    for (int yy = Math.max(0, cy - r); yy < Math.min(base.sh, cy + r + 1); yy++) {
                        for (int xx = Math.max(0, cx - r); xx < Math.min(base.sw, cx + r + 1); xx++) mx = Math.max(mx, base.dens(xx, yy));
                    }
                    if (mx > 0.25) continue;
                }
                points.add(new int[]{Img.rond(x), Img.rond(y)});
            }
        }
        return points;
    }

    // ── le combat ──

    static final class Combat_ {
        final Tel tel;
        final boolean debug;
        final int numero;
        final double duree;
        final int w, h;
        Places places;
        int etape = 0;
        double debut = 0;
        boolean abandon = false;
        final Map<Integer, Double> poses = new LinkedHashMap<>();
        final List<double[]> competences = new ArrayList<>();      // {heure, rang}
        final Set<Integer> faites = new HashSet<>(), abandonnees = new HashSet<>(), lancees = new HashSet<>();
        double derniereCompetence = 0, herosTape = 0, facteur = 1.0;
        final Combat.Vitesse vitesse;

        Combat_(Tel tel, boolean debug, int numero, double duree) {
            this.tel = tel;
            this.debug = debug;
            this.numero = numero;
            this.duree = duree;
            int[] t = tel.taille();
            w = t[0];
            h = t[1];
            vitesse = new Combat.Vitesse(tel);
        }

        Object[] lire(Ecran ecran) {
            if (ecran == null) ecran = tel.capture();
            Barre barre = lireBarre(ecran, places);
            if (barre.valide() && places == null) places = barre.places;
            return new Object[]{ecran, barre};
        }

        void nouvelleEtape(Ecran ecran, Barre barre) {
            if (places == null && barre.valide()) places = barre.places;
            etape++;
            poses.clear();
            faites.clear();
            abandonnees.clear();
            competences.clear();
            List<CarteNuit> aPoser = dispo(barre);
            log("  étape " + etape + " : " + (aPoser.isEmpty() ? "rien à poser" : decrire(aPoser)));
            if (aPoser.isEmpty()) return;
            if (!Combat.dezoomer(tel)) log("  je ne peux pas dézoomer moi-même : dézoome à fond à la main (le jeu garde le zoom)");
            Places ancien = places;
            if (etape == 1) places = null;
            Object[] r = lire(tel.capture());
            ecran = (Ecran) r[0];
            barre = (Barre) r[1];
            if (!barre.valide() && ancien != null) {
                places = ancien;
                barre = lireBarre(ecran, ancien);
            }
            if (!barre.valide()) throw new FinDuCombat();
            Object[] v = etape >= 2 ? Combat.lireVitesse(ecran) : null;
            facteur = v != null && "4x".equals(v[0]) ? 4.0 : 1.0;
            if (facteur > 1) log("  combat déjà en ×4 : compétences 4 fois plus rapprochées (même rythme dans le jeu)");
            aPoser = dispo(barre);
            List<double[]> zones = zones(ecran, barre);
            Base base = losange(ecran, zones);
            if (base == null) {
                log("  base adverse introuvable : je pose autour du centre de l'écran");
                double cu = 0.42 * h + R_ISO * 0.5 * w, cv = 0.42 * h - R_ISO * 0.5 * w;
                base = new Base();
                base.U0 = cu - 150;
                base.U1 = cu + 150;
                base.V0 = cv - 150;
                base.V1 = cv + 150;
                base.b = 15.84 * h / 1080;
                base.k = 3;
                base.sw = w / 3;
                base.sh = h / 3;
                base.dens = new float[base.sw * base.sh];
            }
            poser(ecran, base, zones, aPoser);
        }

        private static List<CarteNuit> dispo(Barre barre) {
            List<CarteNuit> r = new ArrayList<>();
            for (CarteNuit c : barre.cartes) if (c.etat.equals("dispo")) r.add(c);
            return r;
        }

        private static String decrire(List<CarteNuit> cs) {
            List<String> l = new ArrayList<>();
            for (CarteNuit c : cs) l.add(c.decrire());
            return String.join(", ", l);
        }

        /** Pose les cartes tout autour de la base, espacées ; vérifie ; repose plus loin ce qui a
         *  été refusé (3 essais), en évitant les endroits déjà refusés. */
        void poser(Ecran ecran, Base base, List<double[]> zones, List<CarteNuit> aPoser) {
            List<int[]> refuses = new ArrayList<>(), dessin = null;
            boolean fini = false;
            for (int passe = 0; passe < MARGES.length; passe++) {
                double marge = MARGES[passe];
                List<int[]> anneau = anneau(base, marge, zones, w, h, true);
                if (anneau.isEmpty()) anneau = anneau(base, marge, zones, w, h, false);
                if (anneau.isEmpty()) {
                    log("  aucun point de pose visible autour de la base");
                    fini = true;
                    break;
                }
                List<int[]> loin = new ArrayList<>();
                for (int[] p : anneau) {
                    boolean ok = true;
                    for (int[] r : refuses) if (Math.hypot(p[0] - r[0], p[1] - r[1]) <= 1.5 * base.b) ok = false;
                    if (ok) loin.add(p);
                }
                List<int[]> points = loin.isEmpty() ? anneau : loin;
                if (dessin == null) dessin = points;
                int n = aPoser.size();
                if (debut == 0) debut = Tel.maintenant();
                log("  pose (" + (passe == 0 ? "tout autour de la base" : String.format(java.util.Locale.FRANCE, "%.1f cases plus loin", marge))
                        + ") : " + decrire(aPoser));
                Map<Integer, Double> heures = new LinkedHashMap<>();
                Map<Integer, int[]> ouPose = new LinkedHashMap<>();
                for (int q = 0; q < n; q++) {
                    CarteNuit c = aPoser.get(q);
                    int[] p = points.get(Math.floorMod((int) (((q + 0.5) / n + 0.37 * passe) * points.size()), points.size()));
                    tel.toucherLent(50, c.point(h), p);
                    heures.put(c.rang, Tel.maintenant());
                    ouPose.put(c.rang, p);
                }
                tel.dormir(0.7);
                Barre barre = (Barre) lire(null)[1];
                if (!barre.valide()) throw new FinDuCombat();
                List<CarteNuit> restant = new ArrayList<>();
                for (CarteNuit c : aPoser) {
                    CarteNuit d = barre.carte(c.rang);
                    if (d != null && d.etat.equals("dispo")) {
                        restant.add(d);
                        refuses.add(ouPose.get(c.rang));
                    } else if (!c.heros && !lancees.contains(c.rang) && (d == null || !d.etat.equals("utilisee"))) {
                        poses.putIfAbsent(c.rang, heures.get(c.rang));
                    }
                }
                if (restant.isEmpty()) {
                    log("  vérifié : tout est posé");
                    fini = true;
                    break;
                }
                aPoser = restant;
            }
            if (!fini) {
                log("  impossible de poser : " + decrire(aPoser));
                for (CarteNuit c : aPoser) abandonnees.add(c.rang);
            }
            planifier();
            if (debug && dessin != null) Combat.sauver(annoter(ecran, lireBarre(ecran, places), zones, base, dessin), "nuit_" + numero + "_etape" + etape);
        }

        /** Compétence de chaque camp posé : 5 s après sa pose, 2 s au moins après la précédente
         *  (temps du jeu : divisés par 4 si le combat tourne en ×4). */
        void planifier() {
            double derniere = 0;
            for (double[] c : competences) derniere = Math.max(derniere, c[0]);
            List<Map.Entry<Integer, Double>> l = new ArrayList<>(poses.entrySet());
            l.sort((a, b) -> a.getValue().equals(b.getValue()) ? Integer.compare(a.getKey(), b.getKey()) : Double.compare(a.getValue(), b.getValue()));
            for (Map.Entry<Integer, Double> e : l) {
                int rang = e.getKey();
                if (faites.contains(rang)) continue;
                double t = Math.max(e.getValue() + COMPETENCE_TROUPES_APRES / facteur, derniere + COMPETENCE_ECART / facteur);
                competences.add(new double[]{t, rang});
                faites.add(rang);
                derniere = t;
            }
            competences.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : Double.compare(a[1], b[1]));
        }

        /** Lance les compétences prévues d'ici `avance` s, seulement sur une carte vue verte. */
        void competencesDues(Barre barre, double avance) {
            while (!competences.isEmpty()) {
                double tPrevu = competences.get(0)[0];
                int rang = (int) competences.get(0)[1];
                double t = Math.max(tPrevu, derniereCompetence + COMPETENCE_ECART / facteur);
                if (t > Tel.maintenant() + avance) break;
                CarteNuit c = barre.carte(rang);
                if (c == null && Tel.maintenant() - tPrevu < 3.0) break;
                double attente = t - Tel.maintenant();
                if (attente > 0) tel.dormir(attente);
                competences.remove(0);
                if (c == null || !c.etat.equals("posee")) continue;
                tel.toucher(c.point(h));
                derniereCompetence = Tel.maintenant();
                lancees.add(rang);
                log("  → compétence du camp " + rang);
            }
        }

        /** Compétences à l'heure et surveillance de la barre, jusqu'à ce qu'elle disparaisse (fin
         *  du combat ou étape suivante). */
        void surveiller() {
            int absent = 0, nouveau = 0;
            double fin = Tel.maintenant() + 420, tour = 0;
            while (Tel.maintenant() < fin) {
                tel.dormir(Math.max(0.0, tour + 0.25 - Tel.maintenant()));
                tour = Tel.maintenant();
                if (duree > 0 && debut > 0 && !abandon && Tel.maintenant() - debut >= duree) {
                    abandon = true;
                    competences.clear();
                    Combat.abandonner(tel, null, "durée atteinte");
                    return;
                }
                Barre barre = (Barre) lire(null)[1];
                if (!barre.valide()) {
                    absent++;
                    if (absent >= 2) return;
                    continue;
                }
                absent = 0;
                boolean aPoser = false;
                for (CarteNuit c : barre.cartes) if (c.etat.equals("dispo") && !abandonnees.contains(c.rang)) aPoser = true;
                nouveau = aPoser ? nouveau + 1 : 0;
                if (nouveau >= 2) {
                    nouveau = 0;
                    tel.dormir(1.0);
                    Object[] r = lire(tel.capture());
                    if (((Barre) r[1]).valide()) nouvelleEtape((Ecran) r[0], (Barre) r[1]);
                    continue;
                }
                CarteNuit hz = barre.heros();
                if (hz != null && hz.etat.equals("pose") && "pleine".equals(hz.jauge) && Tel.maintenant() - herosTape > 5) {
                    tel.toucher(hz.point(h));
                    herosTape = Tel.maintenant();
                    log("  → compétence du héros (jauge pleine)");
                }
                competencesDues(barre, 0.3);
                if (competences.isEmpty() && !abandon) vitesse.guetter(debut);
            }
            throw new Bloque("combat de plus de 7 minutes ?");
        }
    }

    static Img annoter(Ecran ecran, Barre barre, List<double[]> zones, Base base, List<int[]> anneau) {
        Mat img = ecran.img().mat();
        try {
            int ep = Math.max(2, ecran.w / 800);
            for (double[] z : zones) Imgproc.rectangle(img, new Point((int) z[0], (int) z[1]), new Point((int) z[2], (int) z[3]), new Scalar(0, 0, 255), ep);
            if (base != null) {
                double[][] coins = {{base.U0, base.V1}, {base.U0, base.V0}, {base.U1, base.V0}, {base.U1, base.V1}};
                for (int i = 0; i < 4; i++) {
                    double[] a = versXY(coins[i][0], coins[i][1]), b = versXY(coins[(i + 1) % 4][0], coins[(i + 1) % 4][1]);
                    Imgproc.line(img, new Point(a[0], a[1]), new Point(b[0], b[1]), new Scalar(0, 220, 255), ep);
                }
            }
            for (int[] p : anneau) Imgproc.circle(img, new Point(p[0], p[1]), 9, new Scalar(0, 230, 0), -1);
            if (barre != null) for (CarteNuit c : barre.cartes) {
                Scalar col = c.etat.equals("dispo") ? new Scalar(255, 140, 0) : c.etat.startsWith("pose") ? new Scalar(0, 200, 0) : new Scalar(160, 160, 160);
                Imgproc.rectangle(img, new Point(c.x0, (int) (0.82 * ecran.h)), new Point(c.x1, (int) (0.98 * ecran.h)), col, ep);
            }
            return Img.de(img);
        } finally {
            img.release();
        }
    }

    // ── le cycle ──

    /** Du village jusqu'au combat : « Combat » (haches), puis « Trouver ! », jusqu'à la barre. */
    static Object[] attendreCombat(Tel tel, double delai) {
        double fin = Tel.maintenant() + delai;
        Map<String, Double> derniere = new LinkedHashMap<>();
        Ecran ecran = null;
        List<String> noms = new ArrayList<>(Gabarits.popups());
        noms.addAll(List.of("nuit/trouver", "nuit/trouver_partie", "trouver_partie", "nuit/attaquer", "nuit/combat"));
        Map<String, int[]> vus = new LinkedHashMap<>();
        while (Tel.maintenant() < fin) {
            ecran = tel.capture();
            Barre barre = lireBarre(ecran);
            if (barre.valide()) return new Object[]{ecran, barre};
            Map<String, int[]> nouveaux = new LinkedHashMap<>();
            for (String nom : noms) {
                Trouve t = Gabarits.localiser(ecran, nom);
                if (t == null) continue;
                nouveaux.put(nom, new int[]{t.x, t.y});
                if (Gabarits.pose(vus, nom, t, 12) && Tel.maintenant() - derniere.getOrDefault(nom, 0.0) > 2.5) {
                    log("  → " + nom.replace("nuit/", ""));
                    tel.toucher(t.point());
                    derniere.put(nom, Tel.maintenant());
                    if (nom.contains("trouver")) derniere.put("nuit/combat", Tel.maintenant() + 10);
                }
                break;
            }
            vus = nouveaux;
            tel.dormir(0.3);
        }
        throw new Bloque("le combat n'a pas commencé", ecran);
    }

    /** Après le combat : « Rentrer ». "combat" si la barre revient (2e village). */
    static String attendreFin(Tel tel, double delai) {
        double fin = Tel.maintenant() + delai, debut = Tel.maintenant();
        Ecran ecran = null;
        while (Tel.maintenant() < fin) {
            ecran = tel.capture();
            if (lireBarre(ecran).valide()) return "combat";
            if (Gabarits.localiser(ecran, "nuit/combat") != null) return "village";
            for (String nom : new String[]{"nuit/rentrer", "rentrer_village"}) {
                Trouve t = Gabarits.localiser(ecran, nom);
                if (t != null) {
                    tel.toucher(t.point());
                    log("  → rentrer");
                    return "rentre";
                }
            }
            for (String nom : Gabarits.popups()) {
                Trouve p = Gabarits.localiser(ecran, nom);
                if (p != null) {
                    tel.toucher(p.point());
                    break;
                }
            }
            if (Tel.maintenant() - debut > 8) {
                int[] p = Cartes.boutonVert(ecran, new double[]{0.25, 0.70, 0.75, 0.98});
                if (p != null) {
                    tel.toucher(p);
                    log("  → rentrer (bouton vert du bas)");
                    return "rentre";
                }
            }
            tel.dormir(0.4);
        }
        throw new Bloque("pas d'écran de fin après " + Math.round(delai) + " s", ecran);
    }

    static void uneAttaque(Tel tel, int numero, boolean debug, double duree) {
        log("Attaque n°" + numero + " (base des ouvriers)");
        Controle.attaque(numero);
        Object[] r = attendreCombat(tel, 120);
        Ecran ecran = (Ecran) r[0];
        Barre barre = (Barre) r[1];
        List<String> l = new ArrayList<>();
        for (CarteNuit c : barre.cartes) l.add(c.decrire() + " (" + c.etat + ")");
        log("  barre : " + String.join(", ", l));
        Combat_ combat = new Combat_(tel, debug, numero, duree);
        String suite = "combat";
        boolean premiere = true;
        while (suite.equals("combat")) {
            try {
                if (premiere) {
                    premiere = false;
                    combat.nouvelleEtape(ecran, barre);
                }
                combat.surveiller();
            } catch (FinDuCombat ignored) {
            }
            suite = attendreFin(tel, 90);
        }
        if (suite.equals("village")) log("  déjà revenu au village");
    }
}

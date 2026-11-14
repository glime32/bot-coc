package fr.glime.botcoc;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Combat : la barre des troupes (cartes, compteurs, sorts, héros), l'état du combat, les
 *  boutons rouge et vert repérés à leur couleur. */
final class Cartes {
    private Cartes() {}

    static boolean aNombre(Carte c) { return c.nombre != null && c.nombre != 0; }

    // ── couleurs ──

    /** Vrai tant que le bouton rouge « Terminer la bataille » / « Abandonner » est là. */
    static boolean enCombat(Ecran ecran, int[] boite) {
        int x = boite[0], y = boite[1], w = boite[2], h = boite[3];
        Img z = ecran.zone(x, y, x + w, y + h);
        if (z.vide()) return false;
        Img hsv = z.hsv();
        int n = 0, tot = z.w * z.h;
        for (int i = 0; i < tot; i++) {
            int t = hsv.p[i * 3] & 0xff, s = hsv.p[i * 3 + 1] & 0xff, v = hsv.p[i * 3 + 2] & 0xff;
            if ((t < 10 || t > 170) && s > 120 && v > 90) n++;
        }
        return (double) n / tot > 0.2;
    }

    /** Part de pixels vifs et colorés : tombe presque à zéro quand une carte grise. */
    static double tauxCouleur(Img img) {
        if (img.vide()) return Double.NaN;
        Img hsv = img.hsv();
        int n = 0, tot = img.w * img.h;
        for (int i = 0; i < tot; i++) {
            if ((hsv.p[i * 3 + 1] & 0xff) > 90 && (hsv.p[i * 3 + 2] & 0xff) > 90) n++;
        }
        return (double) n / tot;
    }

    private static Img carteImg(Ecran ecran, Carte c) { return ecran.zone(c.x0, c.y0, c.x1, c.y1); }

    // ── cartes reconnues par leur dessin ──

    private static final class Modele {
        String nom;
        Img gris;
        int w, h, dx, dy;
        double[] couleur;
    }

    private static List<Modele> modeles;

    static synchronized void oublier() { modeles = null; sorts = null; }

    /** Partie centrale d'une carte (le dessin) : {x0, y0, x1, y1} dans la carte. */
    private static int[] art(int w, int h) {
        return new int[]{(int) (0.10 * w), (int) (0.30 * h), (int) (0.90 * w), (int) (0.74 * h)};
    }

    private static File dossierCartes() { return new File(Config.templates(), "cartes"); }

    private static synchronized List<Modele> modeles() {
        if (modeles == null) {
            modeles = new ArrayList<>();
            File[] fs = dossierCartes().listFiles();
            if (fs != null) {
                Arrays.sort(fs);
                for (File f : fs) {
                    if (!f.getName().endsWith(".png")) continue;
                    Img img = Img.lire(f, true);
                    if (img == null) continue;
                    int[] a = art(img.w, img.h);
                    Img dessin = img.crop(a[0], a[1], a[2], a[3]);
                    Modele m = new Modele();
                    m.nom = f.getName().substring(0, f.getName().length() - 4);
                    m.gris = dessin.gris().facteur(Config.REDUCTION, Imgproc.INTER_AREA);
                    m.w = img.w;
                    m.h = img.h;
                    m.dx = a[0];
                    m.dy = a[1];
                    m.couleur = dessin.moyenne();
                    modeles.add(m);
                }
            }
        }
        return modeles;
    }

    static int nombreModeles() { return modeles().size(); }

    static List<Carte> cartesConnues(Ecran ecran) {
        int ya = (int) (ecran.h * Config.BARRE[0]);
        Img g = ecran.gris();
        Img bande = g.crop(0, (int) (ya * Config.REDUCTION), g.w, g.h);
        List<Object[]> trouvees = new ArrayList<>();
        Mat mb = bande.mat();
        try {
            for (Modele m : modeles()) {
                if (m.gris.h > bande.h || m.gris.w > bande.w) continue;
                Mat t = m.gris.mat(), res = new Mat();
                Core.MinMaxLocResult r;
                try {
                    Imgproc.matchTemplate(mb, t, res, Imgproc.TM_CCOEFF_NORMED);
                    r = Core.minMaxLoc(res);
                } finally {
                    t.release();
                    res.release();
                }
                if (r.maxVal < 0.80) continue;
                int x0 = (int) (r.maxLoc.x / Config.REDUCTION) - m.dx;
                int y0 = (int) (r.maxLoc.y / Config.REDUCTION) + ya - m.dy;
                Img vue = ecran.zone(Math.max(0, x0), Math.max(0, y0), x0 + m.w, y0 + m.h);
                int[] a = art(vue.w, vue.h);
                Img dessin = vue.crop(a[0], a[1], a[2], a[3]);
                if (dessin.vide()) continue;
                double[] moy = dessin.moyenne();
                double ecart = 0;
                for (int k = 0; k < 3; k++) ecart = Math.max(ecart, Math.abs(moy[k] - m.couleur[k]));
                if (ecart > 45) continue;
                Carte c = new Carte(x0, y0, x0 + m.w, y0 + m.h);
                c.connue = m.nom;
                trouvees.add(new Object[]{r.maxVal, c});
            }
        } finally {
            mb.release();
        }
        trouvees.sort((p, q) -> Double.compare((double) q[0], (double) p[0]));
        List<Carte> cartes = new ArrayList<>();
        for (Object[] t : trouvees) {
            Carte c = (Carte) t[1];
            boolean libre = true;
            for (Carte d : cartes) if (!(c.x1 <= d.x0 || c.x0 >= d.x1)) libre = false;
            if (libre) cartes.add(c);
        }
        return cartes;
    }

    // ── cartes vues comme des blocs clairs (décor sombre) ──

    static List<Carte> cartesParLuminosite(Ecran ecran) {
        int h = ecran.h, w = ecran.w;
        int ya = (int) (h * Config.BARRE[0]);
        Img z = ecran.zone(0, ya, w, (int) (h * Config.BARRE[1]));
        Img hsv = z.hsv();
        int zw = z.w, zh = z.h;
        boolean[] clair = new boolean[zw * zh];
        for (int i = 0; i < zw * zh; i++) clair[i] = (hsv.p[i * 3 + 2] & 0xff) > 120;
        double[] col = new double[zw];
        for (int y = 0; y < zh; y++) for (int x = 0; x < zw; x++) if (clair[y * zw + x]) col[x]++;
        for (int x = 0; x < zw; x++) col[x] /= zh;
        int k = Math.max(3, (int) (0.002 * w) | 1);
        int r = (k - 1) / 2;
        boolean[] haut = new boolean[zw];
        for (int x = 0; x < zw; x++) {
            double s = 0;
            for (int t = -r; t <= k - 1 - r; t++) {
                int xx = x + t;
                if (xx >= 0 && xx < zw) s += col[xx];
            }
            haut[x] = s / k > 0.28;
        }
        List<int[]> blocs = new ArrayList<>();
        for (int[] s : Img.segments(haut)) {
            if (!blocs.isEmpty()) {
                int[] dernier = blocs.get(blocs.size() - 1);
                if (s[0] - dernier[1] < 0.004 * w && s[1] - dernier[0] <= 0.07 * w) {
                    dernier[1] = s[1];
                    continue;
                }
            }
            blocs.add(new int[]{s[0], s[1]});
        }
        List<Carte> cartes = new ArrayList<>();
        for (int[] b : blocs) {
            int a = b[0], bb = b[1];
            if (!(0.035 * w <= bb - a && bb - a <= 0.09 * w)) continue;
            boolean[] lignes = new boolean[zh];
            for (int y = 0; y < zh; y++) {
                int n = 0;
                for (int x = a; x < bb; x++) if (clair[y * zw + x]) n++;
                lignes[y] = (double) n / (bb - a) > 0.3;
            }
            List<int[]> segs = Img.segments(lignes);
            if (segs.isEmpty()) continue;
            int[] plusLong = segs.get(0);
            for (int[] s : segs) if (s[1] - s[0] > plusLong[1] - plusLong[0]) plusLong = s;
            if (plusLong[1] - plusLong[0] >= 0.10 * h) cartes.add(new Carte(a, ya + plusLong[0], bb, ya + plusLong[1]));
        }
        return cartes;
    }

    // ── cartes vues par leur contour sombre (décor clair) ──

    /** Gris de la barre en entiers, flou vertical 5 lignes (comme cv2.blur sur int16). */
    private static short[] grisFlou(Ecran ecran, int y0, int y1, int[] dims, Size noyau) {
        Img z = ecran.zone(0, y0, ecran.w, y1).gris();
        dims[0] = z.w;
        dims[1] = z.h;
        if (z.vide()) return new short[0];
        Mat a = new Mat(), b = new Mat(), m = z.mat();
        try {
            m.convertTo(a, CvType.CV_16S);
            Imgproc.blur(a, b, noyau);
            short[] r = new short[z.w * z.h];
            b.get(0, 0, r);
            return r;
        } finally {
            a.release();
            b.release();
            m.release();
        }
    }

    static final class Traits {
        final List<Double> positions;
        final short[] g;
        final int w, h;

        Traits(List<Double> positions, short[] g, int w, int h) {
            this.positions = positions;
            this.g = g;
            this.w = w;
            this.h = h;
        }
    }

    static Traits traitsSombres(Ecran ecran, int y0, int y1) {
        int[] dims = new int[2];
        short[] g = grisFlou(ecran, y0 + 8, y1 - 8, dims, new Size(1, 5));
        int w = dims[0], h = dims[1];
        double[] vallee = new double[w];
        if (h > 0) {
            for (int d = 2; d <= 5; d++) {
                for (int x = 0; x < w; x++) {
                    int xg = Math.floorMod(x - d, w), xd = Math.floorMod(x + d, w);
                    int n = 0;
                    for (int y = 0; y < h; y++) {
                        int v = g[y * w + x];
                        if (Math.min(g[y * w + xg], g[y * w + xd]) - v > 18) n++;
                    }
                    vallee[x] = Math.max(vallee[x], (double) n / h);
                }
            }
        }
        boolean[] m = new boolean[w];
        for (int x = 0; x < w; x++) m[x] = vallee[x] >= 0.6;
        List<Double> pos = new ArrayList<>();
        for (int[] s : Img.segments(m)) pos.add((s[0] + s[1] - 1) / 2.0);
        return new Traits(pos, g, w, h);
    }

    static boolean videEntre(List<Double> traits, double a, double b) {
        for (double t : traits) if (a + 4 < t && t < b - 4) return false;
        return true;
    }

    static Double largeurParContours(List<Double> traits, int h) {
        List<Double> ecarts = new ArrayList<>();
        for (int i = 0; i < traits.size(); i++) {
            for (int j = i + 1; j < traits.size(); j++) {
                double e = traits.get(j) - traits.get(i);
                if (0.105 * h <= e && e <= 0.14 * h && videEntre(traits, traits.get(i), traits.get(j))) ecarts.add(e);
            }
        }
        if (ecarts.isEmpty()) return null;
        int meilleur = 0, votesMax = -1;
        for (int i = 0; i < ecarts.size(); i++) {
            double e = ecarts.get(i);
            int v = 0;
            for (double f : ecarts) if (Math.abs(e - f) <= 0.02 * e) v++;
            if (v > votesMax) {
                votesMax = v;
                meilleur = i;
            }
        }
        double ref = ecarts.get(meilleur);
        List<Double> proches = new ArrayList<>();
        for (double e : ecarts) if (Math.abs(e - ref) <= 0.02 * e) proches.add(e);
        return Img.mediane(proches);
    }

    static List<Carte> cartesParContours(Ecran ecran, double largeur, int y0, int y1, Traits tr) {
        List<Double> traits = tr.positions;
        short[] g = tr.g;
        int w = tr.w, h = tr.h;
        List<Carte> cartes = new ArrayList<>();
        double fin = -1;
        for (int i = 0; i < traits.size(); i++) {
            double xa = traits.get(i);
            if (xa <= fin) continue;
            for (int j = i + 1; j < traits.size(); j++) {
                double xb = traits.get(j);
                if (Math.abs(xb - xa - largeur) <= 0.06 * largeur && videEntre(traits, xa, xb)) {
                    int a = Math.max(0, (int) xa + 6), b = Math.min(w, (int) xb - 6);
                    if (b > a && h > 1) {
                        double s = 0;
                        for (int y = 0; y + 1 < h; y++) {
                            for (int x = a; x < b; x++) s += Math.abs(g[(y + 1) * w + x] - g[y * w + x]);
                        }
                        if (s / ((double) (h - 1) * (b - a)) >= 3.5) {
                            cartes.add(new Carte(Img.rond(xa), y0, Img.rond(xb), y1));
                            fin = xb;
                        }
                    }
                    break;
                }
                if (xb - xa > 1.1 * largeur) break;
            }
        }
        return cartes;
    }

    /** Les cartes de la barre du bas, de gauche à droite. */
    static List<Carte> detecter(Ecran ecran) {
        List<Carte> connues = cartesConnues(ecran);
        List<Carte> autres = cartesParLuminosite(ecran);
        List<Carte> ref = !connues.isEmpty() ? connues : autres;
        int y0, y1;
        if (!ref.isEmpty()) {
            List<Integer> a = new ArrayList<>(), b = new ArrayList<>();
            for (Carte c : ref) {
                a.add(c.y0);
                b.add(c.y1);
            }
            y0 = (int) Img.mediane(a);
            y1 = (int) Img.mediane(b);
        } else {
            y0 = (int) (0.83 * ecran.h);
            y1 = (int) (0.983 * ecran.h);
        }
        Traits tr = traitsSombres(ecran, y0, y1);
        double largeur;
        if (!connues.isEmpty()) {
            List<Integer> l = new ArrayList<>();
            for (Carte c : connues) l.add(c.x1 - c.x0);
            largeur = Img.mediane(l);
        } else {
            Double lc = largeurParContours(tr.positions, ecran.h);
            if (lc != null) {
                largeur = lc;
            } else if (autres.size() >= 3) {
                List<Integer> l = new ArrayList<>();
                for (Carte c : autres) l.add(c.x1 - c.x0);
                largeur = Img.mediane(l);
            } else {
                largeur = 0.123 * ecran.h;
            }
        }
        autres.addAll(cartesParContours(ecran, largeur, y0, y1, tr));
        List<Carte> cartes = new ArrayList<>(connues);
        for (Carte c : autres) {
            if (Math.abs((c.x1 - c.x0) - largeur) > 0.15 * largeur || Math.abs(c.y1 - y1) > 0.06 * ecran.h) continue;
            boolean libre = true;
            for (Carte d : cartes) {
                if (!(c.x1 <= d.x0 + 0.1 * largeur || c.x0 >= d.x1 - 0.1 * largeur)) libre = false;
            }
            if (libre) cartes.add(c);
        }
        for (Carte c : cartes) {
            c.y0 = y0;
            c.y1 = y1;
            c.couleur0 = tauxCouleur(carteImg(ecran, c));
        }
        cartes.sort((p, q) -> Integer.compare(p.x0, q.x0));
        return cartes;
    }

    /** Retient le dessin des nouvelles cartes sûres (compteur lu, cadre de héros, sort reconnu). */
    static void apprendre(Ecran ecran, List<Carte> cartes) {
        int nouvelles = 0;
        for (Carte c : cartes) {
            if (c.connue != null || !(aNombre(c) || c.heros || c.sort != null)) continue;
            File[] fs = dossierCartes().listFiles();
            if (fs != null && fs.length >= 60) break;
            String nom = "appris_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(new Date()) + "_" + c.x0;
            if (ecran.zone(c.x0, c.y0, c.x1, c.y1).ecrire(new File(dossierCartes(), nom + ".png"))) {
                c.connue = nom;
                nouvelles++;
            }
        }
        if (nouvelles > 0) {
            synchronized (Cartes.class) { modeles = null; }
            Journal.log("  " + nouvelles + " nouvelle(s) carte(s) mémorisée(s)");
        }
    }

    static boolean epuisee(Ecran ecran, Carte c) {
        return tauxCouleur(carteImg(ecran, c)) < 0.4 * c.couleur0;
    }

    private static Map<String, Img> sorts;

    static String identifierSort(Ecran ecran, Carte carte) {
        synchronized (Cartes.class) {
            if (sorts == null) {
                sorts = new LinkedHashMap<>();
                File[] fs = new File(Config.templates(), "sorts").listFiles();
                if (fs != null) {
                    Arrays.sort(fs);
                    for (File f : fs) {
                        if (f.getName().endsWith(".png")) sorts.put(f.getName().substring(0, f.getName().length() - 4), Img.lire(f, true));
                    }
                }
            }
        }
        Img cas = ecran.zone(carte.x0, carte.y0, carte.x1, carte.y1);
        String meilleur = null;
        double score = 0.6;
        Mat mc = cas.mat();
        try {
            for (Map.Entry<String, Img> e : sorts.entrySet()) {
                Img icone = e.getValue();
                if (icone == null || icone.h > cas.h || icone.w > cas.w) continue;
                Mat t = icone.mat(), res = new Mat();
                try {
                    Imgproc.matchTemplate(mc, t, res, Imgproc.TM_CCOEFF_NORMED);
                    double s = Core.minMaxLoc(res).maxVal;
                    if (s > score) {
                        score = s;
                        meilleur = e.getKey();
                    }
                } finally {
                    t.release();
                    res.release();
                }
            }
        } finally {
            mc.release();
        }
        return meilleur;
    }

    /** Les cartes de héros ont un épais cadre bleu foncé (l'engin de siège, non). */
    static boolean cadreHeros(Ecran ecran, Carte c) {
        int w = c.x1 - c.x0, h = c.y1 - c.y0;
        Img bord = ecran.zone(c.x0, c.y0, c.x0 + Math.max(4, (int) (0.08 * w)), c.y0 + h / 2);
        if (bord.vide()) return false;
        Img hsv = bord.hsv();
        int n = 0, tot = bord.w * bord.h;
        for (int i = 0; i < tot; i++) {
            int t = hsv.p[i * 3] & 0xff, s = hsv.p[i * 3 + 1] & 0xff, v = hsv.p[i * 3 + 2] & 0xff;
            if (t >= 105 && t <= 140 && s > 120 && v < 170) n++;
        }
        return (double) n / tot >= 0.15;
    }

    /** Bande juste au-dessus d'une carte : là qu'apparaît la barre de vie de l'unité posée. */
    static Img bandeDessus(Ecran ecran, Carte c) {
        int hc = c.y1 - c.y0, m = (c.x1 - c.x0) / 20;
        int ya = Math.max(0, c.y0 - (int) (0.27 * hc)), yb = c.y0 - (int) (0.09 * hc);
        if (yb <= ya) return new Img(0, 0, 3);
        return ecran.zone(c.x0 + m, ya, c.x1 - m, yb);
    }

    /** Une ligne porte-t-elle une barre de vie : remplissage uni vert, jaune, orange ou rouge,
     *  en retrait des deux bords de la carte et long d'au moins un quart de sa largeur ? */
    static boolean barreDeVie(Img zone) {
        if (zone.vide()) return false;
        Img hsv = zone.hsv();
        int largeur = zone.w;
        for (int r = 0; r < zone.h; r++) {
            boolean[] ligne = new boolean[largeur];
            for (int x = 0; x < largeur; x++) {
                int i = (r * largeur + x) * 3;
                int t = hsv.p[i] & 0xff, s = hsv.p[i + 1] & 0xff, v = hsv.p[i + 2] & 0xff;
                ligne[x] = s > 120 && v > 110 && (t <= 28 || (t >= 48 && t <= 82) || t >= 168);
            }
            int a = -1;
            for (int x = 0; x < largeur / 4; x++) if (ligne[x]) { a = x; break; }
            if (a < 2) continue;
            int b = largeur;
            for (int x = a; x < largeur; x++) if (!ligne[x]) { b = x; break; }
            if (b - a >= 0.25 * largeur && b <= largeur - 3) {
                double maxStd = 0;
                for (int k = 0; k < 3; k++) {
                    double s = 0, s2 = 0;
                    for (int x = a; x < b; x++) {
                        int v = zone.v(x, r, k);
                        s += v;
                        s2 += (double) v * v;
                    }
                    double m = s / (b - a);
                    maxStd = Math.max(maxStd, Math.sqrt(Math.max(0, s2 / (b - a) - m * m)));
                }
                if (maxStd < 30) return true;
            }
        }
        return false;
    }

    /** Le cadre d'une carte posée monte haut au-dessus d'elle : au moins deux bords horizontaux
     *  nets qui s'arrêtent aux bords de la carte. */
    static boolean cadreAuDessus(Ecran ecran, Carte c) {
        int hc = c.y1 - c.y0, w = c.x1 - c.x0;
        int ya = c.y0 - (int) (0.29 * hc), yb = c.y0 - (int) (0.15 * hc);
        int xa = c.x0 - (int) (0.11 * w), xb = c.x1 + (int) (0.11 * w);
        if (ya < 2 || xa < 0 || xb > ecran.w) return false;
        Img z = ecran.zone(xa, ya - 2, xb, yb + 2).gris();
        if (z.vide() || z.h <= 4) return false;
        Mat m = z.mat(), a = new Mat(), b = new Mat();
        short[] g;
        try {
            m.convertTo(a, CvType.CV_16S);
            Imgproc.blur(a, b, new Size(5, 1));
            g = new short[z.w * z.h];
            b.get(0, 0, g);
        } finally {
            m.release();
            a.release();
            b.release();
        }
        int gw = z.w, gh = z.h - 4;
        int mm = (int) (0.09 * w), d = c.x0 - xa;
        int n = 0;
        for (int y = 0; y < gh; y++) {
            int dans = 0, nd = 0, hg = 0, ng = 0, hd = 0, ndr = 0;
            for (int x = 0; x < gw; x++) {
                boolean fort = Math.abs(g[(y + 4) * gw + x] - g[y * gw + x]) > 25;
                if (x >= d + mm && x < d + w - mm) { nd++; if (fort) dans++; }
                if (x < d - 3) { ng++; if (fort) hg++; }
                if (x >= d + w + 3) { ndr++; if (fort) hd++; }
            }
            double dedans = nd > 0 ? (double) dans / nd : Double.NaN;
            double gauche = ng > 0 ? (double) hg / ng : Double.NaN;
            double droite = ndr > 0 ? (double) hd / ndr : Double.NaN;
            double dehors = Double.isNaN(gauche) || Double.isNaN(droite) ? Double.NaN : Math.max(gauche, droite);
            if (dedans >= 0.7 && dehors < 0.5) n++;
        }
        return n >= 2;
    }

    static boolean motifPose(Ecran ecran, Carte c) {
        return barreDeVie(bandeDessus(ecran, c)) || cadreAuDessus(ecran, c);
    }

    /** Unité unique (héros, engin) posée ? Oui si sa carte est grise ou si sa barre de vie est
     *  apparue au-dessus d'elle, dans un cadre. */
    static boolean deploye(Ecran ecran, Carte c) {
        if (epuisee(ecran, c)) return true;
        return !bandeDessus(ecran, c).vide() && motifPose(ecran, c);
    }

    /** Avant toute touche : compteur de chaque carte, sorts, héros. */
    static void analyser(Ecran ecran, List<Carte> cartes) {
        for (Carte c : cartes) {
            c.nombre = Chiffres.lireCompteur(ecran, c);
            c.sort = identifierSort(ecran, c);
            c.heros = c.nombre == null && c.sort == null && cadreHeros(ecran, c);
        }
    }

    /** Rectangles où le déploiement ne doit jamais toucher. */
    static List<double[]> zonesInterdites(Ecran ecran, List<Carte> cartes, int[] boiteRouge, int[] boiteSuivant) {
        int w = ecran.w, h = ecran.h;
        int m = (int) (0.015 * w);
        int hautBarre = (int) (h * Config.BARRE[0]);
        if (!cartes.isEmpty()) {
            hautBarre = Integer.MAX_VALUE;
            for (Carte c : cartes) hautBarre = Math.min(hautBarre, c.y0);
        }
        List<double[]> zones = new ArrayList<>();
        zones.add(new double[]{0, hautBarre - m, w, h});
        zones.add(new double[]{0, 0, w, (int) (0.05 * h)});
        for (double[] u : Config.UI_FIXE) zones.add(new double[]{u[0] * w, u[1] * h, u[2] * w, u[3] * h});
        if (boiteSuivant == null) {
            Trouve t = Gabarits.localiser(ecran, "suivant");
            boiteSuivant = t != null ? t.boite : null;
        }
        List<int[]> boites = new ArrayList<>();
        boites.add(boiteRouge);
        if (boiteSuivant != null) boites.add(boiteSuivant);
        for (int[] b : boites) zones.add(new double[]{b[0] - m, b[1] - m, b[0] + b[2] + m, b[1] + b[3] + m});
        return zones;
    }

    // ── boutons repérés à leur couleur ──

    private static int[] plusGrosBouton(Img zone, int teinteMin, int teinteMax, boolean rouge, int satMin, int valMin,
                                        int noyau, double aireMin, double rMin, double rMax, double remplissage) {
        Img hsv = zone.hsv();
        int w = zone.w, h = zone.h;
        byte[] m = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            int t = hsv.p[i * 3] & 0xff, s = hsv.p[i * 3 + 1] & 0xff, v = hsv.p[i * 3 + 2] & 0xff;
            boolean teinte = rouge ? (t < 10 || t > 170) : (t >= teinteMin && t <= teinteMax);
            if (teinte && s > satMin && v > valMin) m[i] = 1;
        }
        Mat a = new Mat(h, w, CvType.CV_8UC1), b = new Mat(), k = Mat.ones(noyau, noyau, CvType.CV_8UC1);
        byte[] ferme = new byte[w * h];
        try {
            a.put(0, 0, m);
            Imgproc.morphologyEx(a, b, Imgproc.MORPH_CLOSE, k);
            b.get(0, 0, ferme);
        } finally {
            a.release();
            b.release();
            k.release();
        }
        Chiffres.Composantes cc = Chiffres.composantes(ferme, w, h, false);
        int choix = -1;
        for (int i = 1; i < cc.n; i++) {
            int bw = cc.lw(i), bh = cc.lh(i), ar = cc.aire(i);
            double r = (double) bw / Math.max(1, bh);
            if (ar >= aireMin && r >= rMin && r <= rMax && ar >= remplissage * bw * bh
                    && (choix < 0 || ar > cc.aire(choix))) choix = i;
        }
        return choix < 0 ? null : new int[]{cc.x(choix), cc.y(choix), cc.lw(choix), cc.lh(choix)};
    }

    /** Le bouton rouge « Capituler » / « Terminer la bataille » : {x, y, l, h} ou null. */
    static int[] boutonRouge(Ecran ecran) {
        int h = ecran.h, w = ecran.w;
        int ya = (int) (0.55 * h), xb = (int) (0.22 * w), yb = (int) (0.82 * h);
        Img z = ecran.zone(0, ya, xb, yb);
        if (z.vide()) return null;
        int[] b = plusGrosBouton(z, 0, 0, true, 130, 110, 7, 0.002 * w * h, 1.5, 5, 0);
        return b == null ? null : new int[]{b[0], b[1] + ya, b[2], b[3]};
    }

    /** Centre du plus gros bouton vert vif de la zone (fractions de l'écran), ou null. */
    static int[] boutonVert(Ecran ecran, double[] zone) {
        int h = ecran.h, w = ecran.w;
        int xa = (int) (zone[0] * w), ya = (int) (zone[1] * h), xb = (int) (zone[2] * w), yb = (int) (zone[3] * h);
        Img z = ecran.zone(xa, ya, xb, yb);
        if (z.vide()) return null;
        int[] b = plusGrosBouton(z, 35, 75, false, 140, 150, 9, 0.004 * w * h, 1.3, 5.5, 0.5);
        return b == null ? null : new int[]{(int) (xa + b[0] + b[2] / 2.0), (int) (ya + b[1] + b[3] / 2.0)};
    }

    static int[] boutonVert(Ecran ecran) { return boutonVert(ecran, new double[]{0.20, 0.30, 0.80, 0.94}); }
}

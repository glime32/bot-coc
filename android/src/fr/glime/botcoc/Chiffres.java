package fr.glime.botcoc;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lecture des chiffres du jeu : butin, « Dégâts généraux », compteurs « x50 », étoiles. */
final class Chiffres {
    private Chiffres() {}

    /** Masque binaire d'un caractère (valeurs 0 ou 1). */
    static final class Masque {
        final int w, h;
        final byte[] p;

        Masque(int w, int h) { this(w, h, new byte[Math.max(0, w) * Math.max(0, h)]); }

        Masque(int w, int h, byte[] p) {
            this.w = Math.max(0, w);
            this.h = Math.max(0, h);
            this.p = p;
        }

        boolean at(int x, int y) { return p[y * w + x] != 0; }

        int taille() { return w * h; }

        Masque colonnes(int a, int b) {
            a = Math.max(0, Math.min(w, a));
            b = Math.max(a, Math.min(w, b));
            Masque r = new Masque(b - a, h);
            for (int y = 0; y < h; y++) System.arraycopy(p, y * w + a, r.p, y * r.w, r.w);
            return r;
        }

        Masque lignes(int a, int b) {
            a = Math.max(0, Math.min(h, a));
            b = Math.max(a, Math.min(h, b));
            return new Masque(w, b - a, Arrays.copyOfRange(p, a * w, b * w));
        }
    }

    /** Un caractère lu : (caractère, score de 0 à 1). */
    static final class Lu {
        final String c;
        final double s;

        Lu(String c, double s) {
            this.c = c;
            this.s = s;
        }
    }

    private static Map<String, List<float[]>> glyphes;     // vecteurs centrés
    private static Map<String, List<Double>> normes;

    static synchronized Map<String, List<float[]>> modeles() {
        if (glyphes == null) {
            glyphes = new LinkedHashMap<>();
            normes = new LinkedHashMap<>();
            File[] fs = new File(Config.templates(), "glyphes").listFiles();
            if (fs != null) {
                String[] noms = new String[fs.length];
                for (int i = 0; i < fs.length; i++) noms[i] = fs[i].getPath();
                Arrays.sort(noms);
                for (String n : noms) {
                    if (!n.endsWith(".png")) continue;
                    Img img = Img.lire(new File(n), false);
                    if (img == null) continue;
                    String nom = new File(n).getName();
                    String c = nom.substring(0, nom.length() - 4).split("_")[0];
                    float[] v = new float[img.w * img.h];
                    double s = 0;
                    for (int i = 0; i < v.length; i++) {
                        v[i] = (img.p[i] & 0xff) / 255f;
                        s += v[i];
                    }
                    float m = (float) (s / v.length);
                    double n2 = 0;
                    for (int i = 0; i < v.length; i++) {
                        v[i] -= m;
                        n2 += (double) v[i] * v[i];
                    }
                    glyphes.computeIfAbsent(c, k -> new ArrayList<>()).add(v);
                    normes.computeIfAbsent(c, k -> new ArrayList<>()).add(Math.sqrt(n2));
                }
            }
        }
        return glyphes;
    }

    /** Composantes connexes (8 voisins) : {n, stats (x, y, w, h, aire) × n, étiquettes}. */
    static final class Composantes {
        final int n;
        final int[] stats;
        final int[] etiquettes;
        final int w;

        Composantes(int n, int[] stats, int[] etiquettes, int w) {
            this.n = n;
            this.stats = stats;
            this.etiquettes = etiquettes;
            this.w = w;
        }

        int x(int i) { return stats[i * 5]; }
        int y(int i) { return stats[i * 5 + 1]; }
        int lw(int i) { return stats[i * 5 + 2]; }
        int lh(int i) { return stats[i * 5 + 3]; }
        int aire(int i) { return stats[i * 5 + 4]; }
    }

    static Composantes composantes(byte[] masque, int w, int h, boolean etiquettes) {
        if (w == 0 || h == 0) return new Composantes(1, new int[5], new int[0], w);
        Mat m = new Mat(h, w, CvType.CV_8UC1), lab = new Mat(), st = new Mat(), cen = new Mat();
        try {
            m.put(0, 0, masque);
            int n = Imgproc.connectedComponentsWithStats(m, lab, st, cen, 8, CvType.CV_32S);
            int[] stats = new int[n * 5];
            st.get(0, 0, stats);
            int[] l = new int[0];
            if (etiquettes) {
                l = new int[w * h];
                lab.get(0, 0, l);
            }
            return new Composantes(n, stats, l, w);
        } finally {
            m.release();
            lab.release();
            st.release();
            cen.release();
        }
    }

    /** Caractères d'une ligne de texte du jeu (clairs, cernés de noir), de gauche à droite,
     *  découpés s'ils se touchent. */
    static List<Masque> glyphes(Img roi, double hRef) {
        List<Masque> sortie = new ArrayList<>();
        if (roi.vide()) return sortie;
        Img hsv = roi.hsv();
        int w = roi.w, h = roi.h;
        byte[] m = new byte[w * h];
        for (int i = 0; i < w * h; i++) {
            if ((hsv.p[i * 3 + 2] & 0xff) > 165 && (hsv.p[i * 3 + 1] & 0xff) < 90) m[i] = 1;
        }
        Composantes cc = composantes(m, w, h, true);
        List<Integer> cands = new ArrayList<>();
        for (int i = 1; i < cc.n; i++) {
            if (0.4 * hRef <= cc.lh(i) && cc.lh(i) <= 0.85 * hRef && cc.aire(i) >= 0.035 * hRef * hRef) cands.add(i);
        }
        if (cands.isEmpty()) return sortie;
        List<Integer> ys = new ArrayList<>(), hs = new ArrayList<>();
        for (int i : cands) {
            ys.add(cc.y(i));
            hs.add(cc.lh(i));
        }
        double haut = Img.mediane(ys), hh = Img.mediane(hs);
        List<Integer> gardes = new ArrayList<>();
        for (int i : cands) {
            if (Math.abs(cc.y(i) - haut) < 0.3 * hh && Math.abs(cc.lh(i) - hh) < 0.3 * hh) gardes.add(i);
        }
        gardes.sort((a, b) -> Integer.compare(cc.x(a), cc.x(b)));
        List<Integer> larges = new ArrayList<>();
        for (int i : gardes) if (cc.lw(i) > 0.45 * hh) larges.add(cc.lw(i));
        double lt = larges.isEmpty() ? 0.75 * hh : Img.mediane(larges);
        Integer finPrec = null;
        for (int i : gardes) {
            int x = cc.x(i), y = cc.y(i), cw = cc.lw(i), ch = cc.lh(i);
            if (finPrec != null && x - finPrec > 1.6 * lt) break;
            Masque mk = new Masque(cw, ch);
            for (int yy = 0; yy < ch; yy++) {
                for (int xx = 0; xx < cw; xx++) {
                    if (cc.etiquettes[(y + yy) * w + x + xx] == i) mk.p[yy * cw + xx] = 1;
                }
            }
            int k = cw > 1.45 * lt ? Img.rond(cw / lt) : 1;
            if (k <= 1) {
                sortie.add(mk);
            } else {
                // chiffres collés : en k - 1, k ou k + 1 morceaux (deux « 0 » collés sont plus larges
                // que deux chiffres moyens) ; on garde la découpe dont le morceau le moins
                // ressemblant ressemble le plus à un caractère
                List<Masque> mieux = null;
                double scoreMieux = 0;
                for (int n = k - 1; n <= k + 1; n++) {
                    if (n < 2) continue;
                    List<Masque> parts = couper(mk, n, lt);
                    double pire = parts.isEmpty() ? -1.0 : Double.MAX_VALUE;
                    for (Masque part : parts) pire = Math.min(pire, reconnaitre(part, null).s);
                    if (mieux == null || pire > scoreMieux) {
                        mieux = parts;
                        scoreMieux = pire;
                    }
                }
                sortie.addAll(mieux);
            }
            finPrec = x + cw;
        }
        return sortie;
    }

    /** Un masque de k caractères collés, coupé aux colonnes les plus creuses. */
    private static List<Masque> couper(Masque mk, int k, double lt) {
        int cw = mk.w, ch = mk.h;
        int[] cols = new int[cw];
        for (int yy = 0; yy < ch; yy++) for (int xx = 0; xx < cw; xx++) cols[xx] += mk.p[yy * cw + xx];
        List<Integer> coupes = new ArrayList<>();
        coupes.add(0);
        for (int j = 1; j < k; j++) {
            int c = (int) ((double) cw * j / k);
            int a = Math.max(1, c - (int) (0.25 * lt)), b = Math.min(cw - 1, c + (int) (0.25 * lt));
            if (b > a) {
                int best = a;
                for (int t = a; t < b; t++) if (cols[t] < cols[best]) best = t;
                coupes.add(best);
            } else {
                coupes.add(c);
            }
        }
        coupes.add(cw);
        List<Masque> parts = new ArrayList<>();
        for (int q = 0; q + 1 < coupes.size(); q++) {
            Masque part = mk.colonnes(coupes.get(q), coupes.get(q + 1));
            int premiere = -1, derniere = -1;
            for (int yy = 0; yy < part.h; yy++) {
                boolean plein = false;
                for (int xx = 0; xx < part.w && !plein; xx++) plein = part.at(xx, yy);
                if (plein) {
                    if (premiere < 0) premiere = yy;
                    derniere = yy;
                }
            }
            if (premiere >= 0) parts.add(part.lignes(premiere, derniere + 1));
        }
        return parts;
    }

    /** Masque d'un caractère centré dans un cadre fixe de 24×28 (proportions conservées). */
    static float[] normaliser(Masque mk) {
        int h = mk.h, w = mk.w;
        int largeur = Math.max(w, Img.rond(0.9 * h));
        float[] boite = new float[h * largeur];
        int dx = (largeur - w) / 2;
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) boite[y * largeur + dx + x] = mk.p[y * w + x];
        Mat a = new Mat(h, largeur, CvType.CV_32FC1), b = new Mat();
        try {
            a.put(0, 0, boite);
            Imgproc.resize(a, b, new Size(24, 28), 0, 0, Imgproc.INTER_AREA);
            float[] r = new float[24 * 28];
            b.get(0, 0, r);
            return r;
        } finally {
            a.release();
            b.release();
        }
    }

    static Lu reconnaitre(Masque mk, String alphabet) {
        Map<String, List<float[]>> modeles = modeles();
        if (mk.w == 0 || mk.h == 0) return new Lu(null, -1.0);
        float[] v = normaliser(mk);
        double s = 0;
        for (float f : v) s += f;
        float moy = (float) (s / v.length);
        double nv2 = 0;
        for (int i = 0; i < v.length; i++) {
            v[i] -= moy;
            nv2 += (double) v[i] * v[i];
        }
        double nv = Math.sqrt(nv2);
        String meilleurC = null;
        double meilleurS = -1.0;
        for (Map.Entry<String, List<float[]>> e : modeles.entrySet()) {
            String c = e.getKey();
            if (alphabet != null && !alphabet.contains(c)) continue;
            List<float[]> liste = e.getValue();
            List<Double> ns = normes.get(c);
            for (int q = 0; q < liste.size(); q++) {
                float[] t = liste.get(q);
                if (t.length != v.length) continue;
                double d = nv * ns.get(q);
                double dot = 0;
                for (int i = 0; i < v.length; i++) dot += (double) v[i] * t[i];
                double sc = d != 0 ? dot / d : 0.0;
                if (sc > meilleurS) {
                    meilleurS = sc;
                    meilleurC = c;
                }
            }
        }
        return new Lu(meilleurC, meilleurS);
    }

    /** Plus grande tache pleine et à peu près ronde d'un masque : {x, y, w, h} ou null. */
    static int[] tache(byte[] masque, int w, int h, double aireMin) {
        Composantes cc = composantes(masque, w, h, false);
        int choix = -1;
        for (int i = 1; i < cc.n; i++) {
            int lw = cc.lw(i), lh = cc.lh(i), a = cc.aire(i);
            double r = (double) lw / lh;
            if (a >= aireMin && r >= 0.7 && r <= 1.4 && a >= 0.55 * lw * lh && (choix < 0 || a > cc.aire(choix))) choix = i;
        }
        return choix < 0 ? null : new int[]{cc.x(choix), cc.y(choix), cc.lw(choix), cc.lh(choix)};
    }

    /** Chiffres (masques) des trois lignes du butin disponible : or, élixir, élixir noir ; null
     *  si l'écran n'affiche pas de butin. */
    static List<List<Masque>> lignesButin(Ecran ecran) {
        Img zone = ecran.zone(0, 0, (int) (0.25 * ecran.w), (int) (0.40 * ecran.h));
        Img hsv = zone.hsv();
        int w = zone.w, h = zone.h, n = w * h;
        double aireMin = 0.002 * h * w;
        byte[] piece = new byte[n], goutte = new byte[n];
        for (int i = 0; i < n; i++) {
            int t = hsv.p[i * 3] & 0xff, s = hsv.p[i * 3 + 1] & 0xff, v = hsv.p[i * 3 + 2] & 0xff;
            if (t >= 15 && t <= 35 && s > 140 && v > 170) piece[i] = 1;
            if (t >= 135 && t <= 170 && s > 110 && v > 120) goutte[i] = 1;
        }
        int[] p = tache(piece, w, h, aireMin);
        if (p == null) return null;
        int x = p[0], y = p[1], pw = p[2], ph = p[3];
        double pas = 1.48 * ph;
        int[] g = tache(goutte, w, h, 0.6 * aireMin);
        if (g != null) {
            double dy = g[1] + g[3] / 2.0 - (y + ph / 2.0);
            if (Math.abs(g[0] + g[2] / 2.0 - (x + pw / 2.0)) < pw && 1.1 * ph < dy && dy < 2.0 * ph) pas = dy;
        }
        List<List<Masque>> lignes = new ArrayList<>();
        for (int k = 0; k < 3; k++) {
            double cy = y + ph / 2.0 + k * pas;
            Img roi = zone.crop((int) (x + 1.1 * pw), Math.max(0, (int) (cy - 0.62 * ph)), (int) (x + 7.5 * pw), (int) (cy + 0.62 * ph));
            lignes.add(roi.vide() ? new ArrayList<>() : glyphes(roi, ph));
        }
        return lignes;
    }

    /** Masque débarrassé de ses lignes et colonnes vides. */
    static Masque rogner(Masque mk) {
        int l0 = -1, l1 = -1, c0 = -1, c1 = -1;
        for (int y = 0; y < mk.h; y++) {
            for (int x = 0; x < mk.w; x++) {
                if (mk.at(x, y)) {
                    if (l0 < 0) l0 = y;
                    l1 = y;
                    if (c0 < 0 || x < c0) c0 = x;
                    if (x > c1) c1 = x;
                }
            }
        }
        if (l0 < 0) return mk;
        return mk.lignes(l0, l1 + 1).colonnes(c0, c1 + 1);
    }

    static final String CHIFFRES = "0123456789";
    /** Au-delà, une lecture du butin est fausse (chiffres mal coupés). */
    static final long BUTIN_MAX = 20_000_000;

    /** Un glyphe large et mal reconnu est essayé coupé en deux à chaque colonne. */
    static List<Lu> lireChiffres(List<Masque> masques) {
        List<Lu> sortie = new ArrayList<>();
        for (Masque m : masques) {
            Lu lu = reconnaitre(m, CHIFFRES);
            int h = m.h, w = m.w;
            if (lu.s < 0.65 && w > 0.9 * h) {
                Lu[] meilleure = null;
                for (int x = (int) (0.2 * w); x <= (int) (0.8 * w); x++) {
                    Masque g = rogner(m.colonnes(0, x)), d = rogner(m.colonnes(x, w));
                    if (g.taille() > 0 && d.taille() > 0) {
                        Lu[] paire = {reconnaitre(g, CHIFFRES), reconnaitre(d, CHIFFRES)};
                        if (meilleure == null || Math.min(paire[0].s, paire[1].s) > Math.min(meilleure[0].s, meilleure[1].s)) {
                            meilleure = paire;
                        }
                    }
                }
                if (meilleure != null && Math.min(meilleure[0].s, meilleure[1].s) >= 0.65) {
                    sortie.add(meilleure[0]);
                    sortie.add(meilleure[1]);
                    continue;
                }
            }
            sortie.add(lu);
        }
        return sortie;
    }

    private static Long nombre(List<Lu> lus, double mini) {
        if (lus.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (Lu l : lus) {
            if (l.s < mini || l.c == null) return null;
            sb.append(l.c);
        }
        try {
            return Long.parseLong(sb.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static final class Butin {
        final long or, elixir, noir;

        Butin(long or, long elixir, long noir) {
            this.or = or;
            this.elixir = elixir;
            this.noir = noir;
        }

        long total() { return or + elixir + (long) Config.POIDS_NOIR * noir; }

        boolean suffisant() {
            return total() >= Config.butinMinTotal
                    && (Config.minOr < 0 || or >= Config.minOr)
                    && (Config.minElixir < 0 || elixir >= Config.minElixir)
                    && (Config.minNoir < 0 || noir >= Config.minNoir);
        }

        static boolean parRessource() { return Config.minOr >= 0 || Config.minElixir >= 0 || Config.minNoir >= 0; }

        @Override
        public String toString() {
            String t = espaces(or) + " or · " + espaces(elixir) + " élixir · " + espaces(noir) + " noir";
            if (!parRessource()) t += " = " + espaces(total()) + " au total";
            return t;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Butin && ((Butin) o).or == or && ((Butin) o).elixir == elixir && ((Butin) o).noir == noir;
        }

        @Override
        public int hashCode() { return Long.hashCode(or * 31 + elixir * 7 + noir); }
    }

    static String espaces(long n) {
        String s = Long.toString(n);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) sb.append(' ');
            sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    static String regleButin() {
        String t;
        if (Butin.parRessource()) {
            List<String> parts = new ArrayList<>();
            if (Config.minOr >= 0) parts.add("or ≥ " + espaces(Config.minOr));
            if (Config.minElixir >= 0) parts.add("élixir ≥ " + espaces(Config.minElixir));
            if (Config.minNoir >= 0) parts.add("noir ≥ " + espaces(Config.minNoir));
            t = "attaque si " + String.join(" et ", parts);
            if (Config.butinMinTotal > 0) t += " et or + élixir + " + Config.POIDS_NOIR + " × noir ≥ " + espaces(Config.butinMinTotal);
        } else if (Config.butinMinTotal > 0) {
            t = "attaque si or + élixir + " + Config.POIDS_NOIR + " × noir ≥ " + espaces(Config.butinMinTotal);
        } else {
            t = "attaque le premier village trouvé (pas de butin minimum)";
        }
        return t;
    }

    /** Butin disponible affiché en haut à gauche pendant l'observation, ou null. */
    static Butin lireButin(Ecran ecran) {
        List<List<Masque>> lignes = lignesButin(ecran);
        if (lignes == null) return null;
        long[] valeurs = new long[3];
        for (int k = 0; k < 3; k++) {
            List<Lu> lus = lireChiffres(lignes.get(k));
            Long n = nombre(lus, 0.65);
            if (n != null) {
                valeurs[k] = n;
            } else if (k == 2) {
                valeurs[k] = 0;
            } else {
                return null;
            }
        }
        if (Math.max(valeurs[0], valeurs[1]) > BUTIN_MAX || valeurs[2] > BUTIN_MAX / 50) return null;
        return new Butin(valeurs[0], valeurs[1], valeurs[2]);
    }

    /** « Dégâts généraux » (0 à 100) en bas à droite pendant un combat, ou null. */
    static Integer lireDegats(Ecran ecran) {
        int w = ecran.w, h = ecran.h;
        Img roi = ecran.zone(0.885 * w, 0.722 * h, 0.956 * w, 0.780 * h);
        Long v = nombre(lireChiffres(glyphes(roi, 0.042 * h)), 0.65);
        return v != null && v <= 100 ? (int) (long) v : null;
    }

    /** Étoiles gagnées (0 à 3), à gauche des « Dégâts généraux ». */
    static int etoiles(Ecran ecran) {
        int w = ecran.w, h = ecran.h, n = 0;
        for (double fx : new double[]{0.8573, 0.8782, 0.8983}) {
            Img z = ecran.zone((fx - 0.005) * w, 0.737 * h, (fx + 0.005) * w, 0.759 * h);
            if (z.vide()) continue;
            Img hsv = z.hsv();
            int clairs = 0;
            for (int i = 0; i < z.w * z.h; i++) if ((hsv.p[i * 3 + 2] & 0xff) > 170) clairs++;
            if ((double) clairs / (z.w * z.h) > 0.3) n++;
        }
        return n;
    }

    /** Nombre en haut d'une carte (« x50 » → 50), ou null (héros, engin de siège…). */
    static Integer lireCompteur(Ecran ecran, Carte carte) {
        Integer n = lireCompteur(ecran, carte, 0.2);
        return n != null ? n : lireCompteur(ecran, carte, 0.05);
    }

    /** La zone lue déborde de `haut` (fraction de la carte) au-dessus d'elle : une carte
     *  sélectionnée remonte un peu. Si le décor au-dessus gêne, on relit sans le débord. */
    static Integer lireCompteur(Ecran ecran, Carte carte, double haut) {
        int w = carte.x1 - carte.x0, h = carte.y1 - carte.y0;
        Img roi = ecran.zone(carte.x0 + (int) (0.25 * w), Math.max(0, carte.y0 - (int) (haut * h)), carte.x1, carte.y0 + (int) (0.3 * h));
        List<Lu> lus = new ArrayList<>();
        for (Masque m : glyphes(roi, 0.2 * h)) lus.add(reconnaitre(m, null));
        for (int i = 0; i < lus.size(); i++) {
            Lu l = lus.get(i);
            if ("x".equals(l.c) && l.s >= 0.75) {
                List<Lu> chiffres = lus.subList(i + 1, lus.size());
                if (chiffres.isEmpty()) return null;
                StringBuilder sb = new StringBuilder();
                for (Lu d : chiffres) {
                    if (d.c == null || !CHIFFRES.contains(d.c) || d.c.length() != 1 || d.s < 0.65) return null;
                    sb.append(d.c);
                }
                try {
                    return Integer.parseInt(sb.toString());
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }
}

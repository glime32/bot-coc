package fr.glime.botcoc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** La carte vue dans les deux positions fixes de la caméra (dézoom maximal, en butée). Une case
 *  (i, j) : i va du bord haut-droite (i0) au bord bas-gauche (i1), j du bord haut-gauche (j0) au
 *  bord bas-droite (j1). En vue haut : point = sommet + i·(-a, b) + j·(a, b) ; la vue bas est la
 *  vue haut décalée. Bords gardés dans templates/carte.json. */
final class Plan {
    static final String[] CLES = {"i0", "j0", "i1", "j1"};

    static String nomBord(String cle) {
        switch (cle) {
            case "i0": return "haut-droite";
            case "j0": return "haut-gauche";
            case "i1": return "bas-gauche";
            default: return "bas-droite";
        }
    }

    final int w, h;
    final double kx, ky;
    double[] ecranRef = {2340, 1080};
    double[] sommet = {1170.0, 115.0};
    double[] cas = {21.2, 15.84};
    double[] phase = {0.0, 0.43};
    double[] decalageBas = {-70.0, -614.0};
    final JSONObject bords = new JSONObject();
    final JSONObject trouves = new JSONObject();
    final List<double[]> decalagesMesures = new ArrayList<>();
    double[] decalage;
    private JSONObject brut = new JSONObject();

    private static File fichier() { return new File(Config.templates(), "carte.json"); }

    private static double[] tableau(JSONArray a, double[] defaut) {
        if (a == null || a.length() < 2) return defaut;
        return new double[]{a.optDouble(0, defaut[0]), a.optDouble(1, defaut[1])};
    }

    Plan(int w, int h) {
        this.w = w;
        this.h = h;
        try {
            bords.put("i0", -1.5).put("j0", -2.07).put("i1", 42.5).put("j1", 41.93);
            for (String k : CLES) trouves.put(k, false);
        } catch (Exception ignored) {
        }
        File f = fichier();
        if (f.isFile()) {
            try {
                JSONObject lu = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
                brut = lu;
                ecranRef = tableau(lu.optJSONArray("ecran"), ecranRef);
                sommet = tableau(lu.optJSONArray("sommet"), sommet);
                cas = tableau(lu.optJSONArray("case"), cas);
                phase = tableau(lu.optJSONArray("phase"), phase);
                decalageBas = tableau(lu.optJSONArray("decalage_bas"), decalageBas);
                JSONObject b = lu.optJSONObject("bords");
                if (b != null) for (Iterator<String> it = b.keys(); it.hasNext(); ) {
                    String k = it.next();
                    bords.put(k, b.getDouble(k));
                }
                JSONObject t = lu.optJSONObject("trouves");
                if (t != null) for (Iterator<String> it = t.keys(); it.hasNext(); ) {
                    String k = it.next();
                    trouves.put(k, t.getBoolean(k));
                }
                JSONArray m = lu.optJSONArray("decalages_mesures");
                if (m != null) for (int i = 0; i < m.length(); i++) {
                    JSONArray p = m.getJSONArray(i);
                    decalagesMesures.add(new double[]{p.getDouble(0), p.getDouble(1)});
                }
            } catch (Exception e) {
                Journal.log("  templates/carte.json illisible : je repars des valeurs mesurées");
            }
        }
        kx = w / ecranRef[0];
        ky = h / ecranRef[1];
        decalage = decalageBas.clone();
    }

    double bord(String cle) { return bords.optDouble(cle); }

    boolean trouve(String cle) { return trouves.optBoolean(cle); }

    void sauver() {
        try {
            JSONObject d = brut;
            d.put("ecran", new JSONArray(ecranRef));
            d.put("sommet", new JSONArray(sommet));
            d.put("case", new JSONArray(cas));
            d.put("phase", new JSONArray(phase));
            d.put("decalage_bas", new JSONArray(decalageBas));
            d.put("bords", bords);
            d.put("trouves", trouves);
            JSONArray m = new JSONArray();
            for (double[] p : decalagesMesures) m.put(new JSONArray(p));
            d.put("decalages_mesures", m);
            Files.write(fichier().toPath(), d.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** Une mesure de la vue bas : utilisée pour ce combat, et gardée (les 9 dernières) pour que
     *  la position habituelle soit leur médiane. */
    void noterDecalage(double[] mesure) {
        decalage = new double[]{Math.round(mesure[0] * 10) / 10.0, Math.round(mesure[1] * 10) / 10.0};
        while (decalagesMesures.size() > 8) decalagesMesures.remove(0);
        decalagesMesures.add(decalage.clone());
        double[] xs = new double[decalagesMesures.size()], ys = new double[decalagesMesures.size()];
        for (int i = 0; i < xs.length; i++) {
            xs[i] = decalagesMesures.get(i)[0];
            ys[i] = decalagesMesures.get(i)[1];
        }
        decalageBas = new double[]{Img.mediane(xs), Img.mediane(ys)};
        sauver();
    }

    double[] ecran(String vue, double i, double j) {
        double a = cas[0], b = cas[1];
        double x = sommet[0] - a * i + a * j, y = sommet[1] + b * i + b * j;
        if (vue.equals("bas")) {
            x += decalage[0];
            y += decalage[1];
        }
        return new double[]{x * kx, y * ky};
    }

    boolean libre(List<double[]> zones, double x, double y) {
        if (!(0.01 * w < x && x < 0.99 * w && 0 < y && y < h)) return false;
        for (double[] z : zones) if (z[0] <= x && x <= z[2] && z[1] <= y && y <= z[3]) return false;
        return true;
    }

    double[] centre() {
        return new double[]{(bord("i0") + bord("i1")) / 2, (bord("j0") + bord("j1")) / 2};
    }

    static List<Double> pasDe(double debut, double fin, double pas) {
        int n = (int) Math.floor((fin - debut) / pas + 1e-9) + 1;
        List<Double> r = new ArrayList<>();
        for (int k = 0; k < Math.max(0, n); k++) r.add(debut + k * pas);
        return r;
    }

    /** Cases le long des deux bords visibles dans cette vue, à « retrait » cases du bord. */
    List<double[]> rangee(String vue, double retrait) {
        List<double[]> cases = new ArrayList<>();
        if (vue.equals("haut")) {
            double j = bord("j0") + retrait;
            for (double i : pasDe(bord("i1") - 0.5, bord("i0") + retrait, -1)) cases.add(new double[]{i, j});
            double i = bord("i0") + retrait;
            for (double jj : pasDe(bord("j0") + retrait + 1, bord("j1") - 0.5, 1)) cases.add(new double[]{i, jj});
        } else {
            double i = bord("i1") - retrait;
            for (double jj : pasDe(bord("j0") + 0.5, bord("j1") - retrait, 1)) cases.add(new double[]{i, jj});
            double j = bord("j1") - retrait;
            for (double ii : pasDe(bord("i1") - retrait - 1, bord("i0") + 0.5, -1)) cases.add(new double[]{ii, j});
        }
        return cases;
    }

    /** Points d'écran de la rangée du bord, hors boutons et affichages. */
    List<int[]> chemin(String vue, List<double[]> zones, double retrait) {
        List<int[]> points = new ArrayList<>();
        for (double[] c : rangee(vue, retrait)) {
            double[] p = ecran(vue, c[0], c[1]);
            if (libre(zones, p[0], p[1])) points.add(new int[]{Img.rond(p[0]), Img.rond(p[1])});
        }
        return points;
    }

    /** Cases à essayer pour trouver un bord, de l'extérieur de la carte vers l'intérieur. */
    List<double[]> sondes(String cle, int position) {
        double pi = phase[0], pj = phase[1];
        List<double[]> r = new ArrayList<>();
        switch (cle) {
            case "i0": for (int k = -4; k < 2; k++) r.add(new double[]{pi + k, pj + position}); break;
            case "j0": for (int k = -4; k < 2; k++) r.add(new double[]{pi + position, pj + k}); break;
            case "i1": for (int k = 46; k > 40; k--) r.add(new double[]{pi + k, pj + position}); break;
            default: for (int k = 46; k > 40; k--) r.add(new double[]{pi + position, pj + k}); break;
        }
        return r;
    }

    /** La première case acceptée (en venant de l'extérieur) est la rangée du bord. */
    void fixerBord(String cle, double[] cas) {
        try {
            double valeur = cle.charAt(0) == 'i' ? cas[0] : cas[1];
            bords.put(cle, cle.endsWith("0") ? valeur - 0.5 : valeur + 0.5);
            trouves.put(cle, true);
            for (String[] paire : new String[][]{{"i0", "i1"}, {"j0", "j1"}}) {
                String a0 = paire[0], a1 = paire[1];
                if (trouve(a0) && trouve(a1)) {
                    if (bord(a1) - bord(a0) < 43.5) {
                        double b0 = bord(a0), b1 = bord(a1);
                        bords.put(a0, Math.min(b0, b1 - 44));
                        bords.put(a1, Math.max(b1, b0 + 44));
                    }
                } else if (trouve(a0)) {
                    bords.put(a1, bord(a0) + 44);
                } else if (trouve(a1)) {
                    bords.put(a0, bord(a1) - 44);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Un bord trouvé donne le bord opposé (44 cases plus loin). True si tout est connu. */
    boolean completerBords() {
        try {
            for (String[] paire : new String[][]{{"i0", "i1"}, {"j0", "j1"}}) {
                if (trouve(paire[0]) || trouve(paire[1])) {
                    trouves.put(paire[0], true);
                    trouves.put(paire[1], true);
                }
            }
        } catch (Exception ignored) {
        }
        for (String k : CLES) if (!trouve(k)) return false;
        return true;
    }
}

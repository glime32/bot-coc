package fr.glime.botcoc;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.util.Arrays;

/** Image 8 bits (1 canal gris ou 3 canaux BGR) dans un tableau Java : le ramasse-miettes s'en
 *  occupe. OpenCV ne travaille que sur des copies temporaires, libérées tout de suite. */
final class Img {
    final int w, h, c;
    final byte[] p;

    Img(int w, int h, int c) { this(w, h, c, new byte[Math.max(0, w) * Math.max(0, h) * c]); }

    Img(int w, int h, int c, byte[] p) {
        this.w = Math.max(0, w);
        this.h = Math.max(0, h);
        this.c = c;
        this.p = p;
    }

    boolean vide() { return w == 0 || h == 0; }

    int taille() { return w * h * c; }

    int v(int x, int y, int k) { return p[(y * w + x) * c + k] & 0xff; }

    int v(int x, int y) { return p[(y * w + x) * c] & 0xff; }

    /** Le morceau [y0:y1, x0:x1], bornes ramenées dans l'image comme les tranches numpy. */
    Img crop(int x0, int y0, int x1, int y1) {
        x0 = Math.max(0, Math.min(w, x0));
        x1 = Math.max(x0, Math.min(w, x1));
        y0 = Math.max(0, Math.min(h, y0));
        y1 = Math.max(y0, Math.min(h, y1));
        int nw = x1 - x0, nh = y1 - y0;
        Img r = new Img(nw, nh, c);
        for (int y = 0; y < nh; y++) {
            System.arraycopy(p, ((y0 + y) * w + x0) * c, r.p, y * nw * c, nw * c);
        }
        return r;
    }

    Mat mat() {
        Mat m = new Mat(h, w, c == 1 ? CvType.CV_8UC1 : CvType.CV_8UC3);
        if (!vide()) m.put(0, 0, p);
        return m;
    }

    static Img de(Mat m) {
        int c = m.channels();
        byte[] b = new byte[(int) m.total() * c];
        if (b.length > 0) {
            if (m.isContinuous()) {
                m.get(0, 0, b);
            } else {
                Mat copie = m.clone();
                copie.get(0, 0, b);
                copie.release();
            }
        }
        return new Img(m.cols(), m.rows(), c, b);
    }

    Img convertir(int code) {
        if (vide()) return new Img(w, h, code == Imgproc.COLOR_BGR2GRAY ? 1 : 3);
        Mat a = mat(), b = new Mat();
        try {
            Imgproc.cvtColor(a, b, code);
            return de(b);
        } finally {
            a.release();
            b.release();
        }
    }

    Img gris() { return c == 1 ? this : convertir(Imgproc.COLOR_BGR2GRAY); }

    Img hsv() { return convertir(Imgproc.COLOR_BGR2HSV); }

    Img redim(int nw, int nh, int interp) {
        Mat a = mat(), b = new Mat();
        try {
            Imgproc.resize(a, b, new Size(nw, nh), 0, 0, interp);
            return de(b);
        } finally {
            a.release();
            b.release();
        }
    }

    /** cv2.resize(img, None, fx=f, fy=f) : même calcul de la taille d'arrivée qu'OpenCV. */
    Img facteur(double f, int interp) {
        Mat a = mat(), b = new Mat();
        try {
            Imgproc.resize(a, b, new Size(0, 0), f, f, interp);
            return de(b);
        } finally {
            a.release();
            b.release();
        }
    }

    double[] moyenne() {
        double[] s = new double[c];
        int n = w * h;
        if (n == 0) return s;
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < c; k++) s[k] += p[i * c + k] & 0xff;
        }
        for (int k = 0; k < c; k++) s[k] /= n;
        return s;
    }

    /** Écart type de toutes les valeurs (canaux confondus), comme img.std(). */
    double ecartType() {
        int n = p.length;
        if (n == 0) return 0;
        double s = 0, s2 = 0;
        for (byte b : p) {
            int v = b & 0xff;
            s += v;
            s2 += (double) v * v;
        }
        double m = s / n;
        return Math.sqrt(Math.max(0, s2 / n - m * m));
    }

    static Img lire(File f, boolean couleur) {
        if (!f.isFile()) return null;
        Mat m = Imgcodecs.imread(f.getPath(), couleur ? Imgcodecs.IMREAD_COLOR : Imgcodecs.IMREAD_GRAYSCALE);
        try {
            return m.empty() ? null : de(m);
        } finally {
            m.release();
        }
    }

    boolean ecrire(File f) {
        f.getParentFile().mkdirs();
        Mat m = mat();
        try {
            return Imgcodecs.imwrite(f.getPath(), m);
        } finally {
            m.release();
        }
    }

    Img copie() { return new Img(w, h, c, p.clone()); }

    // ── petits calculs à la numpy ──

    static double mediane(double[] v) {
        if (v.length == 0) return Double.NaN;
        double[] t = v.clone();
        Arrays.sort(t);
        int n = t.length;
        return n % 2 == 1 ? t[n / 2] : (t[n / 2 - 1] + t[n / 2]) / 2.0;
    }

    static double mediane(java.util.List<? extends Number> v) {
        double[] t = new double[v.size()];
        for (int i = 0; i < t.length; i++) t[i] = v.get(i).doubleValue();
        return mediane(t);
    }

    /** round() de Python : au plus proche, et à égalité vers le pair. */
    static int rond(double x) { return (int) Math.rint(x); }

    /** Plages [début, fin) où un masque 1D est vrai. */
    static java.util.List<int[]> segments(boolean[] m) {
        java.util.List<int[]> r = new java.util.ArrayList<>();
        int debut = -1;
        for (int i = 0; i <= m.length; i++) {
            boolean v = i < m.length && m[i];
            if (v && debut < 0) debut = i;
            if (!v && debut >= 0) {
                r.add(new int[]{debut, i});
                debut = -1;
            }
        }
        return r;
    }
}

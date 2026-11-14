package fr.glime.botcoc;

import org.opencv.imgproc.Imgproc;

/** Une capture. Celle du partage d'écran arrive à la moitié de la taille de l'écran : l'image
 *  pleine taille n'est fabriquée qu'au premier accès à img(), gris() sort de la petite et
 *  zone() n'agrandit que le morceau demandé. Coordonnées toujours en pixels de l'écran. */
final class Ecran {
    final int w, h;
    private final Img petite;
    private Img img, gris, gris4;

    Ecran(Img pleine) {
        this.img = pleine;
        this.petite = null;
        this.w = pleine.w;
        this.h = pleine.h;
    }

    Ecran(Img petite, int w, int h) {
        this.petite = petite;
        this.w = w;
        this.h = h;
    }

    synchronized Img img() {
        if (img == null) img = petite.redim(w, h, Imgproc.INTER_LINEAR);
        return img;
    }

    synchronized Img gris() {
        if (gris == null) {
            Img p = petite;
            if (p != null && p.w == Img.rond(w * Config.REDUCTION) && p.h == Img.rond(h * Config.REDUCTION)) {
                gris = p.gris();
            } else {
                gris = img().gris().facteur(Config.REDUCTION, Imgproc.INTER_AREA);
            }
        }
        return gris;
    }

    /** Le gris réduit encore de moitié (quart de l'écran) : premier passage des recherches. */
    synchronized Img gris4() {
        if (gris4 == null) gris4 = gris().facteur(0.5, Imgproc.INTER_AREA);
        return gris4;
    }

    Img zone(double fx0, double fy0, double fx1, double fy1) {
        int x0 = Math.max(0, (int) fx0), y0 = Math.max(0, (int) fy0);
        int x1 = Math.min(w, (int) fx1), y1 = Math.min(h, (int) fy1);
        Img deja;
        synchronized (this) { deja = img; }
        if (deja != null || petite == null || x1 <= x0 || y1 <= y0) {
            return img().crop(x0, y0, x1, y1);
        }
        double kx = (double) petite.w / w, ky = (double) petite.h / h;
        int a = Math.max(0, (int) (x0 * kx) - 1), b = Math.max(0, (int) (y0 * ky) - 1);
        int c = Math.min(petite.w, (int) Math.ceil(x1 * kx) + 1);
        int d = Math.min(petite.h, (int) Math.ceil(y1 * ky) + 1);
        Img grand = petite.crop(a, b, c, d).redim(Img.rond((c - a) / kx), Img.rond((d - b) / ky), Imgproc.INTER_LINEAR);
        int ox = x0 - Img.rond(a / kx), oy = y0 - Img.rond(b / ky);
        return grand.crop(ox, oy, ox + (x1 - x0), oy + (y1 - y0));
    }
}

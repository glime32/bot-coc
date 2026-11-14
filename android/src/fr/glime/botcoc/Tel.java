package fr.glime.botcoc;

/** Ce que le bot sait faire avec le téléphone : voir l'écran et le toucher. */
abstract class Tel {
    boolean dezoomVerifie = false;

    abstract Ecran capture();

    /** Touche les points dans l'ordre (x, y en pixels de l'écran). */
    abstract void toucher(int[]... points);

    /** Pareil, avec une pause plus longue entre deux touches. */
    void toucherLent(long ecartMs, int[]... points) { toucher(points); }

    /** Un doigt qui glisse : déplace la caméra (ne pose rien). */
    abstract void glisser(double x1, double y1, double x2, double y2, long ms, long tenirMs);

    /** Pincement à deux doigts ; false si le téléphone ne sait pas le faire. */
    abstract boolean pincer(boolean dezoom);

    abstract int[] taille();

    /** Les images arrivent vite (partage d'écran) : on peut guetter un écran qui se fige. */
    boolean video() { return true; }

    /** Le jeu ne répond plus : on le remet au premier plan. */
    boolean relancerJeu() { return false; }

    /** Pause qui s'interrompt si l'arrêt est demandé. */
    void dormir(double secondes) {
        Controle.verifier();
        if (secondes <= 0) return;
        try {
            Thread.sleep((long) (secondes * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Arret();
        }
        Controle.verifier();
    }

    static double maintenant() { return System.nanoTime() / 1e9; }
}

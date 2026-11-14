package fr.glime.botcoc;

/** Arrêt demandé (bouton, notification, volume bas) : remonte jusqu'à la boucle. */
final class Arret extends RuntimeException {
    Arret() { super("arrêt demandé"); }
}

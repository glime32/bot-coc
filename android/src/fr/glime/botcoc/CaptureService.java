package fr.glime.botcoc;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.WindowManager;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;

/** Partage d'écran : une image fraîche de l'écran à la demande, en demi-taille (1170×540).
 *  Tant que personne ne demande d'image, le partage ne copie rien. */
public final class CaptureService extends Service {
    static final String DEMARRER = "fr.glime.botcoc.DEMARRER";
    static final String ARRETER = "fr.glime.botcoc.ARRETER";
    static final String FERMER = "fr.glime.botcoc.FERMER";
    private static final String CANAL = "bot";
    private static final String CANAL_FIN = "fin";
    private static final int NOTIF = 1, NOTIF_FIN = 2;

    static volatile CaptureService instance;

    private MediaProjection projection;
    private VirtualDisplay affichage;
    private ImageReader lecteur;
    private HandlerThread fil;
    private Handler handler;
    private final Object verrou = new Object();
    private long images = 0;
    private int largeur, hauteur;
    private boolean enVeille = false;
    private Img derniere;
    private long demandes = 0, reveils = 0, attenteTotale = 0;
    private final Controle.Ecouteur ecouteur = this::majNotification;
    private Controle.Etat etatPrecedent = Controle.Etat.ARRETE;

    static boolean actif() {
        CaptureService s = instance;
        return s != null && s.affichage != null;
    }

    /** Taille réelle de l'écran, en paysage. */
    static int[] tailleEcran(Context c) {
        WindowManager wm = c.getSystemService(WindowManager.class);
        android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
        return new int[]{Math.max(b.width(), b.height()), Math.min(b.width(), b.height())};
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        Config.opencv();
        Fichiers.installer(this);
        Acces.assurer(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CANAL, "Bot en marche", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CANAL_FIN, "Fin du bot", NotificationManager.IMPORTANCE_DEFAULT));
        fil = new HandlerThread("capture");
        fil.start();
        handler = new Handler(fil.getLooper());
        Controle.ecouter(ecouteur);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ARRETER.equals(action)) {
            Controle.arreter();
            return START_NOT_STICKY;
        }
        if (FERMER.equals(action)) {
            Controle.arreter();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        if (DEMARRER.equals(action) && affichage == null) {
            int code = intent.getIntExtra("code", 0);
            Intent data = intent.getParcelableExtra("data", Intent.class);
            if (!demarrer(code, data)) {
                Journal.log("Partage d'écran refusé.");
                stopSelf();
                return START_NOT_STICKY;
            }
            instance = this;
            if (!intent.getBooleanExtra("lancer", false)) veille(true);
        }
        if (intent != null && intent.getBooleanExtra("lancer", false) && affichage != null) {
            Controle.lancer(this, intent.getBundleExtra("essai"));
        }
        return START_NOT_STICKY;
    }

    private boolean demarrer(int code, Intent data) {
        if (data == null) return false;
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        projection = mpm.getMediaProjection(code, data);
        if (projection == null) return false;
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                Journal.log("Partage d'écran arrêté.");
                Controle.arreter();
                fermer();
                stopSelf();
            }
        }, handler);
        int[] t = tailleEcran(this);
        largeur = t[0] / 2;
        hauteur = t[1] / 2;
        lecteur = ImageReader.newInstance(largeur, hauteur, PixelFormat.RGBA_8888, 3);
        lecteur.setOnImageAvailableListener(r -> {
            synchronized (verrou) {
                images++;
                verrou.notifyAll();
            }
        }, handler);
        affichage = projection.createVirtualDisplay("botcoc", largeur, hauteur, 225,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, lecteur.getSurface(), null, handler);
        return affichage != null;
    }

    /** Une image arrivée après l'appel (les anciennes sont jetées), en BGR demi-taille ; null si
     *  rien n'arrive en `delaiMs` (écran éteint, partage coupé). Si l'écran ne change pas, la
     *  dernière image reste la bonne. */
    /** Bot arrêté : plus aucune image n'est produite (aucun calcul ni réveil) ; la prochaine
     *  demande d'image rallume le partage. */
    void veille(boolean oui) {
        synchronized (verrou) {
            if (affichage == null || lecteur == null || oui == enVeille) return;
            affichage.setSurface(oui ? null : lecteur.getSurface());
            enVeille = oui;
        }
    }

    String bilan() {
        synchronized (verrou) {
            return demandes == 0 ? "aucune image" : String.format(java.util.Locale.FRANCE,
                    "%d images, attente moyenne %.0f ms, %d réveils", demandes, (double) attenteTotale / demandes, reveils);
        }
    }

    Img image(long delaiMs) {
        long t0 = System.currentTimeMillis();
        try {
            return attendreImage(delaiMs);
        } finally {
            synchronized (verrou) {
                demandes++;
                attenteTotale += System.currentTimeMillis() - t0;
            }
        }
    }

    private Img attendreImage(long delaiMs) {
        synchronized (verrou) {
            if (lecteur == null) return null;
            if (enVeille) {
                affichage.setSurface(lecteur.getSurface());
                enVeille = false;
                reveils++;
                delaiMs = Math.max(delaiMs, 600);
            }
            Image garde = null;
            try {
                while (true) {
                    Image im;
                    try {
                        im = lecteur.acquireNextImage();
                    } catch (IllegalStateException e) {
                        break;
                    }
                    if (im == null) break;
                    if (garde != null) garde.close();
                    garde = im;
                }
                long vues = images;
                long fin = System.currentTimeMillis() + delaiMs;
                Image fraiche = null;
                while (System.currentTimeMillis() < fin) {
                    try {
                        verrou.wait(Math.max(1, fin - System.currentTimeMillis()));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Arret();
                    }
                    if (images != vues) {
                        try {
                            fraiche = lecteur.acquireLatestImage();
                        } catch (IllegalStateException e) {
                            fraiche = null;
                        }
                        if (fraiche != null) break;
                    }
                }
                Image choisie = fraiche != null ? fraiche : garde;
                if (choisie == null) return derniere;      // rien de neuf : l'écran n'a pas changé
                try {
                    derniere = convertir(choisie);
                    return derniere;
                } finally {
                    if (fraiche != null) fraiche.close();
                }
            } finally {
                if (garde != null) garde.close();
            }
        }
    }

    private static Img convertir(Image im) {
        Image.Plane plan = im.getPlanes()[0];
        int w = im.getWidth(), h = im.getHeight();
        Mat rgba = new Mat(h, w, CvType.CV_8UC4, plan.getBuffer(), plan.getRowStride());
        Mat bgr = new Mat();
        try {
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR);
            return Img.de(bgr);
        } finally {
            rgba.release();
            bgr.release();
        }
    }

    private void fermer() {
        synchronized (verrou) {
            if (affichage != null) affichage.release();
            affichage = null;
            if (lecteur != null) lecteur.close();
            lecteur = null;
            derniere = null;
        }
        if (projection != null) {
            MediaProjection p = projection;
            projection = null;
            try { p.stop(); } catch (RuntimeException ignored) {}
        }
    }

    @Override
    public void onDestroy() {
        Controle.oublier(ecouteur);
        Controle.arreter();
        fermer();
        instance = null;
        fil.quitSafely();
        super.onDestroy();
    }

    // ── notifications ──

    private PendingIntent action(String action) {
        Intent i = new Intent(this, CaptureService.class).setAction(action);
        return PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        boolean marche = Controle.enMarche();
        String titre = marche ? "Bot CoC : attaque " + Controle.attaque + (Controle.total > 0 ? " / " + Controle.total : "") : "Bot CoC prêt";
        String texte = marche ? Controle.suivants + " Suivant · " + Controle.blocages + " blocage" + (Controle.blocages > 1 ? "s" : "")
                : "Partage d'écran ouvert : lance le bot depuis la pastille";
        Intent ouvrir = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification.Builder b = new Notification.Builder(this, CANAL)
                .setSmallIcon(R.drawable.icone)
                .setContentTitle(titre)
                .setContentText(texte)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, ouvrir, PendingIntent.FLAG_IMMUTABLE));
        if (marche) {
            b.addAction(new Notification.Action.Builder(null, "Arrêter", action(ARRETER)).build());
        } else {
            b.addAction(new Notification.Action.Builder(null, "Fermer", action(FERMER)).build());
        }
        return b.build();
    }

    private void majNotification() {
        if (!Controle.enMarche()) veille(true);
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (instance == this) nm.notify(NOTIF, notification());
        Controle.Etat e = Controle.etat;
        if (e == Controle.Etat.ARRETE && etatPrecedent != Controle.Etat.ARRETE && Controle.derniereFin != null) {
            Notification fin = new Notification.Builder(this, CANAL_FIN)
                    .setSmallIcon(R.drawable.icone)
                    .setContentTitle("Bot CoC arrêté")
                    .setContentText(Controle.derniereFin)
                    .setStyle(new Notification.BigTextStyle().bigText(Controle.derniereFin))
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_FIN, fin);
        }
        etatPrecedent = e;
    }
}

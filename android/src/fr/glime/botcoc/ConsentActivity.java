package fr.glime.botcoc;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Demande « Partager l'écran avec Bot CoC ? », puis démarre le partage (et le bot). */
public final class ConsentActivity extends Activity {
    private boolean lancer;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        lancer = getIntent().getBooleanExtra("lancer", false);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), 1);
    }

    @Override
    protected void onActivityResult(int requete, int resultat, Intent data) {
        if (resultat == RESULT_OK && data != null) {
            Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.DEMARRER)
                    .putExtra("code", resultat).putExtra("data", data).putExtra("lancer", lancer);
            android.os.Bundle essai = getIntent().getBundleExtra("essai");
            if (essai != null) i.putExtra("essai", essai);
            startForegroundService(i);
        }
        finish();
    }
}

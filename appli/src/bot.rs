#[cxx_qt::bridge]
pub mod qobject {
    unsafe extern "C++" {
        include!("cxx-qt-lib/qstring.h");
        type QString = cxx_qt_lib::QString;
    }

    extern "RustQt" {
        #[qobject]
        #[qml_element]
        #[qproperty(bool, actif)]
        #[qproperty(i32, attaques)]
        #[qproperty(i32, suivants)]
        #[qproperty(i32, blocages)]
        type Bot = super::BotRust;

        #[qinvokable]
        fn lancer(self: Pin<&mut Bot>, village: &QString, attaques: i32, duree: i32, fige: i32, butin: &QString);

        #[qinvokable]
        fn connecter(self: Pin<&mut Bot>);

        #[qinvokable]
        fn quitter(self: Pin<&mut Bot>);

        #[qinvokable]
        fn arreter(self: Pin<&mut Bot>);

        #[qinvokable]
        fn retrouver(self: Pin<&mut Bot>);

        #[qsignal]
        fn ligne(self: Pin<&mut Bot>, texte: QString);
    }

    impl cxx_qt::Threading for Bot {}
}

use core::pin::Pin;
use cxx_qt::{CxxQtType, Threading};
use cxx_qt_lib::QString;
use std::fs::{self, File};
use std::ffi::CString;
use std::io::Read;
use std::os::unix::ffi::OsStrExt;
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::thread;
use std::time::Duration;

#[derive(Default)]
pub struct BotRust {
    actif: bool,
    attaques: i32,
    suivants: i32,
    blocages: i32,
    pid: Option<i32>,
    arret_demande: bool,
    bloque: bool,
    fini: bool,
    derniere: String,
    connexion: bool,
    annule: bool,
    sans_fil_par_moi: bool,
}

/// Ce qu'il faut pour lancer le bot une fois le téléphone joignable.
struct Reglages {
    village: String,
    attaques: i32,
    duree: i32,
    fige: i32,
    butin: String,
}

enum Liaison {
    Usb,
    SansFil,
    Connecte,
    Echec(String),
}

/// [(numéro, en Wi-Fi ?)] des téléphones prêts.
fn appareils() -> Vec<(String, bool)> {
    let Ok(sortie) = Command::new("adb").arg("devices").output() else { return Vec::new() };
    String::from_utf8_lossy(&sortie.stdout)
        .lines()
        .skip(1)
        .filter_map(|l| {
            let mut c = l.split_whitespace();
            let serie = c.next()?;
            (c.next()? == "device").then(|| (serie.to_string(), serie.contains(':') || serie.contains("_adb-tls-connect")))
        })
        .collect()
}

/// Câble branché : rien à faire. Sinon Wi-Fi, connecté par adb-a54 s'il ne l'est pas déjà.
fn assurer_liaison() -> Liaison {
    let liste = appareils();
    if liste.iter().any(|(_, sans_fil)| !sans_fil) {
        return Liaison::Usb;
    }
    if !liste.is_empty() {
        return Liaison::SansFil;
    }
    match Command::new("adb-a54").stdin(Stdio::null()).output() {
        Ok(o) => {
            let texte = String::from_utf8_lossy(&o.stdout);
            let derniere = texte.lines().last().unwrap_or("adb-a54 n'a rien répondu").trim().to_string();
            if o.status.success() && appareils().iter().any(|(_, sans_fil)| *sans_fil) {
                Liaison::Connecte
            } else {
                Liaison::Echec(derniere)
            }
        }
        Err(_) => Liaison::Echec("Téléphone introuvable : branche le câble ou fais adb connect IP:PORT.".into()),
    }
}

fn dossier() -> PathBuf {
    match std::env::var_os("BOT_COC_DOSSIER") {
        Some(d) => PathBuf::from(d),
        None => Path::new(env!("CARGO_MANIFEST_DIR")).parent().unwrap_or(Path::new(".")).to_path_buf(),
    }
}

fn journal() -> PathBuf {
    dossier().join("debug/journal.txt")
}

fn fichier_pid() -> PathBuf {
    dossier().join("debug/bot.pid")
}

fn vivant(pid: i32) -> bool {
    unsafe { libc::kill(pid, 0) == 0 }
}

/// Un python qui exécute bot_coc.py (pas un éditeur ouvert sur le fichier).
fn est_le_bot(pid: i32) -> bool {
    let Ok(brut) = fs::read(format!("/proc/{pid}/cmdline")) else { return false };
    let args: Vec<String> = brut
        .split(|&o| o == 0)
        .map(|a| String::from_utf8_lossy(a).into_owned())
        .collect();
    let programme = args.first().map(|a| a.rsplit('/').next().unwrap_or("").to_string()).unwrap_or_default();
    programme.starts_with("python") && args.iter().any(|a| a == "bot_coc.py" || a.ends_with("/bot_coc.py"))
}

/// Un bot lancé ailleurs (depuis un terminal) : son numéro de processus.
fn autre_bot() -> Option<i32> {
    fs::read_dir("/proc")
        .ok()?
        .filter_map(|e| e.ok()?.file_name().to_str()?.parse().ok())
        .find(|&pid| est_le_bot(pid))
}

/// Attend, sans aucun réveil périodique, que le journal soit écrit (inotify) ou que le bot se
/// termine (pidfd). Si le noyau refuse l'un des deux, un coup d'œil par seconde.
struct Veilleur {
    inotify: i32,
    pidfd: i32,
    pid: i32,
}

impl Veilleur {
    fn new(chemin: &Path, pid: i32) -> Self {
        let chemin = CString::new(chemin.as_os_str().as_bytes()).unwrap_or_default();
        unsafe {
            let mut inotify = libc::inotify_init1(libc::IN_CLOEXEC | libc::IN_NONBLOCK);
            if inotify >= 0 && libc::inotify_add_watch(inotify, chemin.as_ptr(), libc::IN_MODIFY) < 0 {
                libc::close(inotify);
                inotify = -1;
            }
            let pidfd = libc::syscall(libc::SYS_pidfd_open, pid, 0) as i32;
            Veilleur { inotify, pidfd, pid }
        }
    }

    /// Renvoie true quand le bot s'est terminé.
    fn attendre(&self) -> bool {
        let mut fds = [
            libc::pollfd { fd: self.inotify, events: libc::POLLIN, revents: 0 },
            libc::pollfd { fd: self.pidfd, events: libc::POLLIN, revents: 0 },
        ];
        let delai = if self.inotify >= 0 && self.pidfd >= 0 { -1 } else { 1000 };
        unsafe { libc::poll(fds.as_mut_ptr(), 2, delai) };
        if fds[0].revents & libc::POLLIN != 0 {
            let mut tampon = [0u8; 4096];
            unsafe { libc::read(self.inotify, tampon.as_mut_ptr().cast(), tampon.len()) };
        }
        if self.pidfd >= 0 {
            fds[1].revents & libc::POLLIN != 0
        } else {
            !vivant(self.pid)
        }
    }
}

impl Drop for Veilleur {
    fn drop(&mut self) {
        for fd in [self.inotify, self.pidfd] {
            if fd >= 0 {
                unsafe { libc::close(fd) };
            }
        }
    }
}

fn notifier(titre: &str, texte: &str) {
    if let Ok(mut n) = Command::new("notify-send")
        .args(["-a", "Bot CoC", "-i", "org.glime.botcoc", titre, texte])
        .spawn()
    {
        thread::spawn(move || n.wait());
    }
}

fn nombre_apres(ligne: &str, motif: &str) -> Option<i32> {
    let reste = &ligne[ligne.find(motif)? + motif.len()..];
    let chiffres: String = reste.chars().take_while(char::is_ascii_digit).collect();
    chiffres.parse().ok()
}

impl qobject::Bot {
    pub fn lancer(mut self: Pin<&mut Self>, village: &QString, attaques: i32, duree: i32, fige: i32, butin: &QString) {
        if *self.actif() {
            return;
        }
        if let Some(pid) = autre_bot() {
            self.as_mut().ligne(QString::from(&format!(
                "Un bot tourne déjà (processus {pid}, lancé depuis un terminal ?) : je n'en lance pas un deuxième."
            )));
            return;
        }
        let reglages = Reglages { village: village.to_string(), attaques, duree, fige, butin: butin.to_string() };
        self.as_mut().rust_mut().annule = false;
        self.as_mut().set_actif(true);
        self.as_mut().ligne(QString::from("Connexion au téléphone…"));
        let qt = self.qt_thread();
        thread::spawn(move || {
            let liaison = assurer_liaison();
            let _ = qt.queue(move |bot| bot.liaison_faite(liaison, Some(reglages)));
        });
    }

    /// À l'ouverture de la fenêtre : le téléphone est-il joignable ? (Wi-Fi seulement sans câble.)
    pub fn connecter(mut self: Pin<&mut Self>) {
        if self.rust().connexion {
            return;
        }
        self.as_mut().rust_mut().connexion = true;
        let qt = self.qt_thread();
        thread::spawn(move || {
            let liaison = assurer_liaison();
            let _ = qt.queue(move |bot| bot.liaison_faite(liaison, None));
        });
    }

    fn liaison_faite(mut self: Pin<&mut Self>, liaison: Liaison, reglages: Option<Reglages>) {
        self.as_mut().rust_mut().connexion = false;
        let message = match &liaison {
            Liaison::Usb => "Téléphone branché en USB".to_string(),
            Liaison::SansFil => "Téléphone connecté en Wi-Fi".to_string(),
            Liaison::Connecte => {
                self.as_mut().rust_mut().sans_fil_par_moi = true;
                "Téléphone connecté en Wi-Fi (adb-a54)".to_string()
            }
            Liaison::Echec(m) => m.clone(),
        };
        self.as_mut().ligne(QString::from(&message));
        let Some(reglages) = reglages else { return };
        if matches!(liaison, Liaison::Echec(_)) || self.rust().annule {
            self.as_mut().set_actif(false);
            return;
        }
        self.demarrer(reglages);
    }

    fn demarrer(mut self: Pin<&mut Self>, r: Reglages) {
        let d = dossier();
        let _ = fs::create_dir_all(d.join("debug"));
        let sortie = match File::create(journal()).and_then(|f| f.try_clone().map(|g| (f, g))) {
            Ok(fichiers) => fichiers,
            Err(e) => {
                self.as_mut().ligne(QString::from(&format!("Impossible d'écrire le journal : {e}")));
                self.as_mut().set_actif(false);
                return;
            }
        };
        let mut commande = Command::new(d.join(".venv/bin/python"));
        commande
            .args(["-u", "bot_coc.py"])
            .current_dir(&d)
            .stdin(Stdio::null())
            .stdout(sortie.0)
            .stderr(sortie.1);
        match r.village.as_str() {
            "auto" => {
                commande.arg("--auto");
            }
            "ouvriers" => {
                commande.arg("--nuit");
            }
            _ => {}
        }
        if r.attaques > 0 {
            commande.args(["--attaques", &r.attaques.to_string()]);
        }
        if r.duree > 0 && r.village != "ouvriers" {
            commande.args(["--duree", &r.duree.to_string()]);
        }
        if r.fige >= 0 && r.village != "ouvriers" {
            commande.args(["--fige", &r.fige.to_string()]);
        }
        if r.village != "ouvriers" {
            commande.args(r.butin.split_whitespace());
        }
        // sa propre session : le bot ne s'arrête pas si la fenêtre se ferme ou plante ; et
        // Ctrl+C remis à la normale (lancée en arrière-plan, la fenêtre peut l'ignorer, et le
        // bot ne pourrait plus s'arrêter proprement)
        unsafe {
            commande.pre_exec(|| {
                libc::setsid();
                libc::signal(libc::SIGINT, libc::SIG_DFL);
                libc::signal(libc::SIGQUIT, libc::SIG_DFL);
                Ok(())
            });
        }
        match commande.spawn() {
            Ok(mut enfant) => {
                let pid = enfant.id() as i32;
                let _ = fs::write(fichier_pid(), pid.to_string());
                thread::spawn(move || enfant.wait());
                self.suivre(pid);
            }
            Err(e) => {
                self.as_mut().ligne(QString::from(&format!("Impossible de lancer le bot : {e}")));
                self.as_mut().set_actif(false);
            }
        }
    }

    /// Fenêtre fermée : le bot s'arrête proprement ; si c'est la fenêtre qui a connecté le
    /// téléphone en Wi-Fi, un petit processus attend la fin du bot (30 s au plus) puis coupe le
    /// Wi-Fi d'adb. Il survit à la fenêtre et s'arrête aussitôt après.
    pub fn quitter(mut self: Pin<&mut Self>) {
        let pid = self.rust().pid;
        if pid.is_some() {
            self.as_mut().arreter();
        }
        if !self.rust().sans_fil_par_moi {
            return;
        }
        let attente = pid
            .map(|p| format!("i=0; while kill -0 {p} 2>/dev/null && [ $i -lt 60 ]; do sleep 0.5; i=$((i+1)); done; "))
            .unwrap_or_default();
        let script = format!(
            "{attente}adb devices | awk 'NR>1 && $2==\"device\" && ($1 ~ /:/ || $1 ~ /_adb-tls-connect/) {{print $1}}' \
             | xargs -r -n1 adb disconnect >/dev/null 2>&1"
        );
        let mut commande = Command::new("sh");
        commande.args(["-c", &script]).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null());
        unsafe {
            commande.pre_exec(|| {
                libc::setsid();
                Ok(())
            });
        }
        let _ = commande.spawn();
    }

    pub fn arreter(mut self: Pin<&mut Self>) {
        let Some(pid) = self.rust().pid else {
            if *self.actif() {
                self.as_mut().rust_mut().annule = true;        // encore en train de connecter
                self.as_mut().set_actif(false);
                self.as_mut().ligne(QString::from("Lancement annulé."));
            }
            return;
        };
        unsafe { libc::kill(pid, libc::SIGINT) };
        self.as_mut().rust_mut().arret_demande = true;
        self.as_mut().ligne(QString::from("Arrêt demandé : le bot termine proprement…"));
        let qt = self.qt_thread();
        thread::spawn(move || {
            thread::sleep(Duration::from_secs(20));
            let _ = qt.queue(move |mut bot| {
                if bot.rust().pid == Some(pid) && vivant(pid) {
                    unsafe { libc::kill(pid, libc::SIGKILL) };
                    bot.as_mut().ligne(QString::from("Le bot ne répondait plus : arrêt forcé."));
                }
            });
        });
    }

    /// À l'ouverture de la fenêtre : un bot lancé par elle tourne encore ? On le reprend.
    pub fn retrouver(mut self: Pin<&mut Self>) {
        let pid = fs::read_to_string(fichier_pid()).ok().and_then(|p| p.trim().parse().ok());
        match pid {
            Some(pid) if vivant(pid) && est_le_bot(pid) => self.suivre(pid),
            _ => {
                if let Some(pid) = autre_bot() {
                    self.as_mut().ligne(QString::from(&format!(
                        "Un bot tourne déjà depuis un terminal (processus {pid}) : son journal n'apparaît pas ici."
                    )));
                }
            }
        }
    }

    /// Lit le journal au fil de l'eau (3 fois par seconde) tant que le bot tourne.
    fn suivre(mut self: Pin<&mut Self>, pid: i32) {
        {
            let mut r = self.as_mut().rust_mut();
            r.pid = Some(pid);
            r.arret_demande = false;
            r.bloque = false;
            r.fini = false;
        }
        self.as_mut().set_attaques(0);
        self.as_mut().set_suivants(0);
        self.as_mut().set_blocages(0);
        self.as_mut().set_actif(true);
        let qt = self.qt_thread();
        thread::spawn(move || {
            let veilleur = Veilleur::new(&journal(), pid);
            let mut fichier = File::open(journal()).ok();
            let mut reste = String::new();
            let mut fini = false;
            loop {
                if let Some(f) = fichier.as_mut() {
                    let mut neuf = Vec::new();
                    if f.read_to_end(&mut neuf).is_ok() {
                        reste.push_str(&String::from_utf8_lossy(&neuf));
                    }
                }
                let mut lignes: Vec<String> = Vec::new();
                while let Some(i) = reste.find('\n') {
                    lignes.push(reste[..i].trim_end().to_string());
                    reste.drain(..=i);
                }
                if fini && !reste.trim().is_empty() {
                    lignes.push(std::mem::take(&mut reste));
                }
                if !lignes.is_empty() {
                    let _ = qt.queue(move |mut bot| {
                        for l in &lignes {
                            bot.as_mut().recevoir(l);
                        }
                    });
                }
                if fini {
                    let _ = qt.queue(move |bot| bot.terminer(pid));
                    break;
                }
                fini = veilleur.attendre();
            }
        });
    }

    fn recevoir(mut self: Pin<&mut Self>, ligne: &str) {
        if let Some(n) = nombre_apres(ligne, "Attaque n°") {
            let faites = (*self.attaques()).max(n - 1);
            self.as_mut().set_attaques(faites);
        }
        if let Some(k) = nombre_apres(ligne, "Fin : ") {
            self.as_mut().set_attaques(k);
            self.as_mut().rust_mut().fini = true;
        }
        if ligne.contains("→ suivant (pas assez de butin)") {
            let n = *self.suivants() + 1;
            self.as_mut().set_suivants(n);
        }
        if ligne.contains(" Bloqué : ") {
            let n = *self.blocages() + 1;
            self.as_mut().set_blocages(n);
        }
        if ligne.contains("blocages d'affilée") {
            self.as_mut().rust_mut().bloque = true;
        }
        if !ligne.trim().is_empty() {
            self.as_mut().rust_mut().derniere = ligne.trim().to_string();
        }
        self.as_mut().ligne(QString::from(ligne));
    }

    fn terminer(mut self: Pin<&mut Self>, pid: i32) {
        if self.rust().pid != Some(pid) {
            return;
        }
        let _ = fs::remove_file(fichier_pid());
        let attaques = *self.attaques();
        let (arret, bloque, fini, derniere) = {
            let r = self.as_mut().rust_mut();
            (r.arret_demande, r.bloque, r.fini, r.derniere.clone())
        };
        self.as_mut().rust_mut().pid = None;
        self.as_mut().set_actif(false);
        if arret {
            return;
        }
        if bloque {
            notifier("Bot CoC bloqué", &format!("Arrêté après 5 blocages de suite ({attaques} attaques faites)."));
        } else if fini {
            notifier("Bot CoC a fini", &format!("{attaques} attaque(s) terminée(s)."));
        } else {
            notifier("Bot CoC s'est arrêté", &derniere);
        }
    }
}

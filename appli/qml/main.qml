import QtCore
import QtQuick
import QtQuick.Controls as QQC2
import QtQuick.Layouts
import QtQuick.Shapes
import org.kde.kirigami as Kirigami
import org.glime.botcoc

Kirigami.ApplicationWindow {
    id: fenetre

    readonly property color noir: "#000000"
    readonly property color carte: "#0a0a0a"
    readonly property color bord: "#3a0a55"
    readonly property color violet: "#61008a"
    readonly property color violetClair: "#c79be0"
    readonly property color texte: "#eeeeee"
    readonly property color doux: "#9a8aa6"
    readonly property color rouge: "#ff6b6b"
    readonly property color vert: "#7fd18b"
    readonly property int arrondi: 14
    readonly property int etoile: 20
    readonly property int retrait: etoile / 2

    property string phase: "Prêt"
    property string detail: "Choisis les réglages puis lance le bot."

    title: "Bot CoC"
    width: 920
    height: Math.max(580, minimumHeight)
    minimumWidth: 680
    minimumHeight: Math.max(460, gauche.implicitHeight + Kirigami.Units.gridUnit * 2)
    color: noir

    Kirigami.Theme.inherit: false
    Kirigami.Theme.colorSet: Kirigami.Theme.Window
    Kirigami.Theme.backgroundColor: noir
    Kirigami.Theme.alternateBackgroundColor: carte
    Kirigami.Theme.textColor: texte
    Kirigami.Theme.disabledTextColor: "#5f5068"
    Kirigami.Theme.highlightColor: violet
    Kirigami.Theme.highlightedTextColor: "#ffffff"
    Kirigami.Theme.hoverColor: "#8a2fb8"
    Kirigami.Theme.focusColor: "#9a3fd0"
    Kirigami.Theme.positiveTextColor: vert
    Kirigami.Theme.negativeTextColor: rouge

    Settings {
        id: reglages
        category: "bot"
        property string village: "auto"
        property int attaques: 23
        property int fige: 6
        property int duree: 37
        property real butin: 2.2
        property bool parRessource: false
        property bool avecOr: true
        property bool avecElixir: false
        property bool avecNoir: true
        property real butinOr: 1.0
        property real butinElixir: 1.0
        property int butinNoir: 8000
        property bool historique: true
    }

    Bot {
        id: bot
        onLigne: texte => fenetre.recevoir(texte)
    }

    function couleurDe(l) {
        if (/Bloqué|impossible|Erreur|Traceback|Error/.test(l)) return "#ff6b6b"
        if (/Fin :|vérifié : tout est posé|Village principal :|Base des ouvriers/.test(l)) return "#7fd18b"
        if (/Attaque n°|→/.test(l)) return "#c79be0"
        return "#bdbdbd"
    }

    function suivrePhase(l) {
        const t = l.replace(/^\d\d:\d\d:\d\d\s+/, "").trim()
        if (!t) return
        let m
        if ((m = t.match(/^Attaque n°(\d+)/))) phase = "Attaque " + m[1] + " : recherche d'un village"
        else if (/^Connexion au téléphone/.test(t)) phase = "Connexion au téléphone"
        else if (/^Téléphone (branché|connecté)/.test(t)) phase = bot.actif ? "Démarrage" : "Prêt"
        else if (/^Village détecté/.test(t)) phase = t.replace("Village détecté : ", "").replace(/^./, c => c.toUpperCase())
        else if (/liaison Wi-Fi perdue/.test(t)) phase = "Reconnexion en Wi-Fi"
        else if (/→ suivant/.test(t)) phase = "Village suivant (pas assez de butin)"
        else if (/^butin/.test(t)) phase = "Village trouvé"
        else if (/^(barre|armée|vue haut|vue bas :.*touches|il reste|vérifié)/.test(t)) phase = "Pose des troupes"
        else if (/sorts sur|pas de troupes visibles/.test(t)) phase = "Sorts lancés, combat en cours"
        else if (/compétences/.test(t)) phase = "Combat en cours"
        else if (/vitesse ×4/.test(t)) phase = "Combat en cours (×4)"
        else if (/capituler|→ confirmer/.test(t)) phase = "Capitulation"
        else if (/→ rentrer|déjà revenu/.test(t)) phase = "Retour au village"
        else if (/^Bloqué/.test(t)) phase = "Bloqué, je réessaie"
        else if (/relance le jeu/.test(t)) phase = "Redémarrage du jeu"
        else if (/^Fin :/.test(t)) phase = "Terminé"
        else if (/^Arrêt demandé|termine proprement/.test(t)) phase = "Arrêt en cours"
        else if (/→ (combat|trouver|attaquer)/.test(t)) phase = "Recherche d'un village"
        detail = t
    }

    function recevoir(l) {
        journal.append({ texte: l, couleur: couleurDe(l) })
        if (journal.count > 3000)
            journal.remove(0, 500)
        suivrePhase(l)
    }

    function lancerOuArreter() {
        if (bot.actif) {
            bot.arreter()
            return
        }
        journal.clear()
        phase = "Démarrage"
        detail = ""
        bot.lancer(reglages.village, reglages.attaques, reglages.duree, reglages.fige, argumentsButin())
    }

    function argumentsButin() {
        if (!reglages.parRessource)
            return "--butin " + reglages.butin.toFixed(1) + "M"
        const a = []
        if (reglages.avecOr) a.push("--or " + reglages.butinOr.toFixed(1) + "M")
        if (reglages.avecElixir) a.push("--elixir " + reglages.butinElixir.toFixed(1) + "M")
        if (reglages.avecNoir) a.push("--noir " + reglages.butinNoir)
        return a.length ? a.join(" ") : "--butin 0"
    }

    function millions(v) {
        return v.toFixed(1).replace(".", ",") + " M"
    }

    onClosing: bot.quitter()
    Component.onCompleted: {
        bot.retrouver()
        bot.connecter()
    }

    ListModel { id: journal }

    component Titre: QQC2.Label {
        color: fenetre.doux
        font.pointSize: Kirigami.Theme.smallFont.pointSize
        font.capitalization: Font.AllUppercase
        font.letterSpacing: 1
    }

    component Reglage: ColumnLayout {
        id: reglage
        property alias titre: etiquette.text
        property string valeur
        property real min: 0
        property real max: 1
        property real pas: 1
        property real position: 0
        signal deplace(real v)
        spacing: 12
        Layout.fillWidth: true
        RowLayout {
            Layout.leftMargin: fenetre.retrait
            Layout.rightMargin: fenetre.retrait
            Titre {
                id: etiquette
                Layout.fillWidth: true
                Layout.preferredWidth: Kirigami.Units.gridUnit * 6
                wrapMode: Text.WordWrap
            }
            QQC2.TextField {
                id: saisie
                Layout.preferredWidth: Kirigami.Units.gridUnit * 4.5
                horizontalAlignment: Text.AlignRight
                text: reglage.valeur
                enabled: !bot.actif
                color: "#ffffff"
                font.bold: true
                selectByMouse: true
                inputMethodHints: Qt.ImhFormattedNumbersOnly
                background: Rectangle {
                    radius: 6
                    color: fenetre.carte
                    border.color: saisie.activeFocus ? fenetre.violetClair : fenetre.violet
                }
                onActiveFocusChanged: if (activeFocus) selectAll()
                onEditingFinished: {
                    const v = parseFloat(text.replace(",", ".").replace(/\s/g, ""))
                    if (!isNaN(v))
                        reglage.deplace(Math.max(reglage.min, v))
                    text = Qt.binding(() => reglage.valeur)
                    focus = false
                }
            }
        }
        QQC2.Slider {
            id: glissiere
            Layout.fillWidth: true
            enabled: !bot.actif
            leftPadding: 0
            rightPadding: 0
            from: reglage.min
            to: reglage.max
            stepSize: reglage.pas
            value: reglage.position
            onMoved: reglage.deplace(value)
            background: Item {
                x: glissiere.leftPadding
                y: glissiere.topPadding + glissiere.availableHeight / 2 - 2
                width: glissiere.availableWidth
                height: 4
                Rectangle {
                    x: fenetre.retrait
                    width: parent.width - fenetre.etoile
                    height: 4
                    radius: 2
                    color: "#1c0029"
                }
                Rectangle {
                    x: fenetre.retrait
                    width: glissiere.visualPosition * (parent.width - fenetre.etoile)
                    height: 4
                    radius: 2
                    color: glissiere.enabled ? fenetre.violet : "#2b1838"
                }
            }
            handle: Shape {
                x: glissiere.leftPadding + glissiere.visualPosition * (glissiere.availableWidth - width)
                y: glissiere.topPadding + (glissiere.availableHeight - height) / 2
                width: fenetre.etoile
                height: fenetre.etoile
                rotation: glissiere.pressed ? 45 : 0
                preferredRendererType: Shape.CurveRenderer
                Behavior on rotation { NumberAnimation { duration: 150; easing.type: Easing.OutCubic } }
                ShapePath {
                    strokeColor: "transparent"
                    fillColor: glissiere.enabled ? "#d7a6f0" : "#4a3a55"
                    scale: Qt.size(fenetre.etoile / 24, fenetre.etoile / 24)
                    PathSvg { path: "M12 0c.75 6.6 5.4 11.25 12 12-6.6.75-11.25 5.4-12 12-.75-6.6-5.4-11.25-12-12C6.6 11.25 11.25 6.6 12 0z" }
                }
            }
        }
    }

    component BoutonVillage: QQC2.Button {
        id: choix
        Layout.fillWidth: true
        checkable: true
        enabled: !bot.actif
        contentItem: QQC2.Label {
            text: choix.text
            color: "#ffffff"
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }
        background: Rectangle {
            radius: fenetre.arrondi
            opacity: choix.enabled ? 1 : 0.5
            color: choix.checked ? fenetre.violet : choix.hovered ? "#14001f" : fenetre.noir
            border.color: choix.checked ? fenetre.violet : fenetre.bord
        }
    }

    pageStack.initialPage: Kirigami.Page {
        id: page
        padding: Kirigami.Units.gridUnit
        globalToolBarStyle: Kirigami.ApplicationHeaderStyle.None
        background: Rectangle { color: fenetre.noir }

        RowLayout {
            anchors.fill: parent
            spacing: Kirigami.Units.gridUnit

            ColumnLayout {
                id: gauche
                Layout.preferredWidth: Kirigami.Units.gridUnit * 16
                Layout.maximumWidth: Kirigami.Units.gridUnit * 18
                Layout.fillHeight: true
                spacing: Kirigami.Units.largeSpacing

                Titre { text: "Village"; Layout.leftMargin: fenetre.retrait }
                QQC2.ButtonGroup { id: villages }
                BoutonVillage {
                    text: "Auto"
                    checked: reglages.village === "auto"
                    QQC2.ButtonGroup.group: villages
                    onClicked: reglages.village = "auto"
                }
                RowLayout {
                    Layout.fillWidth: true
                    spacing: Kirigami.Units.smallSpacing
                    BoutonVillage {
                        text: "Principal"
                        checked: reglages.village === "principal"
                        QQC2.ButtonGroup.group: villages
                        onClicked: reglages.village = "principal"
                    }
                    BoutonVillage {
                        text: "Ouvriers"
                        checked: reglages.village === "ouvriers"
                        QQC2.ButtonGroup.group: villages
                        onClicked: reglages.village = "ouvriers"
                    }
                }

                Reglage {
                    titre: "Attaques"
                    valeur: reglages.attaques === 0 ? "sans fin" : reglages.attaques
                    max: 60
                    position: reglages.attaques
                    onDeplace: v => reglages.attaques = Math.round(v)
                }
                Reglage {
                    titre: "Durée max d'un combat"
                    visible: reglages.village !== "ouvriers"
                    valeur: reglages.duree === 0 ? "sans limite" : reglages.duree + " s"
                    max: 180
                    position: reglages.duree
                    onDeplace: v => reglages.duree = Math.round(v)
                }
                Reglage {
                    titre: "Capituler sans destruction depuis"
                    visible: reglages.village !== "ouvriers"
                    valeur: reglages.fige === 0 ? "jamais" : reglages.fige + " s"
                    max: 60
                    position: reglages.fige
                    onDeplace: v => reglages.fige = Math.round(v)
                }
                RowLayout {
                    Layout.fillWidth: true
                    visible: reglages.village !== "ouvriers"
                    Titre { text: "Butin minimum"; Layout.leftMargin: fenetre.retrait }
                    Item { Layout.fillWidth: true }
                    QQC2.Switch {
                        text: "Par ressource"
                        enabled: !bot.actif
                        checked: reglages.parRessource
                        onToggled: reglages.parRessource = checked
                    }
                }
                Reglage {
                    titre: "Total"
                    visible: reglages.village !== "ouvriers" && !reglages.parRessource
                    valeur: fenetre.millions(reglages.butin)
                    max: 6
                    pas: 0.1
                    position: reglages.butin
                    onDeplace: v => reglages.butin = Math.round(v * 10) / 10
                }
                RowLayout {
                    Layout.fillWidth: true
                    visible: reglages.village !== "ouvriers" && reglages.parRessource
                    spacing: Kirigami.Units.smallSpacing
                    BoutonVillage {
                        text: "Or"
                        checked: reglages.avecOr
                        onClicked: reglages.avecOr = checked
                    }
                    BoutonVillage {
                        text: "Élixir"
                        checked: reglages.avecElixir
                        onClicked: reglages.avecElixir = checked
                    }
                    BoutonVillage {
                        text: "Noir"
                        checked: reglages.avecNoir
                        onClicked: reglages.avecNoir = checked
                    }
                }
                Reglage {
                    titre: "Or"
                    visible: reglages.village !== "ouvriers" && reglages.parRessource && reglages.avecOr
                    valeur: fenetre.millions(reglages.butinOr)
                    max: 3
                    pas: 0.1
                    position: reglages.butinOr
                    onDeplace: v => reglages.butinOr = Math.round(v * 10) / 10
                }
                Reglage {
                    titre: "Élixir"
                    visible: reglages.village !== "ouvriers" && reglages.parRessource && reglages.avecElixir
                    valeur: fenetre.millions(reglages.butinElixir)
                    max: 3
                    pas: 0.1
                    position: reglages.butinElixir
                    onDeplace: v => reglages.butinElixir = Math.round(v * 10) / 10
                }
                Reglage {
                    titre: "Noir"
                    visible: reglages.village !== "ouvriers" && reglages.parRessource && reglages.avecNoir
                    valeur: String(reglages.butinNoir).replace(/\B(?=(\d{3})+$)/g, " ")
                    max: 20000
                    pas: 500
                    position: reglages.butinNoir
                    onDeplace: v => reglages.butinNoir = Math.round(v)
                }

                Item { Layout.fillHeight: true }

                QQC2.Button {
                    id: bouton
                    Layout.fillWidth: true
                    Layout.preferredHeight: Kirigami.Units.gridUnit * 2.6
                    text: bot.actif ? "■  Arrêter" : "▶  Lancer"
                    font.bold: true
                    font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.15
                    onClicked: fenetre.lancerOuArreter()
                    contentItem: QQC2.Label {
                        text: bouton.text
                        font: bouton.font
                        color: bot.actif ? fenetre.violetClair : "#ffffff"
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                    }
                    background: Rectangle {
                        radius: fenetre.arrondi
                        color: bot.actif ? fenetre.noir : (bouton.hovered ? "#7a10ab" : fenetre.violet)
                        border.color: fenetre.violet
                        border.width: bot.actif ? 2 : 0
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: compteurs.implicitHeight + Kirigami.Units.largeSpacing * 2
                    radius: Kirigami.Units.cornerRadius
                    color: fenetre.carte
                    border.color: fenetre.bord
                    ColumnLayout {
                        id: compteurs
                        anchors.centerIn: parent
                        spacing: 0
                        QQC2.Label {
                            Layout.alignment: Qt.AlignHCenter
                            text: bot.attaques
                            color: "#ffffff"
                            font.pointSize: Kirigami.Theme.defaultFont.pointSize * 3
                            font.bold: true
                        }
                        QQC2.Label {
                            Layout.alignment: Qt.AlignHCenter
                            text: bot.attaques > 1 ? "attaques" : "attaque"
                            color: fenetre.violetClair
                        }
                        QQC2.Label {
                            Layout.alignment: Qt.AlignHCenter
                            Layout.topMargin: Kirigami.Units.smallSpacing
                            text: bot.suivants + " Suivant · " + bot.blocages + (bot.blocages > 1 ? " blocages" : " blocage")
                            color: fenetre.doux
                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                        }
                    }
                }
            }

            ColumnLayout {
                Layout.fillWidth: true
                Layout.fillHeight: true
                spacing: Kirigami.Units.smallSpacing

                RowLayout {
                    Layout.fillWidth: true
                    Titre { text: reglages.historique ? "Journal" : "État"; Layout.leftMargin: fenetre.retrait }
                    Item { Layout.fillWidth: true }
                    QQC2.Switch {
                        text: "Historique"
                        checked: reglages.historique
                        onToggled: reglages.historique = checked
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    radius: Kirigami.Units.cornerRadius
                    color: fenetre.noir
                    border.color: fenetre.bord

                    ListView {
                        id: liste
                        property bool enBas: true
                        visible: reglages.historique
                        anchors.fill: parent
                        anchors.margins: Kirigami.Units.smallSpacing
                        clip: true
                        model: journal
                        boundsBehavior: Flickable.StopAtBounds
                        QQC2.ScrollBar.vertical: QQC2.ScrollBar {}
                        delegate: QQC2.Label {
                            required property string texte
                            required property color couleur
                            width: liste.width - Kirigami.Units.largeSpacing
                            text: texte
                            color: couleur
                            wrapMode: Text.Wrap
                            font.family: Kirigami.Theme.fixedWidthFont.family
                            font.pointSize: Kirigami.Theme.smallFont.pointSize
                        }
                        onMovementEnded: enBas = atYEnd
                        onCountChanged: if (enBas) Qt.callLater(positionViewAtEnd)
                    }

                    ColumnLayout {
                        visible: !reglages.historique
                        anchors.centerIn: parent
                        width: parent.width - Kirigami.Units.gridUnit * 2
                        spacing: Kirigami.Units.largeSpacing
                        QQC2.Label {
                            Layout.fillWidth: true
                            horizontalAlignment: Text.AlignHCenter
                            wrapMode: Text.Wrap
                            text: fenetre.phase
                            color: /Bloqué/.test(fenetre.phase) ? fenetre.rouge
                                 : fenetre.phase === "Terminé" ? fenetre.vert : "#ffffff"
                            font.pointSize: Kirigami.Theme.defaultFont.pointSize * 1.8
                            font.bold: true
                        }
                        QQC2.Label {
                            Layout.fillWidth: true
                            horizontalAlignment: Text.AlignHCenter
                            wrapMode: Text.Wrap
                            text: fenetre.detail
                            color: fenetre.doux
                            font.family: Kirigami.Theme.fixedWidthFont.family
                        }
                    }
                }
            }
        }
    }
}

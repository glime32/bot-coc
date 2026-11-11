mod bot;

use cxx_qt_lib::{QGuiApplication, QQmlApplicationEngine, QQuickStyle, QString, QUrl};

fn main() {
    // Style KDE écrit en QML : il prend les couleurs imposées par la fenêtre (noir et violet).
    QQuickStyle::set_style(&QString::from("org.kde.breeze"));
    let mut app = QGuiApplication::new();
    QGuiApplication::set_desktop_file_name(&QString::from("org.glime.botcoc"));
    if let Some(mut app) = app.as_mut() {
        app.as_mut().set_application_name(&QString::from("bot-coc"));
        app.as_mut().set_application_display_name(&QString::from("Bot CoC"));
        app.as_mut().set_organization_name(&QString::from("glime"));
    }

    let mut engine = QQmlApplicationEngine::new();
    if let Some(engine) = engine.as_mut() {
        engine.load(&QUrl::from("qrc:/qt/qml/org/glime/botcoc/qml/main.qml"));
    }
    if let Some(app) = app.as_mut() {
        app.exec();
    }
}

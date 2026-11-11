use cxx_qt_build::{CxxQtBuilder, QmlModule};

fn main() {
    CxxQtBuilder::new_qml_module(QmlModule::new("org.glime.botcoc").qml_files(["qml/main.qml"]))
        .qt_module("Quick")
        .qt_module("QuickControls2")
        .files(["src/bot.rs"])
        .build();
}

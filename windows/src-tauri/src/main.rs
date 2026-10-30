// Tutor de Inglês para Windows: abre o Tutor numa janela própria, com o motor de páginas do Windows (WebView2).
// Links de fora do Tutor (YouTube, Spotify) abrem no navegador padrão.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::{WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_opener::OpenerExt;

const TUTOR: &str = "https://tutor-ingles.guigtx1080.workers.dev/";
const HOST: &str = "tutor-ingles.guigtx1080.workers.dev";

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let handle = app.handle().clone();
            WebviewWindowBuilder::new(app, "principal", WebviewUrl::External(TUTOR.parse().unwrap()))
                .title("Tutor de Inglês")
                .inner_size(1200.0, 800.0)
                .min_inner_size(360.0, 500.0)
                .on_navigation(move |url| {
                    let dentro = url.host_str() == Some(HOST);
                    if !dentro {
                        let _ = handle.opener().open_url(url.as_str(), None::<&str>);
                    }
                    dentro
                })
                .build()?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("erro ao abrir o Tutor de Inglês");
}

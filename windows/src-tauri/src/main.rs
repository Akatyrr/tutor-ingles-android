// Tutor de Inglês para Windows: abre o Tutor numa janela própria, com o motor de páginas do Windows (WebView2).
// Sem a barra de título do Windows ("borda infinita"): o fundo do app vai até as bordas, e os botões de
// minimizar, maximizar e fechar são desenhados pelo próprio app. Arrastar a janela: pelo topo do app.
// Links de fora do Tutor (YouTube, Spotify) abrem no navegador padrão.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use tauri::{WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_opener::OpenerExt;

const TUTOR: &str = "https://tutor-ingles.guigtx1080.workers.dev/";
const HOST: &str = "tutor-ingles.guigtx1080.workers.dev";

// Botões da janela e área de arrastar, colocados na página assim que ela abre
const BORDA: &str = r#"
(() => {
  const w = () => window.__TAURI__ && window.__TAURI__.window.getCurrentWindow()
  const monta = () => {
    if (document.getElementById('janelaCtl')) return
    const st = document.createElement('style')
    st.textContent = `#janelaArrasta{position:fixed;top:0;left:0;right:138px;height:10px;z-index:9998}
#janelaCtl{position:fixed;top:0;right:0;z-index:9999;display:flex}
#janelaCtl button{width:46px;height:30px;border:none;background:transparent;color:var(--ink,#1f1f1e);font:13px/1 "Segoe UI",system-ui,sans-serif;cursor:default;opacity:.75}
#janelaCtl button:hover{background:color-mix(in srgb,var(--ink,#000) 12%,transparent);opacity:1}
#janelaCtl button.x:hover{background:#C42B1C;color:#fff}`
    document.head.appendChild(st)
    const a = document.createElement('div'); a.id = 'janelaArrasta'; a.setAttribute('data-tauri-drag-region', ''); document.body.appendChild(a)
    const c = document.createElement('div'); c.id = 'janelaCtl'
    c.innerHTML = '<button type="button" title="Minimizar" aria-label="Minimizar">&#xE921;</button><button type="button" title="Maximizar" aria-label="Maximizar">&#xE922;</button><button type="button" class="x" title="Fechar" aria-label="Fechar">&#xE8BB;</button>'
    c.querySelectorAll('button').forEach(b => b.style.fontFamily = '"Segoe Fluent Icons","Segoe MDL2 Assets",sans-serif')
    const [mi, ma, fe] = c.querySelectorAll('button')
    mi.onclick = () => w().minimize(); ma.onclick = () => w().toggleMaximize(); fe.onclick = () => w().close()
    document.body.appendChild(c)
    // o topo do app (cabeçalho e título da barra lateral) também arrasta a janela
    const marca = () => document.querySelectorAll('#head, .brand, #side > header').forEach(e => e.setAttribute('data-tauri-drag-region', ''))
    marca(); new MutationObserver(marca).observe(document.body, { childList: true, subtree: true })
  }
  // links de fora (YouTube, Spotify) abrem em "nova aba", que esta janela não tem: vira uma navegação comum,
  // que o app manda para o navegador padrão
  document.addEventListener('click', e => {
    const a = e.target.closest && e.target.closest('a[href]')
    if (!a || a.host === location.host || !/^https?:/.test(a.href)) return
    e.preventDefault(); location.href = a.href
  }, true)
  const abre = window.open
  window.open = (u, ...r) => { try { const x = new URL(u, location.href); if (x.host !== location.host) { location.href = x.href; return null } } catch (er) {} return abre.call(window, u, ...r) }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', monta); else monta()
})()
"#;

fn main() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            let handle = app.handle().clone();
            WebviewWindowBuilder::new(app, "principal", WebviewUrl::External(TUTOR.parse().unwrap()))
                .title("Atarax")
                .inner_size(1200.0, 800.0)
                .min_inner_size(360.0, 500.0)
                .decorations(false)
                .shadow(true)
                .initialization_script(BORDA)
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
        .expect("erro ao abrir o Atarax");
}

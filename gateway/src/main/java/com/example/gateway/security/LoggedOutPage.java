package com.example.gateway.security;

/**
 * Where Keycloak sends the browser after the logout. The root of the gateway has no
 * route, so landing there would answer 404.
 */
final class LoggedOutPage {

    static final String HTML = """
            <!doctype html>
            <html lang="pt-BR">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Sessão encerrada · tools</title>
            <style>
              :root { color-scheme: light dark; --bg:#f4f5f7; --card:#fff; --text:#1d2330; --muted:#5b6475; --line:#d5d9e0; --accent:#1f5fbf; }
              @media (prefers-color-scheme: dark) { :root { --bg:#14171c; --card:#1d2128; --text:#e7e9ee; --muted:#a3abb8; --line:#343a45; --accent:#7aa7ff; } }
              * { box-sizing: border-box; }
              body { margin:0; min-height:100vh; display:grid; place-items:center; background:var(--bg); color:var(--text); font:16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; padding:16px; }
              main { width:100%; max-width:360px; background:var(--card); border:1px solid var(--line); border-radius:12px; padding:28px; }
              h1 { font-size:1.25rem; margin:0 0 4px; }
              p { margin:0 0 20px; color:var(--muted); font-size:.9rem; }
              a { display:block; padding:10px 12px; border-radius:8px; background:var(--accent); color:#fff; font-weight:600; text-align:center; text-decoration:none; }
              a:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
            </style>
            </head>
            <body>
            <main>
              <h1>Sessão encerrada</h1>
              <p>Você saiu do gateway e do login único.</p>
              <a href="/_gateway/login/start?returnTo=/_gateway/whoami">Entrar de novo</a>
            </main>
            </body>
            </html>
            """;

    private LoggedOutPage() {
    }
}

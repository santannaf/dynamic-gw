package com.example.gateway.security.ldap;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.security.ActiveSecurity;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.gateway.security.LoginOutcomeHandlers;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.server.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

/**
 * Login form of the {@code ldap} provider: self-contained HTML, no external
 * assets. Registered in every mode; answers 404 unless the provider is ldap.
 */
@RestController
public class LoginPageController {

    private final ActiveSecurity security;

    public LoginPageController(ActiveSecurity security) {
        this.security = security;
    }

    @GetMapping(path = LdapLogin.LOGIN_PATH, produces = MediaType.TEXT_HTML_VALUE)
    public Mono<ResponseEntity<String>> loginPage(ServerWebExchange exchange) {
        if (!security.provider(GatewaySecurityProperties.PROVIDER_LDAP)) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        exchange.getAttributes().put(AccessAttributes.AUDIT_ACTION, LoginOutcomeHandlers.LOGIN_START_ACTION);
        Mono<CsrfToken> token = exchange.getAttribute(CsrfToken.class.getName());
        String notice = exchange.getRequest().getQueryParams().containsKey("error")
                ? "Usuário ou senha inválidos."
                : exchange.getRequest().getQueryParams().containsKey("logout") ? "Sessão encerrada." : null;
        return (token == null ? Mono.<CsrfToken>empty() : token)
                .map(csrf -> page(csrf.getParameterName(), csrf.getToken(), notice))
                .defaultIfEmpty(page(null, null, notice))
                .map(html -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .header("X-Frame-Options", "DENY")
                        .header("Content-Security-Policy",
                                "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'")
                        .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8))
                        .body(html));
    }

    private static String page(String csrfParameter, String csrfToken, String notice) {
        String csrfField = csrfParameter == null ? "" : "<input type=\"hidden\" name=\"" + HtmlUtils.htmlEscape(csrfParameter)
                + "\" value=\"" + HtmlUtils.htmlEscape(csrfToken) + "\">";
        String noticeBlock = notice == null ? "" : "<p class=\"notice\" role=\"alert\">" + HtmlUtils.htmlEscape(notice) + "</p>";
        return """
                <!doctype html>
                <html lang="pt-BR">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Entrar · tools</title>
                <style>
                  :root { color-scheme: light dark; --bg:#f4f5f7; --card:#fff; --text:#1d2330; --muted:#5b6475; --line:#d5d9e0; --accent:#1f5fbf; --alert:#b3261e; }
                  @media (prefers-color-scheme: dark) { :root { --bg:#14171c; --card:#1d2128; --text:#e7e9ee; --muted:#a3abb8; --line:#343a45; --accent:#7aa7ff; --alert:#ff8a80; } }
                  * { box-sizing: border-box; }
                  body { margin:0; min-height:100vh; display:grid; place-items:center; background:var(--bg); color:var(--text); font:16px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; padding:16px; }
                  main { width:100%%; max-width:360px; background:var(--card); border:1px solid var(--line); border-radius:12px; padding:28px; }
                  h1 { font-size:1.25rem; margin:0 0 4px; }
                  p.hint { margin:0 0 20px; color:var(--muted); font-size:.9rem; }
                  label { display:block; font-size:.875rem; margin:14px 0 6px; }
                  input[type=text], input[type=password] { width:100%%; padding:10px 12px; border:1px solid var(--line); border-radius:8px; background:transparent; color:inherit; font:inherit; }
                  input:focus-visible, button:focus-visible { outline:2px solid var(--accent); outline-offset:2px; }
                  button { margin-top:22px; width:100%%; padding:10px 12px; border:0; border-radius:8px; background:var(--accent); color:#fff; font:inherit; font-weight:600; cursor:pointer; }
                  .notice { margin:0 0 8px; color:var(--alert); font-size:.9rem; }
                </style>
                </head>
                <body>
                <main>
                  <h1>Entrar</h1>
                  <p class="hint">Use seu usuário e senha da rede.</p>
                  %s
                  <form method="post" action="/_gateway/login">
                    <label for="username">Usuário</label>
                    <input id="username" name="username" type="text" autocomplete="username" required autofocus>
                    <label for="password">Senha</label>
                    <input id="password" name="password" type="password" autocomplete="current-password" required>
                    %s
                    <button type="submit">Entrar</button>
                  </form>
                </main>
                </body>
                </html>
                """.formatted(noticeBlock, csrfField);
    }
}

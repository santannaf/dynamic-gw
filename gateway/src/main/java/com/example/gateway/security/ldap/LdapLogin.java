package com.example.gateway.security.ldap;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.AccessDecision;
import com.example.gateway.access.ClientAddress;
import com.example.gateway.security.LoginOutcomeHandlers;
import com.example.gateway.security.session.BrowserSession;
import com.example.gateway.security.session.ProviderUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.DefaultServerRedirectStrategy;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;

/** The pieces of {@code POST /_gateway/login} for the {@code ldap} provider. */
public final class LdapLogin {

    public static final String LOGIN_PATH = "/_gateway/login";
    private static final URI LOGIN_ERROR = URI.create(LOGIN_PATH + "?error");

    private LdapLogin() {
    }

    /** Reads {@code username}/{@code password} from the form; the client address rides along as details. */
    public static ServerAuthenticationConverter converter() {
        return exchange -> exchange.getFormData().map(form -> {
            String username = form.getFirst("username");
            exchange.getAttributes().put(AccessAttributes.ATTEMPTED_USERNAME, username == null ? "" : username);
            UsernamePasswordAuthenticationToken token =
                    UsernamePasswordAuthenticationToken.unauthenticated(username, form.getFirst("password"));
            token.setDetails(ClientAddress.bestEffortClient(exchange.getRequest()));
            return token;
        });
    }

    public static ReactiveAuthenticationManager authenticationManager(LdapDirectory directory, LoginAttemptLimiter limiter) {
        return authentication -> {
            if (!(authentication instanceof UsernamePasswordAuthenticationToken attempt)) {
                return Mono.empty();
            }
            String username = attempt.getName();
            String password = attempt.getCredentials() == null ? "" : attempt.getCredentials().toString();
            String client = attempt.getDetails() == null ? null : attempt.getDetails().toString();
            if (limiter.isBlocked(username, client)) {
                return Mono.error(new LoginThrottledException());
            }
            return Mono.fromCallable(() -> directory.authenticate(username, password))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorMap(ProviderUnavailableException.class,
                            e -> new AuthenticationServiceException("Directory unavailable", e))
                    .flatMap(user -> user
                            .<Mono<Authentication>>map(u -> Mono.just(new LdapUserAuthentication(u)))
                            .orElseGet(() -> Mono.error(new BadCredentialsException("Bad credentials"))));
        };
    }

    public static ServerAuthenticationSuccessHandler success(LdapSessionManager sessionManager,
                                                             LoginAttemptLimiter limiter,
                                                             BrowserSession session) {
        ServerAuthenticationSuccessHandler redirect = LoginOutcomeHandlers.success(session);
        return (webFilterExchange, authentication) -> {
            LdapUser user = ((LdapUserAuthentication) authentication).user();
            limiter.reset(user.username());
            sessionManager.establish(webFilterExchange.getExchange(), user);
            return redirect.onAuthenticationSuccess(webFilterExchange, authentication);
        };
    }

    public static ServerAuthenticationFailureHandler failure(LoginAttemptLimiter limiter) {
        DefaultServerRedirectStrategy redirects = new DefaultServerRedirectStrategy();
        return (webFilterExchange, exception) -> {
            var exchange = webFilterExchange.getExchange();
            if (exception instanceof LoginThrottledException) {
                LoginOutcomeHandlers.mark(exchange, AccessDecision.THROTTLED);
                return LoginOutcomeHandlers.plain(webFilterExchange, HttpStatus.TOO_MANY_REQUESTS,
                        "Muitas tentativas. Aguarde alguns minutos e tente de novo.");
            }
            if (exception instanceof AuthenticationServiceException) {
                LoginOutcomeHandlers.mark(exchange, AccessDecision.ERROR);
                return LoginOutcomeHandlers.plain(webFilterExchange, HttpStatus.SERVICE_UNAVAILABLE,
                        "Diretório indisponível. Tente de novo em instantes.");
            }
            String username = exchange.getAttribute(AccessAttributes.ATTEMPTED_USERNAME);
            limiter.recordFailure(username, ClientAddress.bestEffortClient(exchange.getRequest()));
            LoginOutcomeHandlers.mark(exchange, AccessDecision.UNAUTHENTICATED);
            return redirects.sendRedirect(exchange, LOGIN_ERROR);
        };
    }

    /** Successful directory login, before the gateway session exists. */
    static final class LdapUserAuthentication extends AbstractAuthenticationToken {

        private final LdapUser user;

        LdapUserAuthentication(LdapUser user) {
            super(List.of());
            this.user = user;
            setAuthenticated(true);
        }

        LdapUser user() {
            return user;
        }

        @Override
        public Object getCredentials() {
            return "";
        }

        @Override
        public Object getPrincipal() {
            return user.username();
        }
    }

    static final class LoginThrottledException extends AuthenticationException {

        LoginThrottledException() {
            super("Too many failed logins");
        }
    }
}

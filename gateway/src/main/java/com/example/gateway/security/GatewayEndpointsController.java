package com.example.gateway.security;

import com.example.gateway.access.AccessAttributes;
import com.example.gateway.access.GatewayIdentity;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Gateway-owned endpoints shared by both providers. Annotated controllers are
 * matched before gateway routes, so these paths can never be shadowed by a
 * {@code GatewayRoute} (the operator also rejects routes under {@code /_gateway/}).
 * Registered in every mode; with security off they answer 404.
 */
@RestController
@RequestMapping("/_gateway")
public class GatewayEndpointsController {

    public static final String WHOAMI_ACTION = "auth.whoami";
    public static final String LOGOUT_ACTION = "auth.logout";

    private final ActiveSecurity security;

    public GatewayEndpointsController(ActiveSecurity security) {
        this.security = security;
    }

    @GetMapping("/whoami")
    public ResponseEntity<Map<String, Object>> whoami(ServerWebExchange exchange) {
        if (!security.enabled()) {
            return ResponseEntity.notFound().build();
        }
        exchange.getAttributes().put(AccessAttributes.AUDIT_ACTION, WHOAMI_ACTION);
        GatewayIdentity identity = exchange.getAttribute(AccessAttributes.IDENTITY);
        if (identity == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("authenticated", false));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authenticated", true);
        body.put("user", identity.username());
        body.put("email", identity.email());
        body.put("subject", identity.subject());
        body.put("authorities", identity.authorities().stream().sorted().toList());
        body.put("method", identity.method().auditValue());
        body.put("clientId", identity.clientId());
        body.put("expiresAt", identity.expiresAt() == null ? null : identity.expiresAt().toString());
        return ResponseEntity.ok(body);
    }

    @RequestMapping(path = "/logout", method = {RequestMethod.GET, RequestMethod.POST})
    public Mono<ResponseEntity<Void>> logout(ServerWebExchange exchange) {
        if (!security.enabled()) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        exchange.getAttributes().put(AccessAttributes.AUDIT_ACTION, LOGOUT_ACTION);
        return security.sessionManager().logout(exchange)
                .map(target -> ResponseEntity.status(HttpStatus.FOUND).location(target).<Void>build());
    }
}

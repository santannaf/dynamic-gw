package com.example.gateway.security;

import com.example.gateway.security.session.GatewaySessionManager;
import com.example.gateway.security.session.SessionRevocations;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Where a replica hears about a logout that ran on another one. Only another replica can
 * write a valid notice (it is encrypted with the session key), and nginx-lb refuses the
 * path from outside, like the route reload.
 */
@RestController
public class InternalSessionsController {

    private final ActiveSecurity security;

    public InternalSessionsController(ActiveSecurity security) {
        this.security = security;
    }

    @PostMapping(path = SessionRevocations.NOTICE_PATH, consumes = MediaType.TEXT_PLAIN_VALUE)
    public Mono<ResponseEntity<Void>> revoked(@RequestBody Mono<String> notice) {
        GatewaySessionManager sessions = security.sessionManager();
        if (sessions == null) {
            return Mono.just(ResponseEntity.notFound().build());
        }
        return notice.map(body -> sessions.revocations().accept(body)
                ? ResponseEntity.noContent().<Void>build()
                : ResponseEntity.badRequest().<Void>build());
    }
}

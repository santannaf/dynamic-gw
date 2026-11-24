package com.example.gateway.routes;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayRoutesBootstrapTest {

    @Test
    void runDelegatesToReloadService() {
        GatewayRouteReloadService reloadService = mock(GatewayRouteReloadService.class);
        when(reloadService.reloadFromStore()).thenReturn(List.of("a", "b"));
        GatewayRoutesBootstrap bootstrap = new GatewayRoutesBootstrap(reloadService);

        bootstrap.run(null);

        verify(reloadService, times(1)).reloadFromStore();
    }

    @Test
    void runSwallowsExceptionToLetAppStart() {
        GatewayRouteReloadService reloadService = mock(GatewayRouteReloadService.class);
        when(reloadService.reloadFromStore()).thenThrow(new RuntimeException("backend down"));
        GatewayRoutesBootstrap bootstrap = new GatewayRoutesBootstrap(reloadService);

        bootstrap.run(null);

        verify(reloadService, times(1)).reloadFromStore();
    }
}

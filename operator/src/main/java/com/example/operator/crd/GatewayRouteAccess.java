package com.example.operator.crd;

import com.example.shared.routes.RouteObo;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayRouteAccess {

    private String mode;
    private List<String> anyOf;
    private Boolean forwardToken;
    private String channel;
    private String identityProvider;
    private String audience;
    private RouteObo obo;

    public String getMode() { return mode; }

    public void setMode(String mode) { this.mode = mode; }

    public List<String> getAnyOf() { return anyOf; }

    public void setAnyOf(List<String> anyOf) { this.anyOf = anyOf; }

    public Boolean getForwardToken() { return forwardToken; }

    public void setForwardToken(Boolean forwardToken) { this.forwardToken = forwardToken; }

    public String getChannel() { return channel; }

    public void setChannel(String channel) { this.channel = channel; }

    public String getIdentityProvider() { return identityProvider; }

    public void setIdentityProvider(String identityProvider) { this.identityProvider = identityProvider; }

    public String getAudience() { return audience; }

    public void setAudience(String audience) { this.audience = audience; }

    public RouteObo getObo() { return obo; }

    public void setObo(RouteObo obo) { this.obo = obo; }
}

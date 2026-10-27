package com.example.operator.crd;

import java.util.List;

public class GatewayRouteAccess {

    private String mode;
    private List<String> anyOf;
    private Boolean forwardToken;

    public String getMode() { return mode; }

    public void setMode(String mode) { this.mode = mode; }

    public List<String> getAnyOf() { return anyOf; }

    public void setAnyOf(List<String> anyOf) { this.anyOf = anyOf; }

    public Boolean getForwardToken() { return forwardToken; }

    public void setForwardToken(Boolean forwardToken) { this.forwardToken = forwardToken; }
}

package com.example.operator.crd;

import java.util.List;

public class GatewayRouteSpec {

    private String path;
    private String targetUri;
    private Integer stripPrefix;
    private List<String> methods;
    private Boolean enabled;
    // Optional metadata. Does not affect routing — propagated as-is into the
    // snapshot so the gateway can surface it via /internal/routes.
    private String team;
    private String description;

    public String getPath() { return path; }

    public void setPath(String path) { this.path = path; }

    public String getTargetUri() { return targetUri; }

    public void setTargetUri(String targetUri) { this.targetUri = targetUri; }

    public Integer getStripPrefix() { return stripPrefix; }

    public void setStripPrefix(Integer stripPrefix) { this.stripPrefix = stripPrefix; }

    public List<String> getMethods() { return methods; }

    public void setMethods(List<String> methods) { this.methods = methods; }

    public Boolean getEnabled() { return enabled; }

    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public String getTeam() { return team; }

    public void setTeam(String team) { this.team = team; }

    public String getDescription() { return description; }

    public void setDescription(String description) { this.description = description; }
}

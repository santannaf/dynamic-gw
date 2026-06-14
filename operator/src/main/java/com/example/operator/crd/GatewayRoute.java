package com.example.operator.crd;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("platform.saca.pags")
@Version("v1alpha1")
@Kind("GatewayRoute")
@Plural("gatewayroutes")
@Singular("gatewayroute")
@ShortNames("gwr")
public class GatewayRoute extends CustomResource<GatewayRouteSpec, Void> implements Namespaced {
}

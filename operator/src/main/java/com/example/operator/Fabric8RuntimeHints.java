package com.example.operator;

import org.jspecify.annotations.NonNull;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class Fabric8RuntimeHints implements RuntimeHintsRegistrar {

    private static final String[] FABRIC8_IMPL_CLASSES = {
        "io.fabric8.kubernetes.client.impl.Adapters",
        "io.fabric8.kubernetes.client.impl.AdmissionRegistrationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.ApiextensionsAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.AppsAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.AuthenticationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.AuthorizationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.AutoscalingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.BaseClient",
        "io.fabric8.kubernetes.client.impl.BatchAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.CertificatesAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.DiscoveryAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.DynamicResourceAllocationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.EventingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.ExtensionsAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.FlowControlAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.Handlers",
        "io.fabric8.kubernetes.client.impl.InternalExtensionAdapter",
        "io.fabric8.kubernetes.client.impl.KubernetesClientImpl",
        "io.fabric8.kubernetes.client.impl.MetricAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.NamespaceableResourceAdapter",
        "io.fabric8.kubernetes.client.impl.NetworkAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.PolicyAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.RbacAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.ResourcedHasMetadataOperation",
        "io.fabric8.kubernetes.client.impl.ResourceHandler",
        "io.fabric8.kubernetes.client.impl.ResourceHandlerImpl",
        "io.fabric8.kubernetes.client.impl.SchedulingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.StorageAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.URLFromClusterIPImpl",
        "io.fabric8.kubernetes.client.impl.URLFromEnvVarsImpl",
        "io.fabric8.kubernetes.client.impl.URLFromIngressImpl",
        "io.fabric8.kubernetes.client.impl.URLFromNodePortImpl",
        "io.fabric8.kubernetes.client.impl.V1APIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1AdmissionRegistrationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1Alpha1CertificatesAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1Alpha2DynamicResourceAllocationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1ApiextensionsAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1AuthenticationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1AuthorizationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1AutoscalingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1BatchAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1CertificatesAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1DiscoveryAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1DynamicResourceAllocationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1EventingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1FlowControlAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1NetworkAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1PolicyAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1SchedulingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1StorageAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1AdmissionRegistrationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1ApiextensionsAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1AuthorizationAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1BatchAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1CertificatesAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1DiscoveryAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1EventingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1FlowControlAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1NetworkAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1PolicyAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1SchedulingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta1StorageAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta2FlowControlAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V1beta3FlowControlAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V2AutoscalingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V2beta1AutoscalingAPIGroupClient",
        "io.fabric8.kubernetes.client.impl.V2beta2AutoscalingAPIGroupClient"
    };

    @Override
    public void registerHints(@NonNull RuntimeHints hints, ClassLoader classLoader) {
        for (String className : FABRIC8_IMPL_CLASSES) {
            hints.reflection().registerTypeIfPresent(classLoader, className,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.INVOKE_PUBLIC_METHODS);
        }
    }
}

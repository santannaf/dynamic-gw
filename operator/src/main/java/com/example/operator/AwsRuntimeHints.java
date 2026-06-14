package com.example.operator;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

import java.util.List;

/**
 * GraalVM hints for the AWS SDK v2 S3 client used by the operator when the
 * route config store backend is "s3". See gateway/AwsRuntimeHints for the
 * rationale; the lists are identical because both modules build the same
 * S3Client.
 */
public class AwsRuntimeHints implements RuntimeHintsRegistrar {

    private static final List<String> REFLECTIVE_CLASSES = List.of(
            "software.amazon.awssdk.auth.credentials.EnvironmentVariableCredentialsProvider",
            "software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider",
            "software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider",
            "software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider",
            "software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider",
            "software.amazon.awssdk.auth.credentials.WebIdentityTokenFileCredentialsProvider",
            "software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider",
            "software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain",
            "software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient"
    );

    private static final List<String> RESOURCE_PATTERNS = List.of(
            "software/amazon/awssdk/global/handlers/execution.interceptors",
            "software/amazon/awssdk/services/s3/execution.interceptors",
            "software/amazon/awssdk/services/s3/internal/endpoints/endpoint-rule-set.json",
            "software/amazon/awssdk/services/s3/internal/endpoints/partitions.json.resource"
    );

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        REFLECTIVE_CLASSES.forEach(name -> hints.reflection().registerTypeIfPresent(
                classLoader,
                name,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.INVOKE_PUBLIC_METHODS,
                MemberCategory.INVOKE_DECLARED_METHODS));
        RESOURCE_PATTERNS.forEach(p -> hints.resources().registerPattern(p));
    }
}

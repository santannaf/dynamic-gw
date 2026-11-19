package com.example.gateway;

import com.example.gateway.routes.store.RouteStoreProperties;
import com.example.shared.routes.RoutePolicyRules;
import com.example.gateway.security.GatewaySecurityProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The profiles embedded in the image, combined as the k8s/sso manifests do:
 * every key must bind (a typo would be silently ignored at runtime) and no
 * secret may come from them. Each profile file lives in a folder, which Spring
 * Boot does not search, so application.yaml imports it, and the native image
 * only embeds it with a hint.
 */
class GatewayProfilesTest {

    private static final Map<String, String> PROFILE_FILES = Map.of(
            "keycloak", "keycloak/application-keycloak.yaml",
            "ldap", "ldap/application-ldap.yaml",
            "oauth", "oauth/application-oauth.yaml",
            "s3", "s3/application-s3.yaml",
            "standalone", "properties/application-standalone.yaml",
            "standalone-sso", "properties/application-standalone-sso.yaml");

    private static final Pattern FOLDER_RESOURCE = Pattern.compile("class path resource \\[([^\\]]+/[^\\]]+)]");

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(List.of("keycloak"), "configmap", "keycloak"),
                Arguments.of(List.of("ldap"), "configmap", "ldap"),
                Arguments.of(List.of("s3", "keycloak"), "s3", "keycloak"),
                Arguments.of(List.of("s3", "ldap"), "s3", "ldap"),
                Arguments.of(List.of("standalone-sso", "keycloak"), "properties", "keycloak"),
                Arguments.of(List.of("standalone-sso", "ldap"), "properties", "ldap"),
                Arguments.of(List.of("oauth"), "configmap", "oauth"),
                Arguments.of(List.of("s3", "oauth"), "s3", "oauth"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyScenarioTurnsOnSsoAndAuditOverItsRouteStore(List<String> profiles, String store, String provider) {
        ConfigurableEnvironment env = environment(profiles, Map.of());
        GatewaySecurityProperties security = bindStrictly(env, "gateway.security", GatewaySecurityProperties.class);
        RouteStoreProperties routes = bindStrictly(env, "gateway.routes.store", RouteStoreProperties.class);

        assertThat(security.isEnabled()).isTrue();
        assertThat(security.getProvider()).isEqualTo(provider);
        assertThat(security.getDefaultAccess()).isEqualTo("authenticated");
        assertThat(env.getProperty("gateway.audit.enabled", Boolean.class)).isTrue();
        assertThat(env.getProperty("gateway.audit.include-query", Boolean.class)).isFalse();
        assertThat(routes.getType()).isEqualTo(store);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void secretsNeverComeFromTheEmbeddedProfiles(List<String> profiles, String store, String provider) {
        GatewaySecurityProperties security = bindStrictly(environment(profiles, Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getOidc().getClientSecret()).isNull();
        assertThat(security.getLdap().getBindPassword()).isNull();
        assertThat(security.getSession().getKeys()).isEmpty();
        assertThat(security.getObo().getClientSecret()).isNull();
    }

    @Test
    void keycloakProfileTargetsTheToolsRealm() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("keycloak"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getOidc().getIssuer()).isEqualTo("http://localhost:8080/auth/realms/tools");
        assertThat(security.getOidc().getInternalIssuer())
                .isEqualTo("http://keycloak.platform.svc.cluster.local:8080/auth/realms/tools");
        assertThat(security.getOidc().getClientId()).isEqualTo("tools-gateway");
        assertThat(security.getOidc().getAudience()).isEqualTo("tools-gateway");
        assertThat(security.getInternal().getAnyOf()).containsExactly("role:gateway-admin");
        assertThat(security.getSession().isCookieSecure()).isTrue();
    }

    @Test
    void keycloakRolesComeOnlyFromTheGatewayClient() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("keycloak"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getAuthorities().getRoleClaims()).containsExactly("resource_access.{client-id}.roles");
        assertThat(security.getAuthorities().getGroupClaims()).containsExactly("groups");
    }

    @Test
    void oauthProfileValidatesTokensWithoutSession() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("oauth"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getOauth().getIssuer()).isEqualTo("http://localhost:8080/auth/realms/tools");
        assertThat(security.getOauth().getJwkSetUri()).isEqualTo(
                "http://keycloak.platform.svc.cluster.local:8080/auth/realms/tools/protocol/openid-connect/certs");
        assertThat(security.getOauth().getAudience()).isEqualTo("tools-gateway");
        assertThat(security.getAuthorities().getClaims()).containsEntry("scope", List.of("scope"));
        assertThat(security.getAuthorities().getRoleClaims()).containsExactly("resource_access.tools-gateway.roles");
        assertThat(security.getPublicBaseUrl()).isNull();
    }

    @Test
    void issuerFollowsThePublicBaseUrlSetByTheDeployment() {
        GatewaySecurityProperties security = bindStrictly(
                environment(List.of("keycloak"), Map.of("GATEWAY_SECURITY_PUBLIC_BASE_URL", "http://localhost:18080")),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getPublicBaseUrl()).isEqualTo("http://localhost:18080");
        assertThat(security.getOidc().getIssuer()).isEqualTo("http://localhost:18080/auth/realms/tools");
    }

    @Test
    void ldapProfileSearchesTheToolsOrganizationalUnit() {
        GatewaySecurityProperties.Ldap ldap = bindStrictly(environment(List.of("ldap"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class).getLdap();

        assertThat(ldap.getUrl()).isEqualTo("ldap://openldap.platform.svc.cluster.local:389");
        assertThat(ldap.getBindDn()).isEqualTo("cn=readonly,dc=tools,dc=local");
        assertThat(ldap.getUserSearch().getBase()).isEqualTo("ou=people,dc=tools,dc=local");
        assertThat(ldap.getUserSearch().getFilter()).isEqualTo("(uid={0})");
        assertThat(ldap.getGroupSearch().getBase()).isEqualTo("ou=tools,dc=tools,dc=local");
        assertThat(ldap.getGroupSearch().getFilter()).isEqualTo("(member={0})");
        assertThat(ldap.getRevalidateAfter()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void ldapOperatorsAreTheirOwnGroupBecauseTheDirectoryHasNoRoles() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("ldap"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getInternal().getAnyOf()).containsExactly("group:gateway-admins");
    }

    @Test
    void s3ProfileLeavesOnlyTheBucketToTheEnvironment() {
        RouteStoreProperties.S3 s3 = bindStrictly(environment(List.of("s3"), Map.of()),
                "gateway.routes.store", RouteStoreProperties.class).getS3();

        assertThat(s3.getBucket()).isEmpty();
        assertThat(s3.getKey()).isEqualTo("snapshots/routes.yaml");
        assertThat(s3.getRegion()).isEqualTo("us-east-1");
        assertThat(s3.getPollInterval()).isEqualTo(Duration.ofSeconds(15));
        assertThat(s3.getEndpoint()).isNull();
    }

    @Test
    void standaloneSampleRoutesBindStrictlyAndFollowThePolicyRules() {
        RouteStoreProperties routes = bindStrictly(environment(List.of("standalone"), Map.of()),
                "gateway.routes.store", RouteStoreProperties.class);

        assertThat(routes.getProperties().getRoutes()).hasSize(18).allSatisfy(route ->
                assertThat(RoutePolicyRules.validate(route, RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX))
                        .as(route.id()).isEmpty());
        assertThat(routes.getProperties().getRoutes()).filteredOn(route -> route.aggregate() != null).hasSize(4);
        assertThat(routes.getProperties().getRoutes()).filteredOn(route -> route.id().equals("jp-artigos")).singleElement()
                .satisfies(route -> assertThat(route.transform().mapping()).as("map keys keep their case")
                        .containsEntry("userId", "autorId"));
    }

    @Test
    void standaloneSsoAloneStillTurnsSecurityOn() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("standalone-sso"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);
        RouteStoreProperties routes = bindStrictly(environment(List.of("standalone-sso"), Map.of()),
                "gateway.routes.store", RouteStoreProperties.class);

        assertThat(security.isEnabled()).isTrue();
        assertThat(routes.getProperties().getRoutes()).hasSize(15).allSatisfy(route ->
                assertThat(RoutePolicyRules.validate(route, RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX))
                        .as(route.id()).isEmpty());
    }

    static Stream<String> profiles() {
        return PROFILE_FILES.keySet().stream().sorted();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void eachProfileImportsOnlyTheFileInItsFolder(String profile) {
        assertThat(importedFiles(environment(List.of(profile), Map.of()))).containsExactly(PROFILE_FILES.get(profile));
    }

    @Test
    void withoutProfilesNoFolderIsImported() {
        assertThat(importedFiles(environment(List.of(), Map.of()))).isEmpty();
    }

    @Test
    void theProviderFileTakesPrecedenceOverTheRouteOriginFile() {
        assertThat(importedFiles(environment(List.of("s3", "keycloak"), Map.of())))
                .containsExactly(PROFILE_FILES.get("keycloak"), PROFILE_FILES.get("s3"));
        assertThat(importedFiles(environment(List.of("ldap", "standalone-sso"), Map.of())))
                .containsExactly(PROFILE_FILES.get("ldap"), PROFILE_FILES.get("standalone-sso"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void aProfileWhoseFileIsMissingStopsTheStartup(String profile) {
        String file = PROFILE_FILES.get(profile);
        ResourceLoader withoutTheFile = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return location.endsWith(file) ? new ClassPathResource("missing/" + file) : super.getResource(location);
            }
        };

        assertThatThrownBy(() -> environment(List.of(profile), Map.of(), withoutTheFile))
                .isInstanceOf(ConfigDataResourceNotFoundException.class)
                .hasMessageContaining(file);
    }

    @Test
    void everyFileInAProfileFolderIsAProfileFileEmbeddedInTheNativeImage() throws Exception {
        RuntimeHints hints = new RuntimeHints();
        new GatewayRuntimeHints().registerHints(hints, getClass().getClassLoader());
        Path resources = Path.of(new ClassPathResource("application.yaml").getURI()).getParent();

        List<String> folderFiles;
        try (Stream<Path> files = Files.walk(resources)) {
            folderFiles = files
                    .filter(file -> resources.relativize(file).getNameCount() > 1)
                    .filter(file -> file.toString().endsWith(".yaml") || file.toString().endsWith(".yml"))
                    .map(file -> resources.relativize(file).toString().replace(File.separatorChar, '/'))
                    .toList();
        }

        assertThat(folderFiles).containsExactlyInAnyOrderElementsOf(PROFILE_FILES.values());
        assertThat(folderFiles).allSatisfy(file ->
                assertThat(RuntimeHintsPredicates.resource().forResource(file).test(hints)).as(file).isTrue());
    }

    private static ConfigurableEnvironment environment(List<String> profiles, Map<String, Object> variables) {
        return environment(profiles, variables, null);
    }

    private static ConfigurableEnvironment environment(List<String> profiles, Map<String, Object> variables,
                                                       ResourceLoader resourceLoader) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("deployment", variables));
        ConfigDataEnvironmentPostProcessor.applyTo(env, resourceLoader, null, profiles);
        return env;
    }

    private static List<String> importedFiles(ConfigurableEnvironment env) {
        return env.getPropertySources().stream()
                .map(source -> FOLDER_RESOURCE.matcher(source.getName()))
                .filter(Matcher::find)
                .map(matcher -> matcher.group(1))
                .toList();
    }

    private static <T> T bindStrictly(ConfigurableEnvironment env, String prefix, Class<T> type) {
        return Binder.get(env)
                .bind(prefix, Bindable.of(type), new NoUnboundElementsBindHandler(BindHandler.DEFAULT))
                .get();
    }
}

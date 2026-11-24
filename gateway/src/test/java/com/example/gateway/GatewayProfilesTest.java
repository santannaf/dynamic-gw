package com.example.gateway;

import com.example.gateway.routes.store.RouteStoreProperties;
import com.example.gateway.security.GatewaySecurityProperties;
import com.example.shared.routes.RoutePolicyRules;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.context.config.ConfigDataResourceNotFoundException;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.UnboundConfigurationPropertiesException;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.ConfigurationPropertyState;
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
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The profiles embedded in the image, combined as the k8s/sso manifests do:
 * every key must bind (a typo would be silently ignored at runtime) and no
 * secret may come from them. Each profile file lives in a folder, which Spring
 * Boot does not search, so application.yaml imports it, and the native image
 * only embeds it with a hint. Nothing here relies on the defaults of
 * application.yaml, which each gateway built from this one changes and tests
 * on its own: a scenario carries the variables its manifest sets.
 */
class GatewayProfilesTest {

    private static final Map<String, String> PROFILE_FILES = Map.of(
            "keycloak", "keycloak/application-keycloak.yaml",
            "keycloak-ldap", "keycloak-ldap/application-keycloak-ldap.yaml",
            "ldap", "ldap/application-ldap.yaml",
            "ldap-directory", "ldap-directory/application-ldap-directory.yaml",
            "oauth", "oauth/application-oauth.yaml",
            "s3", "s3/application-s3.yaml",
            "standalone", "properties/application-standalone.yaml",
            "standalone-sso", "properties/application-standalone-sso.yaml");

    private static final Pattern FOLDER_RESOURCE = Pattern.compile("class path resource \\[([^\\]]+/[^\\]]+)]");

    // The v2 manifests (k8s/gateway, k8s/sso) pick the ConfigMap by variable, not by profile.
    private static final Map<String, Object> CONFIGMAP = Map.of("GATEWAY_ROUTES_STORE_TYPE", "configmap");

    static Stream<Arguments> scenarios() {
        return Stream.of(
                Arguments.of(List.of("keycloak"), CONFIGMAP, "configmap", "keycloak"),
                Arguments.of(List.of("ldap"), CONFIGMAP, "configmap", "ldap"),
                Arguments.of(List.of("s3", "keycloak"), Map.of(), "s3", "keycloak"),
                Arguments.of(List.of("s3", "ldap"), Map.of(), "s3", "ldap"),
                Arguments.of(List.of("standalone-sso", "keycloak"), Map.of(), "properties", "keycloak"),
                Arguments.of(List.of("standalone-sso", "ldap"), Map.of(), "properties", "ldap"),
                Arguments.of(List.of("oauth"), CONFIGMAP, "configmap", "oauth"),
                Arguments.of(List.of("s3", "oauth"), Map.of(), "s3", "oauth"),
                Arguments.of(List.of("keycloak-ldap"), CONFIGMAP, "configmap", "keycloak"),
                Arguments.of(List.of("s3", "keycloak-ldap"), Map.of(), "s3", "keycloak"),
                Arguments.of(List.of("standalone-sso", "keycloak-ldap"), Map.of(), "properties", "keycloak"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    void everyScenarioTurnsOnSsoAndAuditOverItsRouteStore(List<String> profiles, Map<String, Object> variables,
                                                          String store, String provider) {
        ConfigurableEnvironment env = environment(profiles, variables);
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
    void secretsNeverComeFromTheEmbeddedProfiles(List<String> profiles) {
        GatewaySecurityProperties security = bindStrictly(environment(profiles, Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getOidc().getClientSecret()).isNull();
        assertThat(security.getLdap().getBindPassword()).isNull();
        assertThat(security.getSession().getKeys()).isEmpty();
        assertThat(security.getObo().getClientSecret()).isNull();
    }

    static Stream<Arguments> scenariosWithoutSso() {
        return Stream.of(
                Arguments.of(List.of(), CONFIGMAP, "configmap"),
                Arguments.of(List.of("standalone"), Map.of(), "properties"),
                Arguments.of(List.of("s3"), Map.of(), "s3"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenariosWithoutSso")
    void withoutALoginProfileTheRouteStoreRunsWithoutSso(List<String> profiles, Map<String, Object> variables,
                                                         String store) {
        ConfigurableEnvironment env = environment(profiles, variables);
        GatewaySecurityProperties security = bindStrictly(env, "gateway.security", GatewaySecurityProperties.class);
        RouteStoreProperties routes = bindStrictly(env, "gateway.routes.store", RouteStoreProperties.class);

        assertThat(security.isEnabled()).isFalse();
        assertThat(routes.getType()).isEqualTo(store);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keycloak", "ldap", "oauth", "keycloak-ldap"})
    void aLoginProfileKeepsTheRouteStoreOfTheImage(String profile) {
        RouteStoreProperties image = bindStrictly(environment(List.of(), Map.of()),
                "gateway.routes.store", RouteStoreProperties.class);
        RouteStoreProperties withLogin = bindStrictly(environment(List.of(profile), Map.of()),
                "gateway.routes.store", RouteStoreProperties.class);

        assertThat(withLogin.getType()).isEqualTo(image.getType());
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
    void keycloakLdapRunsTheLdapChannelNextToKeycloak() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("keycloak-ldap"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getProvider()).isEqualTo("keycloak");
        assertThat(security.getAdditionalProviders()).containsExactly("ldap");
        assertThat(security.getOidc().getClientId()).isEqualTo("tools-gateway");
        assertThat(security.getLdap().getUrl()).isEqualTo("ldap://openldap.platform.svc.cluster.local:389");
        assertThat(security.getLdap().getGroupSearch().getBase()).isEqualTo("ou=tools,dc=tools,dc=local");
        assertThat(security.getInternal().getAnyOf()).containsExactly("role:gateway-admin", "group:gateway-admins");
    }

    @Test
    void ldapOperatorsAreTheirOwnGroupBecauseTheDirectoryHasNoRoles() {
        GatewaySecurityProperties security = bindStrictly(environment(List.of("ldap"), Map.of()),
                "gateway.security", GatewaySecurityProperties.class);

        assertThat(security.getInternal().getAnyOf()).containsExactly("group:gateway-admins");
    }

    @Test
    void s3ProfileLeavesOnlyTheBucketToTheEnvironment() {
        var env = environment(List.of("s3"), Map.of());
        RouteStoreProperties.S3 s3 = bindStrictly(env,
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
        assertThat(routes.getProperties().getRoutes()).hasSize(18).allSatisfy(route ->
                assertThat(RoutePolicyRules.validate(route, RoutePolicyRules.DEFAULT_IDENTITY_HEADER_PREFIX))
                        .as(route.id()).isEmpty());
    }

    static Stream<String> profiles() {
        return PROFILE_FILES.keySet().stream().sorted();
    }

    // Profile groups of application.yaml, in the order their files take precedence.
    private static final Map<String, List<String>> PROFILE_GROUPS = Map.of(
            "ldap", List.of("ldap", "ldap-directory"),
            "keycloak-ldap", List.of("keycloak-ldap", "ldap-directory", "keycloak"));

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void eachProfileImportsOnlyTheFilesOfItsFolderAndItsGroup(String profile) {
        assertThat(importedFiles(environment(List.of(profile), Map.of())))
                .containsExactlyElementsOf(PROFILE_GROUPS.getOrDefault(profile, List.of(profile)).stream()
                        .map(PROFILE_FILES::get).toList());
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
                .containsExactly(PROFILE_FILES.get("ldap"), PROFILE_FILES.get("ldap-directory"),
                        PROFILE_FILES.get("standalone-sso"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("profiles")
    void aProfileWhoseFileIsMissingStopsTheStartup(String profile) {
        String file = PROFILE_FILES.get(profile);
        ResourceLoader withoutTheFile = new DefaultResourceLoader() {
            @NonNull
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
        // Only what a profile loads has to be a profile file embedded in the native image: a file that
        // no profile loads stays out of the JVM and of the native image alike.
        Set<String> loaded = Stream.concat(PROFILE_FILES.keySet().stream(), folderFiles.stream().map(GatewayProfilesTest::profileOf))
                .distinct()
                .flatMap(profile -> importedFiles(environment(List.of(profile), Map.of())).stream())
                .collect(Collectors.toSet());
        List<String> profileFiles = folderFiles.stream().filter(loaded::contains).toList();

        assertThat(profileFiles).containsExactlyInAnyOrderElementsOf(PROFILE_FILES.values());
        assertThat(profileFiles).allSatisfy(file ->
                assertThat(RuntimeHintsPredicates.resource().forResource(file).test(hints)).as(file).isTrue());
    }

    private static String profileOf(String file) {
        String name = file.substring(file.lastIndexOf('/') + 1);
        return name.substring(0, name.lastIndexOf('.')).replaceFirst("^application-", "");
    }

    static ConfigurableEnvironment environment(List<String> profiles, Map<String, Object> variables) {
        return environment(profiles, variables, null);
    }

    private static ConfigurableEnvironment environment(List<String> profiles, Map<String, Object> variables,
                                                       ResourceLoader resourceLoader) {
        StandardEnvironment env = new StandardEnvironment();
        // Only the scenario's variables count: neither the environment nor the -D of the machine.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
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

    // Spring Boot replaces a list as a whole: a profile with its own catalog replaces the one of
    // application.yaml, which then does not bind. NoUnboundElementsBindHandler forgives most of the
    // replaced elements, but not a list inside one (routes[2].methods), and only those are forgiven here.
    static <T> T bindStrictly(ConfigurableEnvironment env, String prefix, Class<T> type) {
        Binder binder = Binder.get(env);
        try {
            return binder.bindOrCreate(prefix, Bindable.of(type), new NoUnboundElementsBindHandler(BindHandler.DEFAULT));
        } catch (BindException ex) {
            if (ex.getCause() instanceof UnboundConfigurationPropertiesException unbound
                    && unbound.getUnboundProperties().stream().allMatch(property -> inAReplacedList(env, property))) {
                return binder.bindOrCreate(prefix, Bindable.of(type));
            }
            throw ex;
        }
    }

    private static boolean inAReplacedList(ConfigurableEnvironment env, ConfigurationProperty property) {
        ConfigurationPropertyName name = property.getName();
        for (int i = 0; i < name.getNumberOfElements(); i++) {
            if (name.isNumericIndex(i)) {
                ConfigurationPropertyName list = name.chop(i);
                for (ConfigurationPropertySource source : ConfigurationPropertySources.get(env)) {
                    if (source.containsDescendantOf(list) == ConfigurationPropertyState.PRESENT) {
                        return source.getUnderlyingSource() != property.getSource().getUnderlyingSource();
                    }
                }
                return false;
            }
        }
        return false;
    }
}

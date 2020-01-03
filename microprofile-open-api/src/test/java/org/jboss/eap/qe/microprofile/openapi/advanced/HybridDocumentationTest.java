package org.jboss.eap.qe.microprofile.openapi.advanced;

import static io.restassured.RestAssured.get;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;

import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.openapi.models.OpenAPI;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.OperateOnDeployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.eap.qe.microprofile.openapi.OpenApiServerConfiguration;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.ProviderApplication;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.RoutingServiceConstants;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.api.DistrictService;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.data.DistrictEntity;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.model.District;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.rest.DistrictsResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.provider.services.InMemoryDistrictService;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.RouterApplication;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.model.DistrictObject;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.LocalServiceRouterInfoResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.routed.RouterDistrictsResource;
import org.jboss.eap.qe.microprofile.openapi.apps.routing.router.services.DistrictServiceClient;
import org.jboss.eap.qe.microprofile.openapi.model.OpenApiFilter;
import org.jboss.eap.qe.microprofile.openapi.model.OpenApiModelReader;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.ConfigurationException;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.arquillian.ArquillianContainerProperties;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.arquillian.ArquillianDescriptorWrapper;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientProvider;
import org.jboss.eap.qe.microprofile.tooling.server.configuration.creaper.ManagementClientRelatedException;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.wildfly.extras.creaper.core.online.OnlineManagementClient;

/**
 * Test to assess that the process of generating OpenAPI document is properly executed by following all the
 * MicroProfile OpenAPI spec processing rules - i.e. starting from static file and performing customization and
 * filtering.
 * The real world scenario for Local Services Router app is implemented here: the OpenAPI document is provided as
 * deliverable by Service Provider staff to Local Services Router staff so that it can be used as official documentation
 * once proper customization is applied.
 */
@RunWith(Arquillian.class)
@ServerSetup({ HybridDocumentationTest.OpenApiExtensionSetup.class })
@RunAsClient
public class HybridDocumentationTest {

    private final static String PROVIDER_DEPLOYMENT_NAME = "serviceProviderDeployment";
    private final static String ROUTER_DEPLOYMENT_NAME = "localServicesRouterDeployment";
    private final static String CONFIGURATION_TEMPLATE = "mp.openapi.scan.exclude.packages=org.jboss.eap.qe.microprofile.openapi.apps.routing.router.rest.routed"
            + "\n" +
            "mp.openapi.model.reader=org.jboss.eap.qe.microprofile.openapi.model.OpenApiModelReader"
            + "\n" +
            "mp.openapi.filter=org.jboss.eap.qe.microprofile.openapi.model.OpenApiFilter"
            + "\n" +
            "mp.openapi.scan.disable=false"
            + "\n" +
            "services.provider.host=%s"
            + "\n" +
            "services.provider.port=%d";

    private static ArquillianContainerProperties arquillianContainerProperties = new ArquillianContainerProperties(
            ArquillianDescriptorWrapper.getArquillianDescriptor());

    @Deployment(name = PROVIDER_DEPLOYMENT_NAME, order = 1, testable = false)
    public static Archive<?> serviceProviderDeployment() {
        //  MP OpenAPI disabled on this Services Provider deployment for testing purposes, we don't want it here
        String mpConfigProperties = "mp.openapi.extensions.enabled=false";
        return ShrinkWrap.create(
                WebArchive.class, PROVIDER_DEPLOYMENT_NAME + ".war")
                .addClasses(
                        ProviderApplication.class,
                        District.class,
                        DistrictEntity.class,
                        DistrictService.class,
                        InMemoryDistrictService.class,
                        DistrictsResource.class,
                        RoutingServiceConstants.class)
                .addAsManifestResource(new StringAsset(mpConfigProperties), "microprofile-config.properties")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    /**
     * MP Config is used to tell MP OpenAPI to skip doc generation for exposed "routed" JAX-RS endpoints as service
     * consumers must rely to the original documentation (META-INF/openapi.yaml), plus annotations from Local Service
     * Provider "non-routed" JAX-RS endpoints, edited through an OASModelReader implementation and eventually filtered
     * through a OASFilter one.
     * Here we also add config properties to reach the Services Provider.
     *
     * @return A string containing a set of name/value configuration properties
     * @throws ManagementClientRelatedException CLI management exceptions
     * @throws ConfigurationException Arquillian container configuration exception
     * @throws IOException Management client disposal
     */
    private static String buildMicroProfileConfigProperties()
            throws ManagementClientRelatedException, ConfigurationException, IOException {

        int configuredHTTPPort;
        try (OnlineManagementClient client = ManagementClientProvider.onlineStandalone()) {
            configuredHTTPPort = OpenApiServerConfiguration.getHTTPPort(client);
        }
        return String.format(CONFIGURATION_TEMPLATE, arquillianContainerProperties.getDefaultManagementAddress(),
                configuredHTTPPort);
    }

    @Deployment(name = ROUTER_DEPLOYMENT_NAME, order = 2, testable = false)
    public static Archive<?> localServicesRouterDeployment()
            throws ConfigurationException, IOException, ManagementClientRelatedException {

        String mpConfigProperties = buildMicroProfileConfigProperties();

        return ShrinkWrap.create(
                WebArchive.class, ROUTER_DEPLOYMENT_NAME + ".war")
                .addClasses(
                        RouterApplication.class,
                        LocalServiceRouterInfoResource.class,
                        DistrictObject.class,
                        RouterDistrictsResource.class,
                        DistrictServiceClient.class,
                        OpenApiModelReader.class,
                        OpenApiFilter.class)
                .addAsManifestResource(new StringAsset(mpConfigProperties), "microprofile-config.properties")
                .addAsResource("META-INF/openapi.yaml")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml");
    }

    static class OpenApiExtensionSetup implements ServerSetupTask {

        @Override
        public void setup(ManagementClient managementClient, String containerId) throws Exception {
            OpenApiServerConfiguration.enableOpenApi(ManagementClientProvider.onlineStandalone(managementClient));
        }

        @Override
        public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
            OpenApiServerConfiguration.disableOpenApi(ManagementClientProvider.onlineStandalone(managementClient));
        }
    }

    /**
     * @tpTestDetails Integration test to verify MP Rest Client usage by Local Service Router JAX-RS resource
     * @tpPassCrit One of the Local Service Router endpoints is called to retrieve data from Service Provider,
     *             verifying the HTTP response code and content type
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    public void testRoutedEndpoint(@ArquillianResource @OperateOnDeployment(ROUTER_DEPLOYMENT_NAME) URL baseURL) {
        get(baseURL.toExternalForm() + "districts/all")
                .then()
                .statusCode(200)
                .contentType(equalToIgnoringCase(MediaType.APPLICATION_JSON));
    }

    /**
     * @tpTestDetails Verifies proper processing by assessing that information stored in non-routed services
     *                annotations is preserved in final document
     * @tpPassCrit The generated document contains a string that uniquely identifies one of the Local Service Router
     *             endpoints URL and that was generated from local JAX-RS resources annotation
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    public void testNonRoutedEndpoint(
            @ArquillianResource @OperateOnDeployment(ROUTER_DEPLOYMENT_NAME) URL baseURL)
            throws URISyntaxException {
        get(baseURL.toExternalForm() + "info/fqdn")
                .then()
                .statusCode(200)
                //  RestEasy >3.9.3 adds charset info unless resteasy.add.charset is set to false,
                //  thus expecting for it to be there, see
                //  https://docs.jboss.org/resteasy/docs/3.9.3.Final/userguide/html/Installation_Configuration.html#configuration_switches
                .contentType(equalToIgnoringCase("text/plain;charset=UTF-8"));
    }

    /**
     * @tpTestDetails Test to verify that static file has been used for OpenAPI document generation
     * @tpPassCrit The generated document does contain a string which uniquely identifies one of the routed Service
     *             Provider endpoints URL
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    public void testExpectedStaticFileInformationInOpenApiDoc(
            @ArquillianResource @OperateOnDeployment(ROUTER_DEPLOYMENT_NAME) URL baseURL) throws URISyntaxException {
        get(baseURL.toURI().resolve("/openapi"))
                .then()
                .statusCode(200)
                .contentType(equalToIgnoringCase("application/yaml"))
                .body(containsString("/districts/all:"));
    }

    /**
     * @tpTestDetails Verifies proper processing by using MP OpenAPI programming model to create a custom base document
     * @tpPassCrit Verifies that {@link OpenAPI#getInfo()} has been successfully modified by {@link OpenApiModelReader}
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    public void testOpenApiDocumentForOpenApiInfoChange(
            @ArquillianResource @OperateOnDeployment(ROUTER_DEPLOYMENT_NAME) URL baseURL) throws URISyntaxException {
        get(baseURL.toURI().resolve("/openapi"))
                .then()
                .statusCode(200)
                .contentType(equalToIgnoringCase("application/yaml"))
                .body(containsString("Generated"));
    }

    /**
     * @tpTestDetails Verifies proper processing by using MP OpenAPI programming model to filter the generated document
     * @tpPassCrit Verifies that {@link RoutingServiceConstants#OPENAPI_OPERATION_EXTENSION_PROXY_FQDN_NAME)} extension
     *             value has been successfully modified by {@link OpenApiFilter}
     * @tpSince EAP 7.4.0.CD19
     */
    @Test
    public void testOpenApiDocumentForRouterFqdnExtensionModification(
            @ArquillianResource @OperateOnDeployment(ROUTER_DEPLOYMENT_NAME) URL baseURL)
            throws URISyntaxException {
        get(baseURL.toURI().resolve("/openapi"))
                .then()
                .statusCode(200)
                .contentType(equalToIgnoringCase("application/yaml"))
                .body(containsString(OpenApiFilter.LOCAL_TEST_ROUTER_FQDN));
    }
}

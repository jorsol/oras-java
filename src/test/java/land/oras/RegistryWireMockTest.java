/*-
 * =LICENSE=
 * ORAS Java SDK
 * ===
 * Copyright (C) 2024 - 2025 ORAS
 * ===
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =LICENSEEND=
 */

package land.oras;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import land.oras.auth.FileStoreAuthenticationProvider;
import land.oras.auth.UsernamePasswordProvider;
import land.oras.credentials.FileStore;
import land.oras.exception.OrasException;
import land.oras.utils.Const;
import land.oras.utils.JsonUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@WireMockTest
@Execution(ExecutionMode.SAME_THREAD)
public class RegistryWireMockTest {

    private static final Logger LOG = LoggerFactory.getLogger(RegistryWireMockTest.class);

    private final UsernamePasswordProvider authProvider = new UsernamePasswordProvider("myuser", "mypass");

    @TempDir
    private Path configDir;

    @TempDir
    private Path ociLayout;

    @Test
    void shouldRedirectWhenDownloadingBlob(WireMockRuntimeInfo wmRuntimeInfo) {

        // Return data from wiremock
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(WireMock.any(WireMock.urlEqualTo("/v2/library/artifact-text/blobs/sha256:one"))
                .willReturn(WireMock.temporaryRedirect("http://localhost:%d/v2/library/artifact-text/blobs/sha256:other"
                        .formatted(wmRuntimeInfo.getHttpPort()))));

        // Return blob on new location
        wireMock.register(WireMock.head(WireMock.urlEqualTo("/v2/library/artifact-text/blobs/sha256:other"))
                .willReturn(WireMock.ok()));
        wireMock.register(WireMock.get(WireMock.urlEqualTo("/v2/library/artifact-text/blobs/sha256:other"))
                .willReturn(WireMock.ok().withBody("blob-data")));

        // Insecure registry
        Registry registry = Registry.Builder.builder()
                .withAuthProvider(authProvider)
                .withInsecure(true)
                .build();

        ContainerRef containerRef =
                ContainerRef.parse("localhost:%d/library/artifact-text".formatted(wmRuntimeInfo.getHttpPort()));
        byte[] blob = registry.getBlob(containerRef.withDigest("sha256:one"));
        assertEquals("blob-data", new String(blob));
    }

    @Test
    void shouldRedirectWhenPushingBlob(WireMockRuntimeInfo wmRuntimeInfo) throws IOException {

        // Return data from wiremock
        WireMock wireMock = wmRuntimeInfo.getWireMock();

        // Return location without domain when pushing blob
        wireMock.register(WireMock.head(WireMock.urlPathMatching("/v2/library/artifact-redirect/blobs/.*"))
                .willReturn(WireMock.status(404)));
        wireMock.register(WireMock.post(WireMock.urlPathMatching("/v2/library/artifact-redirect/blobs/uploads/.*"))
                .willReturn(WireMock.status(202).withHeader("Location", "/foobar")));

        // Push is on foobar
        wireMock.register(WireMock.put(WireMock.urlPathMatching("/foobar.*")).willReturn(WireMock.status(201)));

        // Insecure registry
        Registry registry = Registry.Builder.builder()
                .withAuthProvider(authProvider)
                .withInsecure(true)
                .build();

        ContainerRef containerRef = ContainerRef.parse(
                "localhost:%d/library/artifact-redirect@sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
                        .formatted(wmRuntimeInfo.getHttpPort()));
        registry.pushBlob(containerRef, "hello".getBytes());

        // Via file
        Path testFile = configDir.resolve("test-data.temp");
        Files.writeString(testFile, "Test Content");
        registry.pushBlob(containerRef, testFile);
    }

    @Test
    void shouldListTags(WireMockRuntimeInfo wmRuntimeInfo) {

        // Return data from wiremock
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(WireMock.get(WireMock.urlEqualTo("/v2/library/artifact-text/tags/list"))
                .willReturn(WireMock.okJson(JsonUtils.toJson(new Tags("artifact-text", List.of("latest", "0.1.1"))))));

        // Insecure registry
        Registry registry = Registry.Builder.builder()
                .withAuthProvider(authProvider)
                .withInsecure(true)
                .build();

        // Test
        List<String> tags = registry.getTags(ContainerRef.parse("%s/library/artifact-text"
                .formatted(wmRuntimeInfo.getHttpBaseUrl().replace("http://", ""))));

        // Assert
        assertEquals(2, tags.size());
        assertEquals("latest", tags.get(0));
        assertEquals("0.1.1", tags.get(1));
    }

    @Test
    void shouldListTagsWithFileStoreAuth(WireMockRuntimeInfo wmRuntimeInfo) throws IOException {

        // Auth file for current registry
        String authFile =
                """
                {
                        "auths": {
                                "localhost:%d": {
                                        "auth": "bXl1c2VyOm15cGFzcw=="
                                }
                        }
                }
                """
                        .formatted(wmRuntimeInfo.getHttpPort());

        Files.writeString(configDir.resolve("config.json"), authFile, StandardCharsets.UTF_8);

        ContainerRef containerRef = ContainerRef.forRegistry("localhost:%d".formatted(wmRuntimeInfo.getHttpPort()));
        FileStoreAuthenticationProvider authProvider =
                new FileStoreAuthenticationProvider(FileStore.newFileStore(List.of(configDir.resolve("config.json"))));

        // Return data from wiremock
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        wireMock.register(WireMock.get(WireMock.urlEqualTo("/v2/library/artifact-text-store/tags/list"))
                .willReturn(WireMock.okJson(
                        JsonUtils.toJson(new Tags("artifact-text-store", List.of("latest", "0.1.1"))))));

        // Insecure registry
        Registry registry = Registry.Builder.builder()
                .withAuthProvider(authProvider)
                .withInsecure(true)
                .build();

        // Test
        List<String> tags = registry.getTags(ContainerRef.parse("%s/library/artifact-text-store"
                .formatted(wmRuntimeInfo.getHttpBaseUrl().replace("http://", ""))));

        // Assert
        assertEquals(2, tags.size());
        assertEquals("latest", tags.get(0));
        assertEquals("0.1.1", tags.get(1));
    }

    // Errors from registry
    @Test
    void shouldHandle500Error(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String registryUrl = wmRuntimeInfo.getHttpBaseUrl().replace("http://", "");

        // Register the wiremock
        wireMock.register(WireMock.get(WireMock.urlEqualTo("/v2/library/error-artifact/tags/list"))
                .willReturn(WireMock.serverError().withBody("Internal Server Error")));
        Registry registry = Registry.Builder.builder().withInsecure(true).build();

        // Now we should have a reference to container
        ContainerRef ref = ContainerRef.parse("%s/library/error-artifact".formatted(registryUrl));

        // Get exception and assert
        OrasException exception = assertThrows(OrasException.class, () -> registry.getTags(ref));
        assertEquals(500, exception.getStatusCode());
    }

    // Timeout with similar structure as previous test and request 408 with different artifact name
    @Test
    void shouldHandleTimeout(WireMockRuntimeInfo wmRuntimeInfo) {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String registryUrl = wmRuntimeInfo.getHttpBaseUrl().replace("http://", "");

        // Using here a unique container reference to avoid conflicts when running in parallel
        ContainerRef ref = ContainerRef.parse("%s/library/timeout-artifact".formatted(registryUrl));

        // We Set up the stub for the timeout scenario
        wireMock.register(WireMock.get(WireMock.urlEqualTo("/v2/library/timeout-artifact/tags/list"))
                .willReturn(WireMock.aResponse().withStatus(408).withBody("Request timed out")));

        Registry registry = Registry.Builder.builder().withInsecure(true).build();

        OrasException exception = assertThrows(OrasException.class, () -> registry.getTags(ref));
        assertEquals(408, exception.getStatusCode());
    }

    // Timeout with similar structure as previous test and request 408 with different artifact name
    @Test
    void copyToOciLayoutMissingInvalidContentType(WireMockRuntimeInfo wmRuntimeInfo) {

        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String registryUrl = wmRuntimeInfo.getHttpBaseUrl().replace("http://", "");

        // Using here a unique container reference to avoid conflicts when running in parallel
        ContainerRef ref = ContainerRef.parse("%s/library/invalid-copy-artifact".formatted(registryUrl));

        wireMock.register(WireMock.head(WireMock.urlEqualTo("/v2/library/invalid-copy-artifact/manifests/latest"))
                .willReturn(WireMock.noContent()));

        // No content type
        Registry registry = Registry.Builder.builder().withInsecure(true).build();
        OrasException exception = assertThrows(OrasException.class, () -> registry.copy(ref, ociLayout));
        assertEquals("Content type not found in headers", exception.getMessage());

        // No manifest digest
        wireMock.register(WireMock.head(WireMock.urlEqualTo("/v2/library/invalid-copy-artifact/manifests/latest"))
                .willReturn(
                        WireMock.noContent().withHeader(Const.CONTENT_TYPE_HEADER, Const.DEFAULT_MANIFEST_MEDIA_TYPE)));
        exception = assertThrows(OrasException.class, () -> registry.copy(ref, ociLayout));
        assertEquals("Manifest digest not found in headers", exception.getMessage());

        // Invalid content type
        wireMock.register(WireMock.head(WireMock.urlEqualTo("/v2/library/invalid-copy-artifact/manifests/latest"))
                .willReturn(WireMock.noContent()
                        .withHeader(Const.CONTENT_TYPE_HEADER, "application/json")
                        .withHeader(Const.DOCKER_CONTENT_DIGEST_HEADER, "sha256:1234")));
        exception = assertThrows(OrasException.class, () -> registry.copy(ref, ociLayout));
        assertEquals("Unsupported content type: application/json", exception.getMessage());
    }

    // Note: Currently this test is @Disabled because the retry functionality isn't implemented.
    // remove the @Disabled annotation and the test should pass.
    @Test
    @Disabled("Automatic retry not yet implemented in SDK")
    void shouldRetryBlobUpload(WireMockRuntimeInfo wmRuntimeInfo) throws IOException {
        WireMock wireMock = wmRuntimeInfo.getWireMock();
        String registryUrl = wmRuntimeInfo.getHttpBaseUrl().replace("http://", "");
        String uploadUrl = "/v2/library/artifact-text/blobs/uploads/";

        // Setting up WireMock to simulate a failed first attempt
        wireMock.register(WireMock.post(WireMock.urlEqualTo(uploadUrl))
                .inScenario("upload retry scenario")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(WireMock.serverError())
                .willSetStateTo("retry"));

        // Setting up WireMock for successful retry
        wireMock.register(WireMock.post(WireMock.urlEqualTo(uploadUrl))
                .inScenario("upload retry scenario")
                .whenScenarioStateIs("retry")
                .willReturn(WireMock.aResponse().withStatus(202).withHeader("Location", uploadUrl + "12345")));

        wireMock.register(
                WireMock.put(WireMock.urlMatching(uploadUrl + "12345.*")).willReturn(WireMock.created()));

        Registry registry = Registry.Builder.builder().withInsecure(true).build();
        ContainerRef ref = ContainerRef.parse("%s/library/artifact-text".formatted(registryUrl));

        Path testFile = configDir.resolve("test-data.temp");
        Files.writeString(testFile, "Test Content");

        try (InputStream inputStream = Files.newInputStream(testFile)) {
            // when retry is implemented we dont want to throw an exception here as it will retry
            Layer layer = registry.pushBlobStream(ref, inputStream, Files.size(testFile));

            // assertions will verify that the upload succeeded after retry
            assertNotNull(layer);
            assertNotNull(layer.getDigest());
        }
    }
}

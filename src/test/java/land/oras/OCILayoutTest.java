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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import land.oras.exception.OrasException;
import land.oras.utils.Const;
import land.oras.utils.DigestUtils;
import land.oras.utils.JsonUtils;
import land.oras.utils.SupportedAlgorithm;
import land.oras.utils.ZotContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Execution(ExecutionMode.CONCURRENT)
public class OCILayoutTest {

    @TempDir
    private Path blobDir;

    @TempDir
    private Path layoutPath;

    @TempDir
    private Path layoutPathIndex;

    @Container
    private final ZotContainer registry = new ZotContainer().withStartupAttempts(3);

    @Test
    void testShouldCopyArtifactFromRegistryIntoOciLayout() throws IOException {

        Registry registry = Registry.Builder.builder()
                .defaults("myuser", "mypass")
                .withInsecure(true)
                .build();

        OCILayout ociLayout = OCILayout.Builder.builder().defaults(layoutPath).build();

        ContainerRef containerRef =
                ContainerRef.parse("%s/library/artifact-oci-layout".formatted(this.registry.getRegistry()));
        Path file1 = blobDir.resolve("artifact-oci-layout.txt");
        Files.writeString(file1, "artifact-oci-layout");

        // Push
        Manifest manifest = registry.pushArtifact(containerRef, LocalPath.of(file1));

        // Cannot copy if directory doesn't exists
        assertThrows(
                OrasException.class,
                () -> {
                    ociLayout.copy(registry, containerRef, layoutPath.resolve("not-exists"));
                },
                "Directory not found");

        // Copy to oci layout
        ociLayout.copy(registry, containerRef, layoutPath);

        assertTrue(Files.exists(layoutPath.resolve("oci-layout")));

        OCILayout layoutFile = JsonUtils.fromJson(layoutPath.resolve("oci-layout"), OCILayout.class);
        assertEquals("1.0.0", layoutFile.getImageLayoutVersion());

        // Assert the empty config
        assertEquals(
                "{}",
                Files.readString(layoutPath
                        .resolve("blobs")
                        .resolve("sha256")
                        .resolve(SupportedAlgorithm.getDigest(Config.empty().getDigest()))));

        // Check index exists
        assertTrue(Files.exists(layoutPath.resolve("index.json")));
        Index index = JsonUtils.fromJson(layoutPath.resolve("index.json"), Index.class);
        assertEquals(2, index.getSchemaVersion());
        assertEquals(1, index.getManifests().size());
        assertEquals(Const.DEFAULT_INDEX_MEDIA_TYPE, index.getMediaType());
        assertEquals(
                manifest.getDescriptor().getSize(), index.getManifests().get(0).getSize());
    }

    @Test
    void testShouldCopyImageIntoOciLayoutWithoutIndex() {

        Registry registry = Registry.Builder.builder()
                .defaults("myuser", "mypass")
                .withInsecure(true)
                .build();

        OCILayout ociLayout = OCILayout.Builder.builder().defaults(layoutPath).build();

        ContainerRef containerRef =
                ContainerRef.parse("%s/library/image-no-index".formatted(this.registry.getRegistry()));

        Layer layer1 = registry.pushBlob(containerRef, Layer.empty().getDataBytes());
        Layer layer2 = registry.pushBlob(containerRef, "foobar".getBytes());

        Manifest emptyManifest = Manifest.empty()
                .withLayers(List.of(Layer.fromDigest(layer1.getDigest(), 2), Layer.fromDigest(layer2.getDigest(), 6)));
        String manifestDigest =
                SupportedAlgorithm.SHA256.digest(emptyManifest.toJson().getBytes(StandardCharsets.UTF_8));
        String configDigest =
                SupportedAlgorithm.SHA256.digest(Config.empty().toJson().getBytes(StandardCharsets.UTF_8));

        // Push config and manifest
        registry.pushConfig(containerRef.withDigest(configDigest), Config.empty());
        Manifest pushedManifest = registry.pushManifest(containerRef, emptyManifest);

        // Copy to oci layout
        ociLayout.copy(registry, containerRef, layoutPath);

        assertTrue(Files.exists(layoutPath.resolve("oci-layout")));

        OCILayout layoutFile = JsonUtils.fromJson(layoutPath.resolve("oci-layout"), OCILayout.class);
        assertEquals("1.0.0", layoutFile.getImageLayoutVersion());

        // Check index exists
        assertTrue(Files.exists(layoutPath.resolve("index.json")));
        JsonUtils.fromJson(layoutPath.resolve("index.json"), Index.class);

        // Check manifest exists
        assertTrue(Files.exists(layoutPath
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(
                        pushedManifest.getDescriptor().getDigest()))));

        // Ensure manifest serialized correctly (check sha256)
        String computedManifestDigest = DigestUtils.digest(
                "sha256",
                layoutPath
                        .resolve("blobs")
                        .resolve("sha256")
                        .resolve(SupportedAlgorithm.getDigest(
                                pushedManifest.getDescriptor().getDigest())));
        assertEquals(
                SupportedAlgorithm.getDigest(pushedManifest.getDescriptor().getDigest()),
                SupportedAlgorithm.getDigest(computedManifestDigest),
                "Manifest digest should match");

        // Ensure layer1 is copied
        assertTrue(Files.exists(layoutPath
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(layer1.getDigest()))));
        // Ensure layer2 is copied
        assertTrue(Files.exists(layoutPath
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(layer2.getDigest()))));

        // Copy to oci layout again
        ociLayout.copy(registry, containerRef, layoutPath);

        // Check manifest exists
        assertTrue(Files.exists(layoutPath
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(
                        pushedManifest.getDescriptor().getDigest()))));
    }

    @Test
    void testShouldCopyImageIntoOciLayoutWithIndex() {

        Registry registry = Registry.Builder.builder()
                .defaults("myuser", "mypass")
                .withInsecure(true)
                .build();

        OCILayout ociLayout = OCILayout.Builder.builder().defaults(layoutPath).build();

        ContainerRef containerRef =
                ContainerRef.parse("%s/library/artifact-image-pull".formatted(this.registry.getRegistry()));

        Layer layer1 = registry.pushBlob(containerRef, Layer.empty().getDataBytes());
        Layer layer2 = registry.pushBlob(containerRef, "foobar".getBytes());

        Manifest emptyManifest = Manifest.empty()
                .withLayers(List.of(Layer.fromDigest(layer1.getDigest(), 2), Layer.fromDigest(layer2.getDigest(), 6)));
        String manifestDigest =
                SupportedAlgorithm.SHA256.digest(emptyManifest.toJson().getBytes(StandardCharsets.UTF_8));
        String configDigest =
                SupportedAlgorithm.SHA256.digest(Config.empty().toJson().getBytes(StandardCharsets.UTF_8));

        // Push config and manifest
        registry.pushConfig(containerRef.withDigest(configDigest), Config.empty());
        Manifest pushedManifest = registry.pushManifest(containerRef.withDigest(manifestDigest), emptyManifest);
        Index index = registry.pushIndex(containerRef, Index.fromManifests(List.of(pushedManifest.getDescriptor())));

        // Copy to oci layout
        ociLayout.copy(registry, containerRef, layoutPathIndex);

        assertTrue(Files.exists(layoutPathIndex.resolve("oci-layout")));

        OCILayout layoutFile = JsonUtils.fromJson(layoutPathIndex.resolve("oci-layout"), OCILayout.class);
        assertEquals("1.0.0", layoutFile.getImageLayoutVersion());

        // Check index exists
        assertTrue(Files.exists(layoutPathIndex.resolve("index.json")));
        JsonUtils.fromJson(layoutPathIndex.resolve("index.json"), Index.class);
        assertEquals(2, index.getSchemaVersion());
        assertEquals(1, index.getManifests().size());
        assertEquals(Const.DEFAULT_INDEX_MEDIA_TYPE, index.getMediaType());

        // Check manifest exists
        assertTrue(Files.exists(layoutPathIndex
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(
                        pushedManifest.getDescriptor().getDigest()))));

        // Ensure manifest serialized correctly (check sha256)
        String computedManifestDigest = DigestUtils.digest(
                "sha256",
                layoutPathIndex
                        .resolve("blobs")
                        .resolve("sha256")
                        .resolve(SupportedAlgorithm.getDigest(
                                pushedManifest.getDescriptor().getDigest())));
        assertEquals(
                SupportedAlgorithm.getDigest(pushedManifest.getDescriptor().getDigest()),
                SupportedAlgorithm.getDigest(computedManifestDigest),
                "Manifest digest should match");

        // Ensure layer1 is copied
        assertTrue(Files.exists(layoutPathIndex
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(layer1.getDigest()))));
        // Ensure layer2 is copied
        assertTrue(Files.exists(layoutPathIndex
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(layer2.getDigest()))));
        // Ensure index is also copied as blob
        assertTrue(Files.exists(layoutPathIndex
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(index.getDescriptor().getDigest()))));

        // Copy to oci layout again
        ociLayout.copy(registry, containerRef, layoutPathIndex);

        // Check manifest exists
        assertTrue(Files.exists(layoutPathIndex
                .resolve("blobs")
                .resolve("sha256")
                .resolve(SupportedAlgorithm.getDigest(
                        pushedManifest.getDescriptor().getDigest()))));
    }

    @Test
    void testShouldCopyIntoOciLayoutWithBlobConfig() throws IOException {
        Registry registry = Registry.Builder.builder()
                .defaults("myuser", "mypass")
                .withInsecure(true)
                .build();

        OCILayout ociLayout = OCILayout.Builder.builder().defaults(layoutPath).build();

        ContainerRef containerRef =
                ContainerRef.parse("%s/library/artifact-oci-layout".formatted(this.registry.getRegistry()));
        Path file1 = blobDir.resolve("artifact-oci-layout.txt");
        Files.writeString(file1, "artifact-oci-layout");

        // Push
        Layer layer = registry.pushBlob(containerRef, "foobartest".getBytes(StandardCharsets.UTF_8));
        Config config = Config.fromBlob("text/plain", layer);
        Manifest manifest = registry.pushArtifact(
                containerRef, ArtifactType.from("my/artifact"), Annotations.empty(), config, LocalPath.of(file1));

        // Copy to oci layout
        ociLayout.copy(registry, containerRef, layoutPath);

        assertTrue(Files.exists(layoutPath.resolve("oci-layout")));

        OCILayout layoutFile = JsonUtils.fromJson(layoutPath.resolve("oci-layout"), OCILayout.class);
        assertEquals("1.0.0", layoutFile.getImageLayoutVersion());

        // Assert the config
        assertEquals(
                "foobartest",
                Files.readString(layoutPath
                        .resolve("blobs")
                        .resolve("sha256")
                        .resolve(SupportedAlgorithm.getDigest(layer.getDigest()))));

        // Check index exists
        assertTrue(Files.exists(layoutPath.resolve("index.json")));
        Index index = JsonUtils.fromJson(layoutPath.resolve("index.json"), Index.class);
        assertEquals(2, index.getSchemaVersion());
        assertEquals(1, index.getManifests().size());
        assertEquals(Const.DEFAULT_INDEX_MEDIA_TYPE, index.getMediaType());
        assertEquals(
                manifest.getDescriptor().getSize(), index.getManifests().get(0).getSize());
    }
}

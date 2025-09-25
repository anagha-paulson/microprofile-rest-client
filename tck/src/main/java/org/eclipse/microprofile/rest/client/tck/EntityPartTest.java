/*
 * Copyright 2024 Contributors to the Eclipse Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.microprofile.rest.client.tck;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.microprofile.rest.client.RestClientBuilder;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.testng.Arquillian;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.EmptyAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.testng.Assert;
import org.testng.annotations.Test;

import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonValue;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * @author <a href="mailto:jperkins@redhat.com">James R. Perkins</a>
 */
public class EntityPartTest extends Arquillian {

    @org.jboss.arquillian.container.test.api.RunAsClient

    @Deployment
    public static WebArchive createDeployment() {
        // Ensure the necessary resources are included in the deployment
        return ShrinkWrap.create(WebArchive.class, EntityPartTest.class.getSimpleName() + ".war")
                .addAsWebInfResource(EmptyAsset.INSTANCE, "beans.xml")
                .addAsResource("multipart/test-file1.txt", "multipart/test-file1.txt")
                .addAsResource("multipart/test-file2.txt", "multipart/test-file2.txt")
                .addClasses(EntityPartTest.class, FileManagerClient.class, FileManagerFilter.class);
    }

    @org.testng.annotations.BeforeMethod
    public void setupStub() {
        // Reset all WireMock stubs before each test run
        reset();

        // Stub for single file upload
        stubFor(
                post(urlEqualTo("/upload"))
                        .withRequestBody(not(containing("test-file2.txt")))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "[{\"name\":\"test-file1.txt\",\"fileName\":\"test-file1.txt\",\"content\":\"This is a test file for file 1.\\n\"}]")));

        // Stub for multiple files upload
        stubFor(
                post(urlEqualTo("/upload"))
                        .withRequestBody(containing("test-file2.txt"))
                        .willReturn(
                                aResponse()
                                        .withStatus(201)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                "[{\"name\":\"test-file1.txt\",\"fileName\":\"test-file1.txt\",\"content\":\"This is a test file for file 1.\\n\"},{\"name\":\"test-file2.txt\",\"fileName\":\"test-file2.txt\",\"content\":\"This is a test file for file 2.\\n\"}]")));
    }

    /**
     * Tests that a single file is uploaded successfully.
     *
     * @throws Exception
     *             if a test error occurs
     */
    @Test
    public void uploadFile() throws Exception {
        try (FileManagerClient client = createClient()) {
            final byte[] content;
            try (InputStream in = EntityPartTest.class.getResourceAsStream("/multipart/test-file1.txt")) {
                Assert.assertNotNull(in, "Could not find /multipart/test-file1.txt");
                content = in.readAllBytes();
            }
            final List<EntityPart> files = List.of(EntityPart.withFileName("test-file1.txt")
                    .content(new ByteArrayInputStream(content))
                    .mediaType(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                    .build());

            try (Response response = client.uploadFile(files)) {
                Assert.assertEquals(201, response.getStatus());
                final JsonArray jsonArray = response.readEntity(JsonArray.class);
                Assert.assertNotNull(jsonArray);
                Assert.assertEquals(jsonArray.size(), 1);
                final JsonObject json = jsonArray.getJsonObject(0);
                Assert.assertEquals(json.getString("name"), "test-file1.txt");
                Assert.assertEquals(json.getString("fileName"), "test-file1.txt");
                Assert.assertEquals(json.getString("content"), "This is a test file for file 1.\n");
            }
        }
    }

    /**
     * Tests uploading multiple files.
     *
     * @throws Exception
     *             if a test error occurs
     */
    @Test
    public void uploadMultipleFiles() throws Exception {
        try (FileManagerClient client = createClient()) {
            final Map<String, byte[]> entityPartContent = new LinkedHashMap<>(2);
            try (InputStream in = EntityPartTest.class.getResourceAsStream("/multipart/test-file1.txt")) {
                Assert.assertNotNull(in, "Could not find /multipart/test-file1.txt");
                entityPartContent.put("test-file1.txt", in.readAllBytes());
            }
            try (InputStream in = EntityPartTest.class.getResourceAsStream("/multipart/test-file2.txt")) {
                Assert.assertNotNull(in, "Could not find /multipart/test-file2.txt");
                entityPartContent.put("test-file2.txt", in.readAllBytes());
            }

            final List<EntityPart> files = entityPartContent.entrySet()
                    .stream()
                    .map((entry) -> {
                        try {
                            return EntityPart.withName(entry.getKey())
                                    .fileName(entry.getKey())
                                    .content(entry.getValue())
                                    .mediaType(MediaType.APPLICATION_OCTET_STREAM_TYPE)
                                    .build();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .collect(Collectors.toList());

            try (Response response = client.uploadFile(files)) {
                Assert.assertEquals(201, response.getStatus());
                final JsonArray jsonArray = response.readEntity(JsonArray.class);
                Assert.assertNotNull(jsonArray);
                Assert.assertEquals(jsonArray.size(), 2);

                // Verify response JSON content
                for (JsonValue value : jsonArray) {
                    final JsonObject json = value.asJsonObject();
                    if (json.getString("name").equals("test-file1.txt")) {
                        Assert.assertEquals(json.getString("fileName"), "test-file1.txt");
                        Assert.assertEquals(json.getString("content"), "This is a test file for file 1.\n");
                    } else if (json.getString("name").equals("test-file2.txt")) {
                        Assert.assertEquals(json.getString("fileName"), "test-file2.txt");
                        Assert.assertEquals(json.getString("content"), "This is a test file for file 2.\n");
                    } else {
                        Assert.fail(String.format("Unexpected entry %s in JSON response: %n%s", json, jsonArray));
                    }
                }
            }
        }
    }

    private static FileManagerClient createClient() {
        // Ensure that the client connects to a valid server URI
        System.out.println("Creating client with real server URI and filter");
        return RestClientBuilder.newBuilder()
                .baseUri(URI.create("http://localhost:8080")) // Can be dynamically changed to avoid conflicts
                .register(new FileManagerFilter())
                .build(FileManagerClient.class);
    }

    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.MULTIPART_FORM_DATA)
    public interface FileManagerClient extends AutoCloseable {

        @POST
        @Path("upload")
        Response uploadFile(List<EntityPart> entityParts) throws IOException;
    }

    public static class FileManagerFilter implements ClientRequestFilter {

        @Override
        public void filter(final ClientRequestContext requestContext) throws IOException {
            System.out.println("++++++ FileManagerFilter.filter called ++++++");

            if (requestContext.getMethod().equals("POST")) {
                // Log the entity parts for debugging
                @SuppressWarnings("unchecked")
                final List<EntityPart> entityParts = (List<EntityPart>) requestContext.getEntity();
                for (EntityPart part : entityParts) {
                    System.out.println("Entity part name: " + part.getName());
                    part.getFileName().ifPresent(fileName -> System.out.println("File name: " + fileName));
                }
            }
        }
    }
}

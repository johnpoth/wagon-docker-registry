package com.github.johnpoth;


import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Array;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.blob.Blobs;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import com.google.cloud.tools.jib.hash.CountingDigestOutputStream;
import com.google.cloud.tools.jib.hash.Digests;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ContainerConfigurationTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Test class for playing around.
 *
 *
 * Example OCI Image layout:
 *
 * $ find . -type f
 * ./index.json
 * ./oci-layout
 * ./blobs/sha256/3588d02542238316759cbf24502f4344ffcc8a60c803870022f335d1390c13b4
 * ./blobs/sha256/4b0bc1c4050b03c95ef2a8e36e25feac42fd31283e8c30b3ee5df6b043155d3c
 * ./blobs/sha256/7968321274dc6b6171697c33df7815310468e694ac5be0ec03ff053bb135e768
 *
 *
 */
@Disabled
public class WagonTest {

    private static final String OCI_LAYOUT = "{ \"imageLayoutVersion\": \"1.0.0\" }";

    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    private static final String INDEX = "{\n"
            + "  \"schemaVersion\": 2,\n"
            + "  \"manifests\": [\n"
            + "    {\n"
            + "      \"mediaType\": \"application/java-archive\",\n"
            + "      \"size\": {{SIZE}},\n"
            + "      \"digest\": \"sha256:{{DIGEST}}\",\n"
            + "      \"annotations\": {\n"
            + "        \"maven.groupId\": \"{{GROUPD_ID}}\",\n"
            + "        \"maven.artifactId\": \"{{ARTIFACT_ID}}\"\n"
            + "      }\n"
            + "    }\n"
            + "  ],\n"
            + "  \"annotations\": {\n"
            + "    \"maven.timestamp\": \"{{TIMESTAMP}}\"\n"
            + "  }\n"
            + "}";


    @Test
    void simplePut() throws Exception{
        FailoverHttpClient client = new FailoverHttpClient(
                true,
                JibSystemProperties.sendCredentialsOverHttp(),
                EventHandlers.NONE::dispatch);

        Optional<String> remotePath = Optional.of("localhost:5000/org/apache/ant/ant/1.6.3/ant-1.6.3.jar");
        Path localPath = Paths.get("src/main/resources/ant-1.6.3.jar");
        String imageTag = "1.6.3";
        File localFile = localPath.toFile();
        long start = System.currentTimeMillis();
        for (int i = 0; i<10; i++) {

            ImageReference targetImageReference;
            try {
                targetImageReference = ImageReference.parse(remotePath.get());
            } catch (InvalidImageReferenceException e) {
                throw new RuntimeException(e.getMessage());
            }

            RegistryClient.Factory factory = RegistryClient.factory(EventHandlers.NONE, targetImageReference.getRegistry(), targetImageReference.getRepository(), client);
            RegistryClient registryClient = factory.newRegistryClient();

            CountingDigestOutputStream cfo = null;
            CountingDigestOutputStream cfoTar = null;
            ByteArrayOutputStream ba = null;
            try {
                ba = new ByteArrayOutputStream(1024);
                cfo = new CountingDigestOutputStream(ba);
                GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(cfo);
                cfoTar = new CountingDigestOutputStream(gzOut);
                // TODO: see TarStreamBuilder for some additional options (like encoding sigh..)
                TarArchiveOutputStream tOut = new TarArchiveOutputStream(cfoTar);

                TarArchiveEntry tarEntry = new TarArchiveEntry(localPath);
                tarEntry.setName(localPath.getFileName().toString());
                tarEntry.setModTime(Instant.ofEpochMilli((localFile.lastModified())).getEpochSecond());
                tOut.putArchiveEntry(tarEntry);
                IOUtils.copy(localFile, tOut);
                tOut.closeArchiveEntry();
                tOut.finish();
                gzOut.close();
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            BlobDescriptor blobDescriptor = cfo.computeDigest();
            // TODO: try to optimize this a bit. Create custom Blob and use ba.wirteTo (should work)
            ByteArrayInputStream bi = new ByteArrayInputStream(ba.toByteArray());

            // Even though this is not a real image, some clients e.g Docker complain when there is no Config.json in the image tar which
            // helps interoperability
            ContainerConfigurationTemplate containerConfiguration =
                    new ContainerConfigurationTemplate();
            containerConfiguration.setOs("linux");
            containerConfiguration.addLayerDiffId(cfoTar.computeDigest().getDigest());
            containerConfiguration.setCreated(Instant.ofEpochMilli((localFile.lastModified())).toString());
            Blob testContainerConfigurationBlob = Blobs.from(containerConfiguration);
            DescriptorDigest testContainerConfigurationBlobDigest = Digests.computeDigest(containerConfiguration).getDigest();
            // Creates a valid image manifest.
            OciManifestTemplate expectedManifestTemplate = new OciManifestTemplate();
            expectedManifestTemplate.setContainerConfiguration(Digests.computeDigest(containerConfiguration).getSize(), testContainerConfigurationBlobDigest);
            expectedManifestTemplate.addLayer(blobDescriptor.getSize(), blobDescriptor.getDigest());

            if (!registryClient.checkManifest(Digests.computeJsonDigest(expectedManifestTemplate).toString()).isPresent()) {
                boolean b = registryClient.pushBlob(blobDescriptor.getDigest(), Blobs.from(bi), "localhost:5000", ignored -> {
                });
                boolean d = registryClient.pushBlob(
                        testContainerConfigurationBlobDigest,
                        testContainerConfigurationBlob,
                        "localhost:5000",
                        ignored -> {
                        });
//        System.out.println(b);
//        System.out.println(d);

                DescriptorDigest imageDigest = registryClient.pushManifest(expectedManifestTemplate, imageTag);
            }
        }
        long finish = System.currentTimeMillis();
        System.out.println(finish - start);
    }

    @Test
    void simpleGet() {
        FailoverHttpClient client = new FailoverHttpClient(
                true,
                JibSystemProperties.sendCredentialsOverHttp(),
                EventHandlers.NONE::dispatch);
        Optional<String> image = Optional.of("localhost:5000/org/apache/ant/ant/1.6.3/ant-1.6.3.jar");
        String outputPath = "src/main/resources/out.tar.gz";
        String imageTag = "1.6.3";

        ImageReference targetImageReference = null;
        try {
            targetImageReference = ImageReference.parse(image.get());
        } catch (InvalidImageReferenceException e) {
            throw new RuntimeException(e.getMessage());
        }
        RegistryClient.Factory factory = RegistryClient.factory(EventHandlers.NONE, targetImageReference.getRegistry(), targetImageReference.getRepository(), client);
        RegistryClient registryClient = factory.newRegistryClient();
        try {
            ManifestAndDigest<BuildableManifestTemplate> v1 = registryClient.pullManifest(imageTag, BuildableManifestTemplate.class);
            // TODO: populate Manifest annotation with "org.opencontainers.image.created" https://github.com/opencontainers/image-spec/blob/main/annotations.md https://tools.ietf.org/html/rfc3339#section-5.6
//            ManifestAndDigest<ManifestTemplate> v1 = registryClient.pullManifest("1.6.3");
            Blob cc = registryClient.pullBlob(v1.getManifest().getContainerConfiguration().getDigest(), ignored -> {
            }, ignored -> {
            });
            String config = Blobs.writeToString(cc);
            int start = config.indexOf("created") + 10;
            int end = config.indexOf("\"", start);
            String time = config.substring(start, end);


            List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = v1.getManifest().getLayers();

            BuildableManifestTemplate.ContentDescriptorTemplate contentDescriptorTemplate = layers.get(0);
            Blob blob = registryClient.pullBlob(contentDescriptorTemplate.getDigest(), ignored -> {
            }, ignored -> {
            });

            try (ByteArrayOutputStream ba = new ByteArrayOutputStream()) {
                blob.writeTo(ba);

                try (GzipCompressorInputStream gzipIn = new GzipCompressorInputStream(new ByteArrayInputStream(ba.toByteArray()));
                     TarArchiveInputStream tarIn      = new TarArchiveInputStream(gzipIn);
                     FileOutputStream output          = new FileOutputStream(outputPath)) {
                    tarIn.getNextEntry();
                    IOUtils.copy(tarIn, output);
                }
            }
            File fo = new File(outputPath);
            fo.setLastModified(Instant.parse(time).toEpochMilli());
        } catch (IOException e) {
            e.printStackTrace();
        } catch (RegistryException e) {
            e.printStackTrace();
        }
    }


    @Test
    void simpleImageArchive() throws Exception {
        try (OutputStream fOut = Files.newOutputStream(Paths.get("output.tar.gz"))) {
            BufferedOutputStream buffOut = new BufferedOutputStream(fOut);
            GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(buffOut);

            // OCI layout
            try (TarArchiveOutputStream tOut = new TarArchiveOutputStream(gzOut)) {
                createTarArchiveEntry("oci-layout", OCI_LAYOUT.getBytes(), tOut);

                // JAR
                byte[] jar = Files.readAllBytes(Paths.get("src/main/resources/ant-1.6.3.jar"));
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] digestBytes = digest.digest(jar);
                String digestHex = bytesToHex(digestBytes);

                createTarArchiveEntry("blobs/sha256/" + digestHex, jar, tOut);

                // Index.json
                String index = INDEX.replace("{{SIZE}}", String.valueOf(Array.getLength(jar)));
                index = index.replace("{{DIGEST}}", digestHex);
                index = index.replace("{{GROUPD_ID}}", "apache");
                index = index.replace("{{ARTIFACT_ID}}", "ant");
                index = index.replace("{{TIMESTAMP}}", String.valueOf(java.lang.System.currentTimeMillis()));
                createTarArchiveEntry("index.json", index.getBytes(), tOut);
                tOut.finish();
            }
        }
    }

    private void createTarArchiveEntry(String fileName,
                                              byte[] dataInBytes,
                                              TarArchiveOutputStream tOut)
            throws IOException {

        // create a byte[] input stream
        ByteArrayInputStream baOut1 = new ByteArrayInputStream(dataInBytes);

        TarArchiveEntry tarEntry = new TarArchiveEntry(fileName);

        // need defined the file size, else error
        tarEntry.setSize(dataInBytes.length);
        // tarEntry.setSize(baOut1.available()); alternative

        tOut.putArchiveEntry(tarEntry);

        // copy ByteArrayInputStream to TarArchiveOutputStream
        byte[] buffer = new byte[1024];
        int len;
        while ((len = baOut1.read(buffer)) > 0) {
            tOut.write(buffer, 0, len);
        }

        tOut.closeArchiveEntry();

    }

    public String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }


}

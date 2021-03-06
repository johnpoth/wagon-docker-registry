/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.johnpoth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.buildplan.ImageFormat;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.blob.Blobs;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import com.google.cloud.tools.jib.hash.CountingDigestOutputStream;
import com.google.cloud.tools.jib.hash.Digests;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.http.ResponseException;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ContainerConfigurationTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.image.json.V22ManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.events.SessionListener;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferEventSupport;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.proxy.ProxyInfo;
import org.apache.maven.wagon.proxy.ProxyInfoProvider;
import org.apache.maven.wagon.repository.Repository;
import org.apache.maven.wagon.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DockerDistributionWagon
 *
 * Maven wagon that allows us to use Docker Image registries as Maven repositories
 *
 */
public class DockerDistributionWagon implements Wagon {

    private static final Logger LOG = LoggerFactory.getLogger( DockerDistributionWagon.class);

    // set by Maven
    private Repository repository;
    private ProxyInfo proxyInfo;
    private ProxyInfoProvider proxyInfoProvider;
    private AuthenticationInfo authenticationInfo;
    private TransferEventSupport transferEventSupport = new TransferEventSupport();

    // User configuration
    private boolean allowInsecureRegistries = true;
    private boolean sendAuthorizationOverHttp = true;
    private int timeout;
    private int readTimeout;
    private ImageFormat imageFormat = ImageFormat.Docker;
    private ImageNamingStrategy imageNamingStrategy = ImageNamingStrategy.Default;
    private Map<String, String> imageNamingMap = new HashMap<>();

    private static final List<String> PROXY_PROPERTIES = Arrays.asList("proxyHost", "proxyPort", "proxyUser", "proxyPassword");
    private FailoverHttpClient client;


    @Override
    public void get(String resourceName, File destination) throws TransferFailedException, ResourceDoesNotExistException {
        RegistryClient registryClient = getRegistryClient(resourceName);

        ManifestAndDigest<BuildableManifestTemplate> v1 = getManifest(resourceName, registryClient);

        copyResource(resourceName, destination, registryClient, v1);
    }

    @Override
    public boolean getIfNewer(String resourceName, File destination, long timestamp) throws TransferFailedException, ResourceDoesNotExistException {
        RegistryClient registryClient = getRegistryClient(resourceName);

        ManifestAndDigest<BuildableManifestTemplate> v1 = getManifest(resourceName, registryClient);

        if (isOlder(resourceName, timestamp, registryClient, v1)) {
            return false;
        }
        copyResource(resourceName, destination, registryClient, v1);
        return true;
    }

    @Override
    public void put(File source, String destination) throws TransferFailedException, ResourceDoesNotExistException {
        RegistryClient registryClient = getRegistryClient(destination);
        String tag = getTag(destination);
        // while copying data, it helps performance to calculate digests at the same time
        CountingDigestOutputStream cfo;
        CountingDigestOutputStream cfoTar;
        ByteArrayOutputStream ba;
        try {
            ba = new ByteArrayOutputStream();
            cfo = new CountingDigestOutputStream(ba);
            GzipCompressorOutputStream gzOut = new GzipCompressorOutputStream(cfo);
            cfoTar = new CountingDigestOutputStream(gzOut);
            TarArchiveOutputStream tOut = new TarArchiveOutputStream(cfoTar, StandardCharsets.UTF_8.name());
            tOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            tOut.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);

            TarArchiveEntry tarEntry = new TarArchiveEntry(source);
            tarEntry.setName(source.getName());
            tarEntry.setModTime(Instant.ofEpochMilli((source.lastModified())).getEpochSecond());
            tOut.putArchiveEntry(tarEntry);
            IOUtils.copy(source, tOut);
            tOut.closeArchiveEntry();
            tOut.finish();
            gzOut.close();
        } catch (Exception ex) {
            throw new ResourceDoesNotExistException(ex.getMessage());
        }

        // Even though this is not a real image, some clients e.g Docker complain when there is no Config.json in the image tar which
        // helps interoperability
        ContainerConfigurationTemplate containerConfiguration = new ContainerConfigurationTemplate();
        containerConfiguration.addLayerDiffId(cfoTar.computeDigest().getDigest());
        containerConfiguration.setCreated(Instant.ofEpochMilli((source.lastModified())).toString());
        Blob testContainerConfigurationBlob = Blobs.from(containerConfiguration);
        try {
            DescriptorDigest testContainerConfigurationBlobDigest = Digests.computeDigest(containerConfiguration).getDigest();
            // Creates a valid image manifest.
            BuildableManifestTemplate expectedManifestTemplate = getBuildableManifestTemplate();
            expectedManifestTemplate.setContainerConfiguration(Digests.computeDigest(containerConfiguration).getSize(), testContainerConfigurationBlobDigest);
            BlobDescriptor blobDescriptor = cfo.computeDigest();
            expectedManifestTemplate.addLayer(blobDescriptor.getSize(), blobDescriptor.getDigest());

            // if we want to notify Maven of our progress, we'll have to wrap the blobs/stream and notify Maven accordingly
            // not sure how useful this is to users so we'll leave it as it is for now
            registryClient.pushBlob(blobDescriptor.getDigest(), new ByteArrayOutputStreamBlob(ba), null, ignored -> {
            });
            registryClient.pushBlob(testContainerConfigurationBlobDigest, testContainerConfigurationBlob, null,
                    ignored -> {
                    });
            DescriptorDigest imageDigest = registryClient.pushManifest(expectedManifestTemplate, tag);
            LOG.debug("Successfully pushed manifest [{}]",imageDigest.toString());
        } catch(Exception e) {
            LOG.error("Error while putting [{}]", destination, e);
            throw new TransferFailedException(e.getMessage());
        }
    }

    private BuildableManifestTemplate getBuildableManifestTemplate() {
        // Defaults to Docker
        switch (this.imageFormat) {
            case OCI:
                return new OciManifestTemplate();
            default:
                return new V22ManifestTemplate();
        }

    }

    private void copyResource(String resourceName, File destination, RegistryClient registryClient, ManifestAndDigest<BuildableManifestTemplate> v1) throws TransferFailedException {

        Resource resource = new Resource(resourceName);
        fireTransferStartedEvent(resource);
        try {
            List<BuildableManifestTemplate.ContentDescriptorTemplate> layers = v1.getManifest().getLayers();
            BuildableManifestTemplate.ContentDescriptorTemplate contentDescriptorTemplate = layers.get(0);
            Blob blob = registryClient.pullBlob(contentDescriptorTemplate.getDigest(), ignored -> {
            }, ignored -> {
            });

            try (ByteArrayOutputStream ba = new ByteArrayOutputStream()) {
                blob.writeTo(ba);
                try (GzipCompressorInputStream gzipIn    = new GzipCompressorInputStream(new ByteArrayInputStream(ba.toByteArray()));
                     TarArchiveInputStream tarIn         = new TarArchiveInputStream(gzipIn);
                     FileOutputStream output             = new FileOutputStream(destination);
                     FilterProgressOutputStream progress = new FilterProgressOutputStream(output, this.transferEventSupport, this, resource, TransferEvent.TRANSFER_PROGRESS, TransferEvent.REQUEST_GET)){

                    tarIn.getNextEntry();
                    IOUtils.copy(tarIn, progress);
                }
            }
            fireTransferCompletedEvent(resource, TransferEvent.TRANSFER_COMPLETED);
        } catch (IOException e) {
            fireTransferCompletedEvent(resource, TransferEvent.TRANSFER_ERROR);
            throw new TransferFailedException(e.getMessage());
        }
    }

    private void fireTransferCompletedEvent(Resource resource, int transferCompleted) {
        TransferEvent success = new TransferEvent(this, resource, transferCompleted, TransferEvent.REQUEST_GET);
        this.transferEventSupport.fireTransferCompleted(success);
    }

    private void fireTransferStartedEvent(Resource resource) {
        TransferEvent started = new TransferEvent( this, resource, TransferEvent.TRANSFER_STARTED, TransferEvent.REQUEST_GET );
        this.transferEventSupport.fireTransferStarted(started);
        TransferEvent init = new TransferEvent( this, resource, TransferEvent.TRANSFER_INITIATED, TransferEvent.REQUEST_GET);
        this.transferEventSupport.fireTransferInitiated(init);
    }

    private ManifestAndDigest<BuildableManifestTemplate> getManifest(String resourceName, RegistryClient registryClient) throws ResourceDoesNotExistException, TransferFailedException {
        String tag = getTag(resourceName);
        try {
            return registryClient.pullManifest(tag, BuildableManifestTemplate.class);
        } catch (RegistryException ex) {
            ResponseException responseException = (ResponseException) ex.getCause();
            if (404 == responseException.getStatusCode()) {
                throw new ResourceDoesNotExistException(ex.getMessage());
            } else {
                throw new TransferFailedException(ex.getMessage());
            }
        } catch (IOException e) {
            LOG.error("Error while getting {}", resourceName, e);
            throw new TransferFailedException(e.getMessage());
        }
    }

    private boolean isOlder(String resourceName, long timestamp, RegistryClient registryClient, ManifestAndDigest<BuildableManifestTemplate> v1) throws TransferFailedException {
        Blob cc = registryClient.pullBlob(v1.getManifest().getContainerConfiguration().getDigest(), ignored -> {
        }, ignored -> {
        });
        try {
            String config = Blobs.writeToString(cc);
            int start = config.indexOf("created") + 10;
            int end = config.indexOf("\"", start);
            String time = config.substring(start, end);
            if (Instant.parse(time).toEpochMilli() >= timestamp) {
                return true;
            }
        } catch (IOException e){
            throw new TransferFailedException("Error writing Blob to string", e);
        } catch (Exception e){
            LOG.debug("Error checking timestamp for Manifest [{}]. Assuming newer artifact is present", resourceName, e);
        }
        return false;
    }

    private String getTag(String destination) {
        try {
            String path = destination.substring(0, destination.lastIndexOf("/"));
            String tag = path.substring(path.lastIndexOf("/") + 1);
            if (Character.isDigit(tag.charAt(0))) {
                return tag;
            }
            return "latest";
        } catch(Exception e){
            LOG.debug("Error guessing image tag for [{}]. Assuming 'latest'", destination, e);
            return "latest";
        }
    }

    private RegistryClient getRegistryClient(String resourceName) throws TransferFailedException, ResourceDoesNotExistException {
        String imageRepositoryName = getImageRepositoryName(resourceName);
        // removes 'docker://' from repository url
        String url = this.repository.getUrl().replaceFirst("docker://","");
        String image = url + "/" +  imageRepositoryName;

        ImageReference targetImageReference;
        try {
            targetImageReference = ImageReference.parse(image.toLowerCase());
        } catch (InvalidImageReferenceException e) {
            LOG.debug("Error building image reference [{}]", imageRepositoryName, e);
            throw new ResourceDoesNotExistException(e.getMessage());
        }
        String registry = targetImageReference.getRegistry();
        String repository = targetImageReference.getRepository();
        RegistryClient.Factory factory = RegistryClient.factory(EventHandlers.NONE, registry, repository, client);
        boolean setupAuth = this.authenticationInfo != null;
        if(setupAuth) {
            factory.setCredential(Credential.from(this.authenticationInfo.getUserName(), this.authenticationInfo.getPassword()));
        }
        RegistryClient registryClient = factory.newRegistryClient();
        if (setupAuth) {
            try {
                if (!registryClient.doPushBearerAuth()) {
                    registryClient.configureBasicAuth();
                }
            } catch (Exception e) {
                throw new TransferFailedException(e.getMessage());
            }
        }
        return registryClient;
    }

    private String getImageRepositoryName(String resourceName) {
        if (imageNamingMap.containsKey(resourceName)) {
            return imageNamingMap.get(resourceName);
        }
        switch (this.imageNamingStrategy) {
            case None:
                return resourceName;
            case SHA256:
                try {
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    return new String(digest.digest(resourceName.getBytes()));
                } catch (NoSuchAlgorithmException e) {
                    throw new RuntimeException(e);
                }
            default:
                String imageRepositoryName = resourceName.toLowerCase();
                return "maven_" + imageRepositoryName.replaceAll("\\.|/","_");
        }
    }

    private boolean areProxyPropertiesSet(String protocol) {
        return PROXY_PROPERTIES.stream()
                .anyMatch(property -> System.getProperty(protocol + "." + property) != null);
    }

    private void activateHttpAndHttpsProxies() {
        List<ProxyInfo> proxies = new ArrayList<>(2);
        if (this.proxyInfo != null) {
            proxies.add(this.proxyInfo);
        }
        if (proxyInfoProvider!= null) {
            for (String protocol : Arrays.asList("http", "https")) {
                if (areProxyPropertiesSet(protocol)) {
                    continue;
                }
                ProxyInfo proxyInfo = this.proxyInfoProvider.getProxyInfo(protocol);
                if (proxyInfo != null) {
                    proxies.add(proxyInfo);
                }
            }
        }
        proxies.forEach(this::setProxyProperties);
    }

    private void setProxyProperties(ProxyInfo proxy) {
        String protocol = proxy.getType();

        setPropertySafe(protocol + ".proxyHost", proxy.getHost());
        setPropertySafe(protocol + ".proxyPort", String.valueOf(proxy.getPort()));
        setPropertySafe(protocol + ".proxyUser", proxy.getUserName());
        setPropertySafe(protocol + ".proxyPassword", proxy.getPassword());
        setPropertySafe("http.nonProxyHosts", proxy.getNonProxyHosts());
    }

    private void setPropertySafe(String property, String value) {
        if (value != null) {
            System.setProperty(property, value);
        }
    }

    @Override
    public void putDirectory(File sourceDirectory, String destinationDirectory) throws TransferFailedException {
       throw new TransferFailedException("putDirectory not supported!!!");
    }

    @Override
    public boolean resourceExists(String resourceName) throws TransferFailedException{
        RegistryClient registryClient;
        try {
            registryClient = getRegistryClient(resourceName);
            String tag = getTag(resourceName);
            return registryClient.checkManifest(tag).isPresent();
        } catch (ResourceDoesNotExistException e) {
            return false;
        } catch (Exception e) {
            throw new TransferFailedException(e.getMessage());
        }
    }

    @Override
    public List<String> getFileList(String destinationDirectory) throws TransferFailedException {
        throw new TransferFailedException("getFileList not supported!!!");
    }

    @Override
    public boolean supportsDirectoryCopy() {
        return false;
    }

    @Override
    public Repository getRepository() {
        return this.repository;
    }

    private void connectInternal(Repository source, ProxyInfo proxyInfo, ProxyInfoProvider proxyInfoProvider, AuthenticationInfo authenticationInfo) {
        this.repository = source;
        this.proxyInfo = proxyInfo;
        this.proxyInfoProvider = proxyInfoProvider;
        this.authenticationInfo = authenticationInfo;
        activateHttpAndHttpsProxies();
        this.client = new FailoverHttpClient(allowInsecureRegistries,
                sendAuthorizationOverHttp,
                EventHandlers.NONE::dispatch);
    }

    @Override
    public void connect(Repository source) {
        connectInternal(source,null, null, null);
    }

    @Override
    public void connect(Repository source, ProxyInfo proxyInfo) {
        connectInternal(source, proxyInfo, null, null);
    }

    @Override
    public void connect(Repository source, ProxyInfoProvider proxyInfoProvider) {
        connectInternal(source,null, proxyInfoProvider, null);
    }

    @Override
    public void connect(Repository source, AuthenticationInfo authenticationInfo) {
        connectInternal(source,null, null, authenticationInfo);
    }

    @Override
    public void connect(Repository source, AuthenticationInfo authenticationInfo, ProxyInfo proxyInfo) {
        connectInternal(source, proxyInfo, null, authenticationInfo);
    }

    @Override
    public void connect(Repository source, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider) {
        connectInternal(source, null, proxyInfoProvider, authenticationInfo);
    }

    @Override
    public void openConnection() {
        // Nothing to do here (never called by the wagon manager)
    }

    @Override
    public void disconnect() throws ConnectionException {
        try {
            this.client.shutDown();
        } catch (IOException e) {
            throw new ConnectionException(e.getMessage());
        }
    }

    @Override
    public void setTimeout(int timeoutValue) {
        this.timeout = timeoutValue;
        System.setProperty(JibSystemProperties.HTTP_TIMEOUT, String.valueOf(timeoutValue));
    }

    @Override
    public int getTimeout() {
        return this.timeout;
    }

    @Override
    public void setReadTimeout(int timeoutValue) {
        this.readTimeout = timeoutValue;
    }

    @Override
    public int getReadTimeout() {
        return readTimeout;
    }

    @Override
    public void addSessionListener(SessionListener listener) {

    }

    @Override
    public void removeSessionListener(SessionListener listener) {

    }

    @Override
    public boolean hasSessionListener(SessionListener listener) {
        return false;
    }

    @Override
    public void addTransferListener(TransferListener listener) {
        this.transferEventSupport.addTransferListener(listener);
    }

    @Override
    public void removeTransferListener(TransferListener listener) {
        this.transferEventSupport.removeTransferListener(listener);
    }

    @Override
    public boolean hasTransferListener(TransferListener listener) {
        return this.transferEventSupport.hasTransferListener(listener);
    }

    @Override
    public boolean isInteractive() {
        return false;
    }

    @Override
    public void setInteractive(boolean interactive) {

    }
}

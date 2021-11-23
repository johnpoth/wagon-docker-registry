package com.github.johnpoth;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.LogEvent;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.api.RegistryImage;
import com.google.cloud.tools.jib.api.buildplan.AbsoluteUnixPath;
import com.google.cloud.tools.jib.blob.Blob;
import com.google.cloud.tools.jib.blob.BlobDescriptor;
import com.google.cloud.tools.jib.blob.Blobs;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.global.JibSystemProperties;
import com.google.cloud.tools.jib.hash.CountingDigestOutputStream;
import com.google.cloud.tools.jib.hash.Digests;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.http.ResponseException;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ContainerConfigurationTemplate;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;
import com.google.cloud.tools.jib.plugins.common.AuthProperty;
import com.google.cloud.tools.jib.plugins.common.ConfigurationPropertyValidator;
import com.google.cloud.tools.jib.plugins.common.DefaultCredentialRetrievers;
import com.google.cloud.tools.jib.plugins.common.InferredAuthException;
import com.google.cloud.tools.jib.plugins.common.InferredAuthProvider;
import com.google.cloud.tools.jib.plugins.common.InvalidWorkingDirectoryException;
import com.google.cloud.tools.jib.plugins.common.RawConfiguration;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.inject.internal.util.Preconditions;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authentication.AuthenticationInfo;
import org.apache.maven.wagon.authorization.AuthorizationException;
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
 * ImageRegistryWagon
 *
 *
 * @plexus.component role="org.apache.maven.wagon.Wagon"
 * role-hint="oci"
 * instantiation-strategy="per-lookup"
 */
public class ImageRegistryWagon implements Wagon {

    @Parameter(defaultValue = "true", property = "image.registry.maven.wagon.allowInsecureRegistries")
    private boolean allowInsecureRegistries = true;
    private Repository repository;
    private int timeout;
    private int readTimeout;

    private static final ImmutableList<String> PROXY_PROPERTIES =
            ImmutableList.of("proxyHost", "proxyPort", "proxyUser", "proxyPassword");
    private ProxyInfo proxyInfo;
    private ProxyInfoProvider proxyInfoProvider;
    private AuthenticationInfo authenticationInfo;
    private FailoverHttpClient client;
    private static final Logger LOG = LoggerFactory.getLogger( ImageRegistryWagon.class );
    private TransferEventSupport transferEventSupport = new TransferEventSupport();

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
            // TODO: see TarStreamBuilder for some additional options (like encoding sigh..)
            TarArchiveOutputStream tOut = new TarArchiveOutputStream(cfoTar);

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
        containerConfiguration.setOs("linux");
        containerConfiguration.addLayerDiffId(cfoTar.computeDigest().getDigest());
        containerConfiguration.setCreated(Instant.ofEpochMilli((source.lastModified())).toString());
        Blob testContainerConfigurationBlob = Blobs.from(containerConfiguration);
        try {
            DescriptorDigest testContainerConfigurationBlobDigest = Digests.computeDigest(containerConfiguration).getDigest();
            // Creates a valid image manifest.
            OciManifestTemplate expectedManifestTemplate = new OciManifestTemplate();
            expectedManifestTemplate.setContainerConfiguration(Digests.computeDigest(containerConfiguration).getSize(), testContainerConfigurationBlobDigest);
            BlobDescriptor blobDescriptor = cfo.computeDigest();
            expectedManifestTemplate.addLayer(blobDescriptor.getSize(), blobDescriptor.getDigest());

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
        String path = destination.substring(0, destination.lastIndexOf("/"));
        String tag = path.substring(path.lastIndexOf("/") + 1);
        if (Character.isDigit(tag.charAt(0))) {
            return tag;
        }
        return "latest";
    }

    private RegistryClient getRegistryClient(String resourceName) throws TransferFailedException {
        // removes 'oci://' from repository url
        String url = this.repository.getUrl().substring(6);
        Optional<String> image = Optional.of(url + "/" +  resourceName);
        Preconditions.checkArgument(image.isPresent());

        ImageReference targetImageReference;
        try {
            targetImageReference = ImageReference.parse(image.get());
        } catch (InvalidImageReferenceException e) {
            throw new TransferFailedException(e.getMessage());
        }
        RegistryClient.Factory factory = RegistryClient.factory(EventHandlers.NONE, targetImageReference.getRegistry(), targetImageReference.getRepository(), client);
        return factory.newRegistryClient();
    }

    private void log(LogEvent logEvent) {
        System.out.println(logEvent.getLevel().name() + ":" + logEvent.getMessage());
    }

    boolean areProxyPropertiesSet(String protocol) {
        return PROXY_PROPERTIES.stream()
                .anyMatch(property -> System.getProperty(protocol + "." + property) != null);
    }

    void activateHttpAndHttpsProxies() {
        List<ProxyInfo> proxies = new ArrayList<>(2);
        if (this.proxyInfo != null) {
            proxies.add(this.proxyInfo);
        }
        if (proxyInfoProvider!= null) {
            for (String protocol : ImmutableList.of("http", "https")) {
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

    void setProxyProperties(ProxyInfo proxy) {
        String protocol = proxy.getType();

        setPropertySafe(protocol + ".proxyHost", proxy.getHost());
        setPropertySafe(protocol + ".proxyPort", String.valueOf(proxy.getPort()));
        setPropertySafe(protocol + ".proxyUser", proxy.getUserName());
        setPropertySafe(protocol + ".proxyPassword", proxy.getPassword());
        setPropertySafe("http.nonProxyHosts", proxy.getNonProxyHosts());
    }

    void setPropertySafe(String property, String value) {
        if (value != null) {
            System.setProperty(property, value);
        }
    }

    @VisibleForTesting
    private Optional<AbsoluteUnixPath> getWorkingDirectoryChecked(RawConfiguration rawConfiguration)
            throws InvalidWorkingDirectoryException {
        Optional<String> directory = rawConfiguration.getWorkingDirectory();
        if (!directory.isPresent()) {
            return Optional.empty();
        }

        String path = directory.get();
        try {
            return Optional.of(AbsoluteUnixPath.get(path));
        } catch (IllegalArgumentException ex) {
            throw new InvalidWorkingDirectoryException(path, path, ex);
        }
    }

    private void configureCredentialRetrievers(
            RawConfiguration rawConfiguration,
            RegistryImage registryImage,
            ImageReference imageReference,
            String usernamePropertyName,
            String passwordPropertyName,
            AuthProperty rawAuthConfiguration,
            InferredAuthProvider inferredAuthProvider,
            String credHelper)
            throws FileNotFoundException {
        DefaultCredentialRetrievers defaultCredentialRetrievers =
                DefaultCredentialRetrievers.init(
                        CredentialRetrieverFactory.forImage(imageReference, this::log));
        Optional<Credential> optionalCredential =
                ConfigurationPropertyValidator.getImageCredential(
                        this::log,
                        usernamePropertyName,
                        passwordPropertyName,
                        rawAuthConfiguration,
                        rawConfiguration);
        if (optionalCredential.isPresent()) {
            defaultCredentialRetrievers.setKnownCredential(
                    optionalCredential.get(), rawAuthConfiguration.getAuthDescriptor());
        } else {
            try {
                Optional<AuthProperty> optionalInferredAuth =
                        inferredAuthProvider.inferAuth(imageReference.getRegistry());
                if (optionalInferredAuth.isPresent()) {
                    AuthProperty auth = optionalInferredAuth.get();
                    String username = auth.getUsername();
                    String password = auth.getPassword();
                    Credential credential = Credential.from(username, password);
                    defaultCredentialRetrievers.setInferredCredential(credential, auth.getAuthDescriptor());
                }
            } catch (InferredAuthException ex) {
                log(LogEvent.warn("InferredAuthException: " + ex.getMessage()));
            }
        }

        defaultCredentialRetrievers.setCredentialHelper(credHelper);
        defaultCredentialRetrievers.asList().forEach(registryImage::addCredentialRetriever);
    }

    @Override
    public void putDirectory(File sourceDirectory, String destinationDirectory) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
       throw new TransferFailedException("putDirectory not supported!!!");
    }

    @Override
    public boolean resourceExists(String resourceName) throws TransferFailedException, AuthorizationException {
        RegistryClient registryClient = getRegistryClient(resourceName);
        String tag = getTag(resourceName);
        try {
            return registryClient.checkManifest(tag).isPresent();
        } catch (Exception e) {
            throw new TransferFailedException(e.getMessage());
        }
    }

    @Override
    public List<String> getFileList(String destinationDirectory) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
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

    private void connectInternal(Repository source, ProxyInfo proxyInfo, ProxyInfoProvider proxyInfoProvider, AuthenticationInfo authenticationInfo) throws ConnectionException, AuthenticationException {
        this.repository = source;
        this.proxyInfo = proxyInfo;
        this.proxyInfoProvider = proxyInfoProvider;
        this.authenticationInfo = authenticationInfo;
        activateHttpAndHttpsProxies();
        this.client = new FailoverHttpClient(allowInsecureRegistries,
                JibSystemProperties.sendCredentialsOverHttp(),
                EventHandlers.NONE::dispatch);
    }

    @Override
    public void connect(Repository source) throws ConnectionException, AuthenticationException {
        connectInternal(source,null, null, null);
    }

    @Override
    public void connect(Repository source, ProxyInfo proxyInfo) throws ConnectionException, AuthenticationException {
        connectInternal(source, proxyInfo, null, null);
    }

    @Override
    public void connect(Repository source, ProxyInfoProvider proxyInfoProvider) throws ConnectionException, AuthenticationException {
        connectInternal(source,null, proxyInfoProvider, null);
    }

    @Override
    public void connect(Repository source, AuthenticationInfo authenticationInfo) throws ConnectionException, AuthenticationException {
        connectInternal(source,null, null, authenticationInfo);
    }

    @Override
    public void connect(Repository source, AuthenticationInfo authenticationInfo, ProxyInfo proxyInfo) throws ConnectionException, AuthenticationException {
        connectInternal(source, proxyInfo, null, authenticationInfo);
    }

    @Override
    public void connect(Repository source, AuthenticationInfo authenticationInfo, ProxyInfoProvider proxyInfoProvider) throws ConnectionException, AuthenticationException {
        connectInternal(source, null, proxyInfoProvider, authenticationInfo);
    }

    @Override
    public void openConnection() throws ConnectionException, AuthenticationException {
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

//    @Override
//    public void put(File source, String destination) throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException {
//
//        activateHttpAndHttpsProxies();
//
//        try {
//            // TODO remove
//            GlobalConfig globalConfig = GlobalConfig.readConfig();
//            Optional<String> image = Optional.of(this.repository.getUrl().substring(6) + "/" +  destination);
//            Preconditions.checkArgument(image.isPresent());
//
//            ImageReference targetImageReference = ImageReference.parse(image.get());
//            RegistryImage targetImage = RegistryImage.named(targetImageReference);
//
//            InferredAuthProvider inferredAuthProvider =  new MavenServerCredentials(this.authenticationInfo, this.repository);
//            RawConfiguration rawConfiguration = new MavenRawConfiguration(new JibPluginConfiguration() {
//                @Override
//                public void execute() throws MojoExecutionException, MojoFailureException {
//
//                }
//            });
//            configureCredentialRetrievers(
//                    rawConfiguration,
//                    targetImage,
//                    targetImageReference,
//                    PropertyNames.TO_AUTH_USERNAME,
//                    PropertyNames.TO_AUTH_PASSWORD,
//                    rawConfiguration.getToAuth(),
//                    inferredAuthProvider,
//                    rawConfiguration.getToCredHelper().orElse(null));
//
//            Containerizer containerizer =
//                    Containerizer.to(targetImage);
//            Multimaps.asMap(globalConfig.getRegistryMirrors()).forEach(containerizer::addRegistryMirrors);
//
//            JibSystemProperties.checkHttpTimeoutProperty();
//            JibSystemProperties.checkProxyPortProperty();
//
//            containerizer
//                    .setToolName("image-registry-maven-wagon")
//                    .setToolVersion("0.1.0")
//                    .setAllowInsecureRegistries(allowInsecureRegistries);
//
//            String path = destination.substring(0, destination.lastIndexOf("/"));
//            String version = path.substring(path.lastIndexOf("/") + 1 );
//            containerizer.withAdditionalTag(version);
//            this.tags.forEach(containerizer::withAdditionalTag);
//
//            List<Path> paths = new ArrayList<>();
//            paths.add(source.toPath());
//            JibContainerBuilder jibContainerBuilder = Jib.fromScratch()
//                    .addLayer(paths,"/maven/" + path);
//            jibContainerBuilder
//                    .setFormat(rawConfiguration.getImageFormat())
//                    .setLabels(rawConfiguration.getLabels())
//                    .setUser(rawConfiguration.getUser().orElse(null))
//                    .setWorkingDirectory(AbsoluteUnixPath.get("/maven"))
//                    .setCreationTime(Instant.ofEpochMilli(source.lastModified()));
//            getWorkingDirectoryChecked(rawConfiguration)
//                    .ifPresent(jibContainerBuilder::setWorkingDirectory);
//            jibContainerBuilder.containerize(containerizer);
//
////            // when an image is built, write out the digest and id
////            if (imageDigestOutputPath != null) {
////                String imageDigest = jibContainer.getDigest().toString();
////                Files.write(imageDigestOutputPath, imageDigest.getBytes(StandardCharsets.UTF_8));
////            }
////            if (imageIdOutputPath != null) {
////                String imageId = jibContainer.getImageId().toString();
////                Files.write(imageIdOutputPath, imageId.getBytes(StandardCharsets.UTF_8));
////            }
////            if (imageJsonOutputPath != null) {
////                ImageMetadataOutput metadataOutput = ImageMetadataOutput.fromJibContainer(jibContainer);
////                String imageJson = metadataOutput.toJson();
////                Files.write(imageJsonOutputPath, imageJson.getBytes(StandardCharsets.UTF_8));
////            }
//
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//        }
//
//    }
}

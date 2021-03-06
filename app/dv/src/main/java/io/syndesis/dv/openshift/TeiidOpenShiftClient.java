/*
 * Copyright (C) 2016 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.syndesis.dv.openshift;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.fabric8.kubernetes.api.builds.Builds;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerStateRunning;
import io.fabric8.kubernetes.api.model.DoneableService;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ReplicationController;
import io.fabric8.kubernetes.api.model.ReplicationControllerList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceFluent.SpecNested;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.internal.PodOperationsImpl;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildList;
import io.fabric8.openshift.api.model.DeploymentCondition;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.Route;
import io.fabric8.openshift.api.model.RouteSpec;
import io.fabric8.openshift.api.model.TLSConfigBuilder;
import io.fabric8.openshift.client.DefaultOpenShiftClient;
import io.fabric8.openshift.client.NamespacedOpenShiftClient;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.openshift.client.OpenShiftConfig;
import io.fabric8.openshift.client.OpenShiftConfigBuilder;
import io.syndesis.dv.KException;
import io.syndesis.dv.RepositoryManager;
import io.syndesis.dv.StringConstants;
import io.syndesis.dv.datasources.DataSourceDefinition;
import io.syndesis.dv.datasources.DefaultSyndesisDataSource;
import io.syndesis.dv.datasources.H2SQLDefinition;
import io.syndesis.dv.datasources.MongoDBDefinition;
import io.syndesis.dv.datasources.MySQLDefinition;
import io.syndesis.dv.datasources.PostgreSQLDefinition;
import io.syndesis.dv.datasources.SalesforceDefinition;
import io.syndesis.dv.datasources.TeiidDefinition;
import io.syndesis.dv.metadata.MetadataInstance;
import io.syndesis.dv.metadata.TeiidDataSource;
import io.syndesis.dv.metadata.internal.DefaultMetadataInstance;
import io.syndesis.dv.model.DataVirtualization;
import io.syndesis.dv.model.SourceSchema;
import io.syndesis.dv.openshift.BuildStatus.RouteStatus;
import io.syndesis.dv.openshift.BuildStatus.Status;
import io.syndesis.dv.server.DvConfigurationProperties;
import io.syndesis.dv.server.SSOConfigurationProperties;
import io.syndesis.dv.utils.StringNameValidator;
import io.syndesis.dv.utils.StringUtils;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.WeakHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.persistence.PersistenceException;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.shrinkwrap.api.GenericArchive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.ByteArrayAsset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.exporter.TarExporter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.teiid.adminapi.AdminException;
import org.teiid.adminapi.Model;
import org.teiid.adminapi.impl.ModelMetaData;
import org.teiid.adminapi.impl.SourceMappingMetadata;
import org.teiid.adminapi.impl.VDBMetaData;
import org.teiid.core.util.AccessibleByteArrayOutputStream;
import org.teiid.core.util.ObjectConverterUtil;

@SuppressWarnings({"nls", "PMD"}) // TODO refactor
public class TeiidOpenShiftClient {

    private static final int MONITOR_DELAY = 500;
    private static final String AVAILABLE = "Available";
    private static final String PROGRESSING = "Progressing";

    private static final Log LOG = LogFactory.getLog(TeiidOpenShiftClient.class);
    public static final String ID = "id";
    private static final String SERVICE_CA_CERT_FILE = "/var/run/secrets/kubernetes.io/serviceaccount/service-ca.crt";
    private static final String OPENSHIFT_HOST = "https://openshift.default.svc";
    private final long buildTimeoutInSeconds = 2 * 60 * 1000L;
    private final OpenShiftConfig openShiftClientConfig = new OpenShiftConfigBuilder().withMasterUrl(OPENSHIFT_HOST)
        .withCaCertFile(SERVICE_CA_CERT_FILE).withBuildTimeout(buildTimeoutInSeconds).build();
    private NamespacedOpenShiftClient openshiftClient;


    private static final String DESCRIPTION_ANNOTATION_LABEL = "description";
    private static final String DEPLOYMENT_VERSION_LABEL = "syndesis.io/deployment-version";

    private static final String SERVICE_DESCRIPTION = "Virtual Database (VDB)";

    private static final String SYSDESIS = "syndesis";
    private static final String MANAGED_BY = "managed-by";
    private static final String SYNDESISURL = "http://syndesis-server/api/v1";

    private final MetadataInstance metadata;
    private final Map<String, DataSourceDefinition> sources = new ConcurrentHashMap<>();
    private final Map<String, DefaultSyndesisDataSource> syndesisSources = new ConcurrentHashMap<String, DefaultSyndesisDataSource>();
    private Map<String, List<String>> integrationsInUse;
    private long integrationRefreshTime;

    /**
     * Fixed pool of up to 3 threads for configuring images ready to be deployed
     */
    private final ThreadPoolExecutor configureService = new ThreadPoolExecutor(3, 3, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    private final Map<String, PrintWriter> logBuffers = new ConcurrentHashMap<>();
    private final EncryptionComponent encryptionComponent;
    private final DvConfigurationProperties config;

    private final ScheduledThreadPoolExecutor workExecutor = new ScheduledThreadPoolExecutor(1);
    private final RepositoryManager repositoryManager;
    private final Map<String, String> mavenRepos;

    /**
     * Get the OpenShift name, requires lower case and must start/end with
     * alpha - which we have already validated
     */
    public static String getOpenShiftName(String name) {
        return "dv-" + name.toLowerCase(Locale.US); //$NON-NLS-1$
    }

    private NamespacedOpenShiftClient openshiftClient() {
        if (this.openshiftClient == null) {
            ConnectionPool pool = new ConnectionPool(5, 10000, TimeUnit.MILLISECONDS);
            OkHttpClient.Builder builder = new OkHttpClient.Builder().connectionPool(pool);
            OkHttpClient client = HttpClientUtils.createHttpClient(openShiftClientConfig, builder);
            this.openshiftClient = new DefaultOpenShiftClient(client, openShiftClientConfig);
        }
        return this.openshiftClient;
    }

    /**
     * Responsible for sending SUBMITTED work to be configured
     * and for sending completed builds to be deployed.
     */
    private class BuildStatusRunner implements Runnable {

        private final BuildStatus work;

        private BuildStatusRunner(BuildStatus buildStatus) {
            this.work = buildStatus;
        }

        @Override
        @SuppressWarnings({"FutureReturnValueIgnored", "PMD.ExcessiveMethodLength"}) // TODO refactor
        public void run() {
            work.setLastUpdated();
            boolean shouldReQueue = true;
            try {
                switch (work.getStatus()) {
                case CONFIGURING:
                case DELETE_SUBMITTED:
                    //continue to monitor the other thread
                    debug(work.getOpenShiftName(), "Monitoring " + work.getStatus());
                    break;
                case DELETE_REQUEUE:
                    // requeue will change state to submitted and
                    deleteVirtualization(work.getDataVirtualizationName());
                    break;
                case SUBMITTED:
                    //
                    // build submitted for configuration. This is done on another
                    // thread to avoid clogging up the monitor thread.
                    //
                    info(work.getOpenShiftName(), "Publishing - Submitted build to be configured");
                    configureBuild(work);
                    break;
                case BUILDING: {
                    final OpenShiftClient client = openshiftClient();
                    Build build = client.builds().inNamespace(work.getNamespace()).withName(work.getName()).get();
                    if (build == null) {
                        // build got deleted some how ignore, remove from monitoring..
                        error(work.getOpenShiftName(), "Publishing - No build available for building");
                        shouldReQueue = false;
                        break;
                    }

                    String lastStatus = build.getStatus().getPhase();
                    if (Builds.isCompleted(lastStatus)) {
                        DeploymentStatus deploymentStatus = work.getDeploymentStatus();
                        deploymentStatus.setStatus(DeploymentStatus.Status.DEPLOYING);
                        work.setStatus(Status.COMPLETE);
                        work.setStatusMessage("Build complete, see deployment message");
                        info(work.getOpenShiftName(), "Publishing - Build completed. Preparing to deploy");
                        deploymentStatus.setStatusMessage("build completed, deployment started");
                        createSecret(client, work.getNamespace(), work.getOpenShiftName(), work);
                        DeploymentConfig dc = createDeploymentConfig(client, work);
                        deploymentStatus.setDeploymentName(dc.getMetadata().getName());
                        client.deploymentConfigs().inNamespace(work.getNamespace())
                                .withName(dc.getMetadata().getName()).deployLatest();
                    } else if (Builds.isCancelled(lastStatus)) {
                        info(work.getOpenShiftName(), "Publishing - Build cancelled");
                        // once failed do not queue the work again.
                        shouldReQueue = false;
                        work.setStatus(Status.CANCELLED);
                        work.setStatusMessage(build.getStatus().getMessage());
                        debug(work.getOpenShiftName(), "Build cancelled: " + work.getName() + ". Reason "
                                + build.getStatus().getLogSnippet());
                    } else if (Builds.isFailed(lastStatus)) {
                        error(work.getOpenShiftName(), "Publishing - Build failed");
                        // once failed do not queue the work again.
                        shouldReQueue = false;
                        work.setStatus(Status.FAILED);
                        work.setStatusMessage(build.getStatus().getMessage());
                        error(work.getOpenShiftName(),
                                "Build failed :" + work.getName() + ". Reason " + build.getStatus().getLogSnippet());
                    }
                    break;
                }
                case COMPLETE: {
                    final OpenShiftClient client = openshiftClient();
                    DeploymentStatus deploymentStatus = work.getDeploymentStatus();
                    DeploymentConfig dc = client.deploymentConfigs().inNamespace(work.getNamespace())
                            .withName(deploymentStatus.getDeploymentName()).get();
                    DeploymentCondition available = getDeploymentCondition(dc, AVAILABLE);
                    DeploymentCondition progressing = getDeploymentCondition(dc, PROGRESSING);
                    if (isDeploymentAvailable(available, progressing)) {
                        // it is done now..
                        info(work.getOpenShiftName(), "Publishing - Deployment completed");
                        createService(client, work.getNamespace(), work.getOpenShiftName());
                        if (!config.isExposeVia3scale()) {
                            createRoute(client, work.getNamespace(), work.getOpenShiftName(), ProtocolType.ODATA.id());
                        }
                        createSyndesisConnection(client, work.getNamespace(), work.getOpenShiftName(), work.getDataVirtualizationName());
                        deploymentStatus.setStatus(DeploymentStatus.Status.RUNNING);
                        shouldReQueue = false;
                    } else {
                        if (!isDeploymentProgressing(progressing)) {
                            deploymentStatus.setStatus(DeploymentStatus.Status.FAILED);
                            info(work.getOpenShiftName(), "Publishing - Deployment seems to be failed, this could be "
                                    + "due to vdb failure, rediness check failed. Wait threshold is 2 minutes.");
                            shouldReQueue = false;
                        }
                        debug(work.getOpenShiftName(), "Publishing - Deployment not ready");
                        if (available != null) {
                            debug(work.getOpenShiftName(), "Publishing - Deployment condition: " + available.getMessage());
                            deploymentStatus.setStatusMessage(available.getMessage());
                        } else {
                            deploymentStatus.setStatusMessage("Available condition not found in the Deployment Config");
                        }
                    }
                    break;
                }
                default:
                    shouldReQueue = false;
                    break; // a terminal state - should generally not reach here
                }

                if (shouldReQueue) {
                    workExecutor.schedule(this, MONITOR_DELAY, TimeUnit.MILLISECONDS); // add at end
                } else {
                    // Close the log as no longer needed actively
                    closeLog(work.getOpenShiftName());
                }
            } catch (Throwable ex) {
                //
                // Does not specify an id so will only be logged in the KLog.
                //
                error(null, "Monitor exception", ex);
            }
        }
    }

    public TeiidOpenShiftClient(MetadataInstance metadata, EncryptionComponent encryptor,
            DvConfigurationProperties config, RepositoryManager repositoryManager, Map<String, String> mavenRepos) {
        this.metadata = metadata;
        this.encryptionComponent = encryptor;
        this.config = config;
        this.repositoryManager = repositoryManager;
        this.configureService.allowCoreThreadTimeOut(true);
        this.mavenRepos = mavenRepos;

        // data source definitions
        add(new PostgreSQLDefinition());
        add(new MySQLDefinition());
        add(new TeiidDefinition());
        add(new MongoDBDefinition());
        add(new SalesforceDefinition());
        add(new H2SQLDefinition());
    }

    private static String getLogPath(String id) {
        String parentDir;
        try {
            File loggerPath = File.createTempFile("vdb-", "log");
            parentDir = loggerPath.getParent();
        } catch(Exception ex) {
            LOG.error("Failure to get logger path", ex);
            parentDir = System.getProperty(StringConstants.JAVA_IO_TMPDIR);
        }

        return parentDir + File.separator + id + ".log";
    }

    private void closeLog(String id) {
        PrintWriter pw = logBuffers.remove(id);
        if (pw == null) {
            return;
        }

        pw.close();
    }
    private void addLog(String id, String message) {
        if (id == null) {
            return; // Cannot record these log messages
        }

        try {
            PrintWriter pw = logBuffers.get(id);
            if (pw == null) {
                // No cached buffered writer
                String logPath = getLogPath(id);
                File logFile = new File(logPath);

                Writer fw = Files.newBufferedWriter(logFile.toPath(), UTF_8, CREATE, APPEND);
                BufferedWriter bw = new BufferedWriter(fw);
                pw = new PrintWriter(bw);
                logBuffers.put(id, pw);
            }

            pw.write(StringConstants.OPEN_BRACKET);
            pw.write(Instant.now().toString());
            pw.write(StringConstants.CLOSE_BRACKET);
            pw.write(StringConstants.SPACE);
            pw.write(StringConstants.HYPHEN);
            pw.write(StringConstants.SPACE);
            pw.write(message);
            pw.write(StringConstants.NEW_LINE);
            pw.flush();

        } catch (Exception ex) {
            error(id, "Error with logging to file", ex);
        }
    }

    private void removeLog(String id) {
        closeLog(id);

        String logPath = getLogPath(id);
        File logFile = new File(logPath);
        if (logFile.exists() && !logFile.delete()) {
            logFile.deleteOnExit();
        }
    }

    private void debug(String id, String message) {
        if (! LOG.isDebugEnabled()) {
            return;
        }

        LOG.debug(message);
        addLog(id, message);
    }

    private void error(String id, String message, Throwable ex) {
        LOG.error(message, ex);
        String cause = StringUtils.exceptionToString(ex);
        addLog(id, message);
        addLog(id,cause);
    }

    private void error(String id, String message) {
        LOG.error(message);
        addLog(id, message);
    }

    private void info(String id, String message) {
        LOG.info(message);
        addLog(id, message);
    }

    private void add(DataSourceDefinition def) {
        sources.put(def.getType(), def);
    }

    /**
     * Returns DataSourceDefinition based on property sniffing
     * @param properties properties from service creation
     * @return DataSourceDefinition
     */
    public DataSourceDefinition getSourceDefinitionThatMatches(Map<String, String> properties, String type) {
        for (DataSourceDefinition dd : this.sources.values()) {
            if (dd.isTypeOf(properties, type)) {
                return dd;
            }
        }
        return null;
    }

    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    private void createSyndesisConnection(final OpenShiftClient client, final String namespace,
            final String openshiftName, final String virtualizationName) {
        try {
            Service service = client.services().inNamespace(namespace).withName(openshiftName).get();
            if (service == null) {
                info(openshiftName, "Database connection to Virtual Database " +
                        openshiftName + " not created beacuse no service found");
                return;
            }
            String schema = virtualizationName;
            String url = SYNDESISURL+"/connections/";
            String payload = "{\n" +
                    "  \"name\": \""+virtualizationName+"\",\n" +
                    "  \"configuredProperties\": {\n" +
                    "    \"password\": \"password\",\n" +
                    "    \"schema\": \""+schema+"\",\n" +
                    "    \"url\": \"jdbc:teiid:"+virtualizationName+"@mm://"+service.getSpec().getClusterIP()+":31000\",\n" +
                    "    \"user\": \"user\"\n" +
                    "  },\n" +
                    "  \"connectorId\": \"sql\",\n" +
                    "  \"icon\": \"assets:sql.svg\",\n" +
                    "  \"description\": \"Connection to "+virtualizationName+" \"\n" +
                    "}";

            try(SyndesisHttpClient syndesisClient = new SyndesisHttpClient();
                InputStream response = syndesisClient.executePOST(url, payload)){
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                String id = root.get("id").asText();

                this.repositoryManager.runInTransaction(false, () -> {
                    // save the ID to the database
                    DataVirtualization dv = this.repositoryManager.findDataVirtualization(virtualizationName);
                    if (dv != null) {
                        dv.setSourceId(id);
                    }
                    return null;
                });
                info(openshiftName, "Database connection to Virtual Database "
                        + virtualizationName + " created with Id = " + id);
            }
        } catch (Exception e) {
            throw handleError(e);
        }
    }

    private List<String> findIntegrationUsedIn(String virtualizationName) {
        List<String> usedIn = null;
        // only get the status every minute, looks like syndesis server is rejecting otherwise and also
        // pushing the pod to restart.
        if (this.integrationsInUse == null || System.currentTimeMillis() - integrationRefreshTime > 60000) {
            this.integrationsInUse = findIntegrationByConnectionId();
            this.integrationRefreshTime = System.currentTimeMillis();
        }
        DataVirtualization dv = this.repositoryManager.findDataVirtualization(virtualizationName);
        if (dv != null && dv.getSourceId() != null) {
            usedIn = this.integrationsInUse.get(dv.getSourceId());
        }
        return (usedIn == null)?Collections.emptyList():usedIn;
    }

    private static Map<String, List<String>> findIntegrationByConnectionId() {
        Map<String, List<String>> usedIn = new WeakHashMap<>();
        String url = SYNDESISURL+"/integrations";
        try (SyndesisHttpClient syndesisClient = new SyndesisHttpClient();
            InputStream response = syndesisClient.executeGET(url)){
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            JsonNode items = root.get("items");
            if (items == null) {
                return usedIn;
            }
            for (JsonNode item: items) {
                String integrationName = item.get("name").asText();
                JsonNode flows = item.get("flows");
                if (flows == null) {
                    continue;
                }
                for (JsonNode flow: flows) {
                    JsonNode steps = flow.get("steps");
                    if (steps == null) {
                        continue;
                    }
                    for (JsonNode step: steps) {
                        JsonNode connection = step.get("connection");
                        if (connection != null) {
                            JsonNode id = connection.get("id");
                            if (id != null) {
                                String idStr = id.asText();
                                List<String> integrations = usedIn.get(idStr);
                                if (integrations == null) {
                                    integrations = new ArrayList<>();
                                    usedIn.put(idStr, integrations);
                                }
                                integrations.add(integrationName);
                            }
                        }
                    }
                }
            }
        } catch(IOException e) {
            throw handleError(e);
        }
        return usedIn;
    }

    private void removeSyndesisConnection(String virtualizationName) {
        try {
            DataVirtualization dv = this.repositoryManager.runInTransaction(false, () -> {
                return this.repositoryManager.findDataVirtualization(virtualizationName);
            });

            if (dv != null) {
                try(SyndesisHttpClient syndesisClient = new SyndesisHttpClient()) {
                    syndesisClient.executeDELETE(SYNDESISURL + "/connections/" + dv.getSourceId());
                    info(dv.getName(), "Database connection to Virtual Database " + dv.getName()
                        + " deleted with Id = "+ dv.getSourceId());
                    // remove the source id from database
                    dv.setSourceId(null);
                }
            }
        } catch (Exception e) {
            throw handleError(e);
        }
    }

    public Set<DefaultSyndesisDataSource> getSyndesisSources() {
        Set<DefaultSyndesisDataSource> result = new HashSet<>();
        String url = SYNDESISURL+"/connections";
        try (SyndesisHttpClient syndesisClient = new SyndesisHttpClient();
            InputStream response = syndesisClient.executeGET(url)){
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response);
            for (JsonNode item: root.get("items")) {
                String connectorType = item.get("connectorId").asText();
                String name = item.get("name").asText();
                try {
                    DefaultSyndesisDataSource ds = buildSyndesisDataSource(name, item, connectorType);
                    if (ds != null) {
                        result.add(ds);
                    }
                } catch (KException e) {
                    error(name, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            throw handleError(e);
        }
        return result;
    }

    public DefaultSyndesisDataSource getSyndesisDataSourceById(String dsId, boolean checkRemote)
            {
        DefaultSyndesisDataSource source = syndesisSources.get(dsId);
        if (source == null && checkRemote) {
            String url = SYNDESISURL+"/connections/"+dsId;
            try (SyndesisHttpClient syndesisClient = new SyndesisHttpClient();
                InputStream response = syndesisClient.executeGET(url)){
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response);
                String connectorType = root.get("connectorId").asText();
                String name = root.get("name").asText();
                source = buildSyndesisDataSource(name, root, connectorType);
            } catch (Exception e) {
                throw handleError(e);
            }
        }
        return source;
    }

    public DefaultSyndesisDataSource getSyndesisDataSource(String dsName)
            {
        try {
            TeiidDataSource tds = metadata.getDataSource(dsName);
            if (tds == null) {
                return null;
            }
            return getSyndesisDataSourceById(tds.getSyndesisId(), true);
        } catch (Exception e) {
            throw handleError(e);
        }
    }

    private DefaultSyndesisDataSource buildSyndesisDataSource(String syndesisName, JsonNode item, String type)
            {
        Map<String, String> p = new HashMap<>();
        JsonNode configuredProperties = item.get("configuredProperties");
        if (configuredProperties != null) {
            configuredProperties.fieldNames()
                    .forEachRemaining(key -> p.put(key, configuredProperties.get(key).asText()));
        }

        DataSourceDefinition def = getSourceDefinitionThatMatches(p, type);
        if (def == null) {
            LOG.debug("Not SQL Connection, not supported by Data Virtualization yet.");
            return null;
        }
        JsonNode connectorIDNode = item.get(ID);
        if( connectorIDNode == null ) {
            throw new KException("Datasource has no connection ID");
        }
        DefaultSyndesisDataSource dsd = new DefaultSyndesisDataSource();
        dsd.setId(connectorIDNode.asText());
        dsd.setSyndesisName(syndesisName);
        String dsName = findDataSourceNameByEventId(connectorIDNode.asText());
        dsd.setTeiidName(dsName);
        dsd.setTranslatorName(def.getTranslatorName());
        dsd.setProperties(encryptionComponent.decrypt(p));
        dsd.setDefinition(def);
        syndesisSources.putIfAbsent(dsd.getSyndesisConnectionId(), dsd);
        return dsd;
    }

    public void createDataSource(DefaultSyndesisDataSource scd) throws AdminException {
        String syndesisName = scd.getSyndesisName();
        debug(syndesisName, "Creating the Datasource of Type " + scd.getType());

        if (scd.getTeiidName() == null) {
            for (int i = 0; i < 3; i++) {
                try {
                    String name = getUniqueTeiidName(scd, syndesisName);
                    scd.setTeiidName(name);
                    break;
                } catch (PersistenceException | DataIntegrityViolationException ignored) {
                    //multiple pods are trying to assign a name simultaneously
                    //if we try again, then we'll just pickup whatever someone else set
                }
            }
            if (scd.getTeiidName() == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
        }

        //now that the name is set, we can create the properties
        this.metadata.registerDataSource(scd);
    }

    /**
     * Create a unique and valid name the syndesis connection.  The name will be suitable
     * as a schema name as well.
     */
    public String getUniqueTeiidName(DefaultSyndesisDataSource scd, String syndesisName) {
        return repositoryManager.runInTransaction(false, () -> {
            SourceSchema ss = repositoryManager.findSchemaBySourceId(scd.getSyndesisConnectionId());
            if (ss != null) {
                return ss.getName();
            }

            String name = syndesisName;
            int maxLength = StringNameValidator.DEFAULT_MAXIMUM_LENGTH - 6;
            //remove any problematic characters
            name = name.replaceAll("[\\.\\?\\_\\s]", "");
            //slim it down
            if (name.length() > maxLength) {
                name = name.substring(0, maxLength);
            }

            TreeSet<String> taken = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            taken.addAll(ModelMetaData.getReservedNames());

            String toUse = name;
            if (taken.contains(name) || repositoryManager.isNameInUse(name)) {
                //we'll just use lowercase and numbers
                Random r = new Random();
                int val = r.nextInt();
                char[] rand = new char[5];
                for (int i = 0; i < rand.length; i++) {
                    int low = val & 0x001f;
                    if (low < 10) {
                        rand[i] = (char)(low + 48);
                    } else {
                        rand[i] = (char)(low + 87);
                    }
                    val = val >> 5;
                }
                toUse = name + "_" + new String(rand);
            }

            //update the db with the name we'll use
            repositoryManager.createSchema(scd.getSyndesisConnectionId(), toUse, null);
            return toUse;
        });
    }

    public void deleteDataSource(DefaultSyndesisDataSource dsd) {
        String teiidName = dsd.getTeiidName();
        if (teiidName != null) {
            this.metadata.deleteDataSource(teiidName);
        }
        this.syndesisSources.remove(dsd.getSyndesisConnectionId());
    }

    public String findDataSourceNameByEventId(String eventId)  {
        SourceSchema sourceSchema = this.repositoryManager.findSchemaBySourceId(eventId);
        if (sourceSchema != null) {
            return sourceSchema.getName();
        }
        return null;
    }

    private static ImageStream createImageStream(OpenShiftClient client, String namespace, String openShiftName) {
        return client.imageStreams().inNamespace(namespace).createOrReplaceWithNew()
            .withNewMetadata().withName(openShiftName).addToLabels("application", openShiftName).endMetadata()
            .done();
    }

    private static BuildConfig createBuildConfig(OpenShiftClient client, String namespace, String openShiftName, ImageStream is,
            PublishConfiguration pc) {
        String imageStreamName = is.getMetadata().getName()+":latest";
        return client.buildConfigs().inNamespace(namespace).createOrReplaceWithNew()
            .withNewMetadata().withName(getBuildConfigName(openShiftName))
                .addToLabels("application", openShiftName)
                .addToLabels(MANAGED_BY, SYSDESIS)
                .addToLabels(DEPLOYMENT_VERSION_LABEL, String.valueOf(pc.getPublishedRevision()))
                .endMetadata()
            .withNewSpec()
                .withRunPolicy("SerialLatestOnly")
                .withNewSource().withType("Binary").endSource()
                .withNewStrategy()
                .withType("Source").withNewSourceStrategy()
                .withNewFrom()
                    .withKind("ImageStreamTag")
                .withName(pc.getBuildImageStream())
                    .withNamespace(namespace)
                .endFrom()
                .withIncremental(false)
                .withEnv(pc.getUserEnvVars())
                .endSourceStrategy()
                .endStrategy()
                .withNewOutput()
                    .withNewTo().withKind("ImageStreamTag").withName(imageStreamName).endTo()
                .endOutput()
                .withNodeSelector(pc.getBuildNodeSelector()).endSpec()
            .done();
    }

    private static String getBuildConfigName(String openShiftName) {
        return openShiftName+"-build-config";
    }

    private static Build createBuild(OpenShiftClient client, String namespace, BuildConfig config,
            InputStream tarInputStream) {
        return client.buildConfigs()
                .inNamespace(namespace)
                .withName(config.getMetadata().getName())
                .instantiateBinary().fromInputStream(tarInputStream);
    }

    private static DeploymentConfig createDeploymentConfig(OpenShiftClient client, BuildStatus config) {

        return client.deploymentConfigs().inNamespace(config.getNamespace()).createOrReplaceWithNew()
            .withNewMetadata().withName(config.getOpenShiftName())
                .addToLabels("application", config.getOpenShiftName())
            .endMetadata()
            .withNewSpec()
              .withReplicas(1)
              .withNewStrategy().withType("Recreate").endStrategy()
              .addNewTrigger()
                .withType("ConfigChange")
                .withType("ImageChange")
                    .withNewImageChangeParams()
                        .withAutomatic(true)
                        .addToContainerNames(config.getOpenShiftName())
                        .withNewFrom().withKind("ImageStreamTag").withName(config.getOpenShiftName()+":latest").endFrom()
                    .endImageChangeParams()
              .endTrigger()
              .addToSelector("deploymentConfig", config.getOpenShiftName())
              .withNewTemplate()
                .withNewMetadata()
                  .withName(config.getOpenShiftName())
                  .addToLabels("application", config.getOpenShiftName())
                  .addToLabels("deploymentConfig", config.getOpenShiftName())
                  .addToLabels("syndesis.io/type", "datavirtualization")
                  .addToLabels(DEPLOYMENT_VERSION_LABEL, String.valueOf(config.getVersion()))
                  .addToAnnotations("prometheus.io/scrape", "true")
                  .addToAnnotations("prometheus.io/port", String.valueOf(ProtocolType.PROMETHEUS.getTargetPort()))
                .endMetadata()
                .withNewSpec()
                  .addNewContainer()
                    .withName(config.getOpenShiftName())
                    .withImage(" ")
                    .withImagePullPolicy("Always")
                    .addAllToEnv(config.getPublishConfiguration().getEnvironmentVariables())
                    .withNewReadinessProbe()
                      .withNewHttpGet()
                      .withNewPort(8080)
                      .withPath("/actuator/health")
                      .endHttpGet()
                      .withInitialDelaySeconds(30)
                      .withTimeoutSeconds(5)
                      .withPeriodSeconds(20)
                      .withFailureThreshold(5)
                      .withSuccessThreshold(1)
                    .endReadinessProbe()
                    .withNewLivenessProbe()
                      .withNewHttpGet()
                      .withNewPort(8080)
                      .withPath("/actuator/health")
                      .endHttpGet()
                      .withInitialDelaySeconds(30)
                      .withTimeoutSeconds(5)
                      .withPeriodSeconds(20)
                      .withFailureThreshold(5)
                      .withSuccessThreshold(1)
                    .endLivenessProbe()
                    .withNewResources()
                        .addToLimits("memory", new Quantity(config.getPublishConfiguration().getContainerMemorySize()))
                        .addToLimits("cpu", new Quantity(config.getPublishConfiguration().getCpuUnits()))
                        // deployment fails with this.
                        // .addToLimits("ephemeral-storage", new Quantity(config.getPublishConfiguration().getContainerDiskSize()))
                    .endResources()
                    .addAllToPorts(getDeploymentPorts(config.getPublishConfiguration()))
                  .endContainer()
                .endSpec()
              .endTemplate()
            .endSpec()
            .done();
    }

    private static List<ContainerPort> getDeploymentPorts(PublishConfiguration config){
        List<ContainerPort> ports = new ArrayList<>();
        ports.add(createPort(ProtocolType.PROMETHEUS));
        ports.add(createPort(ProtocolType.JOLOKIA));
        ports.add(createPort(ProtocolType.JDBC));
        ports.add(createPort(ProtocolType.PG));
        if (config.isEnableOData()) {
            ports.add(createPort(ProtocolType.ODATA));
        }
        return ports;
    }

    private static ContainerPort createPort(ProtocolType protocol) {
        ContainerPort p = new ContainerPort();
        p.setName(protocol.id());
        p.setContainerPort(protocol.getSourcePort());
        p.setProtocol("TCP");
        return p;
    }

    private Service createService(OpenShiftClient client, String namespace, String openShiftName) {
        String serviceName = openShiftName;
        debug(openShiftName, "Creating the Service for VDB "+openShiftName);
        Service service = client.services().inNamespace(namespace).withName(serviceName).get();
        if (service == null) {

            //TODO: this does not check if odata is enabled

            TreeMap<String, String> labels = new TreeMap<String, String>();
            labels.put("application", openShiftName);

            TreeMap<String, String> annotations = new TreeMap<String, String>();
            annotations.put(DESCRIPTION_ANNOTATION_LABEL, SERVICE_DESCRIPTION);
            if (this.config.isExposeVia3scale()) {
                labels.put("discovery.3scale.net", "true");
                annotations.put("discovery.3scale.net/scheme", "http");
                annotations.put("discovery.3scale.net/port", Integer.toString(ProtocolType.ODATA.getTargetPort()));
                annotations.put("discovery.3scale.net/description-path", "/openapi.json");
            }
            SpecNested<DoneableService> donable = client.services().inNamespace(namespace).createNew()
              .withNewMetadata()
                .withName(serviceName)
                .addToLabels(labels)
                .addToAnnotations(annotations)
              .endMetadata()
              .withNewSpec()
                .withSessionAffinity("ClientIP")
                .addToSelector("application", openShiftName);
            for (ProtocolType type : ProtocolType.values()) {
                donable.addNewPort()
                  .withName(type.id())
                  .withPort(type.getTargetPort())
                  .withNewTargetPort()
                    .withStrVal(type.id())
                  .endTargetPort()
                .endPort();
            }
            service = donable.endSpec().done();
        }
        return service;
    }

    private static String secretName(String name) {
        return name+"-secret";
    }

    private static Secret createSecret(OpenShiftClient client, String namespace, String openShiftName,
            BuildStatus config) {
        String secretName = secretName(openShiftName);

        Secret item = new SecretBuilder().withData(config.getPublishConfiguration().getSecretVariables()).withNewMetadata()
                .addToLabels("application", openShiftName).withName(secretName).endMetadata().build();

        return client.secrets().inNamespace(namespace).withName(secretName).createOrReplace(item);
    }

    private static Route createRoute(OpenShiftClient client, String namespace, String openShiftName, String type) {
        String routeName = openShiftName+"-"+type;
        Route route = client.routes().inNamespace(namespace).withName(routeName).get();
        if (route == null) {
            //
            // Create edge termination SSL configuration
            //
            TLSConfigBuilder builder = new TLSConfigBuilder();
            builder.withTermination("edge");

            //
            // Creates secured route
            //
            route = client.routes().inNamespace(namespace).createNew()
              .withNewMetadata()
                .withName(routeName)
                .addToLabels("application", openShiftName)
                .addToAnnotations(DESCRIPTION_ANNOTATION_LABEL, SERVICE_DESCRIPTION)
              .endMetadata()
              .withNewSpec()
              .withNewPort().withNewTargetPort().withStrVal(type).endTargetPort().endPort()
              .withNewTo().withName(openShiftName).endTo()
              .withTls(builder.build())
              .endSpec()
              .done();
        }
        return route;
    }

    private void waitUntilPodIsReady(String openShiftName, final OpenShiftClient client, String podName, int nAwaitTimeout) {
        try {
            client.pods().withName(podName).waitUntilReady(nAwaitTimeout, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            error(openShiftName, "Publishing - Timeout waiting for pod to become ready", e);
        }
    }

    public String getPodStartedAt(String namespace, String openShiftName) {
        List<Pod> pods = openshiftClient().pods().inNamespace(namespace).withLabel("application", openShiftName).list().getItems();
        if (!pods.isEmpty()) {
            ContainerStateRunning running = pods.get(0).getStatus().getContainerStatuses().get(0).getState().getRunning();
            if (running != null) {
                return running.getStartedAt();
            }
        }
        return null;
    }

    private static boolean statusEquals(DeploymentCondition cond, String value) {
        if (cond == null) {
            return false;
        }
        return cond.getStatus().equals(value);
    }

    /**
     * We'll consider things progressing if true or unknown
     */
    private static boolean isDeploymentProgressing(DeploymentCondition progressing) {
        return !statusEquals(progressing, "False");
    }

    /**
     * We're available if available=true and either progressing true, null (not unknown), or old
     */
    private static boolean isDeploymentAvailable(DeploymentCondition available,
            DeploymentCondition progressing) {
        return statusEquals(available, "True") &&
                (!statusEquals(progressing, "False")
                        || available.getLastTransitionTime().compareTo(progressing.getLastTransitionTime()) > 0);
    }

    private static DeploymentCondition getDeploymentCondition(DeploymentConfig dc, String type) {
        List<DeploymentCondition> conditions = dc.getStatus().getConditions();
        for (DeploymentCondition cond : conditions) {
            if (cond.getType().equals(type)) {
                return cond;
            }
        }
        return null;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private BuildStatus addToQueue(String openshiftName, PublishConfiguration publishConfig) {
        BuildStatus work = new BuildStatus(openshiftName);
        work.setStatus(Status.SUBMITTED);
        work.setNamespace(ApplicationProperties.getNamespace());
        work.setStatusMessage("Submitted build for configuration");
        work.setLastUpdated();
        work.setPublishConfiguration(publishConfig);
        work.setDataVirtualizationName(publishConfig.getDataVirtualizationName());
        work.setVersion(publishConfig.getPublishedRevision());
        this.workExecutor.submit(new BuildStatusRunner(work));
        return work;
    }

    protected void configureBuild(BuildStatus work) {
        work.setStatus(Status.CONFIGURING);
        configureService.execute(new Runnable() {
            @Override
            @SuppressFBWarnings("REC_CATCH_EXCEPTION")
            public void run() {
                info(work.getOpenShiftName(), "Publishing  - Configuring ...");

                String namespace = work.getNamespace();
                PublishConfiguration publishConfig = work.getPublishConfiguration();
                VDBMetaData vdb = publishConfig.getVDB();

                String openShiftName = work.getOpenShiftName();
                try {
                    OpenShiftClient client = openshiftClient();
                    info(openShiftName, "Publishing - Checking for base image");

                    // create build contents as tar file
                    info(openShiftName, "Publishing - Creating zip archive");
                    GenericArchive archive = ShrinkWrap.create(GenericArchive.class, "contents.tar");
                    String pomFile = generatePomXml(vdb, publishConfig.isEnableOData(), publishConfig.isSecurityEnabled());

                    debug(openShiftName, "Publishing - Generated pom file: " + StringConstants.NEW_LINE + pomFile);
                    archive.add(new StringAsset(pomFile), "pom.xml");

                    normalizeDataSourceNames(vdb);

                    AccessibleByteArrayOutputStream vdbContents = DefaultMetadataInstance.toBytes(vdb);
                    archive.add(new ByteArrayAsset(new ByteArrayInputStream(vdbContents.getBuffer(), 0, vdbContents.getCount())), "/src/main/resources/" + vdb.getName() + "-vdb.xml");

                    InputStream configIs = this.getClass().getClassLoader().getResourceAsStream("s2i/application.properties");
                    archive.add(new ByteArrayAsset(ObjectConverterUtil.convertToByteArray(configIs)),
                                "/src/main/resources/application.properties");

                    for (Model model : vdb.getModels()) {
                        if (model.isSource()) {
                            buildDataSourceBuilders(model, archive);
                        }
                    }

                    InputStream appIs = this.getClass().getClassLoader().getResourceAsStream("s2i/Application.java");
                    archive.add(new ByteArrayAsset(ObjectConverterUtil.convertToByteArray(appIs)),
                                "/src/main/java/io/integration/Application.java");

                    info(openShiftName, "Publishing - Converting archive to TarExport");
                    InputStream buildContents = archive.as(TarExporter.class).exportAsInputStream();
                    info(openShiftName, "Publishing - Completed creating build contents construction");

                    info(openShiftName, "Publishing - Creating image stream");
                    // use the contents to invoke a binary build
                    ImageStream is = createImageStream(client, namespace, openShiftName);

                    info(openShiftName, "Publishing - Creating build config");
                    BuildConfig buildConfig = createBuildConfig(client, namespace, openShiftName, is, publishConfig);

                    info(openShiftName, "Publishing - Creating build");
                    Build build = createBuild(client, namespace, buildConfig, buildContents);

                    String buildName = build.getMetadata().getName();
                    info(openShiftName, "Publishing - Build created: " + buildName);

                    PodOperationsImpl publishPod = (PodOperationsImpl)client.pods().withName(buildName + "-build");

                    info(openShiftName, "Publishing - Awaiting pod readiness ...");
                    waitUntilPodIsReady(openShiftName, client, buildName + "-build", 20);

                    info(openShiftName, "Publishing - Fetching environment variables for vdb data sources");

                    if (publishConfig.isSecurityEnabled()) {
                        SSOConfigurationProperties configurationProperties = publishConfig.getSsoConfigurationProperties();
                        publishConfig.addEnvironmentVariables(getEnvironmentVariablesForSecurity(configurationProperties));
                    }

                    publishConfig.addEnvironmentVariables(
                            getEnvironmentVariablesForVDBDataSources(vdb, publishConfig, openShiftName));

                    publishConfig.addSecretVariables(getSecretVariablesForVDBDataSources(vdb, publishConfig));

                    work.setName(buildName);
                    work.setStatusMessage("Build Running");
                    work.setPublishPodName(publishPod.getName());
                    work.setLastUpdated();
                    work.setStatus(Status.BUILDING);

                    info(openShiftName, "Publishing  - Configuration completed. Building ... Pod Name: " + work.getPublishPodName());

                } catch (Exception ex) {
                    work.setStatus(Status.FAILED);
                    work.setStatusMessage(ex.getLocalizedMessage());
                    error(work.getOpenShiftName(), "Publishing - Build failed", ex);
                } finally {
                    //
                    // Building is a long running operation so close the log file
                    //
                    closeLog(openShiftName);
                }
            }
        });
    }

    protected void normalizeDataSourceNames(VDBMetaData vdb) {
        for (ModelMetaData model : vdb.getModelMetaDatas().values()) {
            for (SourceMappingMetadata source : model.getSources().values()) {
                String name = source.getName().toLowerCase(Locale.US);
                name = name.replace("-", "");
                source.setConnectionJndiName(name);
            }
        }
    }

    protected void buildDataSourceBuilders(Model model, GenericArchive archive) {
        for (String name : model.getSourceNames()) {
            try {
                String str = null;
                String replacement = model.getSourceConnectionJndiName(name);
                String translatorName = model.getSourceTranslatorName(name);
                final String resourceName;
                if ("salesforce".equals(translatorName)) {
                    resourceName = "s2i/Salesforce.mustache";
                } else if ("mongodb".equals(translatorName)) {
                    resourceName = "s2i/MongoDB.mustache";
                } else {
                    resourceName = "s2i/Jdbc.mustache";
                }

                try (InputStream is = this.getClass().getClassLoader().getResourceAsStream(resourceName)) {
                    str = inputStreamToString(is);
                }

                str = str.replace("{{packageName}}", "io.integration");
                str = str.replace("{{dsName}}", replacement);

                archive.add(new ByteArrayAsset(ObjectConverterUtil
                        .convertToByteArray(new ByteArrayInputStream(str.getBytes("UTF-8")))),
                        "/src/main/java/io/integration/DataSources" + replacement + ".java");

            } catch (IOException e) {
                throw handleError(e);
            }
        }
    }

    private static String inputStreamToString(InputStream inputStream) throws IOException {
        return ObjectConverterUtil.convertToString(inputStream);
    }

    /**
     * Publish the vdb as a virtualization
     *
     * @return the build status of the virtualization
     * @throws KException if error occurs
     */
    public BuildStatus publishVirtualization(PublishConfiguration publishConfig) {
        String openShiftName = getOpenShiftName(publishConfig.getDataVirtualizationName());
        removeLog(openShiftName);
        info(openShiftName, "Publishing - Start publishing of virtualization: " + openShiftName);

        BuildStatus status = getVirtualizationStatus(publishConfig.getDataVirtualizationName()).getBuildStatus();
        info(openShiftName, "Publishing - Virtualization status: " + status.getStatus());

        //if we are not in a terminal state, don't allow yet
        if (!EnumSet.of(Status.FAILED, Status.NOTFOUND, Status.CANCELLED, Status.DELETE_DONE, Status.COMPLETE).contains(status.getStatus())) {
            info(openShiftName, "Publishing - Previous build request in progress, failed to submit new build request: "
                    + status.getStatus());
            return status;
        }
        info(openShiftName, "Publishing - Adding to work queue for build");
        status = addToQueue(openShiftName, publishConfig);

        debug(openShiftName, "Publishing - Initiating work monitor if not already running");

        info(openShiftName, "Publishing - Status of build + " + status.getStatus());
        return status;
    }

    Map<String, String> getSecretVariablesForVDBDataSources(VDBMetaData vdb, PublishConfiguration publishConfig)
            {
        Map<String, String> properties = new HashMap<>();
        for (Model model : vdb.getModels()) {
            for (String source : model.getSourceNames()) {
                DefaultSyndesisDataSource ds = getSyndesisDataSource(source);
                if (ds == null) {
                    throw new KException("Datasource "+source+" not found in Syndesis");
                }

                // if null this is either file, ws, kind of source where service catalog source does not exist
                DataSourceDefinition def = ds.getDefinition();
                if (def == null) {
                    throw new KException("Failed to determine the source type for "
                            + source + " in VDB " + vdb.getName());
                }

                Map<String, String> config = def.getPublishedImageDataSourceProperties(ds);
                if (config != null) {
                    for (Map.Entry<String, String> entry : config.entrySet()) {
                        properties.put(entry.getKey(), Base64.getEncoder()
                                .encodeToString(encryptionComponent.decrypt(entry.getValue()).getBytes(UTF_8)));
                    }
                }
            }
        }
        return properties;
    }

    Collection<EnvVar> getEnvironmentVariablesForSecurity(SSOConfigurationProperties props) {
        List<EnvVar> envs = new ArrayList<>();
        for (Map.Entry<String, String> prop : props.getKeycloakEnv().entrySet()) {
            envs.add(env(prop.getKey(), props.getAuthServerUrl()));
        }
        return envs;
    }

    Collection<EnvVar> getEnvironmentVariablesForVDBDataSources(VDBMetaData vdb,
            PublishConfiguration publishConfig, String openShiftName) {
        List<EnvVar> envs = new ArrayList<>();
        for (Model model : vdb.getModels()) {
            for (String source : model.getSourceNames()) {
                DefaultSyndesisDataSource ds = getSyndesisDataSource(source);
                if (ds == null) {
                    throw new KException("Datasource "+source+" not found in Syndesis");
                }

                // if null this is either file, ws, kind of source where service catalog source does not exist
                DataSourceDefinition def = ds.getDefinition();
                if (def == null) {
                    throw new KException("Failed to determine the source type for "
                            + source + " in VDB " + vdb.getName());
                }
                // data source properties as ENV variables
                def.getPublishedImageDataSourceProperties(ds).forEach((k,v) -> {
                    envs.add(envFromSecret(secretName(openShiftName), k));
                });
            }
        }
        // These options need to be removed after the base image gets updated with them natively
        for (Map.Entry<String, String> entry : publishConfig.getUserEnvironmentVariables().entrySet()) {
            envs.add(env(entry.getKey(), entry.getValue()));
        }
        envs.add(env("VDB_FILE", vdb.getName()+"-vdb.xml"));
        envs.add(env("JAVA_OPTIONS", publishConfig.getUserJavaOptions()));
        return envs;
    }

    protected String envName(final String key) {
        return key.replace(StringConstants.HYPHEN, "")
                  .replace(StringConstants.DOT, StringConstants.UNDERSCORE)
                  .toUpperCase(Locale.US);
    }

    protected EnvVar env(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    protected EnvVar envFromSecret(String secret, String key) {
        return new EnvVarBuilder().withName(envName(key))
                .withValueFrom(new EnvVarSourceBuilder().withNewSecretKeyRef(key, secret, false).build()).build();
    }

    /**
     * Get the current build and deployment status for a virtualization
     * @return the status, never null
     */
    public VirtualizationStatus getVirtualizationStatus(String virtualization) {
        String openShiftName = getOpenShiftName(virtualization);
        BuildStatus status = getVirtualizationStatusFromQueue(openShiftName);
        DeploymentStatus deploymentStatus = null;
        try {
            OpenShiftClient client = openshiftClient();
            if (status == null) {
                status = getBuildStatus(openShiftName, ApplicationProperties.getNamespace(), client);
                status.setDataVirtualizationName(virtualization);
                deploymentStatus = status.getDeploymentStatus();
            } else if (status.getStatus() != Status.COMPLETE){
                //if we get one from the queue, we don't want to mess with its deployment status
                //so we'll update one here
                deploymentStatus = new DeploymentStatus();
                updateDeploymentStatus(openShiftName, status.getNamespace(), client, null, deploymentStatus, status.getVersion());
            } else {
                deploymentStatus = status.getDeploymentStatus();
            }
        } catch (KubernetesClientException e) {
            LOG.debug("Could not get build status for VDB: "  +openShiftName +" error:"+ e.getMessage());
            status = new BuildStatus(openShiftName);
            deploymentStatus = status.getDeploymentStatus();
        }
        if (deploymentStatus.getStatus() == DeploymentStatus.Status.RUNNING) {
            deploymentStatus.setUsedBy(findIntegrationUsedIn(virtualization));
            //
            // Only if status is running then populate the routes
            // for this virtualization
            //
            ProtocolType[] types = { ProtocolType.ODATA, ProtocolType.JDBC, ProtocolType.PG };
            ArrayList<RouteStatus> routes = new ArrayList<>(1);
            for (ProtocolType type : types) {
                try {
                    RouteStatus route = getRoute(openShiftName, type);
                    if (route == null) {
                        continue;
                    }
                    routes.add(route);
                } catch(KubernetesClientException ignored) {
                    // ignore..
                }
            }
            deploymentStatus.setRoutes(routes);
        }
        return new VirtualizationStatus(status, deploymentStatus);
    }

    public String getVirtualizationLog(String virtualization) {
        String openShiftName = getOpenShiftName(virtualization);
        String logPath = getLogPath(openShiftName);
        File logFile = new File(logPath);
        if (! logFile.exists()) {
            return "No log available";
        }

        try {
            return ObjectConverterUtil.convertFileToString(logFile);
        } catch (IOException e) {
            return "No log available";
        }
    }

    private static Build findBuildWithNumber(long number, BuildList buildList) {
        for (Build b : buildList.getItems()) {
            String buildNumber = b.getMetadata().getAnnotations().get("openshift.io/build.number");
            if (buildNumber != null && Long.parseLong(buildNumber) == number) {
                return b;
            }
        }
        return buildList.getItems().get(0);
    }

    private static Long getDeployedRevision(DeploymentConfig dc, Long defaultNumber, final OpenShiftClient client) {
        long latestVersion = dc.getStatus().getLatestVersion().longValue();
        ReplicationControllerList list = client.replicationControllers().inNamespace(dc.getMetadata().getNamespace())
                .withLabel("application", dc.getMetadata().getName()).list();

        for (ReplicationController rc : list.getItems()) {
            String version = rc.getMetadata().getAnnotations().get("openshift.io/deployment-config.latest-version");
            if (version != null && Long.parseLong(version) == latestVersion) {
                String deployedVersion = rc.getSpec().getTemplate().getMetadata().getLabels().get(DEPLOYMENT_VERSION_LABEL);
                if (deployedVersion != null) {
                    try {
                        return Long.parseLong(deployedVersion);
                    } catch (NumberFormatException e) {
                        LOG.error("unexpected value for deployment-version", e);
                    }
                }
            }
        }
        return defaultNumber;
    }

    private static BuildStatus getBuildStatus(String openShiftName, String namespace, final OpenShiftClient client) {
        BuildStatus status = new BuildStatus(openShiftName);
        status.setNamespace(namespace);

        long lastVersion = 1L;
        BuildConfig bc = client.buildConfigs().inNamespace(namespace).withName(getBuildConfigName(openShiftName)).get();
        if (bc != null) {
            lastVersion = bc.getStatus().getLastVersion().longValue();
        }
        String completionTimestamp = null;
        BuildList buildList = client.builds().inNamespace(namespace).withLabel("application", openShiftName).list();
        if ((buildList !=null) && !buildList.getItems().isEmpty()) {
            Build build = findBuildWithNumber(lastVersion, buildList);
            status.setName(build.getMetadata().getName());
            String deploymentVersion = build.getMetadata().getLabels().get(DEPLOYMENT_VERSION_LABEL);
            if (deploymentVersion != null) {
                try {
                    status.setVersion(Long.valueOf(deploymentVersion));
                } catch (NumberFormatException e) {
                    LOG.error("unexpected value for deployment-version", e);
                }
            }
            if (Builds.isCancelled(build.getStatus().getPhase())) {
                status.setStatus(Status.CANCELLED);
                status.setStatusMessage(build.getStatus().getMessage());
            } else if (Builds.isFailed(build.getStatus().getPhase())) {
                status.setStatus(Status.FAILED);
                status.setStatusMessage(build.getStatus().getMessage());
            } else if (Builds.isCompleted(build.getStatus().getPhase())) {
                status.setStatus(Status.COMPLETE);
                status.setStatusMessage("Build complete, see deployment message");
                completionTimestamp = build.getStatus().getCompletionTimestamp();
            } else {
                status.setStatus(Status.BUILDING);
                status.setStatusMessage(build.getStatus().getMessage());
            }
        } else {
            // special case when there is dangling replication controller after delete is found
            List<ReplicationController> rcs = client.replicationControllers().inNamespace(namespace)
                    .withLabel("application", openShiftName).list().getItems();
            if (!rcs.isEmpty()) {
                ReplicationController rc = rcs.get(0);
                if (rc.getStatus().getReplicas().intValue() == 0) {
                    status.setStatusMessage("Build Completed, but no deployment found. Reason unknown, please redeploy");
                    status.setStatus(Status.FAILED);
                }
            }
        }

        DeploymentStatus deploymentStatus = status.getDeploymentStatus();
        Long buildVersion = status.getVersion();
        updateDeploymentStatus(openShiftName, namespace, client,
                completionTimestamp, deploymentStatus, buildVersion);

        status.setLastUpdated();
        return status;
    }

    private static void updateDeploymentStatus(String openShiftName, String namespace,
            final OpenShiftClient client, String completionTimestamp,
            DeploymentStatus deploymentStatus, Long buildVersion) {
        DeploymentConfig dc = client.deploymentConfigs().inNamespace(namespace).withName(openShiftName).get();
        if (dc != null) {
            deploymentStatus.setStatus(DeploymentStatus.Status.DEPLOYING);
            deploymentStatus.setDeploymentName(dc.getMetadata().getName());
            deploymentStatus.setVersion(getDeployedRevision(dc, buildVersion, client));
            DeploymentCondition available = getDeploymentCondition(dc, AVAILABLE);
            DeploymentCondition progressing = getDeploymentCondition(dc, PROGRESSING);
            if (isDeploymentAvailable(available, progressing)) {
                deploymentStatus.setStatus(DeploymentStatus.Status.RUNNING);
            } else {
                if (!isDeploymentProgressing(progressing)) {
                    deploymentStatus.setStatus(DeploymentStatus.Status.FAILED);
                }
                if (available != null) {
                    deploymentStatus.setStatusMessage(available.getMessage());
                } else {
                    deploymentStatus.setStatusMessage("Available condition not found in deployment, delete the service and re-deploy?");
                }
            }
        } else if (completionTimestamp != null) {
            // need to account for some time between build complete and deployment is in progress
            Instant completionTime = Instant.parse(completionTimestamp);
            if (Instant.now().getEpochSecond() - completionTime.plusMillis(15000).getEpochSecond() > 0) {
                deploymentStatus.setStatusMessage("Build Completed, but no deployment found. Reason unknown, please redeploy");
                deploymentStatus.setStatus(DeploymentStatus.Status.FAILED);
            } else {
                deploymentStatus.setStatusMessage("Build Completed, Waiting for deployment.");
                deploymentStatus.setStatus(DeploymentStatus.Status.DEPLOYING);
            }
        } else {
            deploymentStatus.setStatus(DeploymentStatus.Status.NOTFOUND);
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    @SuppressFBWarnings("RV_RETURN_VALUE_IGNORED_BAD_PRACTICE")
    public BuildStatus deleteVirtualization(String virtualizationName) {
        VirtualizationStatus status = getVirtualizationStatus(virtualizationName);
        BuildStatus runningBuild = status.getBuildStatus();

        if (EnumSet.of(Status.NOTFOUND, Status.DELETE_DONE, Status.DELETE_SUBMITTED).contains(status.getBuildStatus().getStatus())) {
            return runningBuild;
        }

        DeploymentStatus deploymentStatus = status.getDeploymentStatus();
        //check if the lasted deployment is used
        List<String> usedIn = deploymentStatus.getUsedBy();
        if (!usedIn.isEmpty()) {
            runningBuild.setStatusMessage(
                    "The virtualization \"" + virtualizationName + "\" is currently used in integration(s) \""
                    + usedIn + "\" thus can not be deleted. The unpublish has been CANCELED");
            return runningBuild;
        }

        String openShiftName = getOpenShiftName(virtualizationName);
        info(openShiftName, "Deleting virtualization deployed as Service");

        runningBuild.setStatus(Status.DELETE_SUBMITTED);
        runningBuild.setStatusMessage("delete submitted");
        workExecutor.schedule(new BuildStatusRunner(runningBuild), MONITOR_DELAY, TimeUnit.MILLISECONDS);
        configureService.submit(new Callable<Boolean>() {
            @Override
            public Boolean call() {
                final OpenShiftClient client = openshiftClient();
                deleteVDBServiceResources(openShiftName, runningBuild, client);
                //the last call in delete sets the status to delete done, making it very unlikely
                //that we'll requeue, so we'll just remove the connection here
                removeSyndesisConnection(virtualizationName);
                debug(openShiftName, "finished deleteing " + openShiftName + " service");
                return true;
            }
        });
        return runningBuild;
    }

    private BuildStatus getVirtualizationStatusFromQueue(String openshiftName) {
        for(Runnable r : workExecutor.getQueue()) {
            if (r instanceof BuildStatusRunner) {
                BuildStatusRunner status = (BuildStatusRunner)r;
                if (status.work.getOpenShiftName().equals(openshiftName)) {
                    return status.work;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("PMD.NPathComplexity") // TODO refactor
    private void deleteVDBServiceResources(String openshiftName, BuildStatus status, OpenShiftClient client) {
        final String namespace = ApplicationProperties.getNamespace();

        try {
            // delete routes first
            client.routes().inNamespace(namespace).withName(openshiftName + StringConstants.HYPHEN + ProtocolType.ODATA.id()).delete();
            // delete services next
            client.services().inNamespace(namespace).withName(openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }

        try {
            // delete builds
            client.builds().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // delete pods
            client.pods().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // delete image streams
            client.imageStreams().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // delete replication controller
            client.replicationControllers().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // deployment configs
            client.deploymentConfigs().inNamespace(namespace).withName(openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // secrets
            client.secrets().inNamespace(namespace).withName(secretName(openshiftName)).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // delete build configuration
            client.buildConfigs().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }
        try {
            // checking 2nd time as, I found this not being deleted completely
            // delete replication controller
            client.replicationControllers().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }

        try {
            // delete image streams
            client.imageStreams().inNamespace(namespace).withLabel("application", openshiftName).delete();
        } catch (KubernetesClientException e ) {
            error(openshiftName, e.getMessage());
            error(openshiftName, "requeueing the delete request");
            status.setStatus(Status.DELETE_REQUEUE);
        }

        status.setStatus(Status.DELETE_DONE);
    }

    private RouteStatus getRoute(String openShiftName, ProtocolType protocolType) {
        String namespace = ApplicationProperties.getNamespace();
        OpenShiftClient client = openshiftClient();
        RouteStatus theRoute = null;
        debug(openShiftName, "Getting route of type " + protocolType.id() + " for Service");

        Route route = client.routes().inNamespace(namespace).withName(openShiftName + StringConstants.HYPHEN + protocolType.id()).get();

        if (route != null) {
            ObjectMeta metadata = route.getMetadata();
            String name = metadata.getName();
            RouteSpec spec = route.getSpec();
            String target = spec.getTo().getName();

            theRoute = new RouteStatus(name, protocolType);
            theRoute.setHost(spec.getHost());
            theRoute.setPath(spec.getPath());
            theRoute.setPort(spec.getPort().getTargetPort().getStrVal());
            theRoute.setTarget(target);
            theRoute.setSecure(spec.getTls() != null);
        }
        return theRoute;
    }

    /**
     * This method generates the pom.xml file, that needs to be saved in the root of the project.
     * @param vdb - VDB for which pom.xml is generated
     * @return pom.xml contents
     */
    @SuppressWarnings("PMD.ConsecutiveLiteralAppends") // more readable
    protected String generatePomXml(VDBMetaData vdb, boolean enableOdata, boolean enableSecurity) {
        try {
            StringBuilder builder = new StringBuilder();
            InputStream is = this.getClass().getClassLoader().getResourceAsStream("s2i/template-pom.xml");
            builder.append(new String(ObjectConverterUtil.convertToByteArray(is), UTF_8));

            StringBuilder vdbSourceNames = new StringBuilder();
            StringBuilder vdbDependencies = new StringBuilder(400);

            List<Model> models = vdb.getModels();
            for (Model model : models) {
                for (String source : model.getSourceNames()) {
                    DefaultSyndesisDataSource ds = getSyndesisDataSource(source);
                    if (ds == null) {
                        throw new KException("Datasource " + source + " not found");
                    }
                    DataSourceDefinition def = ds.getDefinition();
                    if (def == null) {
                        throw new KException("Failed to determine the source type for "
                                + source + " in VDB " + vdb.getName());
                    }

                    vdbSourceNames.append(source).append(StringConstants.SPACE); // this used as label
                    vdbDependencies.append(def.getPomDendencies());
                    vdbDependencies.append(StringConstants.NEW_LINE);
                }
            }

            StringBuilder mavenRepositories = new StringBuilder(200);
            if (this.mavenRepos != null) {
                for (Map.Entry<String, String> repository: this.mavenRepos.entrySet()) {
                    final String key = repository.getKey();
                    mavenRepositories.append(StringConstants.NEW_LINE).append("<repository>\n")
                        .append("<id>").append(key).append("</id>\n")
                        .append("<name>").append(key).append("</name>\n")
                        .append("<url>").append(repository.getValue()).append("</url>\n")
                        .append(
                            "  <releases>\n" +
                            "    <enabled>true</enabled>\n" +
                            "    <updatePolicy>never</updatePolicy>\n" +
                            "  </releases>\n" +
                            "  <snapshots>\n" +
                            "    <enabled>false</enabled>\n" +
                            "  </snapshots>\n" +
                            "</repository>");
                }
            }

            if (enableOdata) {
                vdbDependencies.append(StringConstants.NEW_LINE).append("<dependency>"
                        + "<groupId>org.teiid</groupId>"
                        + "<artifactId>spring-odata</artifactId>"
                        + "</dependency> ");
            }

            if (enableSecurity) {
                vdbDependencies.append(StringConstants.NEW_LINE).append("<dependency>"
                        + "<groupId>org.teiid</groupId>"
                        + "<artifactId>spring-keycloak</artifactId>"
                        + "</dependency> ");
            }

            String pomXML = builder.toString();
            String vdbName = vdb.getName();
            pomXML = pomXML.replace("<!--vdb-name-->", vdbName);
            pomXML = pomXML.replace("<!--vdb-source-names-->", vdbSourceNames.toString());
            pomXML = pomXML.replace("<!--vdb-dependencies-->", vdbDependencies.toString());
            pomXML = pomXML.replace("<!--internal-repos-->", mavenRepositories.toString());
            return pomXML;
        } catch (IOException e) {
            throw handleError(e);
        }
    }

    protected static KException handleError(Throwable e) {
        assert (e != null);

        if (e instanceof KException) {
            return (KException)e;
        }

        return new KException(e);
    }
}

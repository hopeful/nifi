/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.web;

import com.sun.jersey.core.util.MultivaluedMapImpl;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.action.Action;
import org.apache.nifi.action.Component;
import org.apache.nifi.action.FlowChangeAction;
import org.apache.nifi.action.Operation;
import org.apache.nifi.action.component.details.FlowChangeExtensionDetails;
import org.apache.nifi.action.details.FlowChangeConfigureDetails;
import org.apache.nifi.admin.service.AuditService;
import org.apache.nifi.authorization.AccessDeniedException;
import org.apache.nifi.authorization.AuthorizationRequest;
import org.apache.nifi.authorization.AuthorizationResult;
import org.apache.nifi.authorization.AuthorizationResult.Result;
import org.apache.nifi.authorization.Authorizer;
import org.apache.nifi.authorization.RequestAction;
import org.apache.nifi.authorization.resource.Authorizable;
import org.apache.nifi.authorization.resource.ResourceFactory;
import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.authorization.user.NiFiUserDetails;
import org.apache.nifi.authorization.user.NiFiUserUtils;
import org.apache.nifi.cluster.coordination.ClusterCoordinator;
import org.apache.nifi.cluster.coordination.http.replication.RequestReplicator;
import org.apache.nifi.cluster.manager.NodeResponse;
import org.apache.nifi.cluster.manager.exception.IllegalClusterStateException;
import org.apache.nifi.controller.ControllerService;
import org.apache.nifi.controller.reporting.ReportingTaskProvider;
import org.apache.nifi.controller.service.ControllerServiceProvider;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.web.api.dto.ControllerServiceDTO;
import org.apache.nifi.web.api.dto.ProcessorConfigDTO;
import org.apache.nifi.web.api.dto.ProcessorDTO;
import org.apache.nifi.web.api.dto.ReportingTaskDTO;
import org.apache.nifi.web.api.dto.RevisionDTO;
import org.apache.nifi.web.api.entity.ControllerServiceEntity;
import org.apache.nifi.web.api.entity.ProcessorEntity;
import org.apache.nifi.web.api.entity.ReportingTaskEntity;
import org.apache.nifi.web.util.ClientResponseUtils;
import org.apache.nifi.web.util.WebUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;

/**
 * Implements the NiFiWebConfigurationContext interface to support a context in both standalone and clustered environments.
 */
public class StandardNiFiWebConfigurationContext implements NiFiWebConfigurationContext {

    private static final Logger logger = LoggerFactory.getLogger(StandardNiFiWebConfigurationContext.class);
    public static final String VERBOSE_PARAM = "verbose";

    private NiFiProperties properties;
    private NiFiServiceFacade serviceFacade;
    private ClusterCoordinator clusterCoordinator;
    private RequestReplicator requestReplicator;
    private ControllerServiceProvider controllerServiceProvider;
    private ReportingTaskProvider reportingTaskProvider;
    private AuditService auditService;
    private Authorizer authorizer;

    private void authorizeFlowAccess(final NiFiUser user) {
        // authorize access
        serviceFacade.authorizeAccess(lookup -> {
            final AuthorizationRequest request = new AuthorizationRequest.Builder()
                    .resource(ResourceFactory.getFlowResource())
                    .identity(user.getIdentity())
                    .anonymous(user.isAnonymous())
                    .accessAttempt(true)
                    .action(RequestAction.READ)
                    .build();

            final AuthorizationResult result = authorizer.authorize(request);
            if (!Result.Approved.equals(result.getResult())) {
                final String message = StringUtils.isNotBlank(result.getExplanation()) ? result.getExplanation() : "Access is denied";
                throw new AccessDeniedException(message);
            }
        });
    }

    @Override
    public ControllerService getControllerService(final String serviceIdentifier, final String componentId) {
        final NiFiUser user = NiFiUserUtils.getNiFiUser();
        authorizeFlowAccess(user);
        return controllerServiceProvider.getControllerServiceForComponent(serviceIdentifier, componentId);
    }

    @Override
    public void saveActions(final NiFiWebRequestContext requestContext, final Collection<ConfigurationAction> configurationActions) {
        Objects.requireNonNull(configurationActions, "Actions cannot be null.");

        // ensure the path could be
        if (requestContext.getExtensionType() == null) {
            throw new IllegalArgumentException("The UI extension type must be specified.");
        }

        Component componentType = null;
        switch (requestContext.getExtensionType()) {
            case ProcessorConfiguration:
                // authorize access
                serviceFacade.authorizeAccess(lookup -> {
                    final Authorizable authorizable = lookup.getProcessor(requestContext.getId());
                    authorizable.authorize(authorizer, RequestAction.WRITE);
                });

                componentType = Component.Processor;
                break;
            case ControllerServiceConfiguration:
                // authorize access
                serviceFacade.authorizeAccess(lookup -> {
                    final Authorizable authorizable = lookup.getControllerService(requestContext.getId());
                    authorizable.authorize(authorizer, RequestAction.WRITE);
                });

                componentType = Component.ControllerService;
                break;
            case ReportingTaskConfiguration:
                // authorize access
                serviceFacade.authorizeAccess(lookup -> {
                    final Authorizable authorizable = lookup.getReportingTask(requestContext.getId());
                    authorizable.authorize(authorizer, RequestAction.WRITE);
                });

                componentType = Component.ReportingTask;
                break;
        }

        if (componentType == null) {
            throw new IllegalArgumentException("UI extension type must support Processor, ControllerService, or ReportingTask configuration.");
        }

        // - when running standalone or cluster ncm - actions from custom UIs are stored locally
        // - clustered nodes do not serve custom UIs directly to users so they should never be invoking this method
        final Date now = new Date();
        final Collection<Action> actions = new HashSet<>(configurationActions.size());
        for (final ConfigurationAction configurationAction : configurationActions) {
            final FlowChangeExtensionDetails extensionDetails = new FlowChangeExtensionDetails();
            extensionDetails.setType(configurationAction.getType());

            final FlowChangeConfigureDetails configureDetails = new FlowChangeConfigureDetails();
            configureDetails.setName(configurationAction.getName());
            configureDetails.setPreviousValue(configurationAction.getPreviousValue());
            configureDetails.setValue(configurationAction.getValue());

            final FlowChangeAction action = new FlowChangeAction();
            action.setTimestamp(now);
            action.setSourceId(configurationAction.getId());
            action.setSourceName(configurationAction.getName());
            action.setSourceType(componentType);
            action.setOperation(Operation.Configure);
            action.setUserIdentity(getCurrentUserDn());
            action.setUserName(getCurrentUserName());
            action.setComponentDetails(extensionDetails);
            action.setActionDetails(configureDetails);
            actions.add(action);
        }

        if (!actions.isEmpty()) {
            try {
                // record the operations
                auditService.addActions(actions);
            } catch (final Throwable t) {
                logger.warn("Unable to record actions: " + t.getMessage());
                if (logger.isDebugEnabled()) {
                    logger.warn(StringUtils.EMPTY, t);
                }
            }
        }
    }

    @Override
    public String getCurrentUserDn() {
        final NiFiUser user = NiFiUserUtils.getNiFiUser();
        authorizeFlowAccess(user);
        return user.getIdentity();
    }

    @Override
    public String getCurrentUserName() {
        final NiFiUser user = NiFiUserUtils.getNiFiUser();
        authorizeFlowAccess(user);
        return user.getUserName();
    }

    @Override
    public ComponentDetails getComponentDetails(final NiFiWebRequestContext requestContext) throws ResourceNotFoundException, ClusterRequestException {
        final String id = requestContext.getId();

        if (StringUtils.isBlank(id)) {
            throw new ResourceNotFoundException(String.format("Configuration request context config did not have a component ID."));
        }

        // ensure the path could be
        if (requestContext.getExtensionType() == null) {
            throw new IllegalArgumentException("The UI extension type must be specified.");
        }

        // get the component facade for interacting directly with that type of object
        ComponentFacade componentFacade = null;
        switch (requestContext.getExtensionType()) {
            case ProcessorConfiguration:
                componentFacade = new ProcessorFacade();
                break;
            case ControllerServiceConfiguration:
                componentFacade = new ControllerServiceFacade();
                break;
            case ReportingTaskConfiguration:
                componentFacade = new ReportingTaskFacade();
                break;
        }

        if (componentFacade == null) {
            throw new IllegalArgumentException("UI extension type must support Processor, ControllerService, or ReportingTask configuration.");
        }

        return componentFacade.getComponentDetails(requestContext);
    }

    @Override
    public ComponentDetails setAnnotationData(final NiFiWebConfigurationRequestContext requestContext, final String annotationData)
            throws ResourceNotFoundException, InvalidRevisionException, ClusterRequestException {

        final String id = requestContext.getId();

        if (StringUtils.isBlank(id)) {
            throw new ResourceNotFoundException(String.format("Configuration request context did not have a component ID."));
        }

        // ensure the path could be
        if (requestContext.getExtensionType() == null) {
            throw new IllegalArgumentException("The UI extension type must be specified.");
        }

        // get the component facade for interacting directly with that type of object
        ComponentFacade componentFacade = null;
        switch (requestContext.getExtensionType()) {
            case ProcessorConfiguration:
                componentFacade = new ProcessorFacade();
                break;
            case ControllerServiceConfiguration:
                componentFacade = new ControllerServiceFacade();
                break;
            case ReportingTaskConfiguration:
                componentFacade = new ReportingTaskFacade();
                break;
        }

        if (componentFacade == null) {
            throw new IllegalArgumentException("UI extension type must support Processor, ControllerService, or ReportingTask configuration.");
        }

        return componentFacade.setAnnotationData(requestContext, annotationData);
    }

    /**
     * Facade over accessing different types of NiFi components.
     */
    private interface ComponentFacade {

        /**
         * Gets the component details using the specified request context.
         *
         * @param requestContext context
         * @return the component details using the specified request context
         */
        ComponentDetails getComponentDetails(NiFiWebRequestContext requestContext);

        /**
         * Sets the annotation data using the specified request context.
         *
         * @param requestContext context
         * @param annotationData data
         * @return details
         */
        ComponentDetails setAnnotationData(NiFiWebConfigurationRequestContext requestContext, String annotationData);
    }

    /**
     * Interprets the request/response with the underlying Processor model.
     */
    private class ProcessorFacade implements ComponentFacade {

        @Override
        public ComponentDetails getComponentDetails(final NiFiWebRequestContext requestContext) {
            final String id = requestContext.getId();

            // authorize access
            serviceFacade.authorizeAccess(lookup -> {
                final Authorizable authorizable = lookup.getProcessor(id);
                authorizable.authorize(authorizer, RequestAction.READ);
            });

            final ProcessorDTO processor;
            if (properties.isClustered() && clusterCoordinator != null && clusterCoordinator.isConnected()) {
                // create the request URL
                URI requestUrl;
                try {
                    final String path = "/nifi-api/processors/" + URLEncoder.encode(id, "UTF-8");
                    requestUrl = new URI(requestContext.getScheme(), null, "localhost", 0, path, null, null);
                } catch (final URISyntaxException | UnsupportedEncodingException use) {
                    throw new ClusterRequestException(use);
                }

                // set the request parameters
                final MultivaluedMap<String, String> parameters = new MultivaluedMapImpl();
                parameters.add(VERBOSE_PARAM, "true");

                // replicate request
                NodeResponse nodeResponse;
                try {
                    nodeResponse = requestReplicator.replicate(HttpMethod.GET, requestUrl, parameters, getHeaders(requestContext)).awaitMergedResponse();
                } catch (final InterruptedException e) {
                    throw new IllegalClusterStateException("Request was interrupted while waiting for response from node");
                }

                // check for issues replicating request
                checkResponse(nodeResponse, id);

                // return processor
                ProcessorEntity entity = (ProcessorEntity) nodeResponse.getUpdatedEntity();
                if (entity == null) {
                    entity = nodeResponse.getClientResponse().getEntity(ProcessorEntity.class);
                }
                processor = entity.getComponent();
            } else {
                processor = serviceFacade.getProcessor(id).getComponent();
            }

            // return the processor info
            return getComponentConfiguration(processor);
        }

        @Override
        public ComponentDetails setAnnotationData(final NiFiWebConfigurationRequestContext requestContext, final String annotationData) {
            final NiFiUser user = NiFiUserUtils.getNiFiUser();
            final Revision revision = requestContext.getRevision();
            final String id = requestContext.getId();

            // authorize access
            serviceFacade.authorizeAccess(lookup -> {
                final Authorizable authorizable = lookup.getProcessor(id);
                authorizable.authorize(authorizer, RequestAction.WRITE);
            });

            final ProcessorDTO processor;
            if (properties.isClustered()) {
                // create the request URL
                URI requestUrl;
                try {
                    final String path = "/nifi-api/processors/" + URLEncoder.encode(id, "UTF-8");
                    requestUrl = new URI(requestContext.getScheme(), null, "localhost", 0, path, null, null);
                } catch (final URISyntaxException | UnsupportedEncodingException use) {
                    throw new ClusterRequestException(use);
                }

                // create the revision
                final RevisionDTO revisionDto = new RevisionDTO();
                revisionDto.setClientId(revision.getClientId());
                revisionDto.setVersion(revision.getVersion());

                // create the processor entity
                final ProcessorEntity processorEntity = new ProcessorEntity();
                processorEntity.setRevision(revisionDto);

                // create the processor dto
                final ProcessorDTO processorDto = new ProcessorDTO();
                processorEntity.setComponent(processorDto);
                processorDto.setId(id);

                // create the processor configuration with the given annotation data
                final ProcessorConfigDTO configDto = new ProcessorConfigDTO();
                processorDto.setConfig(configDto);
                configDto.setAnnotationData(annotationData);

                // set the content type to json
                final Map<String, String> headers = getHeaders(requestContext);
                headers.put("Content-Type", "application/json");

                // replicate request
                NodeResponse nodeResponse;
                try {
                    nodeResponse = requestReplicator.replicate(HttpMethod.PUT, requestUrl, processorEntity, headers).awaitMergedResponse();
                } catch (final InterruptedException e) {
                    throw new IllegalClusterStateException("Request was interrupted while waiting for response from node");
                }

                // check for issues replicating request
                checkResponse(nodeResponse, id);

                // return processor
                ProcessorEntity entity = (ProcessorEntity) nodeResponse.getUpdatedEntity();
                if (entity == null) {
                    entity = nodeResponse.getClientResponse().getEntity(ProcessorEntity.class);
                }
                processor = entity.getComponent();
            } else {
                // claim the revision
                serviceFacade.claimRevision(revision, user);
                try {
                    final ProcessorEntity entity = serviceFacade.setProcessorAnnotationData(revision, id, annotationData);
                    processor = entity.getComponent();
                } finally {
                    // ensure the revision is canceled.. if the operation succeed, this is a noop
                    serviceFacade.cancelRevision(revision);
                }
            }

            // return the processor info
            return getComponentConfiguration(processor);
        }

        private ComponentDetails getComponentConfiguration(final ProcessorDTO processor) {
            final ProcessorConfigDTO processorConfig = processor.getConfig();
            return new ComponentDetails.Builder()
                    .id(processor.getId())
                    .name(processor.getName())
                    .type(processor.getType())
                    .state(processor.getState())
                    .annotationData(processorConfig.getAnnotationData())
                    .properties(processorConfig.getProperties())
                    .validateErrors(processor.getValidationErrors()).build();
        }
    }

    /**
     * Interprets the request/response with the underlying ControllerService model.
     */
    private class ControllerServiceFacade implements ComponentFacade {

        @Override
        public ComponentDetails getComponentDetails(final NiFiWebRequestContext requestContext) {
            final String id = requestContext.getId();
            final ControllerServiceDTO controllerService;

            // authorize access
            serviceFacade.authorizeAccess(lookup -> {
                final Authorizable authorizable = lookup.getControllerService(id);
                authorizable.authorize(authorizer, RequestAction.READ);
            });

            // if the lookup has the service that means we are either a node or
            // the ncm and the service is available there only
            if (controllerServiceProvider.getControllerService(id) != null) {
                controllerService = serviceFacade.getControllerService(id).getComponent();
            } else {
                // if this is a standalone instance the service should have been found above... there should
                // no cluster to replicate the request to
                if (!properties.isClustered()) {
                    throw new ResourceNotFoundException(String.format("Controller service[%s] could not be found on this NiFi.", id));
                }

                // create the request URL
                URI requestUrl;
                try {
                    final String path = "/nifi-api/controller-services/node/" + URLEncoder.encode(id, "UTF-8");
                    requestUrl = new URI(requestContext.getScheme(), null, "localhost", 0, path, null, null);
                } catch (final URISyntaxException | UnsupportedEncodingException use) {
                    throw new ClusterRequestException(use);
                }

                // set the request parameters
                final MultivaluedMap<String, String> parameters = new MultivaluedMapImpl();

                // replicate request
                NodeResponse nodeResponse;
                try {
                    nodeResponse = requestReplicator.replicate(HttpMethod.GET, requestUrl, parameters, getHeaders(requestContext)).awaitMergedResponse();
                } catch (final InterruptedException e) {
                    throw new IllegalClusterStateException("Request was interrupted while waiting for response from node");
                }

                // check for issues replicating request
                checkResponse(nodeResponse, id);

                // return controller service
                ControllerServiceEntity entity = (ControllerServiceEntity) nodeResponse.getUpdatedEntity();
                if (entity == null) {
                    entity = nodeResponse.getClientResponse().getEntity(ControllerServiceEntity.class);
                }
                controllerService = entity.getComponent();
            }

            // return the controller service info
            return getComponentConfiguration(controllerService);
        }

        @Override
        public ComponentDetails setAnnotationData(final NiFiWebConfigurationRequestContext requestContext, final String annotationData) {
            final NiFiUser user = NiFiUserUtils.getNiFiUser();
            final Revision revision = requestContext.getRevision();
            final String id = requestContext.getId();

            // authorize access
            serviceFacade.authorizeAccess(lookup -> {
                final Authorizable authorizable = lookup.getControllerService(id);
                authorizable.authorize(authorizer, RequestAction.WRITE);
            });

            final ControllerServiceDTO controllerService;
            if (controllerServiceProvider.getControllerService(id) != null) {
                final ControllerServiceDTO controllerServiceDto = new ControllerServiceDTO();
                controllerServiceDto.setId(id);
                controllerServiceDto.setAnnotationData(annotationData);

                // claim the revision
                serviceFacade.claimRevision(revision, user);
                try {
                    // perform the update
                    final UpdateResult<ControllerServiceEntity> updateResult = serviceFacade.updateControllerService(revision, controllerServiceDto);
                    controllerService = updateResult.getResult().getComponent();
                } finally {
                    // ensure the revision is canceled.. if the operation succeed, this is a noop
                    serviceFacade.cancelRevision(revision);
                }
            } else {
                // if this is a standalone instance the service should have been found above... there should
                // no cluster to replicate the request to
                if (!properties.isClustered()) {
                    throw new ResourceNotFoundException(String.format("Controller service[%s] could not be found on this NiFi.", id));
                }

                // since this PUT request can be interpreted as a request to create a controller service
                // we need to be sure that this service exists on the node before the request is replicated.
                // this is done by attempting to get the details. if the service doesn't exist it will
                // throw a ResourceNotFoundException
                getComponentDetails(requestContext);

                // create the request URL
                URI requestUrl;
                try {
                    final String path = "/nifi-api/controller-services/node/" + URLEncoder.encode(id, "UTF-8");
                    requestUrl = new URI(requestContext.getScheme(), null, "localhost", 0, path, null, null);
                } catch (final URISyntaxException | UnsupportedEncodingException use) {
                    throw new ClusterRequestException(use);
                }

                // create the revision
                final RevisionDTO revisionDto = new RevisionDTO();
                revisionDto.setClientId(revision.getClientId());
                revisionDto.setVersion(revision.getVersion());

                // create the controller service entity
                final ControllerServiceEntity controllerServiceEntity = new ControllerServiceEntity();
                controllerServiceEntity.setRevision(revisionDto);

                // create the controller service dto
                final ControllerServiceDTO controllerServiceDto = new ControllerServiceDTO();
                controllerServiceEntity.setComponent(controllerServiceDto);
                controllerServiceDto.setId(id);
                controllerServiceDto.setAnnotationData(annotationData);

                // set the content type to json
                final Map<String, String> headers = getHeaders(requestContext);
                headers.put("Content-Type", "application/json");

                // replicate request
                NodeResponse nodeResponse;
                try {
                    nodeResponse = requestReplicator.replicate(HttpMethod.PUT, requestUrl, controllerServiceEntity, headers).awaitMergedResponse();
                } catch (final InterruptedException e) {
                    throw new IllegalClusterStateException("Request was interrupted while waiting for response from node");
                }

                // check for issues replicating request
                checkResponse(nodeResponse, id);

                // return controller service
                ControllerServiceEntity entity = (ControllerServiceEntity) nodeResponse.getUpdatedEntity();
                if (entity == null) {
                    entity = nodeResponse.getClientResponse().getEntity(ControllerServiceEntity.class);
                }
                controllerService = entity.getComponent();
            }

            // return the controller service info
            return getComponentConfiguration(controllerService);
        }

        private ComponentDetails getComponentConfiguration(final ControllerServiceDTO controllerService) {
            return new ComponentDetails.Builder()
                    .id(controllerService.getId())
                    .name(controllerService.getName())
                    .type(controllerService.getType())
                    .state(controllerService.getState())
                    .annotationData(controllerService.getAnnotationData())
                    .properties(controllerService.getProperties())
                    .validateErrors(controllerService.getValidationErrors()).build();
        }
    }

    /**
     * Interprets the request/response with the underlying ControllerService model.
     */
    private class ReportingTaskFacade implements ComponentFacade {

        @Override
        public ComponentDetails getComponentDetails(final NiFiWebRequestContext requestContext) {
            final String id = requestContext.getId();
            final ReportingTaskDTO reportingTask;

            // authorize access
            serviceFacade.authorizeAccess(lookup -> {
                final Authorizable authorizable = lookup.getReportingTask(id);
                authorizable.authorize(authorizer, RequestAction.READ);
            });

            // if the provider has the service that means we are either a node or
            // the ncm and the service is available there only
            if (reportingTaskProvider.getReportingTaskNode(id) != null) {
                reportingTask = serviceFacade.getReportingTask(id).getComponent();
            } else {
                // if this is a standalone instance the task should have been found above... there should
                // no cluster to replicate the request to
                if (!properties.isClustered()) {
                    throw new ResourceNotFoundException(String.format("Reporting task[%s] could not be found on this NiFi.", id));
                }

                // create the request URL
                URI requestUrl;
                try {
                    final String path = "/nifi-api/reporting-tasks/node/" + URLEncoder.encode(id, "UTF-8");
                    requestUrl = new URI(requestContext.getScheme(), null, "localhost", 0, path, null, null);
                } catch (final URISyntaxException | UnsupportedEncodingException use) {
                    throw new ClusterRequestException(use);
                }

                // set the request parameters
                final MultivaluedMap<String, String> parameters = new MultivaluedMapImpl();

                // replicate request
                NodeResponse nodeResponse;
                try {
                    nodeResponse = requestReplicator.replicate(HttpMethod.GET, requestUrl, parameters, getHeaders(requestContext)).awaitMergedResponse();
                } catch (final InterruptedException e) {
                    throw new IllegalClusterStateException("Request was interrupted while waiting for response from node");
                }

                // check for issues replicating request
                checkResponse(nodeResponse, id);

                // return reporting task
                ReportingTaskEntity entity = (ReportingTaskEntity) nodeResponse.getUpdatedEntity();
                if (entity == null) {
                    entity = nodeResponse.getClientResponse().getEntity(ReportingTaskEntity.class);
                }
                reportingTask = entity.getComponent();
            }

            // return the reporting task info
            return getComponentConfiguration(reportingTask);
        }

        @Override
        public ComponentDetails setAnnotationData(final NiFiWebConfigurationRequestContext requestContext, final String annotationData) {
            final NiFiUser user = NiFiUserUtils.getNiFiUser();
            final Revision revision = requestContext.getRevision();
            final String id = requestContext.getId();

            // authorize access
            serviceFacade.authorizeAccess(lookup -> {
                final Authorizable authorizable = lookup.getReportingTask(id);
                authorizable.authorize(authorizer, RequestAction.WRITE);
            });

            final ReportingTaskDTO reportingTask;
            if (reportingTaskProvider.getReportingTaskNode(id) != null) {
                final ReportingTaskDTO reportingTaskDto = new ReportingTaskDTO();
                reportingTaskDto.setId(id);
                reportingTaskDto.setAnnotationData(annotationData);

                // claim the revision
                serviceFacade.claimRevision(revision, user);
                try {
                    final UpdateResult<ReportingTaskEntity> updateResult = serviceFacade.updateReportingTask(revision, reportingTaskDto);
                    reportingTask = updateResult.getResult().getComponent();
                } finally {
                    // ensure the revision is canceled.. if the operation succeed, this is a noop
                    serviceFacade.cancelRevision(revision);
                }
            } else {
                // if this is a standalone instance the task should have been found above... there should
                // no cluster to replicate the request to
                if (!properties.isClustered()) {
                    throw new ResourceNotFoundException(String.format("Reporting task[%s] could not be found on this NiFi.", id));
                }

                // since this PUT request can be interpreted as a request to create a reporting task
                // we need to be sure that this task exists on the node before the request is replicated.
                // this is done by attempting to get the details. if the service doesn't exist it will
                // throw a ResourceNotFoundException
                getComponentDetails(requestContext);

                // create the request URL
                URI requestUrl;
                try {
                    final String path = "/nifi-api/reporting-tasks/node/" + URLEncoder.encode(id, "UTF-8");
                    requestUrl = new URI(requestContext.getScheme(), null, "localhost", 0, path, null, null);
                } catch (final URISyntaxException | UnsupportedEncodingException use) {
                    throw new ClusterRequestException(use);
                }

                // create the revision
                final RevisionDTO revisionDto = new RevisionDTO();
                revisionDto.setClientId(revision.getClientId());
                revisionDto.setVersion(revision.getVersion());

                // create the reporting task entity
                final ReportingTaskEntity reportingTaskEntity = new ReportingTaskEntity();
                reportingTaskEntity.setRevision(revisionDto);

                // create the reporting task dto
                final ReportingTaskDTO reportingTaskDto = new ReportingTaskDTO();
                reportingTaskEntity.setComponent(reportingTaskDto);
                reportingTaskDto.setId(id);
                reportingTaskDto.setAnnotationData(annotationData);

                // set the content type to json
                final Map<String, String> headers = getHeaders(requestContext);
                headers.put("Content-Type", "application/json");

                // replicate request
                NodeResponse nodeResponse;
                try {
                    nodeResponse = requestReplicator.replicate(HttpMethod.PUT, requestUrl, reportingTaskEntity, headers).awaitMergedResponse();
                } catch (final InterruptedException e) {
                    throw new IllegalClusterStateException("Request was interrupted while waiting for response from node");
                }

                // check for issues replicating request
                checkResponse(nodeResponse, id);

                // return reporting task
                ReportingTaskEntity entity = (ReportingTaskEntity) nodeResponse.getUpdatedEntity();
                if (entity == null) {
                    entity = nodeResponse.getClientResponse().getEntity(ReportingTaskEntity.class);
                }
                reportingTask = entity.getComponent();
            }

            // return the processor info
            return getComponentConfiguration(reportingTask);
        }

        private ComponentDetails getComponentConfiguration(final ReportingTaskDTO reportingTask) {
            return new ComponentDetails.Builder()
                    .id(reportingTask.getId())
                    .name(reportingTask.getName())
                    .type(reportingTask.getType())
                    .state(reportingTask.getState())
                    .annotationData(reportingTask.getAnnotationData())
                    .properties(reportingTask.getProperties())
                    .validateErrors(reportingTask.getValidationErrors()).build();
        }
    }

    /**
     * Gets the headers for the request to replicate to each node while clustered.
     */
    private Map<String, String> getHeaders(final NiFiWebRequestContext config) {
        final Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json,application/xml");
        if (StringUtils.isNotBlank(config.getProxiedEntitiesChain())) {
            headers.put("X-ProxiedEntitiesChain", config.getProxiedEntitiesChain());
        }

        // add the user's authorities (if any) to the headers
        final Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null) {
            final Object userDetailsObj = authentication.getPrincipal();
            if (userDetailsObj instanceof NiFiUserDetails) {
                // serialize user details object
                final String hexEncodedUserDetails = WebUtils.serializeObjectToHex((Serializable) userDetailsObj);

                // put serialized user details in header
                headers.put("X-ProxiedEntityUserDetails", hexEncodedUserDetails);
            }
        }
        return headers;
    }

    /**
     * Checks the specified response and drains the stream appropriately.
     */
    private void checkResponse(final NodeResponse nodeResponse, final String id) {
        if (nodeResponse.hasThrowable()) {
            ClientResponseUtils.drainClientResponse(nodeResponse.getClientResponse());
            throw new ClusterRequestException(nodeResponse.getThrowable());
        } else if (nodeResponse.getClientResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
            ClientResponseUtils.drainClientResponse(nodeResponse.getClientResponse());
            throw new InvalidRevisionException(String.format("Conflict: the flow may have been updated by another user."));
        } else if (nodeResponse.getClientResponse().getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
            ClientResponseUtils.drainClientResponse(nodeResponse.getClientResponse());
            throw new ResourceNotFoundException("Unable to find component with id: " + id);
        } else if (nodeResponse.getClientResponse().getStatus() != Response.Status.OK.getStatusCode()) {
            ClientResponseUtils.drainClientResponse(nodeResponse.getClientResponse());
            throw new ClusterRequestException("Method resulted in an unsuccessful HTTP response code: " + nodeResponse.getClientResponse().getStatus());
        }
    }

    public void setClusterCoordinator(final ClusterCoordinator clusterCoordinator) {
        this.clusterCoordinator = clusterCoordinator;
    }

    public void setRequestReplicator(final RequestReplicator requestReplicator) {
        this.requestReplicator = requestReplicator;
    }

    public void setProperties(final NiFiProperties properties) {
        this.properties = properties;
    }

    public void setServiceFacade(final NiFiServiceFacade serviceFacade) {
        this.serviceFacade = serviceFacade;
    }

    public void setAuditService(final AuditService auditService) {
        this.auditService = auditService;
    }

    public void setControllerServiceProvider(final ControllerServiceProvider controllerServiceProvider) {
        this.controllerServiceProvider = controllerServiceProvider;
    }

    public void setReportingTaskProvider(final ReportingTaskProvider reportingTaskProvider) {
        this.reportingTaskProvider = reportingTaskProvider;
    }

    public void setAuthorizer(final Authorizer authorizer) {
        this.authorizer = authorizer;
    }
}

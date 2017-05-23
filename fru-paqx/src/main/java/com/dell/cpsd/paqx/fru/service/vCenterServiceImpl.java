/**
 * Copyright &copy; 2017 Dell Inc. or its subsidiaries. All Rights Reserved. Dell EMC Confidential/Proprietary Information
 */

package com.dell.cpsd.paqx.fru.service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.dell.cpsd.hdp.capability.registry.api.Capability;
import com.dell.cpsd.hdp.capability.registry.api.CapabilityProvider;
import com.dell.cpsd.hdp.capability.registry.api.EndpointProperty;
import com.dell.cpsd.hdp.capability.registry.client.CapabilityRegistryException;
import com.dell.cpsd.hdp.capability.registry.client.ICapabilityRegistryLookupManager;
import com.dell.cpsd.hdp.capability.registry.client.callback.ListCapabilityProvidersResponse;
import com.dell.cpsd.paqx.fru.amqp.consumer.handler.AsyncAcknowledgement;
import com.dell.cpsd.paqx.fru.dto.ConsulRegistryResult;
import com.dell.cpsd.paqx.fru.rest.dto.EndpointCredentials;
import com.dell.cpsd.paqx.fru.rest.dto.VCenterHostPowerOperationStatus;
import com.dell.cpsd.paqx.fru.rest.dto.vCenterSystemProperties;
import com.dell.cpsd.paqx.fru.rest.dto.vcenter.ClusterOperationResponse;
import com.dell.cpsd.paqx.fru.rest.dto.vcenter.DestroyVmResponse;
import com.dell.cpsd.paqx.fru.rest.dto.vcenter.HostMaintenanceModeResponse;
import com.dell.cpsd.paqx.fru.rest.representation.HostRepresentation;
import com.dell.cpsd.sdk.client.CapabilityService;
import com.dell.cpsd.sdk.exceptions.CapabilityRetrievalException;
import com.dell.cpsd.service.common.client.exception.ServiceTimeoutException;
import com.dell.cpsd.virtualization.capabilities.api.ClusterOperationRequest;
import com.dell.cpsd.virtualization.capabilities.api.ClusterOperationRequestMessage;
import com.dell.cpsd.virtualization.capabilities.api.ConsulRegisterRequestMessage;
import com.dell.cpsd.virtualization.capabilities.api.Credentials;
import com.dell.cpsd.virtualization.capabilities.api.DestroyVMRequestMessage;
import com.dell.cpsd.virtualization.capabilities.api.DiscoveryRequestInfoMessage;
import com.dell.cpsd.virtualization.capabilities.api.HostMaintenanceModeRequestMessage;
import com.dell.cpsd.virtualization.capabilities.api.HostPowerOperationRequestMessage;
import com.dell.cpsd.virtualization.capabilities.api.MaintenanceModeRequest;
import com.dell.cpsd.virtualization.capabilities.api.MessageProperties;
import com.dell.cpsd.virtualization.capabilities.api.PowerOperationRequest;
import com.dell.cpsd.virtualization.capabilities.api.RegistrationInfo;

/**
 * Copyright &copy; 2017 Dell Inc. or its subsidiaries. All Rights Reserved. Dell EMC Confidential/Proprietary Information
 */
@Service
public class vCenterServiceImpl implements vCenterService
{
    private static final Logger                    LOG = LoggerFactory.getLogger(vCenterServiceImpl.class);

    private final ICapabilityRegistryLookupManager capabilityRegistryLookupManager;
    private final RabbitTemplate                   rabbitTemplate;
    private final AmqpAdmin                        amqpAdmin;
    private final Queue                            responseQueue;
    private final AsyncAcknowledgement             asyncAcknowledgement;
    private final AsyncAcknowledgement             consulRegisterAsyncAcknowledgement;
    private final AsyncAcknowledgement             vmDeletionAsyncAcknowledgement;
    private final AsyncAcknowledgement             vCenterHostPowerAsyncAcknowledgement;
    private final AsyncAcknowledgement             hostMaintenanceModeAsyncAcknowledgement;
    private final AsyncAcknowledgement             vcenterClusterOperationAsyncAcknowledgement;
    private final String                           replyTo;
    private final FruService                       fruService;
    private final DataService                      dataService;
    private final CapabilityService                capabilityService;

    @Autowired
    public vCenterServiceImpl(final ICapabilityRegistryLookupManager capabilityRegistryLookupManager, final RabbitTemplate rabbitTemplate,
            final AmqpAdmin amqpAdmin, final Queue responseQueue,
            @Qualifier(value = "vCenterDiscoverResponseHandler") final AsyncAcknowledgement asyncAcknowledgement,
            @Qualifier(value = "vCenterConsulRegisterResponseHandler") final AsyncAcknowledgement consulRegisterAsyncAcknowledgement,
            @Qualifier(value = "vCenterDestroyVmResponseHandler") final AsyncAcknowledgement vmDeletionAsyncAcknowledgement,
            @Qualifier(value = "vCenterHostPowerOperationResponseHandler") final AsyncAcknowledgement vCenterHostPowerAsyncAcknowledgement,
            @Qualifier(value = "vCenterHostMaintenanceModeResponseHandler") final AsyncAcknowledgement hostMaintenanceModeAsyncAcknowledgement,
            @Qualifier(value = "vCenterClusterOperationsResponseHandler") final AsyncAcknowledgement vcenterClusterOperationAsyncAcknowledgement,
            @Qualifier(value = "replyTo") final String replyTo, final FruService fruService, final DataService dataService,
            final CapabilityService capabilityService)
    {
        this.capabilityRegistryLookupManager = capabilityRegistryLookupManager;
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.responseQueue = responseQueue;
        this.asyncAcknowledgement = asyncAcknowledgement;
        this.consulRegisterAsyncAcknowledgement = consulRegisterAsyncAcknowledgement;
        this.vmDeletionAsyncAcknowledgement = vmDeletionAsyncAcknowledgement;
        this.vCenterHostPowerAsyncAcknowledgement = vCenterHostPowerAsyncAcknowledgement;
        this.hostMaintenanceModeAsyncAcknowledgement = hostMaintenanceModeAsyncAcknowledgement;
        this.vcenterClusterOperationAsyncAcknowledgement = vcenterClusterOperationAsyncAcknowledgement;
        this.replyTo = replyTo;
        this.fruService = fruService;
        this.dataService = dataService;
        this.capabilityService = capabilityService;
    }

    public CompletableFuture<vCenterSystemProperties> showSystem(final EndpointCredentials vcenterCredentials)
    {
        final String requiredCapability = "vcenter-discover";

        try
        {   capabilityService.getCapability(requiredCapability);
            final List<Capability> matchedCapabilities = fruService.findMatchingCapabilities(requiredCapability);
            if (matchedCapabilities.isEmpty())
            {
                LOG.info("No matching capability found for capability [{}]", requiredCapability);
                return CompletableFuture.completedFuture(null);
            }
            final Capability matchedCapability = matchedCapabilities.stream().findFirst().get();
            LOG.debug("Found capability {}", matchedCapability.getProfile());

            // TODO: Work in progress
            final Map<String, String> amqpProperties = fruService.declareBinding(matchedCapability, replyTo);
            final String correlationID = UUID.randomUUID().toString();
        }
        catch (ServiceTimeoutException | CapabilityRegistryException | CapabilityRetrievalException e)
        {
            return null;
        }

        try
        {
            
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));

            for (final CapabilityProvider capabilityProvider : listCapabilityProvidersResponse.getResponse())
            {
                for (final Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();
                        DiscoveryRequestInfoMessage requestMessage = new DiscoveryRequestInfoMessage();
                        requestMessage.setMessageProperties(new MessageProperties().withCorrelationId(correlationId.toString())
                                .withReplyTo(replyTo).withTimestamp(new Date()));

                        try
                        {
                            new URL(vcenterCredentials.getEndpointUrl());
                        }
                        catch (MalformedURLException e)
                        {
                            final CompletableFuture<vCenterSystemProperties> promise = new CompletableFuture<>();
                            promise.completeExceptionally(e);
                            return promise;
                        }
                        final DiscoveryRequestInfoMessage discoveryRequestInfo = new DiscoveryRequestInfoMessage();
                        Credentials credentials = new Credentials();
                        credentials.setUsername(vcenterCredentials.getUsername());
                        credentials.setAddress(vcenterCredentials.getEndpointUrl());
                        credentials.setPassword(vcenterCredentials.getPassword());
                        requestMessage.setCredentials(credentials);

                        final CompletableFuture<vCenterSystemProperties> promise = asyncAcknowledgement.register(correlationId.toString());

                        rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                        return promise;
                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }
        LOG.error("Unable to find required capability: {}", requiredCapability);
        return CompletableFuture.completedFuture(null);

    }

    public CompletableFuture<ConsulRegistryResult> requestConsulRegistration(final EndpointCredentials vcenterCredentials)
    {
        final String requiredCapability = "vcenter-consul-register";
        try
        {
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));

            for (final CapabilityProvider capabilityProvider : listCapabilityProvidersResponse.getResponse())
            {
                for (final Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();
                        ConsulRegisterRequestMessage requestMessage = new ConsulRegisterRequestMessage();
                        requestMessage.setMessageProperties(new MessageProperties().withCorrelationId(correlationId.toString())
                                .withReplyTo(replyTo).withTimestamp(new Date()));

                        try
                        {
                            new URL(vcenterCredentials.getEndpointUrl());
                        }
                        catch (MalformedURLException e)
                        {
                            final CompletableFuture<ConsulRegistryResult> promise = new CompletableFuture<>();
                            promise.completeExceptionally(e);
                            return promise;
                        }
                        final RegistrationInfo registrationInfo = new RegistrationInfo(vcenterCredentials.getEndpointUrl(),
                                vcenterCredentials.getPassword(), vcenterCredentials.getUsername());
                        requestMessage.setRegistrationInfo(registrationInfo);

                        final CompletableFuture<ConsulRegistryResult> promise = consulRegisterAsyncAcknowledgement
                                .register(correlationId.toString());

                        rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                        return promise;
                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }
        LOG.error("Unable to find required capability: {}", requiredCapability);
        return CompletableFuture.completedFuture(null);

    }

    @Override
    public CompletableFuture<DestroyVmResponse> requestVmDeletion(final EndpointCredentials vcenterCredentials, final String jobId,
            final HostRepresentation hostRepresentation)
    {
        final String requiredCapability = "vcenter-destroy-virtualMachine";

        try
        {
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));
            for (final CapabilityProvider capabilityProvider : listCapabilityProvidersResponse.getResponse())
            {
                for (final Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();

                        // TODO: Do we need a list. If list, so should we send all of them.
                        final List<DestroyVMRequestMessage> requestMessages = dataService.getDestroyVMRequestMessage(jobId,
                                hostRepresentation, vcenterCredentials.getEndpointUrl(), vcenterCredentials.getPassword(),
                                vcenterCredentials.getUsername());

                        if (requestMessages != null && !requestMessages.isEmpty())
                        {
                            final DestroyVMRequestMessage requestMessage = requestMessages.get(0);
                            requestMessage.setMessageProperties(new MessageProperties(new Date(), correlationId.toString(), replyTo));

                            try
                            {
                                new URL(vcenterCredentials.getEndpointUrl());
                            }
                            catch (MalformedURLException e)
                            {
                                final CompletableFuture<DestroyVmResponse> promise = new CompletableFuture<>();
                                promise.completeExceptionally(e);
                                return promise;
                            }

                            final CompletableFuture<DestroyVmResponse> promise = vmDeletionAsyncAcknowledgement
                                    .register(correlationId.toString());

                            rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                            return promise;
                        }

                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<HostMaintenanceModeResponse> requestHostMaintenanceModeEnable(final EndpointCredentials vcenterCredentials,
            final String hostname)
    {
        final String requiredCapability = "vcenter-enterMaintenance";

        try
        {
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));
            for (final CapabilityProvider capabilityProvider : listCapabilityProvidersResponse.getResponse())
            {
                for (final Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();
                        final HostMaintenanceModeRequestMessage requestMessage = new HostMaintenanceModeRequestMessage();

                        final MessageProperties messageProperties = new MessageProperties(new Date(), correlationId.toString(), replyTo);
                        requestMessage.setMessageProperties(messageProperties);
                        requestMessage.setCredentials(new Credentials(vcenterCredentials.getEndpointUrl(), vcenterCredentials.getPassword(),
                                vcenterCredentials.getUsername()));

                        MaintenanceModeRequest maintenanceModeRequest = new MaintenanceModeRequest(hostname, Boolean.TRUE);
                        requestMessage.setMaintenanceModeRequest(maintenanceModeRequest);

                        try
                        {
                            new URL(vcenterCredentials.getEndpointUrl());
                        }
                        catch (MalformedURLException e)
                        {
                            final CompletableFuture<HostMaintenanceModeResponse> promise = new CompletableFuture<>();
                            promise.completeExceptionally(e);
                            return promise;
                        }

                        final CompletableFuture<HostMaintenanceModeResponse> promise = hostMaintenanceModeAsyncAcknowledgement
                                .register(correlationId.toString());

                        rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                        return promise;
                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }

        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<VCenterHostPowerOperationStatus> requestHostPowerOff(EndpointCredentials vcenterCredentials, String hostname)
    {
        final String requiredCapability = "vcenter-powercommand";

        try
        {
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));

            if (listCapabilityProvidersResponse == null)
            {
                return CompletableFuture.completedFuture(null);
            }

            final List<CapabilityProvider> capabilityProviders = listCapabilityProvidersResponse.getResponse();

            for (CapabilityProvider capabilityProvider : capabilityProviders)
            {
                for (Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();
                        HostPowerOperationRequestMessage requestMessage = new HostPowerOperationRequestMessage();
                        requestMessage.setMessageProperties(new MessageProperties().withCorrelationId(correlationId.toString())
                                .withReplyTo(replyTo).withTimestamp(new Date()));

                        try
                        {
                            new URL(vcenterCredentials.getEndpointUrl());
                        }
                        catch (MalformedURLException e)
                        {
                            final CompletableFuture<VCenterHostPowerOperationStatus> promise = new CompletableFuture<>();
                            promise.completeExceptionally(e);
                            return promise;
                        }

                        final Credentials credentials = new Credentials(vcenterCredentials.getEndpointUrl(),
                                vcenterCredentials.getPassword(), vcenterCredentials.getUsername());
                        requestMessage.setCredentials(credentials);

                        // TODO hostname is blank BUT SHOULD be filled with appropriate data
                        PowerOperationRequest powerOperationRequest = new PowerOperationRequest(hostname,
                                PowerOperationRequest.PowerOperation.POWER_OFF);

                        requestMessage.setPowerOperationRequest(powerOperationRequest);

                        final CompletableFuture<VCenterHostPowerOperationStatus> promise = vCenterHostPowerAsyncAcknowledgement
                                .register(correlationId.toString());

                        rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                        return promise;
                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }

        return null;
    }

    @Override
    public CompletableFuture<ClusterOperationResponse> requestHostRemoval(final EndpointCredentials vcenterCredentials,
            final String clusterId, final String hostname)
    {
        final String requiredCapability = "vcenter-remove-host";
        try
        {
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));

            for (final CapabilityProvider capabilityProvider : listCapabilityProvidersResponse.getResponse())
            {
                for (final Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();
                        final ClusterOperationRequestMessage requestMessage = new ClusterOperationRequestMessage();
                        requestMessage.setCredentials(new Credentials(vcenterCredentials.getEndpointUrl(), vcenterCredentials.getPassword(),
                                vcenterCredentials.getUsername()));
                        final ClusterOperationRequest clusterOperationRequest = new ClusterOperationRequest();
                        clusterOperationRequest.setHostName(hostname);
                        // TODO: If not required remove the cluster id
                        clusterOperationRequest.setClusterID(clusterId);
                        clusterOperationRequest.setClusterOperation(ClusterOperationRequest.ClusterOperation.REMOVE_HOST);
                        requestMessage.setClusterOperationRequest(clusterOperationRequest);

                        try
                        {
                            new URL(vcenterCredentials.getEndpointUrl());
                        }
                        catch (MalformedURLException e)
                        {
                            final CompletableFuture<ClusterOperationResponse> promise = new CompletableFuture<>();
                            promise.completeExceptionally(e);
                            return promise;
                        }

                        final CompletableFuture<ClusterOperationResponse> promise = vcenterClusterOperationAsyncAcknowledgement
                                .register(correlationId.toString());

                        LOG.info("Host removal request with correlation id [{}]", correlationId.toString());

                        rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                        return promise;
                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }
        LOG.error("Unable to find required capability: {}", requiredCapability);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<ClusterOperationResponse> requestHostAddition(final EndpointCredentials vcenterCredentials,
            final String hostname, final String clusterId, final String hostUsername, final String hostPassword)
    {
        final String requiredCapability = "vcenter-addhostvcenter";
        try
        {
            final ListCapabilityProvidersResponse listCapabilityProvidersResponse = capabilityRegistryLookupManager
                    .listCapabilityProviders(TimeUnit.SECONDS.toMillis(5));

            for (final CapabilityProvider capabilityProvider : listCapabilityProvidersResponse.getResponse())
            {
                for (final Capability capability : capabilityProvider.getCapabilities())
                {
                    LOG.debug("Found capability {}", capability.getProfile());

                    if (requiredCapability.equals(capability.getProfile()))
                    {
                        LOG.debug("Found matching capability {}", capability.getProfile());
                        final List<EndpointProperty> endpointProperties = capability.getProviderEndpoint().getEndpointProperties();
                        final Map<String, String> amqpProperties = endpointProperties.stream()
                                .collect(Collectors.toMap(EndpointProperty::getName, EndpointProperty::getValue));

                        final String requestExchange = amqpProperties.get("request-exchange");
                        final String requestRoutingKey = amqpProperties.get("request-routing-key");

                        final TopicExchange responseExchange = new TopicExchange(amqpProperties.get("response-exchange"));
                        final String responseRoutingKey = amqpProperties.get("response-routing-key").replace("{replyTo}", "." + replyTo);

                        amqpAdmin.declareBinding(BindingBuilder.bind(responseQueue).to(responseExchange).with(responseRoutingKey));

                        LOG.debug("Adding binding {} {}", responseExchange.getName(), responseRoutingKey);

                        final UUID correlationId = UUID.randomUUID();
                        final ClusterOperationRequestMessage requestMessage = new ClusterOperationRequestMessage();
                        requestMessage.setCredentials(new Credentials(vcenterCredentials.getEndpointUrl(), vcenterCredentials.getPassword(),
                                vcenterCredentials.getUsername()));
                        final ClusterOperationRequest clusterOperationRequest = new ClusterOperationRequest();
                        clusterOperationRequest.setHostName(hostname);
                        clusterOperationRequest.setClusterID(clusterId);
                        clusterOperationRequest.setUserName(hostUsername);
                        clusterOperationRequest.setPassword(hostPassword);
                        clusterOperationRequest.setClusterOperation(ClusterOperationRequest.ClusterOperation.ADD_HOST);
                        requestMessage.setClusterOperationRequest(clusterOperationRequest);

                        try
                        {
                            new URL(vcenterCredentials.getEndpointUrl());
                        }
                        catch (MalformedURLException e)
                        {
                            final CompletableFuture<ClusterOperationResponse> promise = new CompletableFuture<>();
                            promise.completeExceptionally(e);
                            return promise;
                        }

                        final CompletableFuture<ClusterOperationResponse> promise = vcenterClusterOperationAsyncAcknowledgement
                                .register(correlationId.toString());

                        LOG.info("Host addition request with correlation id [{}]", correlationId.toString());

                        rabbitTemplate.convertAndSend(requestExchange, requestRoutingKey, requestMessage);

                        return promise;
                    }
                }
            }
        }
        catch (CapabilityRegistryException e)
        {
            LOG.error("Failed while looking up Capability Registry for {}", requiredCapability, e);
        }
        catch (ServiceTimeoutException e)
        {
            LOG.error("Service timed out while querying Capability Registry");
        }
        LOG.error("Unable to find required capability: {}", requiredCapability);
        return CompletableFuture.completedFuture(null);
    }
}

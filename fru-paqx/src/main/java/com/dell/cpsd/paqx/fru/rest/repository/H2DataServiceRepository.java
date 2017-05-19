/**
 * Copyright &copy; 2017 Dell Inc. or its subsidiaries.  All Rights Reserved.
 * Dell EMC Confidential/Proprietary Information
 */

package com.dell.cpsd.paqx.fru.rest.repository;

import com.dell.cpsd.paqx.fru.domain.Datacenter;
import com.dell.cpsd.paqx.fru.domain.FruJob;
import com.dell.cpsd.paqx.fru.domain.Host;
import com.dell.cpsd.paqx.fru.domain.ScaleIOData;
import com.dell.cpsd.paqx.fru.domain.VCenter;
import com.dell.cpsd.paqx.fru.dto.DestroyVMDto;
import com.dell.cpsd.paqx.fru.dto.ScaleIORemoveDto;
import com.dell.cpsd.paqx.fru.dto.ScaleIORemoveDataDto;
import com.dell.cpsd.paqx.fru.rest.representation.HostRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.TypedQuery;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Copyright &copy; 2017 Dell Inc. or its subsidiaries.  All Rights Reserved.
 * Dell EMC Confidential/Proprietary Information
 */
@Repository
public class H2DataServiceRepository implements DataServiceRepository
{
    private static final Logger LOG = LoggerFactory.getLogger(H2DataServiceRepository.class);

    @PersistenceContext
    EntityManager entityManager;

    @Override
    @Transactional
    public Long saveScaleIOData(final UUID jobId, final ScaleIOData data)
    {
        TypedQuery<FruJob> query = entityManager.createQuery("from FruJob where JOB_ID = :jobid", FruJob.class);

        FruJob fruJob = null;

        try
        {
            fruJob = query.setParameter("jobid", jobId.toString()).getSingleResult();
        }
        catch (NoResultException e)
        {
            LOG.error("No result", e);
        }

        if (fruJob == null)
        {
            fruJob = new FruJob(jobId.toString(), data, null);
            entityManager.persist(fruJob);
        }
        else
        {
            fruJob.setScaleIO(data);
            entityManager.merge(fruJob);
        }
        entityManager.flush();
        return fruJob.getUuid();
    }

    @Override
    @Transactional
    public Long saveVCenterData(final UUID jobId, final VCenter data)
    {
        TypedQuery<FruJob> query = entityManager.createQuery("from FruJob where JOB_ID = :jobid", FruJob.class);

        FruJob fruJob = null;

        try
        {
            fruJob = query.setParameter("jobid", jobId.toString()).getSingleResult();
        }
        catch (NoResultException e)
        {
            LOG.error("No result", e);
        }

        if (fruJob == null)
        {
            fruJob = new FruJob(jobId.toString(), null, data);
            entityManager.persist(fruJob);
        }
        else
        {
            fruJob.setVcenter(data);
            entityManager.merge(fruJob);
        }
        return fruJob.getUuid();
    }

    @Override
    public List<Host> getVCenterHosts(final String jobId)
    {
        List<Host> hostList = null;
        TypedQuery<FruJob> query = entityManager.createQuery("from FruJob where JOB_ID = :jobid", FruJob.class);

        FruJob fruJob = null;

        try
        {
            fruJob = query.setParameter("jobid", jobId).getSingleResult();
        }
        catch (NoResultException e)
        {
            LOG.error("No result", e);
        }

        if (fruJob != null)
        {
            VCenter vCenter = fruJob.getVcenter();

            if (vCenter != null)
            {

                //Now need to find all of the VCenter hosts.
                List<Datacenter> dataCenterList = vCenter.getDatacenterList();

                if (dataCenterList != null)
                {
                    hostList = dataCenterList.stream().filter(Objects::nonNull).map(x -> x.getClusterList()).filter(Objects::nonNull)
                            .flatMap(y -> y.stream()).filter(Objects::nonNull).map(z -> z.getHostList()).filter(Objects::nonNull)
                            .flatMap(a -> a.stream()).filter(Objects::nonNull).collect(Collectors.toList());
                }
            }
        }
        return hostList;
    }

    private List<Object[]> correlateSDSDataWithScaleIOData(final HostRepresentation selectedHost)
    {
        String query =
                "SELECT scaleio_ip_list.sds_sds_uuid, scaleio_sds.sds_name,vm_ip.ip_address, virtual_machine.uuid, virtual_machine.host_uuid, virtual_machine.vm_id "
                        + "FROM vm_ip " + "JOIN scaleio_ip_list ON scaleio_ip_list.sds_ip = vm_ip.ip_address "
                        + "JOIN scaleio_sds ON scaleio_ip_list.sds_sds_uuid = scaleio_sds.sds_uuid "
                        + "JOIN vm_guest_network ON vm_ip.vmnetwork_uuid = vm_guest_network.uuid "
                        + "JOIN virtual_machine ON vm_guest_network.virtualmachine_uuid = virtual_machine.uuid "
                        + "where virtual_machine.host_uuid in (select host_uuid from host where host_name='" + selectedHost.getHostName()
                        + "');";

        Query q = entityManager.createNativeQuery(query);
        return q.getResultList();
    }

    @Override
    public ScaleIORemoveDto getScaleIORemoveDtoForSelectedHost(final String jobId, final HostRepresentation selectedHost, String userName,
                                                               String password, String endpointString)
    {
        ScaleIORemoveDto dto = new ScaleIORemoveDto(userName, password, getPortFromEndpoint(endpointString), 443, getIPAddressFromEndpoint(endpointString));
        StringBuilder sdsList = new StringBuilder();
        StringBuilder mdmList = new StringBuilder();
        StringBuilder sdcList = new StringBuilder();
        for (Object[] columns : correlateSDSDataWithScaleIOData(selectedHost))
        {
            String sdsUuid = (String) columns[0];
            String sdsName = (String) columns[1];
            String vmIpAddress = (String) columns[2];
            String vmUuid = (String) columns[3];
            String hostUuid = (String) columns[4];

            sdsList.append(sdsName);
        }

        ScaleIORemoveDataDto s = new ScaleIORemoveDataDto("eth0", "empty", mdmList.toString(), sdsList.toString(), sdcList.toString());
        dto.setScaleIORemoveDataDto(s);
        return dto;
    }

    @Override
    public List<DestroyVMDto> getDestroyVMDtos(final String jobId, final HostRepresentation selectedHost, final String vCenterUserName,
                                               final String vCenterPassword, final String vCenterEndpoint)
    {
        List<DestroyVMDto> returnVal = new ArrayList<>();
        for (Object[] columns : correlateSDSDataWithScaleIOData(selectedHost))
        {

            String vmid = (String)columns[5];
            DestroyVMDto dto = new DestroyVMDto(vCenterUserName, vCenterPassword, vCenterEndpoint, vmid);
            returnVal.add(dto);
        }
        return returnVal;
    }

    private String getIPAddressFromEndpoint(final String endpointString)
    {
        String returnVal = null;
        try
        {
            returnVal = new URL(endpointString).getHost();
        }
        catch (MalformedURLException e)
        {
            LOG.error("Malformed URL", e);
        }
        return returnVal;
    }

    private int getPortFromEndpoint(final String endpointString)
    {
        int returnVal = -1;
        try
        {
            returnVal = new URL(endpointString).getPort();
        }
        catch (MalformedURLException e)
        {
            LOG.error("Malformed URL", e);
        }
        return returnVal;
    }
}
/**
 * Copyright &copy; 2017 Dell Inc. or its subsidiaries.  All Rights Reserved.
 * Dell EMC Confidential/Proprietary Information
 */
package com.dell.cpsd.paqx.fru.rest.representation;

import com.dell.cpsd.paqx.fru.rest.domain.Job;

import java.util.ArrayList;
import java.util.List;

public class HostSelectionJobRepresentation extends JobRepresentation
{

    private List<HostRepresentation> hostRepresentations = new ArrayList<>();

    public HostSelectionJobRepresentation(final Job job)
    {
        super(job);
    }

    public void setHostRepresentations(final List<HostRepresentation> hostRepresentations)
    {
        this.hostRepresentations = hostRepresentations;
    }
}

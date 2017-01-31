/**
 * Copyright 2015 StreamSets Inc.
 *
 * Licensed under the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.streamsets.datacollector.restapi;

import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.streamsets.datacollector.main.RuntimeInfo;
import com.streamsets.datacollector.main.UserGroupManager;
import com.streamsets.datacollector.restapi.bean.UserJson;
import com.streamsets.datacollector.store.AclStoreTask;
import com.streamsets.datacollector.store.PipelineInfo;
import com.streamsets.datacollector.store.PipelineStoreTask;
import com.streamsets.datacollector.store.impl.AclPipelineStoreTask;
import com.streamsets.datacollector.util.AuthzRole;
import com.streamsets.datacollector.util.PipelineException;
import com.streamsets.lib.security.acl.AclDtoJsonMapper;
import com.streamsets.lib.security.acl.dto.Acl;
import com.streamsets.lib.security.acl.dto.Permission;
import com.streamsets.lib.security.acl.dto.ResourceType;
import com.streamsets.lib.security.acl.dto.SubjectType;
import com.streamsets.lib.security.acl.json.AclJson;
import com.streamsets.lib.security.acl.json.PermissionJson;
import com.streamsets.lib.security.http.SSOPrincipal;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.Authorization;

import javax.annotation.security.DenyAll;
import javax.annotation.security.PermitAll;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Path("/v1/acl")
@Api(value = "store")
@DenyAll
public class AclStoreResource {
  private final PipelineStoreTask store;
  private final AclStoreTask aclStore;
  private final UserJson currentUser;

  @Inject
  public AclStoreResource(
      Principal principal,
      PipelineStoreTask store,
      AclStoreTask aclStore,
      RuntimeInfo runtimeInfo,
      UserGroupManager userGroupManager
  ) {
    if (runtimeInfo.isDPMEnabled() && principal instanceof SSOPrincipal) {
      currentUser = new UserJson((SSOPrincipal)principal);
    } else {
      currentUser = userGroupManager.getUser(principal);
    }
    this.store = new AclPipelineStoreTask(store, aclStore, currentUser);
    this.aclStore = aclStore;
  }

  @Path("/{pipelineName}")
  @GET
  @ApiOperation(value ="Get Pipeline ACL", authorizations = @Authorization(value = "basic"))
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({
      AuthzRole.CREATOR, AuthzRole.ADMIN, AuthzRole.CREATOR_REMOTE, AuthzRole.ADMIN_REMOTE
  })
  @SuppressWarnings("unchecked")
  public Response getAcl(
      @PathParam("pipelineName") String name
  ) throws PipelineException, URISyntaxException {
    PipelineInfo pipelineInfo = store.getInfo(name);
    RestAPIUtils.injectPipelineInMDC(pipelineInfo.getTitle(), pipelineInfo.getName());

    Acl acl = aclStore.getAcl(name);
    if (acl == null && pipelineInfo.getCreator().equals(currentUser.getName())) {
      // If no acl, only owner of the pipeline will have all permission
      acl = new Acl();
      acl.setResourceId(name);
      acl.setResourceOwner(pipelineInfo.getCreator());
      acl.setResourceType(ResourceType.PIPELINE);
      acl.setResourceCreatedTime(pipelineInfo.getCreated().getTime());
      acl.setLastModifiedBy(pipelineInfo.getCreator());
      acl.setLastModifiedOn(System.currentTimeMillis());

      Permission ownerPermission = new Permission();
      ownerPermission.setResourceId(name);
      ownerPermission.setSubjectId(pipelineInfo.getCreator());
      ownerPermission.setSubjectType(SubjectType.USER);
      ownerPermission.setLastModifiedOn(pipelineInfo.getCreated().getTime());
      ownerPermission.setLastModifiedBy(pipelineInfo.getCreator());
      ownerPermission.getActions().addAll(ResourceType.PIPELINE.getActions());
      acl.getPermissions().add(ownerPermission);
    }

    return Response.ok(AclDtoJsonMapper.INSTANCE.toAclJson(acl)).build();
  }

  @Path("/{pipelineName}")
  @POST
  @ApiOperation(value ="Update Pipeline ACL", authorizations = @Authorization(value = "basic"))
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @RolesAllowed({
      AuthzRole.CREATOR, AuthzRole.ADMIN, AuthzRole.CREATOR_REMOTE, AuthzRole.ADMIN_REMOTE
  })
  @SuppressWarnings("unchecked")
  public Response saveAcl(
      @PathParam("pipelineName") String name,
      @Context SecurityContext context,
      AclJson aclJson
  ) throws PipelineException, URISyntaxException {
    PipelineInfo pipelineInfo = store.getInfo(name);
    RestAPIUtils.injectPipelineInMDC(pipelineInfo.getTitle(), pipelineInfo.getName());
    aclStore.saveAcl(name, AclDtoJsonMapper.INSTANCE.asAclDto(aclJson));
    return Response.ok().build();
  }

  @Path("/{pipelineName}/permissions")
  @GET
  @ApiOperation(
      value ="Return pipeline permissions for given pipeline ID",
      response = PermissionJson.class,
      responseContainer = "List",
      authorizations = @Authorization(value = "basic")
  )
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  @PermitAll
  @SuppressWarnings("unchecked")
  public Response getPermissions(
      @PathParam("pipelineName") String name
  ) throws PipelineException {
    PipelineInfo pipelineInfo = store.getInfo(name);
    RestAPIUtils.injectPipelineInMDC(pipelineInfo.getTitle(), pipelineInfo.getName());
    List<Permission> permissionList = new ArrayList<>();
    Acl acl = aclStore.getAcl(name);
    if (acl != null) {
      final List<String> subjectIds = new ArrayList<>();
      subjectIds.add(currentUser.getName());
      if (currentUser.getGroups() != null) {
        subjectIds.addAll(currentUser.getGroups());
      }
      Collection<Permission> permissions = Collections2.filter(acl.getPermissions(), new Predicate<Permission>() {
        @Override
        public boolean apply(Permission permission) {
          return subjectIds.contains(permission.getSubjectId());
        }
      });
      permissionList = new ArrayList<>(permissions);

    } else {
      // If no acl, only owner of the pipeline will have all permission
      if (pipelineInfo.getCreator().equals(currentUser.getName())) {
        Permission ownerPermission = new Permission();
        ownerPermission.setResourceId(name);
        ownerPermission.setSubjectId(pipelineInfo.getCreator());
        ownerPermission.setSubjectType(SubjectType.USER);
        ownerPermission.getActions().addAll(ResourceType.PIPELINE.getActions());
        permissionList.add(ownerPermission);
      }
    }
    return Response.ok(AclDtoJsonMapper.INSTANCE.toPermissionsJson(permissionList)).build();
  }
}

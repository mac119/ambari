/*
 * Licensed to the Apache Software Foundation (ASF) under one
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

package org.apache.ambari.server.controller;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.Injector;
import junit.framework.Assert;
import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.Role;
import org.apache.ambari.server.actionmanager.ActionManager;
import org.apache.ambari.server.actionmanager.HostRoleCommand;
import org.apache.ambari.server.actionmanager.RequestFactory;
import org.apache.ambari.server.actionmanager.Stage;
import org.apache.ambari.server.actionmanager.StageFactory;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.controller.internal.ArtifactResourceProvider;
import org.apache.ambari.server.controller.internal.RequestStageContainer;
import org.apache.ambari.server.controller.spi.ClusterController;
import org.apache.ambari.server.controller.spi.Predicate;
import org.apache.ambari.server.controller.spi.Request;
import org.apache.ambari.server.controller.spi.Resource;
import org.apache.ambari.server.controller.spi.ResourceProvider;
import org.apache.ambari.server.metadata.RoleCommandOrder;
import org.apache.ambari.server.orm.DBAccessor;
import org.apache.ambari.server.security.SecurityHelper;
import org.apache.ambari.server.serveraction.kerberos.KDCType;
import org.apache.ambari.server.serveraction.kerberos.KerberosCredential;
import org.apache.ambari.server.serveraction.kerberos.KerberosMissingAdminCredentialsException;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationException;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandler;
import org.apache.ambari.server.serveraction.kerberos.KerberosOperationHandlerFactory;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.HostState;
import org.apache.ambari.server.state.SecurityState;
import org.apache.ambari.server.state.SecurityType;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.apache.ambari.server.state.cluster.ClusterFactory;
import org.apache.ambari.server.state.cluster.ClustersImpl;
import org.apache.ambari.server.state.host.HostFactory;
import org.apache.ambari.server.state.kerberos.KerberosComponentDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.ambari.server.state.kerberos.KerberosIdentityDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosKeytabDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosPrincipalDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosPrincipalType;
import org.apache.ambari.server.state.kerberos.KerberosServiceDescriptor;
import org.apache.ambari.server.state.stack.OsFamily;
import org.easymock.Capture;
import org.easymock.EasyMockSupport;
import org.easymock.IAnswer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.persistence.EntityManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.easymock.EasyMock.*;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("unchecked")
public class KerberosHelperTest extends EasyMockSupport {

  private static Injector injector;
  private final ClusterController clusterController = createStrictMock(ClusterController.class);
  private final KerberosDescriptorFactory kerberosDescriptorFactory = createStrictMock(KerberosDescriptorFactory.class);
  private final AmbariMetaInfo metaInfo = createNiceMock(AmbariMetaInfo.class);

  @Before
  public void setUp() throws Exception {
    reset(clusterController);
    reset(metaInfo);

    final KerberosOperationHandlerFactory kerberosOperationHandlerFactory = createNiceMock(KerberosOperationHandlerFactory.class);

    expect(kerberosOperationHandlerFactory.getKerberosOperationHandler(KDCType.MIT_KDC))
        .andReturn(new KerberosOperationHandler() {
          @Override
          public void open(KerberosCredential administratorCredentials, String defaultRealm, Map<String, String> kerberosConfiguration) throws KerberosOperationException {
            setAdministratorCredentials(administratorCredentials);
            setDefaultRealm(defaultRealm);
            setOpen(true);
          }

          @Override
          public void close() throws KerberosOperationException {

          }

          @Override
          public boolean principalExists(String principal) throws KerberosOperationException {
            return "principal".equals(principal);
          }

          @Override
          public Integer createPrincipal(String principal, String password, boolean service) throws KerberosOperationException {
            return null;
          }

          @Override
          public Integer setPrincipalPassword(String principal, String password) throws KerberosOperationException {
            return null;
          }

          @Override
          public boolean removePrincipal(String principal) throws KerberosOperationException {
            return false;
          }
        })
        .anyTimes();

    injector = Guice.createInjector(new AbstractModule() {

      @Override
      protected void configure() {
        bind(EntityManager.class).toInstance(createNiceMock(EntityManager.class));
        bind(DBAccessor.class).toInstance(createNiceMock(DBAccessor.class));
        bind(ClusterFactory.class).toInstance(createNiceMock(ClusterFactory.class));
        bind(HostFactory.class).toInstance(createNiceMock(HostFactory.class));
        bind(SecurityHelper.class).toInstance(createNiceMock(SecurityHelper.class));
        bind(OsFamily.class).toInstance(createNiceMock(OsFamily.class));
        bind(AmbariCustomCommandExecutionHelper.class).toInstance(createNiceMock(AmbariCustomCommandExecutionHelper.class));
        bind(AmbariManagementController.class).toInstance(createNiceMock(AmbariManagementController.class));
        bind(AmbariMetaInfo.class).toInstance(metaInfo);
        bind(ActionManager.class).toInstance(createNiceMock(ActionManager.class));
        bind(RequestFactory.class).toInstance(createNiceMock(RequestFactory.class));
        bind(StageFactory.class).toInstance(createNiceMock(StageFactory.class));
        bind(Clusters.class).toInstance(createNiceMock(ClustersImpl.class));
        bind(ConfigHelper.class).toInstance(createNiceMock(ConfigHelper.class));
        bind(KerberosOperationHandlerFactory.class).toInstance(kerberosOperationHandlerFactory);
        bind(ClusterController.class).toInstance(clusterController);
        bind(KerberosDescriptorFactory.class).toInstance(kerberosDescriptorFactory);
      }
    });

    //todo: currently don't bind ClusterController due to circular references so can't use @Inject
    setClusterController();
  }

  @After
  public void tearDown() throws Exception {

  }

  @Test(expected = AmbariException.class)
  public void testMissingClusterEnv() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    Cluster cluster = createNiceMock(Cluster.class);
    RequestStageContainer requestStageContainer = createNiceMock(RequestStageContainer.class);

    replayAll();
    kerberosHelper.toggleKerberos(cluster, SecurityType.KERBEROS, requestStageContainer);
    verifyAll();
  }

  @Test(expected = AmbariException.class)
  public void testMissingKrb5Conf() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("ldap_url")).andReturn("").once();
    expect(kerberosEnvProperties.get("container_dn")).andReturn("").once();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).once();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).once();

    replayAll();
    kerberosHelper.toggleKerberos(cluster, SecurityType.KERBEROS, null);
    verifyAll();
  }

  @Test(expected = AmbariException.class)
  public void testMissingKerberosEnvConf() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("realm")).andReturn("EXAMPLE.COM").once();
    expect(kerberosEnvProperties.get("kdc_host")).andReturn("10.0.100.1").once();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);
    expect(krb5ConfProperties.get("kadmin_host")).andReturn("10.0.100.1").once();

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).once();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).once();

    replayAll();
    kerberosHelper.toggleKerberos(cluster, SecurityType.KERBEROS, null);
    verifyAll();
  }

  @Test
  public void testEnableKerberos() throws Exception {
    testEnableKerberos(new KerberosCredential("principal", "password", "keytab"), true, false);
  }

  @Test(expected = KerberosMissingAdminCredentialsException.class)
  public void testEnableKerberosMissingCredentials() throws Exception {
    try {
      testEnableKerberos(null, true, false);
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().startsWith("Missing KDC administrator credentials"));
      throw e;
    }
  }

  @Test(expected = KerberosMissingAdminCredentialsException.class)
  public void testEnableKerberosInvalidCredentials() throws Exception {
    try {
      testEnableKerberos(new KerberosCredential("invalid_principal", "password", "keytab"), true, false);
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().startsWith("Invalid KDC administrator credentials"));
      throw e;
    }
  }

  @Test
  public void testEnableKerberos_GetKerberosDescriptorFromCluster() throws Exception {
    testEnableKerberos(new KerberosCredential("principal", "password", "keytab"), true, false);
  }

  @Test
  public void testEnableKerberos_GetKerberosDescriptorFromStack() throws Exception {
    testEnableKerberos(new KerberosCredential("principal", "password", "keytab"), false, true);
  }

  @Test
  public void testEnsureIdentities() throws Exception {
    testEnsureIdentities(new KerberosCredential("principal", "password", "keytab"));
  }

  @Test(expected = KerberosMissingAdminCredentialsException.class)
  public void testEnsureIdentitiesMissingCredentials() throws Exception {
    try {
      testEnsureIdentities(null);
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().startsWith("Missing KDC administrator credentials"));
      throw e;
    }
  }

  @Test(expected = KerberosMissingAdminCredentialsException.class)
  public void testEnsureIdentitiesInvalidCredentials() throws Exception {
    try {
      testEnsureIdentities(new KerberosCredential("invalid_principal", "password", "keytab"));
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().startsWith("Invalid KDC administrator credentials"));
      throw e;
    }
  }
  @Test
  public void testDeleteIdentities() throws Exception {
    testDeleteIdentities(new KerberosCredential("principal", "password", "keytab"));
  }

  @Test(expected = KerberosMissingAdminCredentialsException.class)
  public void testDeleteIdentitiesMissingCredentials() throws Exception {
    try {
      testDeleteIdentities(null);
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().startsWith("Missing KDC administrator credentials"));
      throw e;
    }
  }

  @Test(expected = KerberosMissingAdminCredentialsException.class)
  public void testDeleteIdentitiesInvalidCredentials() throws Exception {
    try {
      testDeleteIdentities(new KerberosCredential("invalid_principal", "password", "keytab"));
    } catch (IllegalArgumentException e) {
      Assert.assertTrue(e.getMessage().startsWith("Invalid KDC administrator credentials"));
      throw e;
    }
  }

  @Test
  public void testExecuteCustomOperationsInvalidOperation() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);
    final Cluster cluster = createNiceMock(Cluster.class);

    try {
      kerberosHelper.executeCustomOperations(cluster,
          Collections.singletonMap("invalid_operation", "false"), null);
    } catch (Throwable t) {
      Assert.fail("Exception should not have been thrown");
    }
  }

  @Test(expected = AmbariException.class)
  public void testRegenerateKeytabsInvalidValue() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);
    final Cluster cluster = createNiceMock(Cluster.class);

    kerberosHelper.executeCustomOperations(cluster,
        Collections.singletonMap("regenerate_keytabs", "false"), null);
    Assert.fail("AmbariException should have failed");
  }

  @Test
  public void testRegenerateKeytabsValidateRequestStageContainer() throws Exception {
    testRegenerateKeytabs(new KerberosCredential("principal", "password", "keytab"), true, false);
  }

  @Test
  public void testRegenerateKeytabsValidateSkipInvalidHost() throws Exception {
    testRegenerateKeytabs(new KerberosCredential("principal", "password", "keytab"), true, true);
  }

  @Test
  public void testRegenerateKeytabs() throws Exception {
    testRegenerateKeytabs(new KerberosCredential("principal", "password", "keytab"), false, false);
  }

  @Test
  public void testDisableKerberos() throws Exception {
    testDisableKerberos(new KerberosCredential("principal", "password", "keytab"), false, true);
  }

  @Test
  public void testCreateTestIdentity() throws Exception {
    testCreateTestIdentity(new KerberosCredential("principal", "password", "keytab"));
  }

  @Test
  public void testDeleteTestIdentity() throws Exception {
    testDeleteTestIdentity(new KerberosCredential("principal", "password", "keytab"));
  }

  private void testEnableKerberos(final KerberosCredential kerberosCredential,
                                  boolean getClusterDescriptor,
                                  boolean getStackDescriptor) throws Exception {

    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final StackId stackVersion = createNiceMock(StackId.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(schKerberosClient.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(schKerberosClient.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("host1").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1 = createMock(ServiceComponentHost.class);
    expect(sch1.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch1.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch1.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(sch1.getHostName()).andReturn("host1").anyTimes();
    expect(sch1.getState()).andReturn(State.INSTALLED).anyTimes();

    sch1.setDesiredSecurityState(SecurityState.SECURED_KERBEROS);
    expect(expectLastCall()).once();
    sch1.setSecurityState(SecurityState.SECURING);
    expect(expectLastCall()).once();

    final ServiceComponentHost sch2 = createMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").anyTimes();
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT2").anyTimes();
    expect(sch2.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch2.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch2.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(sch2.getHostName()).andReturn("host1").anyTimes();
    expect(sch2.getState()).andReturn(State.INSTALLED).anyTimes();

    sch2.setDesiredSecurityState(SecurityState.SECURED_KERBEROS);
    expect(expectLastCall()).once();
    sch2.setSecurityState(SecurityState.SECURING);
    expect(expectLastCall()).once();

    final Host host = createNiceMock(Host.class);
    expect(host.getHostName()).andReturn("host1").anyTimes();
    expect(host.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(Collections.singletonMap("host1", schKerberosClient)).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
            .times(3);
    serviceKerberos.setSecurityState(SecurityState.SECURED_KERBEROS);
    expectLastCall().once();

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);
    service1.setSecurityState(SecurityState.SECURED_KERBEROS);
    expectLastCall().once();

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);
    service2.setSecurityState(SecurityState.SECURED_KERBEROS);
    expectLastCall().once();

    final Map<String, String> kerberosEnvProperties = createMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createMock(Cluster.class);
    expect(cluster.getClusterId()).andReturn(1L).anyTimes();
    expect(cluster.getSecurityType()).andReturn(SecurityType.KERBEROS).anyTimes();
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("host1"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(schKerberosClient);
            add(sch1);
            add(sch2);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("host1", host);
          }
        })
        .once();
    expect(clusters.getHost("host1"))
        .andReturn(host)
        .once();

    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "host1"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(2);

    final KerberosPrincipalDescriptor principalDescriptor1 = createNiceMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1.getValue()).andReturn("component1/_HOST@${realm}").once();
    expect(principalDescriptor1.getType()).andReturn(KerberosPrincipalType.SERVICE).once();
    expect(principalDescriptor1.getConfiguration()).andReturn("service1-site/component1.kerberos.principal").once();

    final KerberosPrincipalDescriptor principalDescriptor2 = createNiceMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor2.getValue()).andReturn("component2/${host}@${realm}").once();
    expect(principalDescriptor2.getType()).andReturn(KerberosPrincipalType.SERVICE).once();
    expect(principalDescriptor2.getConfiguration()).andReturn("service2-site/component2.kerberos.principal").once();

    final KerberosKeytabDescriptor keytabDescriptor1 = createNiceMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor1.getFile()).andReturn("${keytab_dir}/service1.keytab").once();
    expect(keytabDescriptor1.getOwnerName()).andReturn("service1").once();
    expect(keytabDescriptor1.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor1.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor1.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor1.getConfiguration()).andReturn("service1-site/component1.keytab.file").once();
    expect(keytabDescriptor1.isCachable()).andReturn(false).once();

    final KerberosKeytabDescriptor keytabDescriptor2 = createNiceMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor2.getFile()).andReturn("${keytab_dir}/service2.keytab").once();
    expect(keytabDescriptor2.getOwnerName()).andReturn("service2").once();
    expect(keytabDescriptor2.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor2.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor2.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor2.getConfiguration()).andReturn("service2-site/component2.keytab.file").once();
    expect(keytabDescriptor2.isCachable()).andReturn(false).once();

    final KerberosIdentityDescriptor identityDescriptor1 = createNiceMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1.getPrincipalDescriptor()).andReturn(principalDescriptor1).once();
    expect(identityDescriptor1.getKeytabDescriptor()).andReturn(keytabDescriptor1).once();

    final KerberosIdentityDescriptor identityDescriptor2 = createNiceMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor2.getPrincipalDescriptor()).andReturn(principalDescriptor2).once();
    expect(identityDescriptor2.getKeytabDescriptor()).andReturn(keytabDescriptor2).once();

    final KerberosComponentDescriptor componentDescriptor1 = createNiceMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor1.getIdentities(true)).
        andReturn(new ArrayList<KerberosIdentityDescriptor>() {{
          add(identityDescriptor1);
        }}).once();

    final KerberosComponentDescriptor componentDescriptor2 = createNiceMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor2.getIdentities(true)).
        andReturn(new ArrayList<KerberosIdentityDescriptor>() {{
          add(identityDescriptor2);
        }}).once();

    final KerberosServiceDescriptor serviceDescriptor1 = createNiceMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor1.getComponent("COMPONENT1")).andReturn(componentDescriptor1).once();

    final KerberosServiceDescriptor serviceDescriptor2 = createNiceMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor2.getComponent("COMPONENT2")).andReturn(componentDescriptor2).once();

    final KerberosDescriptor kerberosDescriptor = createNiceMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getService("SERVICE1")).andReturn(serviceDescriptor1).once();
    expect(kerberosDescriptor.getService("SERVICE2")).andReturn(serviceDescriptor2).once();

    if (getClusterDescriptor) {
      setupGetDescriptorFromCluster(kerberosDescriptor);
    } else if (getStackDescriptor) {
      setupGetDescriptorFromStack(kerberosDescriptor);
    }

    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();

    // This is a STRICT mock to help ensure that the end result is what we want.
    final RequestStageContainer requestStageContainer = createStrictMock(RequestStageContainer.class);
    // Create Principals Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Create Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(0L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Distribute Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Update Configs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(2L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // TODO: Add more of these when more stages are added.
    // Clean-up/Finalize Stage
    expect(requestStageContainer.getLastStageId()).andReturn(3L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();

    replayAll();

    // Needed by infrastructure
    metaInfo.init();

    kerberosHelper.toggleKerberos(cluster, SecurityType.KERBEROS, requestStageContainer);

    verifyAll();
  }

  private void testDisableKerberos(final KerberosCredential kerberosCredential,
                                   boolean getClusterDescriptor,
                                   boolean getStackDescriptor) throws Exception {

    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final StackId stackVersion = createNiceMock(StackId.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(schKerberosClient.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(schKerberosClient.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("host1").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1 = createMock(ServiceComponentHost.class);
    expect(sch1.getServiceName()).andReturn("SERVICE1").times(2);
    expect(sch1.getServiceComponentName()).andReturn("COMPONENT1").once();
    expect(sch1.getSecurityState()).andReturn(SecurityState.SECURED_KERBEROS).anyTimes();
    expect(sch1.getDesiredSecurityState()).andReturn(SecurityState.SECURED_KERBEROS).anyTimes();
    expect(sch1.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(sch1.getHostName()).andReturn("host1").anyTimes();
    expect(sch1.getState()).andReturn(State.INSTALLED).anyTimes();

    sch1.setDesiredSecurityState(SecurityState.UNSECURED);
    expect(expectLastCall()).once();
    sch1.setSecurityState(SecurityState.UNSECURING);
    expect(expectLastCall()).once();

    final ServiceComponentHost sch2 = createMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").times(2);
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT2").anyTimes();
    expect(sch2.getSecurityState()).andReturn(SecurityState.SECURED_KERBEROS).anyTimes();
    expect(sch2.getDesiredSecurityState()).andReturn(SecurityState.SECURED_KERBEROS).anyTimes();
    expect(sch2.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(sch2.getHostName()).andReturn("host1").anyTimes();
    expect(sch2.getState()).andReturn(State.INSTALLED).anyTimes();

    sch2.setDesiredSecurityState(SecurityState.UNSECURED);
    expect(expectLastCall()).once();
    sch2.setSecurityState(SecurityState.UNSECURING);
    expect(expectLastCall()).once();

    final Host host = createNiceMock(Host.class);
    expect(host.getHostName()).andReturn("host1").anyTimes();

    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(Collections.singletonMap("host1", schKerberosClient)).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
        .times(3);
    serviceKerberos.setSecurityState(SecurityState.UNSECURED);
    expectLastCall().once();

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);
    service1.setSecurityState(SecurityState.UNSECURED);
    expectLastCall().once();

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);
    service2.setSecurityState(SecurityState.UNSECURED);
    expectLastCall().once();

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getSecurityType()).andReturn(SecurityType.NONE).anyTimes();
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("host1"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(schKerberosClient);
            add(sch1);
            add(sch2);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("host1", host);
          }
        })
        .once();
    expect(clusters.getHost("host1"))
        .andReturn(host)
        .once();


    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "host1"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(2);

    final KerberosPrincipalDescriptor principalDescriptor1 = createNiceMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1.getValue()).andReturn("component1/_HOST@${realm}").once();
    expect(principalDescriptor1.getType()).andReturn(KerberosPrincipalType.SERVICE).once();
    expect(principalDescriptor1.getConfiguration()).andReturn("service1-site/component1.kerberos.principal").once();

    final KerberosPrincipalDescriptor principalDescriptor2 = createNiceMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor2.getValue()).andReturn("component2/${host}@${realm}").once();
    expect(principalDescriptor2.getType()).andReturn(KerberosPrincipalType.SERVICE).once();
    expect(principalDescriptor2.getConfiguration()).andReturn("service2-site/component2.kerberos.principal").once();

    final KerberosKeytabDescriptor keytabDescriptor1 = createNiceMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor1.getFile()).andReturn("${keytab_dir}/service1.keytab").once();
    expect(keytabDescriptor1.getOwnerName()).andReturn("service1").once();
    expect(keytabDescriptor1.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor1.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor1.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor1.getConfiguration()).andReturn("service1-site/component1.keytab.file").once();
    expect(keytabDescriptor1.isCachable()).andReturn(false).once();

    final KerberosKeytabDescriptor keytabDescriptor2 = createNiceMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor2.getFile()).andReturn("${keytab_dir}/service2.keytab").once();
    expect(keytabDescriptor2.getOwnerName()).andReturn("service2").once();
    expect(keytabDescriptor2.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor2.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor2.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor2.getConfiguration()).andReturn("service2-site/component2.keytab.file").once();
    expect(keytabDescriptor2.isCachable()).andReturn(false).once();

    final KerberosIdentityDescriptor identityDescriptor1 = createNiceMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1.getPrincipalDescriptor()).andReturn(principalDescriptor1).once();
    expect(identityDescriptor1.getKeytabDescriptor()).andReturn(keytabDescriptor1).once();

    final KerberosIdentityDescriptor identityDescriptor2 = createNiceMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor2.getPrincipalDescriptor()).andReturn(principalDescriptor2).once();
    expect(identityDescriptor2.getKeytabDescriptor()).andReturn(keytabDescriptor2).once();

    final KerberosComponentDescriptor componentDescriptor1 = createNiceMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor1.getIdentities(true)).
        andReturn(new ArrayList<KerberosIdentityDescriptor>() {{
          add(identityDescriptor1);
        }}).once();

    final KerberosComponentDescriptor componentDescriptor2 = createNiceMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor2.getIdentities(true)).
        andReturn(new ArrayList<KerberosIdentityDescriptor>() {{
          add(identityDescriptor2);
        }}).once();

    final KerberosServiceDescriptor serviceDescriptor1 = createNiceMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor1.getComponent("COMPONENT1")).andReturn(componentDescriptor1).once();

    final KerberosServiceDescriptor serviceDescriptor2 = createNiceMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor2.getComponent("COMPONENT2")).andReturn(componentDescriptor2).once();

    final KerberosDescriptor kerberosDescriptor = createNiceMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getService("SERVICE1")).andReturn(serviceDescriptor1).once();
    expect(kerberosDescriptor.getService("SERVICE2")).andReturn(serviceDescriptor2).once();

    //todo: extract method?
    if (getClusterDescriptor) {
      // needed to mock the static method fromJson()
      setupGetDescriptorFromCluster(kerberosDescriptor);
    } else if (getStackDescriptor) {
      setupGetDescriptorFromStack(kerberosDescriptor);
    }
    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();

    // This is a STRICT mock to help ensure that the end result is what we want.
    final RequestStageContainer requestStageContainer = createStrictMock(RequestStageContainer.class);
    // Update Configs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(2L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Destroy Principals Stage
    expect(requestStageContainer.getLastStageId()).andReturn(2L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Delete Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(2L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Clean-up/Finalize Stage
    expect(requestStageContainer.getLastStageId()).andReturn(3L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();

    replayAll();

    // Needed by infrastructure
    metaInfo.init();

    kerberosHelper.toggleKerberos(cluster, SecurityType.NONE, requestStageContainer);

    verifyAll();
  }

  private void testRegenerateKeytabs(final KerberosCredential kerberosCredential, boolean mockRequestStageContainer, final boolean testInvalidHost) throws Exception {

    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final StackId stackVersion = createNiceMock(StackId.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("host1").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1 = createMock(ServiceComponentHost.class);
    expect(sch1.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch1.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch1.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(sch1.getHostName()).andReturn("host1").anyTimes();

    final ServiceComponentHost sch2 = createMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").anyTimes();
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT2").anyTimes();
    expect(sch2.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch2.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
    expect(sch2.getStackVersion()).andReturn(stackVersion).anyTimes();
    expect(sch2.getHostName()).andReturn("host1").anyTimes();

    final Host host = createNiceMock(Host.class);
    expect(host.getHostName()).andReturn("host1").anyTimes();
    expect(host.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final ServiceComponentHost schKerberosClientInvalid;
    final ServiceComponentHost sch1a;
    final Host hostInvalid;
    if(testInvalidHost) {
      schKerberosClientInvalid = createMock(ServiceComponentHost.class);
      expect(schKerberosClientInvalid.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
      expect(schKerberosClientInvalid.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
      expect(schKerberosClientInvalid.getHostName()).andReturn("host2").anyTimes();
      expect(schKerberosClientInvalid.getState()).andReturn(State.INIT).anyTimes();

      sch1a = createMock(ServiceComponentHost.class);
      expect(sch1a.getServiceName()).andReturn("SERVICE1").anyTimes();
      expect(sch1a.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
      expect(sch1a.getSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
      expect(sch1a.getDesiredSecurityState()).andReturn(SecurityState.UNSECURED).anyTimes();
      expect(sch1a.getStackVersion()).andReturn(stackVersion).anyTimes();
      expect(sch1a.getHostName()).andReturn("host2").anyTimes();

      hostInvalid = createNiceMock(Host.class);
      expect(hostInvalid.getHostName()).andReturn("host1").anyTimes();
      expect(hostInvalid.getState()).andReturn(HostState.HEALTHY).anyTimes();
    }
    else {
      schKerberosClientInvalid = null;
      sch1a = null;
      hostInvalid = null;
    }

    Map<String, ServiceComponentHost> map = new HashMap<String, ServiceComponentHost>();
    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    map.put("host1", schKerberosClient);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();

    if(testInvalidHost) {
      map.put("host2", schKerberosClientInvalid);
    }

    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(map).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
        .times(3);

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getSecurityType()).andReturn(SecurityType.KERBEROS).anyTimes();
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("host1"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(schKerberosClient);
            add(sch1);
            add(sch2);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("host1", host);

            if (testInvalidHost) {
              put("host2", hostInvalid);
            }
          }
        })
        .once();
    expect(clusters.getHost("host1"))
        .andReturn(host)
        .once();

    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "host1"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(2);

    final KerberosPrincipalDescriptor principalDescriptor1 = createNiceMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1.getValue()).andReturn("component1/_HOST@${realm}").once();
    expect(principalDescriptor1.getType()).andReturn(KerberosPrincipalType.SERVICE).once();
    expect(principalDescriptor1.getConfiguration()).andReturn("service1-site/component1.kerberos.principal").once();

    final KerberosPrincipalDescriptor principalDescriptor2 = createNiceMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor2.getValue()).andReturn("component2/${host}@${realm}").once();
    expect(principalDescriptor2.getType()).andReturn(KerberosPrincipalType.SERVICE).once();
    expect(principalDescriptor2.getConfiguration()).andReturn("service2-site/component2.kerberos.principal").once();

    final KerberosKeytabDescriptor keytabDescriptor1 = createNiceMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor1.getFile()).andReturn("${keytab_dir}/service1.keytab").once();
    expect(keytabDescriptor1.getOwnerName()).andReturn("service1").once();
    expect(keytabDescriptor1.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor1.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor1.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor1.getConfiguration()).andReturn("service1-site/component1.keytab.file").once();
    expect(keytabDescriptor1.isCachable()).andReturn(false).once();

    final KerberosKeytabDescriptor keytabDescriptor2 = createNiceMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor2.getFile()).andReturn("${keytab_dir}/service2.keytab").once();
    expect(keytabDescriptor2.getOwnerName()).andReturn("service2").once();
    expect(keytabDescriptor2.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor2.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor2.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor2.getConfiguration()).andReturn("service2-site/component2.keytab.file").once();
    expect(keytabDescriptor2.isCachable()).andReturn(false).once();

    final KerberosIdentityDescriptor identityDescriptor1 = createNiceMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1.getPrincipalDescriptor()).andReturn(principalDescriptor1).once();
    expect(identityDescriptor1.getKeytabDescriptor()).andReturn(keytabDescriptor1).once();

    final KerberosIdentityDescriptor identityDescriptor2 = createNiceMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor2.getPrincipalDescriptor()).andReturn(principalDescriptor2).once();
    expect(identityDescriptor2.getKeytabDescriptor()).andReturn(keytabDescriptor2).once();

    final KerberosComponentDescriptor componentDescriptor1 = createNiceMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor1.getIdentities(true)).
        andReturn(new ArrayList<KerberosIdentityDescriptor>() {{
          add(identityDescriptor1);
        }}).once();

    final KerberosComponentDescriptor componentDescriptor2 = createNiceMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor2.getIdentities(true)).
        andReturn(new ArrayList<KerberosIdentityDescriptor>() {{
          add(identityDescriptor2);
        }}).once();

    final KerberosServiceDescriptor serviceDescriptor1 = createNiceMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor1.getComponent("COMPONENT1")).andReturn(componentDescriptor1).once();

    final KerberosServiceDescriptor serviceDescriptor2 = createNiceMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor2.getComponent("COMPONENT2")).andReturn(componentDescriptor2).once();

    final KerberosDescriptor kerberosDescriptor = createNiceMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getService("SERVICE1")).andReturn(serviceDescriptor1).once();
    expect(kerberosDescriptor.getService("SERVICE2")).andReturn(serviceDescriptor2).once();

    setupGetDescriptorFromCluster(kerberosDescriptor);

    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();


    final RequestStageContainer requestStageContainer;
    if (mockRequestStageContainer) {
      // This is a STRICT mock to help ensure that the end result is what we want.
      requestStageContainer = createStrictMock(RequestStageContainer.class);
      // Create Principals Stage
      expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
      expect(requestStageContainer.getId()).andReturn(1L).once();
      requestStageContainer.addStages(anyObject(List.class));
      expectLastCall().once();
      // Create Keytabs Stage
      expect(requestStageContainer.getLastStageId()).andReturn(0L).anyTimes();
      expect(requestStageContainer.getId()).andReturn(1L).once();
      requestStageContainer.addStages(anyObject(List.class));
      expectLastCall().once();
      // Distribute Keytabs Stage
      expect(requestStageContainer.getLastStageId()).andReturn(1L).anyTimes();
      expect(requestStageContainer.getId()).andReturn(1L).once();
      requestStageContainer.addStages(anyObject(List.class));
      expectLastCall().once();
      // Clean-up/Finalize Stage
      expect(requestStageContainer.getLastStageId()).andReturn(3L).anyTimes();
      expect(requestStageContainer.getId()).andReturn(1L).once();
      requestStageContainer.addStages(anyObject(List.class));
      expectLastCall().once();
    } else {
      requestStageContainer = null;
    }

    replayAll();

    // Needed by infrastructure
    metaInfo.init();

    Assert.assertNotNull(kerberosHelper.executeCustomOperations(cluster, Collections.singletonMap("regenerate_keytabs", "true"), requestStageContainer));

    verifyAll();
  }

  @Test
  public void testIsClusterKerberosEnabled_false() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);
    Cluster cluster = createStrictMock(Cluster.class);

    expect(cluster.getSecurityType()).andReturn(SecurityType.NONE);

    replay(cluster);
    assertFalse(kerberosHelper.isClusterKerberosEnabled(cluster));
    verify(cluster);
  }

  @Test
  public void testIsClusterKerberosEnabled_true() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);
    Cluster cluster = createStrictMock(Cluster.class);

    expect(cluster.getSecurityType()).andReturn(SecurityType.KERBEROS);

    replay(cluster);
    assertTrue(kerberosHelper.isClusterKerberosEnabled(cluster));
    verify(cluster);
  }

  private void setClusterController() throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    Class<?> c = kerberosHelper.getClass();

    Field f = c.getDeclaredField("clusterController");
    f.setAccessible(true);
    f.set(kerberosHelper, clusterController);
  }

  private void setupGetDescriptorFromCluster(KerberosDescriptor kerberosDescriptor) throws Exception {
    ResourceProvider resourceProvider = createStrictMock(ResourceProvider.class);
    expect(clusterController.ensureResourceProvider(Resource.Type.Artifact)).andReturn(resourceProvider).once();

    Resource resource = createStrictMock(Resource.class);
    Set<Resource> result = Collections.singleton(resource);

    Capture<Predicate> predicateCapture = new Capture<Predicate>();
    Capture<Request> requestCapture = new Capture<Request>();

    //todo: validate captures

//      PredicateBuilder pb = new PredicateBuilder();
//      Predicate predicate = pb.begin().property("Artifacts/cluster_name").equals(cluster.getClusterName()).and().
//          property(ArtifactResourceProvider.ARTIFACT_NAME_PROPERTY).equals("kerberos_descriptor").
//          end().toPredicate();

    expect(resourceProvider.getResources(capture(requestCapture),
        capture(predicateCapture))).andReturn(result).once();

    Map<String, Map<String, Object>> resourcePropertiesMap = createStrictMock(Map.class);
    expect(resourcePropertiesMap.get(ArtifactResourceProvider.ARTIFACT_DATA_PROPERTY))
        .andReturn(Collections.<String, Object>emptyMap()).once();
    expect(resourcePropertiesMap.get(ArtifactResourceProvider.ARTIFACT_DATA_PROPERTY + "/properties"))
        .andReturn(Collections.<String, Object>emptyMap()).once();

    expect(resource.getPropertiesMap()).andReturn(resourcePropertiesMap).once();

    expect(kerberosDescriptorFactory.createInstance(anyObject(Map.class)))
        .andReturn(kerberosDescriptor).once();
  }

  private void setupGetDescriptorFromStack(KerberosDescriptor kerberosDescriptor) throws Exception {
    ResourceProvider resourceProvider = createStrictMock(ResourceProvider.class);
    expect(clusterController.ensureResourceProvider(Resource.Type.Artifact)).andReturn(resourceProvider).once();

    Capture<Predicate> predicateCapture = new Capture<Predicate>();
    Capture<Request> requestCapture = new Capture<Request>();

    //todo: validate captures

//      PredicateBuilder pb = new PredicateBuilder();
//      Predicate predicate = pb.begin().property("Artifacts/cluster_name").equals(cluster.getClusterName()).and().
//          property(ArtifactResourceProvider.ARTIFACT_NAME_PROPERTY).equals("kerberos_descriptor").
//          end().toPredicate();

    expect(resourceProvider.getResources(capture(requestCapture),
        capture(predicateCapture))).andReturn(null).once();

    // cluster.getCurrentStackVersion expectation is already specified in main test method
    expect(metaInfo.getKerberosDescriptor("HDP", "2.2")).andReturn(kerberosDescriptor).once();
  }

  private void testEnsureIdentities(final KerberosCredential kerberosCredential) throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("hostA").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1A = createMock(ServiceComponentHost.class);
    expect(sch1A.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1A.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1A.getHostName()).andReturn("hostA").anyTimes();

    final ServiceComponentHost sch1B = createMock(ServiceComponentHost.class);
    expect(sch1B.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1B.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1B.getHostName()).andReturn("hostB").anyTimes();

    final ServiceComponentHost sch1C = createMock(ServiceComponentHost.class);
    expect(sch1C.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1C.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1C.getHostName()).andReturn("hostC").anyTimes();

    final ServiceComponentHost sch2 = createStrictMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").anyTimes();
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();

    final ServiceComponentHost sch3 = createStrictMock(ServiceComponentHost.class);
    expect(sch3.getServiceName()).andReturn("SERVICE3").anyTimes();
    expect(sch3.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();
    expect(sch3.getHostName()).andReturn("hostA").anyTimes();

    final Host hostA = createNiceMock(Host.class);
    expect(hostA.getHostName()).andReturn("hostA").anyTimes();
    expect(hostA.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final Host hostB = createNiceMock(Host.class);
    expect(hostB.getHostName()).andReturn("hostB").anyTimes();
    expect(hostB.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final Host hostC = createNiceMock(Host.class);
    expect(hostC.getHostName()).andReturn("hostC").anyTimes();
    expect(hostC.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(Collections.singletonMap("hostA", schKerberosClient)).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
        .times(5);

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(5);

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(5);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("hostA"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(sch1A);
            add(sch2);
            add(sch3);
            add(schKerberosClient);
          }
        })
        .once();
    expect(cluster.getServiceComponentHosts("hostB"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(sch1B);
            add(schKerberosClient);
          }
        })
        .once();
    expect(cluster.getServiceComponentHosts("hostC"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(sch1C);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("hostA", hostA);
            put("hostB", hostB);
            put("hostC", hostC);
          }
        })
        .once();
    expect(clusters.getHost("hostA"))
        .andReturn(hostA)
        .times(1);
    expect(clusters.getHost("hostB"))
        .andReturn(hostB)
        .times(1);

    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "hostA"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "hostB"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "hostC"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .times(3);
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(4);

    final KerberosPrincipalDescriptor principalDescriptor1a = createMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1a.getValue()).andReturn("component1a/_HOST@${realm}").anyTimes();
    expect(principalDescriptor1a.getType()).andReturn(KerberosPrincipalType.SERVICE).anyTimes();
    expect(principalDescriptor1a.getLocalUsername()).andReturn(null).anyTimes();
    expect(principalDescriptor1a.getConfiguration()).andReturn("service1b-site/component1.kerberos.principal").anyTimes();

    final KerberosPrincipalDescriptor principalDescriptor1b = createMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1b.getValue()).andReturn("component1b/_HOST@${realm}").anyTimes();
    expect(principalDescriptor1b.getType()).andReturn(KerberosPrincipalType.SERVICE).anyTimes();
    expect(principalDescriptor1b.getLocalUsername()).andReturn(null).anyTimes();
    expect(principalDescriptor1b.getConfiguration()).andReturn("service1b-site/component1.kerberos.principal").anyTimes();

    final KerberosPrincipalDescriptor principalDescriptor3 = createMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor3.getValue()).andReturn("component3/${host}@${realm}").anyTimes();
    expect(principalDescriptor3.getType()).andReturn(KerberosPrincipalType.SERVICE).anyTimes();
    expect(principalDescriptor3.getLocalUsername()).andReturn(null).anyTimes();
    expect(principalDescriptor3.getConfiguration()).andReturn("service3-site/component3.kerberos.principal").anyTimes();

    final KerberosKeytabDescriptor keytabDescriptor1 = createMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor1.getFile()).andReturn("${keytab_dir}/service1.keytab").times(3);
    expect(keytabDescriptor1.getOwnerName()).andReturn("service1").times(3);
    expect(keytabDescriptor1.getOwnerAccess()).andReturn("rw").times(3);
    expect(keytabDescriptor1.getGroupName()).andReturn("hadoop").times(3);
    expect(keytabDescriptor1.getGroupAccess()).andReturn("").times(3);
    expect(keytabDescriptor1.getConfiguration()).andReturn("service1-site/component1.keytab.file").times(3);
    expect(keytabDescriptor1.isCachable()).andReturn(false).times(3);

    final KerberosKeytabDescriptor keytabDescriptor3 = createMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor3.getFile()).andReturn("${keytab_dir}/service3.keytab").once();
    expect(keytabDescriptor3.getOwnerName()).andReturn("service3").once();
    expect(keytabDescriptor3.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor3.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor3.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor3.getConfiguration()).andReturn("service3-site/component3.keytab.file").once();
    expect(keytabDescriptor3.isCachable()).andReturn(false).times(1);

    final KerberosIdentityDescriptor identityDescriptor1a = createMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1a.getName()).andReturn("identity1a").anyTimes();
    expect(identityDescriptor1a.getPrincipalDescriptor()).andReturn(principalDescriptor1a).anyTimes();
    expect(identityDescriptor1a.getKeytabDescriptor()).andReturn(keytabDescriptor1).anyTimes();

    final KerberosIdentityDescriptor identityDescriptor1b = createMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1b.getName()).andReturn("identity1b").anyTimes();
    expect(identityDescriptor1b.getPrincipalDescriptor()).andReturn(principalDescriptor1b).anyTimes();

    final KerberosIdentityDescriptor identityDescriptor3 = createMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor3.getName()).andReturn("identity3").anyTimes();
    expect(identityDescriptor3.getPrincipalDescriptor()).andReturn(principalDescriptor3).anyTimes();
    expect(identityDescriptor3.getKeytabDescriptor()).andReturn(keytabDescriptor3).anyTimes();

    final ArrayList<KerberosIdentityDescriptor> identityDescriptors1 = new ArrayList<KerberosIdentityDescriptor>() {{
      add(identityDescriptor1a);
      add(identityDescriptor1b);
    }};
    final KerberosComponentDescriptor componentDescriptor1 = createStrictMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor1.getIdentities(true)).andReturn(identityDescriptors1).times(1);
    expect(componentDescriptor1.getConfigurations(true)).andReturn(null).times(1);
    expect(componentDescriptor1.getIdentities(true)).andReturn(identityDescriptors1).times(1);
    expect(componentDescriptor1.getConfigurations(true)).andReturn(null).times(1);
    expect(componentDescriptor1.getIdentities(true)).andReturn(identityDescriptors1).times(1);
    expect(componentDescriptor1.getConfigurations(true)).andReturn(null).times(1);
    expect(componentDescriptor1.getIdentities(true)).andReturn(identityDescriptors1).times(1);
    expect(componentDescriptor1.getAuthToLocalProperties()).andReturn(null).times(1);

    final ArrayList<KerberosIdentityDescriptor> identityDescriptors3 = new ArrayList<KerberosIdentityDescriptor>() {{
      add(identityDescriptor3);
    }};
    final KerberosComponentDescriptor componentDescriptor3 = createStrictMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor3.getIdentities(true)).andReturn(identityDescriptors3).times(1);
    expect(componentDescriptor3.getConfigurations(true)).andReturn(null).times(1);

    final KerberosServiceDescriptor serviceDescriptor1 = createMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor1.getIdentities(true)).andReturn(null).times(4);
    expect(serviceDescriptor1.getName()).andReturn("SERVICE1").times(1);
    expect(serviceDescriptor1.getComponents()).andReturn(new HashMap<String, KerberosComponentDescriptor>(){{
      put("COMPONENT1", componentDescriptor1);
    }}).times(1);
    expect(serviceDescriptor1.getComponent("COMPONENT1")).andReturn(componentDescriptor1).times(3);
    expect(serviceDescriptor1.getAuthToLocalProperties()).andReturn(null).once();

    final KerberosServiceDescriptor serviceDescriptor3 = createMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor3.getIdentities(true)).andReturn(null).times(1);
    expect(serviceDescriptor3.getName()).andReturn("SERVICE3").times(1);
    expect(serviceDescriptor3.getComponent("COMPONENT3")).andReturn(componentDescriptor3).once();

    final KerberosDescriptor kerberosDescriptor = createStrictMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getProperties()).andReturn(null).once();
    expect(kerberosDescriptor.getService("SERVICE1")).andReturn(serviceDescriptor1).times(3);
    expect(kerberosDescriptor.getService("SERVICE3")).andReturn(serviceDescriptor3).times(1);
    expect(kerberosDescriptor.getIdentities()).andReturn(null).once();
    expect(kerberosDescriptor.getAuthToLocalProperties()).andReturn(null).once();

    expect(kerberosDescriptor.getServices()).andReturn(new HashMap<String, KerberosServiceDescriptor>()
    {{
      put("SERVICE1", serviceDescriptor1);
      put("SERVICE3", serviceDescriptor3);
    }}).once();

    setupGetDescriptorFromCluster(kerberosDescriptor);

    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();

    // This is a STRICT mock to help ensure that the end result is what we want.
    final RequestStageContainer requestStageContainer = createStrictMock(RequestStageContainer.class);
    // Create Principals Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Create Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Distribute Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Clean-up/Finalize Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();

    replayAll();

    // Needed by infrastructure
    injector.getInstance(AmbariMetaInfo.class).init();

    Map<String, Collection<String>> serviceComponentFilter = new HashMap<String, Collection<String>>();
    Collection<String> identityFilter = Arrays.asList("identity1a", "identity3");

    serviceComponentFilter.put("SERVICE3", Collections.singleton("COMPONENT3"));
    serviceComponentFilter.put("SERVICE1", null);

    kerberosHelper.ensureIdentities(cluster, serviceComponentFilter, identityFilter, null, requestStageContainer);

    verifyAll();
  }

  private void testDeleteIdentities(final KerberosCredential kerberosCredential) throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("host1").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1 = createMock(ServiceComponentHost.class);
    expect(sch1.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1.getHostName()).andReturn("host1").anyTimes();

    final ServiceComponentHost sch2 = createStrictMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").anyTimes();
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();

    final ServiceComponentHost sch3 = createStrictMock(ServiceComponentHost.class);
    expect(sch3.getServiceName()).andReturn("SERVICE3").anyTimes();
    expect(sch3.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();
    expect(sch3.getHostName()).andReturn("host1").anyTimes();

    final Host host = createNiceMock(Host.class);
    expect(host.getHostName()).andReturn("host1").anyTimes();
    expect(host.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(Collections.singletonMap("host1", schKerberosClient)).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
        .times(3);

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(3);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("host1"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(sch1);
            add(sch2);
            add(sch3);
            add(schKerberosClient);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("host1", host);
          }
        })
        .once();
    expect(clusters.getHost("host1"))
        .andReturn(host)
        .once();

    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "host1"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(2);

    final KerberosPrincipalDescriptor principalDescriptor1a = createMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1a.getValue()).andReturn("component1a/_HOST@${realm}").anyTimes();
    expect(principalDescriptor1a.getType()).andReturn(KerberosPrincipalType.SERVICE).anyTimes();
    expect(principalDescriptor1a.getLocalUsername()).andReturn(null).anyTimes();
    expect(principalDescriptor1a.getConfiguration()).andReturn("service1b-site/component1.kerberos.principal").anyTimes();

    final KerberosPrincipalDescriptor principalDescriptor1b = createMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor1b.getValue()).andReturn("component1b/_HOST@${realm}").anyTimes();
    expect(principalDescriptor1b.getType()).andReturn(KerberosPrincipalType.SERVICE).anyTimes();
    expect(principalDescriptor1b.getLocalUsername()).andReturn(null).anyTimes();
    expect(principalDescriptor1b.getConfiguration()).andReturn("service1b-site/component1.kerberos.principal").anyTimes();

    final KerberosPrincipalDescriptor principalDescriptor3 = createMock(KerberosPrincipalDescriptor.class);
    expect(principalDescriptor3.getValue()).andReturn("component3/${host}@${realm}").anyTimes();
    expect(principalDescriptor3.getType()).andReturn(KerberosPrincipalType.SERVICE).anyTimes();
    expect(principalDescriptor3.getLocalUsername()).andReturn(null).anyTimes();
    expect(principalDescriptor3.getConfiguration()).andReturn("service3-site/component3.kerberos.principal").anyTimes();

    final KerberosKeytabDescriptor keytabDescriptor1 = createMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor1.getFile()).andReturn("${keytab_dir}/service1.keytab").once();
    expect(keytabDescriptor1.getOwnerName()).andReturn("service1").once();
    expect(keytabDescriptor1.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor1.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor1.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor1.getConfiguration()).andReturn("service1-site/component1.keytab.file").once();
    expect(keytabDescriptor1.isCachable()).andReturn(false).once();

    final KerberosKeytabDescriptor keytabDescriptor3 = createMock(KerberosKeytabDescriptor.class);
    expect(keytabDescriptor3.getFile()).andReturn("${keytab_dir}/service3.keytab").once();
    expect(keytabDescriptor3.getOwnerName()).andReturn("service3").once();
    expect(keytabDescriptor3.getOwnerAccess()).andReturn("rw").once();
    expect(keytabDescriptor3.getGroupName()).andReturn("hadoop").once();
    expect(keytabDescriptor3.getGroupAccess()).andReturn("").once();
    expect(keytabDescriptor3.getConfiguration()).andReturn("service3-site/component3.keytab.file").once();
    expect(keytabDescriptor3.isCachable()).andReturn(false).once();

    final KerberosIdentityDescriptor identityDescriptor1a = createMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1a.getName()).andReturn("identity1a").anyTimes();
    expect(identityDescriptor1a.getPrincipalDescriptor()).andReturn(principalDescriptor1a).anyTimes();
    expect(identityDescriptor1a.getKeytabDescriptor()).andReturn(keytabDescriptor1).anyTimes();

    final KerberosIdentityDescriptor identityDescriptor1b = createMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor1b.getName()).andReturn("identity1b").anyTimes();
    expect(identityDescriptor1b.getPrincipalDescriptor()).andReturn(principalDescriptor1b).anyTimes();

    final KerberosIdentityDescriptor identityDescriptor3 = createMock(KerberosIdentityDescriptor.class);
    expect(identityDescriptor3.getName()).andReturn("identity3").anyTimes();
    expect(identityDescriptor3.getPrincipalDescriptor()).andReturn(principalDescriptor3).anyTimes();
    expect(identityDescriptor3.getKeytabDescriptor()).andReturn(keytabDescriptor3).anyTimes();

    final ArrayList<KerberosIdentityDescriptor> identityDescriptors1 = new ArrayList<KerberosIdentityDescriptor>() {{
      add(identityDescriptor1a);
      add(identityDescriptor1b);
    }};
    final KerberosComponentDescriptor componentDescriptor1 = createStrictMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor1.getIdentities(true)).andReturn(identityDescriptors1).times(1);
    expect(componentDescriptor1.getConfigurations(true)).andReturn(null).times(1);
    expect(componentDescriptor1.getIdentities(true)).andReturn(identityDescriptors1).times(1);
    expect(componentDescriptor1.getAuthToLocalProperties()).andReturn(null).times(1);

    final ArrayList<KerberosIdentityDescriptor> identityDescriptors3 = new ArrayList<KerberosIdentityDescriptor>() {{
      add(identityDescriptor3);
    }};
    final KerberosComponentDescriptor componentDescriptor3 = createStrictMock(KerberosComponentDescriptor.class);
    expect(componentDescriptor3.getIdentities(true)).andReturn(identityDescriptors3).times(1);
    expect(componentDescriptor3.getConfigurations(true)).andReturn(null).times(1);

    final KerberosServiceDescriptor serviceDescriptor1 = createMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor1.getIdentities(true)).andReturn(null).times(1);
    expect(serviceDescriptor1.getName()).andReturn("SERVICE1").times(1);
    expect(serviceDescriptor1.getIdentities(true)).andReturn(null).times(1);
    expect(serviceDescriptor1.getComponents()).andReturn(new HashMap<String, KerberosComponentDescriptor>(){{
      put("COMPONENT1", componentDescriptor1);
    }}).times(1);
    expect(serviceDescriptor1.getComponent("COMPONENT1")).andReturn(componentDescriptor1).once();
    expect(serviceDescriptor1.getAuthToLocalProperties()).andReturn(null).once();

    final KerberosServiceDescriptor serviceDescriptor3 = createMock(KerberosServiceDescriptor.class);
    expect(serviceDescriptor3.getIdentities(true)).andReturn(null).times(1);
    expect(serviceDescriptor3.getName()).andReturn("SERVICE3").times(1);
    expect(serviceDescriptor3.getComponent("COMPONENT3")).andReturn(componentDescriptor3).once();

    final KerberosDescriptor kerberosDescriptor = createStrictMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getProperties()).andReturn(null).once();
    expect(kerberosDescriptor.getService("SERVICE1")).andReturn(serviceDescriptor1).once();
    expect(kerberosDescriptor.getService("SERVICE3")).andReturn(serviceDescriptor3).once();
    expect(kerberosDescriptor.getIdentities()).andReturn(null).once();
    expect(kerberosDescriptor.getAuthToLocalProperties()).andReturn(null).once();
    expect(kerberosDescriptor.getServices()).andReturn(new HashMap<String, KerberosServiceDescriptor>()
    {{
      put("SERVCE1", serviceDescriptor1);
      put("SERVCE2", serviceDescriptor3);
    }}).once();

    setupGetDescriptorFromCluster(kerberosDescriptor);

    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();

    // This is a STRICT mock to help ensure that the end result is what we want.
    final RequestStageContainer requestStageContainer = createStrictMock(RequestStageContainer.class);
    // Delete Principals Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Delete Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Clean-up/Finalize Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();

    replayAll();

    // Needed by infrastructure
    injector.getInstance(AmbariMetaInfo.class).init();

    Map<String, Collection<String>> serviceComponentFilter = new HashMap<String, Collection<String>>();
    Collection<String> identityFilter = Arrays.asList("identity1a", "identity3");

    serviceComponentFilter.put("SERVICE3", Collections.singleton("COMPONENT3"));
    serviceComponentFilter.put("SERVICE1", null);

    kerberosHelper.deleteIdentities(cluster, serviceComponentFilter, identityFilter, requestStageContainer);

    verifyAll();
  }

  private void testCreateTestIdentity(final KerberosCredential kerberosCredential) throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("host1").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1 = createMock(ServiceComponentHost.class);
    expect(sch1.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1.getHostName()).andReturn("host1").anyTimes();

    final ServiceComponentHost sch2 = createStrictMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").anyTimes();
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();

    final ServiceComponentHost sch3 = createStrictMock(ServiceComponentHost.class);
    expect(sch3.getServiceName()).andReturn("SERVICE3").anyTimes();
    expect(sch3.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();
    expect(sch3.getHostName()).andReturn("host1").anyTimes();

    final Host host = createNiceMock(Host.class);
    expect(host.getHostName()).andReturn("host1").anyTimes();
    expect(host.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(Collections.singletonMap("host1", schKerberosClient)).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
        .times(2);

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(2);

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(2);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("host1"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(sch1);
            add(sch2);
            add(sch3);
            add(schKerberosClient);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("host1", host);
          }
        })
        .once();
    expect(clusters.getHost("host1"))
        .andReturn(host)
        .once();

    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "host1"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(1);

    final KerberosDescriptor kerberosDescriptor = createStrictMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getProperties()).andReturn(null).once();

    setupGetDescriptorFromCluster(kerberosDescriptor);

    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();

    // This is a STRICT mock to help ensure that the end result is what we want.
    final RequestStageContainer requestStageContainer = createStrictMock(RequestStageContainer.class);
    // Create Principals Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Create Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Distribute Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Clean-up/Finalize Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();

    replayAll();

    // Needed by infrastructure
    injector.getInstance(AmbariMetaInfo.class).init();

    Map<String, String> commandParamsStage = new HashMap<String, String>();
    kerberosHelper.createTestIdentity(cluster, commandParamsStage, requestStageContainer);

    verifyAll();

    String serviceCheckID = (String)cluster.getSessionAttributes().get("_kerberos_internal_service_check_identifier");
    Assert.assertNotNull(serviceCheckID);

    Assert.assertTrue(commandParamsStage.containsKey("principal_name"));
    Assert.assertEquals("${cluster-env/smokeuser}_" + serviceCheckID + "@${realm}", commandParamsStage.get("principal_name"));

    Assert.assertTrue(commandParamsStage.containsKey("keytab_file"));
    Assert.assertEquals("${keytab_dir}/kerberos.service_check." + serviceCheckID + ".keytab", commandParamsStage.get("keytab_file"));
  }

  private void testDeleteTestIdentity(final KerberosCredential kerberosCredential) throws Exception {
    KerberosHelper kerberosHelper = injector.getInstance(KerberosHelper.class);

    final ServiceComponentHost schKerberosClient = createMock(ServiceComponentHost.class);
    expect(schKerberosClient.getServiceName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(schKerberosClient.getServiceComponentName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(schKerberosClient.getHostName()).andReturn("host1").anyTimes();
    expect(schKerberosClient.getState()).andReturn(State.INSTALLED).anyTimes();

    final ServiceComponentHost sch1 = createMock(ServiceComponentHost.class);
    expect(sch1.getServiceName()).andReturn("SERVICE1").anyTimes();
    expect(sch1.getServiceComponentName()).andReturn("COMPONENT1").anyTimes();
    expect(sch1.getHostName()).andReturn("host1").anyTimes();

    final ServiceComponentHost sch2 = createStrictMock(ServiceComponentHost.class);
    expect(sch2.getServiceName()).andReturn("SERVICE2").anyTimes();
    expect(sch2.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();

    final ServiceComponentHost sch3 = createStrictMock(ServiceComponentHost.class);
    expect(sch3.getServiceName()).andReturn("SERVICE3").anyTimes();
    expect(sch3.getServiceComponentName()).andReturn("COMPONENT3").anyTimes();
    expect(sch3.getHostName()).andReturn("host1").anyTimes();

    final Host host = createNiceMock(Host.class);
    expect(host.getHostName()).andReturn("host1").anyTimes();
    expect(host.getState()).andReturn(HostState.HEALTHY).anyTimes();

    final ServiceComponent serviceComponentKerberosClient = createNiceMock(ServiceComponent.class);
    expect(serviceComponentKerberosClient.getName()).andReturn(Role.KERBEROS_CLIENT.name()).anyTimes();
    expect(serviceComponentKerberosClient.getServiceComponentHosts()).andReturn(Collections.singletonMap("host1", schKerberosClient)).anyTimes();

    final Service serviceKerberos = createStrictMock(Service.class);
    expect(serviceKerberos.getName()).andReturn(Service.Type.KERBEROS.name()).anyTimes();
    expect(serviceKerberos.getServiceComponents())
        .andReturn(Collections.singletonMap(Role.KERBEROS_CLIENT.name(), serviceComponentKerberosClient))
        .times(2);

    final Service service1 = createStrictMock(Service.class);
    expect(service1.getName()).andReturn("SERVICE1").anyTimes();
    expect(service1.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(2);

    final Service service2 = createStrictMock(Service.class);
    expect(service2.getName()).andReturn("SERVICE2").anyTimes();
    expect(service2.getServiceComponents())
        .andReturn(Collections.<String, ServiceComponent>emptyMap())
        .times(2);

    final Map<String, String> kerberosEnvProperties = createNiceMock(Map.class);
    expect(kerberosEnvProperties.get("kdc_type")).andReturn("mit-kdc").anyTimes();
    expect(kerberosEnvProperties.get("realm")).andReturn("FOOBAR.COM").anyTimes();

    final Config kerberosEnvConfig = createNiceMock(Config.class);
    expect(kerberosEnvConfig.getProperties()).andReturn(kerberosEnvProperties).anyTimes();

    final Map<String, String> krb5ConfProperties = createNiceMock(Map.class);

    final Config krb5ConfConfig = createNiceMock(Config.class);
    expect(krb5ConfConfig.getProperties()).andReturn(krb5ConfProperties).anyTimes();

    final Cluster cluster = createNiceMock(Cluster.class);
    expect(cluster.getDesiredConfigByType("krb5-conf")).andReturn(krb5ConfConfig).anyTimes();
    expect(cluster.getDesiredConfigByType("kerberos-env")).andReturn(kerberosEnvConfig).anyTimes();
    expect(cluster.getClusterName()).andReturn("c1").anyTimes();
    expect(cluster.getServices())
        .andReturn(new HashMap<String, Service>() {
          {
            put(Service.Type.KERBEROS.name(), serviceKerberos);
            put("SERVICE1", service1);
            put("SERVICE2", service2);
          }
        })
        .anyTimes();
    expect(cluster.getServiceComponentHosts("host1"))
        .andReturn(new ArrayList<ServiceComponentHost>() {
          {
            add(sch1);
            add(sch2);
            add(sch3);
            add(schKerberosClient);
          }
        })
        .once();
    expect(cluster.getCurrentStackVersion())
        .andReturn(new StackId("HDP", "2.2"))
        .anyTimes();
    expect(cluster.getSessionAttributes()).andReturn(new HashMap<String, Object>() {{
      if (kerberosCredential != null) {
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PRINCIPAL, kerberosCredential.getPrincipal());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_PASSWORD, kerberosCredential.getPassword());
        put("kerberos_admin/" + KerberosCredential.KEY_NAME_KEYTAB, kerberosCredential.getKeytab());
      }
    }}).anyTimes();

    final Clusters clusters = injector.getInstance(Clusters.class);
    expect(clusters.getHostsForCluster("c1"))
        .andReturn(new HashMap<String, Host>() {
          {
            put("host1", host);
          }
        })
        .once();
    expect(clusters.getHost("host1"))
        .andReturn(host)
        .once();

    final AmbariManagementController ambariManagementController = injector.getInstance(AmbariManagementController.class);
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, "host1"))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.findConfigurationTagsWithOverrides(cluster, null))
        .andReturn(Collections.<String, Map<String, String>>emptyMap())
        .once();
    expect(ambariManagementController.getRoleCommandOrder(cluster))
        .andReturn(createNiceMock(RoleCommandOrder.class))
        .once();

    final ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);
    expect(configHelper.getEffectiveConfigProperties(anyObject(Cluster.class), anyObject(Map.class)))
        .andReturn(new HashMap<String, Map<String, String>>() {
          {
            put("cluster-env", new HashMap<String, String>() {{
              put("kerberos_domain", "FOOBAR.COM");
            }});
          }
        })
        .times(1);

    final KerberosDescriptor kerberosDescriptor = createStrictMock(KerberosDescriptor.class);
    expect(kerberosDescriptor.getProperties()).andReturn(null).once();

    setupGetDescriptorFromCluster(kerberosDescriptor);

    final StageFactory stageFactory = injector.getInstance(StageFactory.class);
    expect(stageFactory.createNew(anyLong(), anyObject(String.class), anyObject(String.class),
        anyLong(), anyObject(String.class), anyObject(String.class), anyObject(String.class),
        anyObject(String.class)))
        .andAnswer(new IAnswer<Stage>() {
          @Override
          public Stage answer() throws Throwable {
            Stage stage = createNiceMock(Stage.class);

            expect(stage.getHostRoleCommands())
                .andReturn(Collections.<String, Map<String, HostRoleCommand>>emptyMap())
                .anyTimes();
            replay(stage);
            return stage;
          }
        })
        .anyTimes();

    // This is a STRICT mock to help ensure that the end result is what we want.
    final RequestStageContainer requestStageContainer = createStrictMock(RequestStageContainer.class);
    // Delete  Principals Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Delete Keytabs Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();
    // Clean-up/Finalize Stage
    expect(requestStageContainer.getLastStageId()).andReturn(-1L).anyTimes();
    expect(requestStageContainer.getId()).andReturn(1L).once();
    requestStageContainer.addStages(anyObject(List.class));
    expectLastCall().once();

    replayAll();

    // Needed by infrastructure
    injector.getInstance(AmbariMetaInfo.class).init();

    String serviceCheckID = "some_random_value";

    cluster.getSessionAttributes().put("_kerberos_internal_service_check_identifier", serviceCheckID);

    Map<String, String> commandParamsStage = new HashMap<String, String>();
    commandParamsStage.put("principal_name", "${cluster-env/smokeuser}_" + serviceCheckID + "@${realm}");
    commandParamsStage.put("keytab_file", "${keytab_dir}/kerberos.service_check." + serviceCheckID + ".keytab");

    kerberosHelper.deleteTestIdentity(cluster, commandParamsStage, requestStageContainer);

    verifyAll();
  }
}

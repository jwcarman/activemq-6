/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.tests.util;

import javax.management.MBeanServer;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.api.core.ActiveMQException;
import org.apache.activemq.api.core.Pair;
import org.apache.activemq.api.core.SimpleString;
import org.apache.activemq.api.core.TransportConfiguration;
import org.apache.activemq.api.core.client.ClientConsumer;
import org.apache.activemq.api.core.client.ClientMessage;
import org.apache.activemq.api.core.client.ClientProducer;
import org.apache.activemq.api.core.client.ClientSession;
import org.apache.activemq.api.core.client.ClientSessionFactory;
import org.apache.activemq.api.core.client.ActiveMQClient;
import org.apache.activemq.api.core.client.ServerLocator;
import org.apache.activemq.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.core.client.impl.Topology;
import org.apache.activemq.core.client.impl.TopologyMemberImpl;
import org.apache.activemq.core.config.Configuration;
import org.apache.activemq.core.journal.PreparedTransactionInfo;
import org.apache.activemq.core.journal.RecordInfo;
import org.apache.activemq.core.journal.SequentialFileFactory;
import org.apache.activemq.core.journal.impl.JournalFile;
import org.apache.activemq.core.journal.impl.JournalImpl;
import org.apache.activemq.core.journal.impl.JournalReaderCallback;
import org.apache.activemq.core.journal.impl.NIOSequentialFileFactory;
import org.apache.activemq.core.paging.PagingStore;
import org.apache.activemq.core.postoffice.Binding;
import org.apache.activemq.core.postoffice.Bindings;
import org.apache.activemq.core.postoffice.PostOffice;
import org.apache.activemq.core.postoffice.QueueBinding;
import org.apache.activemq.core.postoffice.impl.LocalQueueBinding;
import org.apache.activemq.core.remoting.impl.invm.InVMRegistry;
import org.apache.activemq.core.remoting.impl.invm.TransportConstants;
import org.apache.activemq.core.server.ActiveMQComponent;
import org.apache.activemq.core.server.ActiveMQServer;
import org.apache.activemq.core.server.ActiveMQServerLogger;
import org.apache.activemq.core.server.ActiveMQServers;
import org.apache.activemq.core.server.NodeManager;
import org.apache.activemq.core.server.Queue;
import org.apache.activemq.core.server.cluster.ClusterConnection;
import org.apache.activemq.core.server.cluster.RemoteQueueBinding;
import org.apache.activemq.core.server.impl.Activation;
import org.apache.activemq.core.server.impl.ActiveMQServerImpl;
import org.apache.activemq.core.server.impl.SharedNothingBackupActivation;
import org.apache.activemq.core.settings.impl.AddressFullMessagePolicy;
import org.apache.activemq.core.settings.impl.AddressSettings;
import org.apache.activemq.spi.core.security.ActiveMQSecurityManager;
import org.apache.activemq.spi.core.security.ActiveMQSecurityManagerImpl;
import org.apache.activemq.utils.UUIDGenerator;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;

/**
 * Base class with basic utilities on starting up a basic server
 */
public abstract class ServiceTestBase extends UnitTestCase
{

   /**
    * Add a "sendCallNumber" property to messages sent using helper classes. Meant to help in
    * debugging.
    */
   private static final String SEND_CALL_NUMBER = "sendCallNumber";
   protected static final long WAIT_TIMEOUT = 20000;
   private int sendMsgCount = 0;

   @Override
   @After
   public void tearDown() throws Exception
   {
      super.tearDown();
      if (InVMRegistry.instance.size() > 0)
      {
         fail("InVMREgistry size > 0");
      }
   }

   @Override
   @Before
   public void setUp() throws Exception
   {
      sendMsgCount = 0;
      super.setUp();
   }

   /**
    * @param queue
    * @throws InterruptedException
    */
   protected void waitForNotPaging(Queue queue) throws InterruptedException
   {
      waitForNotPaging(queue.getPageSubscription().getPagingStore());
   }

   protected void waitForNotPaging(PagingStore store) throws InterruptedException
   {
      long timeout = System.currentTimeMillis() + 10000;
      while (timeout > System.currentTimeMillis() && store.isPaging())
      {
         Thread.sleep(100);
      }
      assertFalse(store.isPaging());
   }

   protected Topology waitForTopology(final ActiveMQServer server, final int nodes) throws Exception
   {
      return waitForTopology(server, nodes, -1, WAIT_TIMEOUT);
   }

   protected Topology waitForTopology(final ActiveMQServer server, final int nodes, final int backups) throws Exception
   {
      return waitForTopology(server, nodes, backups, WAIT_TIMEOUT);
   }

   protected Topology waitForTopology(final ActiveMQServer server, final int liveNodes, final int backupNodes, final long timeout) throws Exception
   {
      ActiveMQServerLogger.LOGGER.debug("waiting for " + liveNodes + " on the topology for server = " + server);

      long start = System.currentTimeMillis();

      Set<ClusterConnection> ccs = server.getClusterManager().getClusterConnections();

      if (ccs.size() != 1)
      {
         throw new IllegalStateException("You need a single cluster connection on this version of waitForTopology on ServiceTestBase");
      }

      Topology topology = server.getClusterManager().getDefaultConnection(null).getTopology();

      int liveNodesCount = 0;

      int backupNodesCount = 0;


      do
      {
         liveNodesCount = 0;
         backupNodesCount = 0;

         for (TopologyMemberImpl member : topology.getMembers())
         {
            if (member.getLive() != null)
            {
               liveNodesCount++;
            }
            if (member.getBackup() != null)
            {
               backupNodesCount++;
            }
         }

         if ((liveNodes == -1 || liveNodes == liveNodesCount) && (backupNodes == -1 || backupNodes == backupNodesCount))
         {
            return topology;
         }

         Thread.sleep(10);
      }
      while (System.currentTimeMillis() - start < timeout);

      String msg = "Timed out waiting for cluster topology of live=" + liveNodes + ",backup=" + backupNodes +
         " (received live=" + liveNodesCount + ", backup=" + backupNodesCount +
         ") topology = " +
         topology.describe() +
         ")";

      ActiveMQServerLogger.LOGGER.error(msg);

      throw new Exception(msg);
   }


   protected void waitForTopology(final ActiveMQServer server, String clusterConnectionName, final int nodes, final long timeout) throws Exception
   {
      ActiveMQServerLogger.LOGGER.debug("waiting for " + nodes + " on the topology for server = " + server);

      long start = System.currentTimeMillis();

      ClusterConnection clusterConnection = server.getClusterManager().getClusterConnection(clusterConnectionName);


      Topology topology = clusterConnection.getTopology();

      do
      {
         if (nodes == topology.getMembers().size())
         {
            return;
         }

         Thread.sleep(10);
      }
      while (System.currentTimeMillis() - start < timeout);

      String msg = "Timed out waiting for cluster topology of " + nodes +
         " (received " +
         topology.getMembers().size() +
         ") topology = " +
         topology +
         ")";

      ActiveMQServerLogger.LOGGER.error(msg);

      throw new Exception(msg);
   }

   protected static final void waitForComponent(final ActiveMQComponent component, final long seconds) throws InterruptedException
   {
      long time = System.currentTimeMillis();
      long toWait = seconds * 1000;
      while (!component.isStarted())
      {
         Thread.sleep(50);
         if (System.currentTimeMillis() > (time + toWait))
         {
            fail("component did not start within timeout of " + seconds);
         }
      }
   }

   protected static final Map<String, Object> generateParams(final int node, final boolean netty)
   {
      Map<String, Object> params = new HashMap<String, Object>();

      if (netty)
      {
         params.put(org.apache.activemq.core.remoting.impl.netty.TransportConstants.PORT_PROP_NAME,
                    org.apache.activemq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + node);
      }
      else
      {
         params.put(org.apache.activemq.core.remoting.impl.invm.TransportConstants.SERVER_ID_PROP_NAME, node);
      }

      return params;
   }

   protected static final TransportConfiguration getNettyAcceptorTransportConfiguration(final boolean live)
   {
      if (live)
      {
         return new TransportConfiguration(NETTY_ACCEPTOR_FACTORY);
      }

      Map<String, Object> server1Params = new HashMap<String, Object>();

      server1Params.put(org.apache.activemq.core.remoting.impl.netty.TransportConstants.PORT_PROP_NAME,
                        org.apache.activemq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);

      return new TransportConfiguration(NETTY_ACCEPTOR_FACTORY, server1Params);
   }

   protected static final TransportConfiguration getNettyConnectorTransportConfiguration(final boolean live)
   {
      if (live)
      {
         return new TransportConfiguration(NETTY_CONNECTOR_FACTORY);
      }

      Map<String, Object> server1Params = new HashMap<String, Object>();

      server1Params.put(org.apache.activemq.core.remoting.impl.netty.TransportConstants.PORT_PROP_NAME,
                        org.apache.activemq.core.remoting.impl.netty.TransportConstants.DEFAULT_PORT + 1);
      return new TransportConfiguration(NETTY_CONNECTOR_FACTORY, server1Params);
   }

   protected static final TransportConfiguration createTransportConfiguration(boolean netty, boolean acceptor,
                                                                              Map<String, Object> params)
   {
      String className;
      if (netty)
      {
         if (acceptor)
         {
            className = NETTY_ACCEPTOR_FACTORY;
         }
         else
         {
            className = NETTY_CONNECTOR_FACTORY;
         }
      }
      else
      {
         if (acceptor)
         {
            className = INVM_ACCEPTOR_FACTORY;
         }
         else
         {
            className = INVM_CONNECTOR_FACTORY;
         }
      }
      if (params == null)
         params = new HashMap<String, Object>();
      return new TransportConfiguration(className, params);
   }

   private final ActiveMQServerLogger log = ActiveMQServerLogger.LOGGER;

   protected void waitForServer(ActiveMQServer server) throws InterruptedException
   {
      if (server == null)
         return;
      final long wait = 5000;
      long timetowait = System.currentTimeMillis() + wait;
      while (!server.isStarted() && System.currentTimeMillis() < timetowait)
      {
         Thread.sleep(50);
      }

      if (!server.isStarted())
      {
         log.info(threadDump("Server didn't start"));
         fail("server didn't start: " + server);
      }

      if (!server.getHAPolicy().isBackup())
      {
         if (!server.waitForActivation(wait, TimeUnit.MILLISECONDS))
            fail("Server didn't initialize: " + server);
      }
   }

   protected void waitForServerToStop(ActiveMQServer server) throws InterruptedException
   {
      if (server == null)
         return;
      final long wait = 5000;
      long timetowait = System.currentTimeMillis() + wait;
      while (server.isStarted() && System.currentTimeMillis() < timetowait)
      {
         Thread.sleep(50);
      }

      if (server.isStarted())
      {
         log.info(threadDump("Server didn't start"));
         fail("server didnt start: " + server);
      }
   }

   /**
    * @param backup
    */
   public static final void waitForRemoteBackupSynchronization(final ActiveMQServer backup)
   {
      waitForRemoteBackup(null, 10, true, backup);
   }

   /**
    * @param sessionFactoryP
    * @param seconds
    * @param waitForSync
    * @param backup
    */
   public static final void waitForRemoteBackup(ClientSessionFactory sessionFactoryP, int seconds,
                                                boolean waitForSync, final ActiveMQServer backup)
   {
      ClientSessionFactoryInternal sessionFactory = (ClientSessionFactoryInternal)sessionFactoryP;
      final ActiveMQServerImpl actualServer = (ActiveMQServerImpl) backup;
      final long toWait = seconds * 1000;
      final long time = System.currentTimeMillis();
      int loop = 0;
      while (true)
      {
         Activation activation = actualServer.getActivation();
         boolean isReplicated = !backup.getHAPolicy().isSharedStore();
         boolean isRemoteUpToDate = true;
         if (isReplicated)
         {
            if (activation instanceof SharedNothingBackupActivation)
            {
               isRemoteUpToDate = ((SharedNothingBackupActivation) activation).isRemoteBackupUpToDate();
            }
            else
            {
               //we may have already failed over and changed the Activation
               if (actualServer.isStarted())
               {
                  //let it fail a few time to have time to start stopping in the case of waiting to failback
                  isRemoteUpToDate = loop++ > 10;
               }
               //we could be waiting to failback or restart if the server is stopping
               else
               {
                  isRemoteUpToDate = false;
               }
            }
         }
         if ((sessionFactory == null || sessionFactory.getBackupConnector() != null) &&
               (isRemoteUpToDate || !waitForSync) &&
            (!waitForSync || actualServer.getBackupManager() != null && actualServer.getBackupManager().isBackupAnnounced()))
         {
            break;
         }
         if (System.currentTimeMillis() > (time + toWait))
         {
            fail("backup started? (" + actualServer.isStarted() + "). Finished synchronizing (" +
                  (activation) + "). SessionFactory!=null ? " + (sessionFactory != null) +
                    " || sessionFactory.getBackupConnector()==" +
                    (sessionFactory != null ? sessionFactory.getBackupConnector() : "not-applicable"));
         }
         try
         {
            Thread.sleep(100);
         }
         catch (InterruptedException e)
         {
            fail(e.getMessage());
         }
      }
   }

   public static final void waitForRemoteBackup(ClientSessionFactory sessionFactory, int seconds)
   {
      ClientSessionFactoryInternal factoryInternal = (ClientSessionFactoryInternal) sessionFactory;
      final long toWait = seconds * 1000;
      final long time = System.currentTimeMillis();
      while (true)
      {
         if (factoryInternal.getBackupConnector() != null)
         {
            break;
         }
         if (System.currentTimeMillis() > (time + toWait))
         {
            fail("Backup wasn't located");
         }
         try
         {
            Thread.sleep(100);
         }
         catch (InterruptedException e)
         {
            fail(e.getMessage());
         }
      }
   }

   protected final ActiveMQServer
   createServer(final boolean realFiles,
                final Configuration configuration,
                final int pageSize,
                final int maxAddressSize,
                final Map<String, AddressSettings> settings,
                final MBeanServer mbeanServer)
   {
      ActiveMQServer server;

      if (realFiles)
      {
         server = ActiveMQServers.newActiveMQServer(configuration, mbeanServer, true);
      }
      else
      {
         server = ActiveMQServers.newActiveMQServer(configuration, mbeanServer, false);
      }
      try
      {
         for (Map.Entry<String, AddressSettings> setting : settings.entrySet())
         {
            server.getAddressSettingsRepository().addMatch(setting.getKey(), setting.getValue());
         }

         AddressSettings defaultSetting = new AddressSettings();
         defaultSetting.setPageSizeBytes(pageSize);
         defaultSetting.setMaxSizeBytes(maxAddressSize);

         server.getAddressSettingsRepository().addMatch("#", defaultSetting);

         return server;
      }
      finally
      {
         addServer(server);
      }
   }

   protected final ActiveMQServer createServer(final boolean realFiles,
                                              final Configuration configuration,
                                              final int pageSize,
                                              final int maxAddressSize,
                                              final Map<String, AddressSettings> settings)
   {
      return createServer(realFiles, configuration, pageSize, maxAddressSize, AddressFullMessagePolicy.PAGE, settings);
   }

   protected final ActiveMQServer createServer(final boolean realFiles,
                                              final Configuration configuration,
                                              final int pageSize,
                                              final int maxAddressSize,
                                              final AddressFullMessagePolicy fullPolicy,
                                              final Map<String, AddressSettings> settings)
   {
      ActiveMQServer server = addServer(ActiveMQServers.newActiveMQServer(configuration, realFiles));
      if (settings != null)
      {
         for (Map.Entry<String, AddressSettings> setting : settings.entrySet())
         {
            server.getAddressSettingsRepository().addMatch(setting.getKey(), setting.getValue());
         }
      }

      AddressSettings defaultSetting = new AddressSettings();
      defaultSetting.setPageSizeBytes(pageSize);
      defaultSetting.setMaxSizeBytes(maxAddressSize);
      defaultSetting.setAddressFullMessagePolicy(fullPolicy);

      server.getAddressSettingsRepository().addMatch("#", defaultSetting);

      return server;
   }


   protected final ActiveMQServer createServer(final boolean realFiles,
                                              Configuration conf,
                                              MBeanServer mbeanServer)
   {
      return createServer(realFiles, conf, mbeanServer, new HashMap<String, AddressSettings>());
   }

   protected final ActiveMQServer
   createServer(final boolean realFiles,
                final Configuration configuration,
                final MBeanServer mbeanServer,
                final Map<String, AddressSettings> settings)
   {
      ActiveMQServer server;

      if (realFiles)
      {
         server = ActiveMQServers.newActiveMQServer(configuration, mbeanServer);
      }
      else
      {
         server = ActiveMQServers.newActiveMQServer(configuration, mbeanServer, false);
      }
      try
      {
         for (Map.Entry<String, AddressSettings> setting : settings.entrySet())
         {
            server.getAddressSettingsRepository().addMatch(setting.getKey(), setting.getValue());
         }

         AddressSettings defaultSetting = new AddressSettings();
         server.getAddressSettingsRepository().addMatch("#", defaultSetting);


         return server;
      }
      finally
      {
         addServer(server);
      }
   }

   protected final ActiveMQServer createServer(final boolean realFiles) throws Exception
   {
      return createServer(realFiles, false);
   }

   protected final ActiveMQServer createServer(final boolean realFiles, final boolean netty) throws Exception
   {
      return createServer(realFiles, createDefaultConfig(netty), -1, -1, new HashMap<String, AddressSettings>());
   }

   protected ActiveMQServer createServer(final boolean realFiles, final Configuration configuration)
   {
      return createServer(realFiles, configuration, -1, -1, new HashMap<String, AddressSettings>());
   }

   protected final ActiveMQServer createServer(final Configuration configuration)
   {
      return createServer(configuration.isPersistenceEnabled(), configuration, -1, -1,
                          new HashMap<String, AddressSettings>());
   }

   protected ActiveMQServer createInVMFailoverServer(final boolean realFiles,
                                                    final Configuration configuration,
                                                    final NodeManager nodeManager,
                                                    final int id)
   {
      return createInVMFailoverServer(realFiles,
                                      configuration,
                                      -1,
                                      -1,
                                      new HashMap<String, AddressSettings>(),
                                      nodeManager,
                                      id);
   }

   protected ActiveMQServer createInVMFailoverServer(final boolean realFiles,
                                                    final Configuration configuration,
                                                    final int pageSize,
                                                    final int maxAddressSize,
                                                    final Map<String, AddressSettings> settings,
                                                    NodeManager nodeManager,
                                                    final int id)
   {
      ActiveMQServer server;
      ActiveMQSecurityManager securityManager = new ActiveMQSecurityManagerImpl();
      configuration.setPersistenceEnabled(realFiles);
      server = new InVMNodeManagerServer(configuration,
                                         ManagementFactory.getPlatformMBeanServer(),
                                         securityManager,
                                         nodeManager);

      try
      {
         server.setIdentity("Server " + id);

         for (Map.Entry<String, AddressSettings> setting : settings.entrySet())
         {
            server.getAddressSettingsRepository().addMatch(setting.getKey(), setting.getValue());
         }

         AddressSettings defaultSetting = new AddressSettings();
         defaultSetting.setPageSizeBytes(pageSize);
         defaultSetting.setMaxSizeBytes(maxAddressSize);

         server.getAddressSettingsRepository().addMatch("#", defaultSetting);

         return server;
      }
      finally
      {
         addServer(server);
      }
   }

   protected ActiveMQServer createColocatedInVMFailoverServer(final boolean realFiles,
                                                    final Configuration configuration,
                                                    NodeManager liveNodeManager,
                                                    NodeManager backupNodeManager,
                                                    final int id)
   {
      return createColocatedInVMFailoverServer(realFiles,
            configuration,
            -1,
            -1,
            new HashMap<String, AddressSettings>(),
            liveNodeManager,
            backupNodeManager,
            id);
   }

   protected ActiveMQServer createColocatedInVMFailoverServer(final boolean realFiles,
                                                    final Configuration configuration,
                                                    final int pageSize,
                                                    final int maxAddressSize,
                                                    final Map<String, AddressSettings> settings,
                                                    NodeManager liveNodeManager,
                                                    NodeManager backupNodeManager,
                                                    final int id)
   {
      ActiveMQServer server;
      ActiveMQSecurityManager securityManager = new ActiveMQSecurityManagerImpl();
      configuration.setPersistenceEnabled(realFiles);
      server = new ColocatedActiveMQServer(configuration,
            ManagementFactory.getPlatformMBeanServer(),
            securityManager,
            liveNodeManager,
            backupNodeManager);

      try
      {
         server.setIdentity("Server " + id);

         for (Map.Entry<String, AddressSettings> setting : settings.entrySet())
         {
            server.getAddressSettingsRepository().addMatch(setting.getKey(), setting.getValue());
         }

         AddressSettings defaultSetting = new AddressSettings();
         defaultSetting.setPageSizeBytes(pageSize);
         defaultSetting.setMaxSizeBytes(maxAddressSize);

         server.getAddressSettingsRepository().addMatch("#", defaultSetting);

         return server;
      }
      finally
      {
         addServer(server);
      }
   }

   protected ActiveMQServer createServer(final boolean realFiles,
                                        final Configuration configuration,
                                        final ActiveMQSecurityManager securityManager)
   {
      ActiveMQServer server;

      if (realFiles)
      {
         server = ActiveMQServers.newActiveMQServer(configuration,
                                                    ManagementFactory.getPlatformMBeanServer(),
                                                    securityManager);
      }
      else
      {
         server = ActiveMQServers.newActiveMQServer(configuration,
                                                    ManagementFactory.getPlatformMBeanServer(),
                                                    securityManager,
                                                    false);
      }
      try
      {
         Map<String, AddressSettings> settings = new HashMap<String, AddressSettings>();

         for (Map.Entry<String, AddressSettings> setting : settings.entrySet())
         {
            server.getAddressSettingsRepository().addMatch(setting.getKey(), setting.getValue());
         }

         AddressSettings defaultSetting = new AddressSettings();

         server.getAddressSettingsRepository().addMatch("#", defaultSetting);

         return server;
      }
      finally
      {
         addServer(server);
      }
   }

   protected ActiveMQServer createClusteredServerWithParams(final boolean isNetty,
                                                           final int index,
                                                           final boolean realFiles,
                                                           final Map<String, Object> params) throws Exception
   {
      String acceptor = isNetty ? NETTY_ACCEPTOR_FACTORY : INVM_ACCEPTOR_FACTORY;
      return createServer(realFiles, createDefaultConfig(index, params, acceptor), -1, -1,
                          new HashMap<String, AddressSettings>());
   }

   protected ActiveMQServer createClusteredServerWithParams(final boolean isNetty,
                                                           final int index,
                                                           final boolean realFiles,
                                                           final int pageSize,
                                                           final int maxAddressSize,
                                                           final Map<String, Object> params) throws Exception
   {
      return createServer(realFiles, createDefaultConfig(index, params, (isNetty ? NETTY_ACCEPTOR_FACTORY : INVM_ACCEPTOR_FACTORY)),
                          pageSize,
                          maxAddressSize,
                          new HashMap<String, AddressSettings>());
   }

   protected ServerLocator createFactory(final boolean isNetty) throws Exception
   {
      if (isNetty)
      {
         return createNettyNonHALocator();
      }
      else
      {
         return createInVMNonHALocator();
      }
   }

   protected void createQueue(final String address, final String queue) throws Exception
   {
      ServerLocator locator = createInVMNonHALocator();
      ClientSessionFactory sf = locator.createSessionFactory();
      ClientSession session = sf.createSession();
      try
      {
         session.createQueue(address, queue);
      }
      finally
      {
         session.close();
         closeSessionFactory(sf);
         closeServerLocator(locator);
      }
   }

   protected final ServerLocator createInVMLocator(final int serverID)
   {
      TransportConfiguration tnspConfig = createInVMTransportConnectorConfig(serverID, UUIDGenerator.getInstance().generateStringUUID());

      ServerLocator locator = ActiveMQClient.createServerLocatorWithHA(tnspConfig);
      return addServerLocator(locator);
   }

   /**
    * @param serverID
    * @return
    */
   protected final TransportConfiguration createInVMTransportConnectorConfig(final int serverID, String name1)
   {
      Map<String, Object> server1Params = new HashMap<String, Object>();

      if (serverID != 0)
      {
         server1Params.put(TransportConstants.SERVER_ID_PROP_NAME, serverID);
      }

      TransportConfiguration tnspConfig = new TransportConfiguration(INVM_CONNECTOR_FACTORY, server1Params, name1);
      return tnspConfig;
   }

   public String getTextMessage(final ClientMessage m)
   {
      m.getBodyBuffer().resetReaderIndex();
      return m.getBodyBuffer().readString();
   }

   protected ClientMessage createBytesMessage(final ClientSession session,
                                              final byte type,
                                              final byte[] b,
                                              final boolean durable)
   {
      ClientMessage message = session.createMessage(type, durable, 0, System.currentTimeMillis(), (byte) 1);
      message.getBodyBuffer().writeBytes(b);
      return message;
   }

   /**
    * @param i
    * @param message
    * @throws Exception
    */
   protected void setBody(final int i, final ClientMessage message)
   {
      message.getBodyBuffer().writeString("message" + i);
   }

   /**
    * @param i
    * @param message
    */
   protected void assertMessageBody(final int i, final ClientMessage message)
   {
      Assert.assertEquals(message.toString(), "message" + i, message.getBodyBuffer().readString());
   }

   /**
    * Send durable messages with pre-specified body.
    *
    * @param session
    * @param producer
    * @param numMessages
    * @throws Exception
    */
   public final void
   sendMessages(ClientSession session, ClientProducer producer, int numMessages) throws ActiveMQException
   {
      for (int i = 0; i < numMessages; i++)
      {
         producer.send(createMessage(session, i, true));
      }
   }

   protected final ClientMessage
   createMessage(ClientSession session, int counter, boolean durable) throws ActiveMQException
   {
      ClientMessage message = session.createMessage(durable);
      setBody(counter, message);
      message.putIntProperty("counter", counter);
      message.putIntProperty(SEND_CALL_NUMBER, sendMsgCount++);
      return message;
   }

   protected final void
   receiveMessages(ClientConsumer consumer, final int start, final int msgCount, final boolean ack) throws ActiveMQException
   {
      for (int i = start; i < msgCount; i++)
      {
         ClientMessage message = consumer.receive(1000);
         Assert.assertNotNull("Expecting a message " + i, message);
         // sendCallNumber is just a debugging measure.
         Object prop = message.getObjectProperty(SEND_CALL_NUMBER);
         if (prop == null)
            prop = Integer.valueOf(-1);
         final int actual = message.getIntProperty("counter").intValue();
         Assert.assertEquals("expected=" + i + ". Got: property['counter']=" + actual + " sendNumber=" + prop, i,
                             actual);
         assertMessageBody(i, message);
         if (ack)
            message.acknowledge();
      }
   }

   /**
    * Reads a journal system and returns a Map<Integer,AtomicInteger> of recordTypes and the number of records per type,
    * independent of being deleted or not
    *
    * @param config
    * @return
    * @throws Exception
    */
   protected Pair<List<RecordInfo>, List<PreparedTransactionInfo>> loadMessageJournal(Configuration config) throws Exception
   {
      JournalImpl messagesJournal = null;
      try
      {
         SequentialFileFactory messagesFF = new NIOSequentialFileFactory(getJournalDir(), null);

         messagesJournal = new JournalImpl(config.getJournalFileSize(),
                                           config.getJournalMinFiles(),
                                           0,
                                           0,
                                           messagesFF,
                                           "activemq-data",
                                           "amq",
                                           1);
         final List<RecordInfo> committedRecords = new LinkedList<RecordInfo>();
         final List<PreparedTransactionInfo> preparedTransactions = new LinkedList<PreparedTransactionInfo>();

         messagesJournal.start();

         messagesJournal.load(committedRecords, preparedTransactions, null, false);

         return new Pair<List<RecordInfo>, List<PreparedTransactionInfo>>(committedRecords, preparedTransactions);
      }
      finally
      {
         try
         {
            if (messagesJournal != null)
            {
               messagesJournal.stop();
            }
         }
         catch (Throwable ignored)
         {
         }
      }

   }

   /**
    * Reads a journal system and returns a Map<Integer,AtomicInteger> of recordTypes and the number of records per type,
    * independent of being deleted or not
    *
    * @param config
    * @return
    * @throws Exception
    */
   protected HashMap<Integer, AtomicInteger> countJournal(Configuration config) throws Exception
   {
      final HashMap<Integer, AtomicInteger> recordsType = new HashMap<Integer, AtomicInteger>();
      SequentialFileFactory messagesFF = new NIOSequentialFileFactory(config.getJournalDirectory(), null);

      JournalImpl messagesJournal = new JournalImpl(config.getJournalFileSize(),
                                                    config.getJournalMinFiles(),
                                                    0,
                                                    0,
                                                    messagesFF,
                                                    "activemq-data",
                                                    "amq",
                                                    1);
      List<JournalFile> filesToRead = messagesJournal.orderFiles();

      for (JournalFile file : filesToRead)
      {
         JournalImpl.readJournalFile(messagesFF, file, new RecordTypeCounter(recordsType));
      }
      return recordsType;
   }

   /**
    * This method will load a journal and count the living records
    *
    * @param config
    * @return
    * @throws Exception
    */
   protected HashMap<Integer, AtomicInteger> countJournalLivingRecords(Configuration config) throws Exception
   {
      return internalCountJournalLivingRecords(config, true);
   }

   /**
    * This method will load a journal and count the living records
    *
    * @param config
    * @param messageJournal if true -> MessageJournal, false -> BindingsJournal
    * @return
    * @throws Exception
    */
   protected HashMap<Integer, AtomicInteger> internalCountJournalLivingRecords(Configuration config, boolean messageJournal) throws Exception
   {
      final HashMap<Integer, AtomicInteger> recordsType = new HashMap<Integer, AtomicInteger>();
      SequentialFileFactory ff;

      JournalImpl journal;

      if (messageJournal)
      {
         ff = new NIOSequentialFileFactory(getJournalDir(), null);
         journal = new JournalImpl(config.getJournalFileSize(),
                                   config.getJournalMinFiles(),
                                   0,
                                   0,
                                   ff,
                                   "activemq-data",
                                   "amq",
                                   1);
      }
      else
      {
         ff = new NIOSequentialFileFactory(getBindingsDir(), null);
         journal = new JournalImpl(1024 * 1024,
                                   2,
                                   config.getJournalCompactMinFiles(),
                                   config.getJournalCompactPercentage(),
                                   ff,
                                   "activemq-bindings",
                                   "bindings",
                                   1);
      }
      journal.start();


      final List<RecordInfo> committedRecords = new LinkedList<RecordInfo>();
      final List<PreparedTransactionInfo> preparedTransactions = new LinkedList<PreparedTransactionInfo>();


      journal.load(committedRecords, preparedTransactions, null, false);

      for (RecordInfo info : committedRecords)
      {
         Integer ikey = new Integer(info.getUserRecordType());
         AtomicInteger value = recordsType.get(ikey);
         if (value == null)
         {
            value = new AtomicInteger();
            recordsType.put(ikey, value);
         }
         value.incrementAndGet();

      }

      journal.stop();
      return recordsType;
   }

   private static final class RecordTypeCounter implements JournalReaderCallback
   {
      private final HashMap<Integer, AtomicInteger> recordsType;

      /**
       * @param recordsType
       */
      public RecordTypeCounter(HashMap<Integer, AtomicInteger> recordsType)
      {
         this.recordsType = recordsType;
      }

      AtomicInteger getType(byte key)
      {
         if (key == 0)
         {
            System.out.println("huh?");
         }
         Integer ikey = new Integer(key);
         AtomicInteger value = recordsType.get(ikey);
         if (value == null)
         {
            value = new AtomicInteger();
            recordsType.put(ikey, value);
         }
         return value;
      }

      public void onReadUpdateRecordTX(long transactionID, RecordInfo recordInfo) throws Exception
      {
         getType(recordInfo.getUserRecordType()).incrementAndGet();
      }

      public void onReadUpdateRecord(RecordInfo recordInfo) throws Exception
      {
         getType(recordInfo.getUserRecordType()).incrementAndGet();
      }

      public void onReadAddRecordTX(long transactionID, RecordInfo recordInfo) throws Exception
      {
         getType(recordInfo.getUserRecordType()).incrementAndGet();
      }

      public void onReadAddRecord(RecordInfo recordInfo) throws Exception
      {
         getType(recordInfo.getUserRecordType()).incrementAndGet();
      }

      public void onReadRollbackRecord(long transactionID) throws Exception
      {
      }

      public void onReadPrepareRecord(long transactionID, byte[] extraData, int numberOfRecords) throws Exception
      {
      }

      public void onReadDeleteRecordTX(long transactionID, RecordInfo recordInfo) throws Exception
      {
      }

      public void onReadDeleteRecord(long recordID) throws Exception
      {
      }

      public void onReadCommitRecord(long transactionID, int numberOfRecords) throws Exception
      {
      }

      public void markAsDataFile(JournalFile file0)
      {
      }
   }

   /**
    * @param server                the server where's being checked
    * @param address               the name of the address being checked
    * @param local                 if true we are looking for local bindings, false we are looking for remoting servers
    * @param expectedBindingCount  the expected number of counts
    * @param expectedConsumerCount the expected number of consumers
    * @param timeout               the timeout used on the check
    * @return
    * @throws Exception
    * @throws InterruptedException
    */
   protected boolean waitForBindings(final ActiveMQServer server,
                                     final String address,
                                     final boolean local,
                                     final int expectedBindingCount,
                                     final int expectedConsumerCount,
                                     long timeout) throws Exception
   {
      final PostOffice po = server.getPostOffice();

      long start = System.currentTimeMillis();

      int bindingCount = 0;

      int totConsumers = 0;

      do
      {
         bindingCount = 0;

         totConsumers = 0;

         Bindings bindings = po.getBindingsForAddress(new SimpleString(address));

         for (Binding binding : bindings.getBindings())
         {
            if (binding.isConnected() && (binding instanceof LocalQueueBinding && local || binding instanceof RemoteQueueBinding && !local))
            {
               QueueBinding qBinding = (QueueBinding) binding;

               bindingCount++;

               totConsumers += qBinding.consumerCount();
            }
         }

         if (bindingCount == expectedBindingCount && totConsumers == expectedConsumerCount)
         {
            return true;
         }

         Thread.sleep(10);
      }
      while (System.currentTimeMillis() - start < timeout);

      String msg = "Timed out waiting for bindings (bindingCount = " + bindingCount +
         " (expecting " +
         expectedBindingCount +
         ") " +
         ", totConsumers = " +
         totConsumers +
         " (expecting " +
         expectedConsumerCount +
         ")" +
         ")";

      log.error(msg);
      return false;
   }

   /**
    * Deleting a file on LargeDir is an asynchronous process. We need to keep looking for a while if
    * the file hasn't been deleted yet.
    */
   protected void validateNoFilesOnLargeDir(final int expect) throws Exception
   {
      File largeMessagesFileDir = new File(getLargeMessagesDir());

      // Deleting the file is async... we keep looking for a period of the time until the file is really gone
      long timeout = System.currentTimeMillis() + 5000;
      while (timeout > System.currentTimeMillis() && largeMessagesFileDir.listFiles().length != expect)
      {
         Thread.sleep(100);
      }


      if (expect != largeMessagesFileDir.listFiles().length)
      {
         for (File file : largeMessagesFileDir.listFiles())
         {
            System.out.println("File " + file + " still on ");
         }
      }

      Assert.assertEquals(expect, largeMessagesFileDir.listFiles().length);
   }

   /**
    * Deleting a file on LargeDire is an asynchronous process. Wee need to keep looking for a while
    * if the file hasn't been deleted yet
    */
   protected void validateNoFilesOnLargeDir() throws Exception
   {
      validateNoFilesOnLargeDir(0);
   }

   public void printBindings(ActiveMQServer server, String address) throws Exception
   {
      PostOffice po = server.getPostOffice();
      Bindings bindings = po.getBindingsForAddress(new SimpleString(address));

      System.err.println("=======================================================================");
      System.err.println("Binding information for address = " + address + " for server " + server);

      for (Binding binding : bindings.getBindings())
      {
         QueueBinding qBinding = (QueueBinding) binding;
         System.err.println("Binding = " + qBinding + ", queue=" + qBinding.getQueue());
      }

   }
}

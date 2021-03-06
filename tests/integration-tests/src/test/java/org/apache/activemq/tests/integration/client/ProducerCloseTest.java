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
package org.apache.activemq.tests.integration.client;
import org.apache.activemq.api.core.ActiveMQException;
import org.apache.activemq.api.core.ActiveMQExceptionType;
import org.junit.Before;

import org.junit.Test;

import org.junit.Assert;

import org.apache.activemq.api.core.TransportConfiguration;
import org.apache.activemq.api.core.client.ClientProducer;
import org.apache.activemq.api.core.client.ClientSession;
import org.apache.activemq.api.core.client.ClientSessionFactory;
import org.apache.activemq.api.core.client.ServerLocator;
import org.apache.activemq.core.config.Configuration;
import org.apache.activemq.core.remoting.impl.invm.InVMAcceptorFactory;
import org.apache.activemq.core.server.ActiveMQServer;
import org.apache.activemq.tests.util.RandomUtil;
import org.apache.activemq.tests.util.ServiceTestBase;
import org.apache.activemq.tests.util.UnitTestCase;

public class ProducerCloseTest extends ServiceTestBase
{

   private ActiveMQServer server;
   private ClientSessionFactory sf;
   private ClientSession session;
   private ServerLocator locator;

   @Test
   public void testCanNotUseAClosedProducer() throws Exception
   {
      final ClientProducer producer = session.createProducer(RandomUtil.randomSimpleString());

      Assert.assertFalse(producer.isClosed());

      producer.close();

      Assert.assertTrue(producer.isClosed());

      UnitTestCase.expectActiveMQException(ActiveMQExceptionType.OBJECT_CLOSED, new ActiveMQAction()
      {
         public void run() throws ActiveMQException
         {
            producer.send(session.createMessage(false));
         }
      });
   }

   // Package protected ---------------------------------------------

   // Protected -----------------------------------------------------

   @Override
   @Before
   public void setUp() throws Exception
   {
      super.setUp();
      Configuration config = createDefaultConfig()
         .addAcceptorConfiguration(new TransportConfiguration(InVMAcceptorFactory.class.getCanonicalName()))
         .setSecurityEnabled(false);
      server = createServer(false, config);
      server.start();
      locator = createInVMNonHALocator();
      sf = createSessionFactory(locator);
      session = sf.createSession(false, true, true);
   }
}

/*
 * Copyright (c) 2008-2014 the original author or authors.
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
package org.cometd.oort.aws;

import java.net.DatagramPacket;
import java.net.SocketTimeoutException;
import java.rmi.Naming;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.cometd.bayeux.server.BayeuxServer;
import org.cometd.oort.Oort;
import org.cometd.oort.OortTest;
import org.eclipse.jetty.server.Server;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;

public class OortURLRMIReceiverTest extends OortTest
{

	@Test
	public void checkAWSDependencies()
	{
		try
		{
			AmazonEC2Client ec2 = new AmazonEC2Client(new BasicAWSCredentials("fake", "fake"));
			Region region = Region.getRegion(Regions.fromName("eu-west-1"));
			ec2.setRegion(region);
			List<Filter> filtersList = new ArrayList<Filter>();
			ArrayList<String> values = new ArrayList<String>();
			values.add("running");
			filtersList.add(new Filter("instance-state-name", values));

			DescribeInstancesRequest describeInstancesRequest = new DescribeInstancesRequest().withFilters(filtersList);
			DescribeInstancesResult describeInstancesResult = ec2.describeInstances(describeInstancesRequest);
			List<Reservation> reservations = describeInstancesResult.getReservations();
			Set<Instance> instances = new HashSet<Instance>();
			// add all instances to a Set.
			for (Reservation reservation : reservations) {
				instances.addAll(reservation.getInstances());
			}
		}
		catch (AmazonServiceException x)
		{
		}
	}

	@Test
	public void testTwoComets() throws Exception
	{
		Server server1 = startServer(0);
		Oort oort1 = startOort(server1);
		OortUrlRMIReceiver cometUrlReceiver1 = new OortUrlRMIReceiver("127.0.0.1", 40000, 50000, 2000, oort1);

		Server server2 = startServer(0);
		Oort oort2 = startOort(server2);
		OortUrlRMIReceiver cometUrlReceiver2 = new OortUrlRMIReceiver("127.0.0.1", 40001, 50001, 2000, oort2);

		//Notifying oortURL 1 to oort2
		String rmiUrlTo2 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40001)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver2 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo2);
		awsCometUrlReceiver2.registerCometUrl(oort1.getURL());

		//Notifying oortURL 2 to oort1
		String rmiUrlTo1 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40000)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver1 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo1);
		awsCometUrlReceiver1.registerCometUrl(oort2.getURL());

		Assert.assertEquals(1, oort1.getKnownComets().size());
		Assert.assertEquals(1, oort2.getKnownComets().size());
		
		cometUrlReceiver1.dispose();
		cometUrlReceiver2.dispose();
	}

//	@Test
	public void testThreeComets() throws Exception
	{
		Server server1 = startServer(0);
		Oort oort1 = startOort(server1);
		OortUrlRMIReceiver cometUrlReceiver1 = new OortUrlRMIReceiver("127.0.0.1", 40000, 50000, 2000, oort1);

		Server server2 = startServer(0);
		Oort oort2 = startOort(server2);
		OortUrlRMIReceiver cometUrlReceiver2 = new OortUrlRMIReceiver("127.0.0.1", 40001, 50001, 2000, oort2);

		//Notifying oortURL 1 to oort2
		String rmiUrlTo2 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40001)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver2 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo2);
		awsCometUrlReceiver2.registerCometUrl(oort1.getURL());

		//Notifying oortURL 2 to oort1
		String rmiUrlTo1 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40000)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver1 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo1);
		awsCometUrlReceiver1.registerCometUrl(oort2.getURL());

		Assert.assertEquals(1, oort1.getKnownComets().size());
		Assert.assertEquals(1, oort2.getKnownComets().size());

		// Create another comet
		Server server3 = startServer(0);
		Oort oort3 = startOort(server3);
		OortUrlRMIReceiver cometUrlReceiver3 = new OortUrlRMIReceiver("127.0.0.1", 40002, 50002, 2000, oort3);

		//Notifying oortURL 1 and 2 to oort3
		String rmiUrlTo3 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40002)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver3 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo3);
		awsCometUrlReceiver3.registerCometUrl(oort1.getURL());
		awsCometUrlReceiver3.registerCometUrl(oort2.getURL());

		//Notifying oortURL 3 oort1
		awsCometUrlReceiver1.registerCometUrl(oort3.getURL());
		//Notifying oortURL 3 oort2
		awsCometUrlReceiver1.registerCometUrl(oort2.getURL());

		Assert.assertEquals(2, oort1.getKnownComets().size());
		Assert.assertEquals(2, oort2.getKnownComets().size());
		Assert.assertEquals(2, oort3.getKnownComets().size());

		stopOort(oort2);
		stopServer(server2);

		// Give some time to advertise
		Thread.sleep(2000);

		Assert.assertEquals(1, oort1.getKnownComets().size());
		Assert.assertEquals(oort3.getURL(), oort1.getKnownComets().iterator().next());
		Assert.assertEquals(1, oort3.getKnownComets().size());
		Assert.assertEquals(oort1.getURL(), oort3.getKnownComets().iterator().next());

		cometUrlReceiver1.dispose();
		cometUrlReceiver2.dispose();
		cometUrlReceiver3.dispose();
	}

	@Test
	public void testTwoCometsOneWithWrongURL() throws Exception
	{
		long connectTimeout = 2000;

		Server server1 = startServer(0);
		Oort oort1 = startOort(server1);
		OortUrlRMIReceiver cometUrlReceiver1 = new OortUrlRMIReceiver("127.0.0.1", 40000, 50000, connectTimeout, oort1);

		Server server2 = startServer(0);
		String wrongURL = "http://localhost:4/cometd";
		BayeuxServer bayeuxServer2 = (BayeuxServer)server2.getAttribute(BayeuxServer.ATTRIBUTE);
		Oort oort2 = new Oort(bayeuxServer2, wrongURL);
		oort2.start();
		OortUrlRMIReceiver cometUrlReceiver2 = new OortUrlRMIReceiver("127.0.0.1", 40001, 50001, connectTimeout, oort2);

		//Notifying oortURL 1 to oort2
		String rmiUrlTo2 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40001)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver2 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo2);
		awsCometUrlReceiver2.registerCometUrl(oort1.getURL());

		//Notifying oortURL 2 to oort1
		String rmiUrlTo1 = new StringBuilder()
		.append("//")
		.append("127.0.0.1")
		.append(":")
		.append(40000)
		.append("/")
		.append(OortUrlRMIReceiver.class.getName())
		.toString();
		OortUrlRMIReceiverIF awsCometUrlReceiver1 = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrlTo1);
		awsCometUrlReceiver1.registerCometUrl(oort2.getURL());

		// Give some time to let the timeout expires
		Thread.sleep(2 * connectTimeout);

		// At this point, A has given up trying to connect to B.
		// However, B was able to connect to A.
		// Node A is still advertising, but node B is not.

		Assert.assertEquals(0, oort1.getKnownComets().size());
		Assert.assertEquals(1, oort2.getKnownComets().size());

		// Now start nodeB with the right URL and notify the oort1
		oort2.stop();
		oort2 = startOort(server2);
		awsCometUrlReceiver1.registerCometUrl(oort2.getURL());

		Assert.assertEquals(1, oort1.getKnownComets().size());
		Assert.assertEquals(1, oort2.getKnownComets().size());

		cometUrlReceiver1.dispose();
		cometUrlReceiver2.dispose();
	}

}

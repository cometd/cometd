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

import java.rmi.Naming;
import java.rmi.UnmarshalException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

import org.cometd.oort.Oort;
import org.cometd.oort.OortConfigException;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.DescribeInstancesRequest;
import com.amazonaws.services.ec2.model.DescribeInstancesResult;
import com.amazonaws.services.ec2.model.Filter;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.ec2.model.Reservation;

public class OortAwsConfigurer extends AbstractLifeCycle
{
	private final Logger logger;
	private final Oort oort;

	private final RefreshAWSInstancesThread refreshAWSInstancesThread;
	private final OortUrlRMIReceiverIF cometUrlReceiver;
	private volatile boolean active;

	public OortAwsConfigurer(String rmiPeerAddress, int rmiPeerPort, int rmiRemotePeerPort, String accessKey, String secretKey, String region, int instancesRefreshInterval, HashMap<String, List<String>> filtersMap, long connectTimeout, Oort oort) throws OortConfigException
	{
		this.logger = LoggerFactory.getLogger(getClass() + "." + Oort.replacePunctuation(oort.getURL(), '_'));

		//		this.accessKey = accessKey;
		//		this.secretKey = secretKey;
		//		this.region = region;
		//		this.instancesRefreshInterval = instancesRefreshInterval;
		//		this.filtersMap = filtersMap;
		
		this.oort = oort;

		refreshAWSInstancesThread = new RefreshAWSInstancesThread(region, accessKey, secretKey, instancesRefreshInterval, filtersMap, rmiPeerAddress, rmiRemotePeerPort);
		try {
			cometUrlReceiver = new OortUrlRMIReceiver(rmiPeerAddress, rmiPeerPort, connectTimeout, oort);
		} catch (Exception e) {
			throw new OortConfigException(e);
		}
	}

	//	/**
	//	 * @return the AWS access key
	//	 */
	//	public String getAccessKey() {
	//		return accessKey;
	//	}
	//
	//	/**
	//	 * @return the AWS secret key
	//	 */
	//	public String getSecretKey() {
	//		return secretKey;
	//	}
	//
	//	/**
	//	 * @return the AWS region
	//	 */
	//	public String getRegion() {
	//		return region;
	//	}
	//
	//	/**
	//	 * @return the AWS instances refresh interval
	//	 */
	//	public int getInstancesRefreshInterval() {
	//		return instancesRefreshInterval;
	//	}
	//
	//	/**
	//	 * @return the AWS instances filters map
	//	 */
	//	public HashMap<String, List<String>> getFiltersMap() {
	//		return filtersMap;
	//	}
	//
	//
	//	/**
	//	 * @return the timeout to connect to another Oort node
	//	 */
	//	public long getConnectTimeout()
	//	{
	//		return connectTimeout;
	//	}

	//	/**
	//	 * @param accessKey the accessKey to connect to AWS API
	//	 */
	//	public void setAccessKey(String accessKey)
	//	{
	//		this.accessKey = accessKey;
	//	}
	//
	//	/**
	//	 * @param secretKey the secretKey to connect to AWS API
	//	 */
	//	public void setSecretKey(String secretKey)
	//	{
	//		this.secretKey = secretKey;
	//	}
	//
	//	/**
	//	 * @param instancesRefreshInterval the interval to refresh the AWS instances belonging to the cluster
	//	 */
	//	public void setInstancesRefreshInterval(int instancesRefreshInterval)
	//	{
	//		this.instancesRefreshInterval = instancesRefreshInterval;
	//	}
	//
	//	/**
	//	 * @param filtersMap the filtersMap used to select the AWS instances belonging to the cluster
	//	 */
	//	public void setFiltersMap(HashMap<String, List<String>> filtersMap)
	//	{
	//		this.filtersMap = filtersMap;
	//	}
	//
	//	/**
	//	 * @param region the region the AWS instances belong to
	//	 */
	//	public void setRegion(String region)
	//	{
	//		this.region = region;
	//	}
	//
	//	/**
	//	 * @param connectTimeout the timeout to connect to another Oort node
	//	 */
	//	public void setConnectTimeout(long connectTimeout)
	//	{
	//		this.connectTimeout = connectTimeout;
	//	}

	@Override
	protected void doStart() throws Exception
	{
		active = true;
		refreshAWSInstancesThread.start();
		if(logger.isDebugEnabled()) {
			logger.debug("Started AWS instances refreshing thread");		
		}
	}

	@Override
	protected void doStop() throws Exception
	{
		active = false;
		refreshAWSInstancesThread.interrupt();
		// We do not interrupt the receiver thread, because it may be processing
		// a received URL and we do not want to get ClosedByInterruptExceptions
	}

	public boolean join(long timeout)
	{
		try
		{
			refreshAWSInstancesThread.join(timeout);
			return true;
		}
		catch (InterruptedException x)
		{
			return false;
		}
	}

	private class RefreshAWSInstancesThread extends Thread
	{

		private final Logger logger;
		private final String rmiPeerAddress;
		private final int rmiPeerPort;
		private final long refreshInterval;
		private final AmazonEC2Client ec2;
		private final DescribeInstancesRequest describeInstancesRequest;

		/**
		 * Constructor
		 */         
		public RefreshAWSInstancesThread(String regionName, String accessKey, String secretKey, int refreshInterval, HashMap<String, List<String>> filtersMap, String rmiPeerAddress, int rmiPeerPort) {
			super("Oort-Refresh-AWS-Instances");
			setDaemon(true);

			this.logger = LoggerFactory.getLogger(getClass() + "." + Oort.replacePunctuation(oort.getURL(), '_'));

			this.refreshInterval = refreshInterval;
			this.rmiPeerAddress = rmiPeerAddress;
			this.rmiPeerPort = rmiPeerPort;
			ec2 = new AmazonEC2Client(new BasicAWSCredentials(accessKey, secretKey));
			Region region = Region.getRegion(Regions.fromName(regionName));
			ec2.setRegion(region);
			List<Filter> filtersList = new ArrayList<Filter>();
			Set<Entry<String, List<String>>> filterEntries = filtersMap.entrySet();
			for (Entry<String, List<String>> entry : filterEntries) {
				String filterName = entry.getKey();
				List<String> filterValues = entry.getValue();
				filtersList.add(new Filter(filterName, filterValues));
			}
			describeInstancesRequest = new DescribeInstancesRequest().withFilters(filtersList);

			if(logger.isDebugEnabled()) {
				logger.debug("Created AWS instances refreshing thread: [region: " + region + ", filters: " + filtersList + "]");		
			}

		}

		public void run()
		{
			logger.debug("Entering refresh AWS instances thread");
			try
			{
				//                final String cometURL = oort.getURL();
				//                byte[] cometURLBytes = cometURL.getBytes("UTF-8");

				while (active)
				{
					//comment for testing: start
					DescribeInstancesResult describeInstancesResult = ec2.describeInstances(describeInstancesRequest);
					List<Reservation> reservations = describeInstancesResult.getReservations();
					Set<Instance> instances = new HashSet<Instance>();
					// add all instances to a Set.
					for (Reservation reservation : reservations) {
						instances.addAll(reservation.getInstances());
					}
					if(instances.size() == 0) {
						logger.info("No instances found belonging to OORT cluster");
					}
					for (Instance ins : instances) {
						String ipAddress = ins.getPrivateIpAddress();
						//comment for testing: end

						// only for testing: start
						//						List<String> ipAddresses = new ArrayList<String>();
						//						ipAddresses.add("192.168.1.221");
						//						ipAddresses.add("192.168.1.224");
						//						for (String ipAddress : ipAddresses) {
						// only for testing: end

						if(ipAddress.equals(rmiPeerAddress)) {
							//skipping my address
							continue;
						}
						if(logger.isDebugEnabled()) {
							logger.debug("Notifying my oortURL to: " + ipAddress);
						}
						String rmiUrl = new StringBuilder()
						.append("//")
						.append(ipAddress)
						.append(":")
						.append(rmiPeerPort)
						.append("/")
						.append(OortUrlRMIReceiver.class.getName())
						.toString();

						try {
							OortUrlRMIReceiverIF awsCometUrlReceiver = (OortUrlRMIReceiverIF) Naming.lookup(rmiUrl);
							awsCometUrlReceiver.registerCometUrl(oort.getURL());
						} catch (UnmarshalException e) {
							String message = e.getMessage();
							if (message.contains("Read time out") || message.contains("Read timed out")) {
								logger.warn(this + " Unable to send message to remote peer due to socket read timeout. Message was: " + message);
							} else {
								logger.debug(this + " Unable to send message to remote peer.  Message was: " + message);
							}
						} catch (Exception e) {
							logger.debug("Error connecting to rmiUrl: " + rmiUrl + ". Initial cause was " + e.getMessage(), e);
						}
					}

					Thread.sleep(refreshInterval);
				}

			} catch (InterruptedException x) {
				if (active) {
					logger.error(x.getMessage());
				}
				// Do nothing, we're stopping
			}

			logger.debug("Exiting refresh AWS instances thread");
		}

	}
}

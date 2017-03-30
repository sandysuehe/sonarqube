/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */

package org.sonar.ce.cluster;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.core.HazelcastInstance;
import java.util.Map;
import java.util.Set;
import org.picocontainer.Startable;
import org.sonar.api.config.Settings;
import org.sonar.process.ProcessProperties;
import org.sonar.server.computation.taskprocessor.ChainingCallbackFactory;

import static com.google.common.base.Preconditions.checkState;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.lang.StringUtils.isNotEmpty;
import static org.sonar.process.cluster.ClusterConstants.CLIENT_UUIDS;
import static org.sonar.process.cluster.ClusterConstants.WORKER_UUIDS;

/**
 * This class will connect as a Hazelcast client to the local instance of Hazelcluster
 */
public class ClusterClient implements Startable {

  private final ChainingCallbackFactory ceChainingCallbackFactory;
  private final ClientConfig hzConfig;

  Map<String, Set<String>> workerUUIDS;
  private HazelcastInstance hzInstance;

  public ClusterClient(Settings settings, ChainingCallbackFactory ceChainingCallbackFactory) {
    boolean clusterEnabled = settings.getBoolean(ProcessProperties.CLUSTER_ENABLED);
    String clusterName = settings.getString(ProcessProperties.CLUSTER_NAME);
    String clusterLocalEndPoint = settings.getString(ProcessProperties.CLUSTER_LOCALENDPOINT);

    checkState(clusterEnabled, "Cluster is not enabled");
    checkState(isNotEmpty(clusterLocalEndPoint), "LocalEndPoint have not been set");
    checkState(isNotEmpty(clusterName), "sonar.cluster.name is missing");

    hzConfig = new ClientConfig();
    hzConfig.getGroupConfig().setName(clusterName);
    hzConfig.getNetworkConfig().addAddress(clusterLocalEndPoint);

    // Tweak HazelCast configuration
    hzConfig
      // Increase the number of tries
      .setProperty("hazelcast.tcp.join.port.try.count", "10")
      // Don't bind on all interfaces
      .setProperty("hazelcast.socket.bind.any", "false")
      // Don't phone home
      .setProperty("hazelcast.phone.home.enabled", "false")
      // Use slf4j for logging
      .setProperty("hazelcast.logging.type", "slf4j");

    // Create the Hazelcast instance
    this.ceChainingCallbackFactory = ceChainingCallbackFactory;
  }

  public Set<String> getWorkerUUIDs() {
    Set<String> connectedWorkerUUIDs = hzInstance.getSet(CLIENT_UUIDS);

    return workerUUIDS.entrySet().stream()
      .filter(e -> connectedWorkerUUIDs.contains(e.getKey()))
      .map(Map.Entry::getValue)
      .flatMap(Set::stream)
      .collect(toSet());
  }

  @Override
  public void start() {
    this.hzInstance = HazelcastClient.newHazelcastClient(hzConfig);
    this.workerUUIDS = hzInstance.getReplicatedMap(WORKER_UUIDS);
    this.workerUUIDS.put(hzInstance.getLocalEndpoint().getUuid(), ceChainingCallbackFactory.getChainingCallbackUUIDs());
  }

  @Override
  public void stop() {
    hzInstance.getReplicatedMap(CLIENT_UUIDS).remove(hzInstance.getLocalEndpoint().getUuid());
    workerUUIDS.remove(hzInstance.getLocalEndpoint().getUuid());
    // Shutdown Hazelcast properly
    hzInstance.shutdown();
  }
}

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

import com.hazelcast.core.HazelcastInstance;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.ExpectedException;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.sonar.api.config.MapSettings;
import org.sonar.api.config.PropertyDefinitions;
import org.sonar.api.config.Settings;
import org.sonar.process.NetworkUtils;
import org.sonar.process.ProcessProperties;
import org.sonar.server.computation.taskprocessor.CeProcessingSchedulerExecutorService;
import org.sonar.server.computation.taskprocessor.CeWorker;
import org.sonar.server.computation.taskprocessor.ChainingCallback;
import org.sonar.server.computation.taskprocessor.ChainingCallbackFactory;

import static java.util.Collections.unmodifiableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

public class ClusterClientTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public TestRule safeGuard = new DisableOnDebug(Timeout.seconds(10));

  private CeWorker worker = mock(CeWorker.class);
  private CeProcessingSchedulerExecutorService executorService = mock(CeProcessingSchedulerExecutorService.class);

  @Test
  public void missing_CLUSTER_ENABLED_throw_ISE() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Cluster is not enabled");

    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_ENABLED);
    ClusterClient clusterClient = new ClusterClient(settings, new ChainingCallbackFactoryTest());
    clusterClient.stop();
  }

  @Test
  public void missing_CLUSTER_NAME_throw_ISE() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("sonar.cluster.name is missing");

    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_NAME);
    ClusterClient clusterClient = new ClusterClient(settings, new ChainingCallbackFactoryTest());
    clusterClient.stop();
  }

  @Test
  public void missing_CLUSTER_LOCALENDPOINT_throw_ISE() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("LocalEndPoint have not been set");

    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_LOCALENDPOINT);
    ClusterClient clusterClient = new ClusterClient(settings, new ChainingCallbackFactoryTest());
    clusterClient.stop();
  }

  @Test
  public void client_must_connect_to_hazelcast() {
    int port = NetworkUtils.freePort();
    // Launch a fake Hazelcast instance
    HazelcastInstance hzInstance = HazelcastTestHelper.createHazelcastCluster("client_must_connect_to_hazelcast", port);
    Settings settings = createClusterSettings("client_must_connect_to_hazelcast", "localhost:" + port);
    ClusterClient clusterClient = new ClusterClient(settings, new ChainingCallbackFactoryTest());
    clusterClient.start();
    // No exception thrown
    clusterClient.stop();
  }

  @Test
  public void worker_uuids_must_be_synchronized_with_cluster() {
    int port = NetworkUtils.freePort();
    HazelcastInstance hzInstance = HazelcastTestHelper.createHazelcastCluster("client_must_connect_to_hazelcast", port);
    Settings settings = createClusterSettings("client_must_connect_to_hazelcast", "localhost:" + port);
    ChainingCallbackFactoryTest ceWorkerFactory = new ChainingCallbackFactoryTest();
    ClusterClient clusterClient = new ClusterClient(settings, ceWorkerFactory);

    // Add a two workers
    ceWorkerFactory.create(worker, executorService, 1, TimeUnit.MINUTES);
    ceWorkerFactory.create(worker, executorService, 1, TimeUnit.MINUTES);

    // Simulate the start of the container
    clusterClient.start();
    assertThat(clusterClient.getWorkerUUIDs()).isEqualTo(ceWorkerFactory.workerUUIDs);
    clusterClient.stop();
  }

  @Test
  public void getWorkerUUIDs_must_filter_absent_client() {
    int port = NetworkUtils.freePort();
    HazelcastInstance hzInstance = HazelcastTestHelper.createHazelcastCluster("client_must_connect_to_hazelcast", port);
    Settings settings = createClusterSettings("client_must_connect_to_hazelcast", "localhost:" + port);
    ChainingCallbackFactoryTest ceWorkerFactory = new ChainingCallbackFactoryTest();
    ClusterClient clusterClient = new ClusterClient(settings, ceWorkerFactory);
    // Add a two workers
    ceWorkerFactory.create(worker, executorService, 1, TimeUnit.MINUTES);
    ceWorkerFactory.create(worker, executorService, 1, TimeUnit.MINUTES);
    clusterClient.start();

    // Inject fake workerUUIDS
    clusterClient.workerUUIDS.put("NON_EXISTENT_UUID1", new HashSet<>(Arrays.asList("a", "b", "c")));
    clusterClient.workerUUIDS.put("NON_EXISTENT_UUID2", new HashSet<>(Arrays.asList("d", "e", "f")));

    assertThat(clusterClient.getWorkerUUIDs()).isEqualTo(ceWorkerFactory.workerUUIDs);
    clusterClient.stop();
  }

  private static Settings createClusterSettings(String name, String localEndPoint) {
    Properties properties = new Properties();
    properties.setProperty(ProcessProperties.CLUSTER_NAME, name);
    properties.setProperty(ProcessProperties.CLUSTER_LOCALENDPOINT, localEndPoint);
    properties.setProperty(ProcessProperties.CLUSTER_ENABLED, "true");
    return new MapSettings(new PropertyDefinitions()).addProperties(properties);
  }

  private class ChainingCallbackFactoryTest implements ChainingCallbackFactory {
    private final Set<String> workerUUIDs = new HashSet<>();

    @Override
    public ChainingCallback create(CeWorker worker, CeProcessingSchedulerExecutorService executorService, long delayBetweenTasks, TimeUnit timeUnit) {
      String uuid = UUID.randomUUID().toString();
      workerUUIDs.add(uuid);
      return mock(ChainingCallback.class);
    }

    @Override
    public Set<String> getChainingCallbackUUIDs() {
      return unmodifiableSet(workerUUIDs);
    }
  }
}

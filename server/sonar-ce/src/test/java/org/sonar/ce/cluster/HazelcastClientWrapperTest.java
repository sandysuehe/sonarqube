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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;

public class HazelcastClientWrapperTest {
  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public TestRule safeGuard = new DisableOnDebug(Timeout.seconds(10));

  @Test
  public void missing_CLUSTER_ENABLED_throw_ISE() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("Cluster is not enabled");

    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_ENABLED);
    HazelcastClientWrapper hazelcastClientWrapper = new HazelcastClientWrapper(settings);
    hazelcastClientWrapper.stop();
  }

  @Test
  public void missing_CLUSTER_NAME_throw_ISE() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("sonar.cluster.name is missing");

    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_NAME);
    HazelcastClientWrapper hazelcastClientWrapper = new HazelcastClientWrapper(settings);
    hazelcastClientWrapper.stop();
  }

  @Test
  public void missing_CLUSTER_LOCALENDPOINT_throw_ISE() {
    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage("LocalEndPoint have not been set");

    Settings settings = createClusterSettings("sonarqube", "localhost:9003");
    settings.removeProperty(ProcessProperties.CLUSTER_LOCALENDPOINT);
    HazelcastClientWrapper hazelcastClientWrapper = new HazelcastClientWrapper(settings);
    hazelcastClientWrapper.stop();
  }

  @Test
  public void client_must_connect_to_hazelcast() {
    int port = NetworkUtils.freePort();
    // Launch a fake Hazelcast instance
    HazelcastInstance hzInstance = HazelcastTestHelper.createHazelcastCluster("client_must_connect_to_hazelcast", port);
    Settings settings = createClusterSettings("client_must_connect_to_hazelcast", "localhost:" + port);
    HazelcastClientWrapper hazelcastClientWrapper = new HazelcastClientWrapper(settings);
    hazelcastClientWrapper.start();
    assertThat(hazelcastClientWrapper.getConnectedClients()).hasSize(1);
    assertThat(hazelcastClientWrapper.getClientUUID()).isNotEmpty();
    hazelcastClientWrapper.stop();
  }

  @Test
  public void client_must_be_able_to_retrieve_clustered_objects() {
    int port = NetworkUtils.freePort();
    // Launch a fake Hazelcast instance
    HazelcastInstance hzInstance = HazelcastTestHelper.createHazelcastCluster("client_must_connect_to_hazelcast", port);
    Settings settings = createClusterSettings("client_must_connect_to_hazelcast", "localhost:" + port);
    HazelcastClientWrapper hazelcastClientWrapper = new HazelcastClientWrapper(settings);
    hazelcastClientWrapper.start();

    // Set
    Set<String> setTest = new HashSet<>();
    setTest.addAll(Arrays.asList("8", "9"));
    hzInstance.getSet("TEST1").addAll(setTest);
    assertThat(hazelcastClientWrapper.getSet("TEST1")).containsAll(setTest);

    // List
    List<String> listTest = Arrays.asList("1", "2");
    hzInstance.getList("TEST2").addAll(listTest);
    assertThat(hazelcastClientWrapper.getList("TEST2")).containsAll(listTest);

    // Map
    Map mapTest = new HashMap<>();
    mapTest.put("a", Arrays.asList("123", "456"));
    hzInstance.getMap("TEST3").putAll(mapTest);
    assertThat(hazelcastClientWrapper.getMap("TEST3")).containsExactly(
      entry("a", Arrays.asList("123", "456"))
    );

    hazelcastClientWrapper.stop();
  }

  private static Settings createClusterSettings(String name, String localEndPoint) {
    Properties properties = new Properties();
    properties.setProperty(ProcessProperties.CLUSTER_NAME, name);
    properties.setProperty(ProcessProperties.CLUSTER_LOCALENDPOINT, localEndPoint);
    properties.setProperty(ProcessProperties.CLUSTER_ENABLED, "true");
    return new MapSettings(new PropertyDefinitions()).addProperties(properties);
  }
}

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
package org.sonar.ce.worker;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.Test;
import org.sonar.ce.cluster.HazelcastClientWrapper;
import org.sonar.server.computation.taskprocessor.CeDistributedInformation;
import org.sonar.server.computation.taskprocessor.CeWorkerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.MapEntry.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.sonar.process.cluster.ClusterConstants.WORKER_UUIDS;

public class CeDistributedInformationImplTest {
  private String clientUUID1 = "1";
  private String clientUUID2 = "2";
  private String clientUUID3 = "3";
  private Map workerMap = ImmutableMap.of(
    clientUUID1, ImmutableSet.of("1", "2"),
    clientUUID2, ImmutableSet.of("3"),
    clientUUID3, ImmutableSet.of("4", "5", "6")
  );

  @Test
  public void getWorkerUUIDs_must_returns_all_the_workers_in_cluster() {
    HazelcastClientWrapper hzClientWrapper = mock(HazelcastClientWrapper.class);
    when(hzClientWrapper.getClientUUID()).thenReturn(clientUUID1);
    when(hzClientWrapper.getConnectedClients()).thenReturn(ImmutableSet.of(clientUUID1, clientUUID2, clientUUID3));
    when(hzClientWrapper.getReplicatedMap(WORKER_UUIDS)).thenReturn(workerMap);

    CeDistributedInformation ceDistributedInformation = new CeDistributedInformationImpl(hzClientWrapper, mock(CeWorkerFactory.class));
    assertThat(ceDistributedInformation.getWorkerUUIDs()).containsExactly("1", "2", "3", "4", "5", "6");
  }

  @Test
  public void getWorkerUUIDs_must_filter_absent_client() {
    HazelcastClientWrapper hzClientWrapper = mock(HazelcastClientWrapper.class);
    when(hzClientWrapper.getClientUUID()).thenReturn(clientUUID1);
    when(hzClientWrapper.getConnectedClients()).thenReturn(ImmutableSet.of(clientUUID1, clientUUID2));
    when(hzClientWrapper.getReplicatedMap(WORKER_UUIDS)).thenReturn(workerMap);

    CeDistributedInformation ceDistributedInformation = new CeDistributedInformationImpl(hzClientWrapper, mock(CeWorkerFactory.class));
    assertThat(ceDistributedInformation.getWorkerUUIDs()).containsExactly("1", "2", "3");
  }

  @Test
  public void broadcastWorkerUUIDs_must_add_workerUUIDs() {
    Set<String> connectedClients = new HashSet<>();
    Map modifiableWorkerMap = new HashMap<>();
    connectedClients.add(clientUUID1);
    connectedClients.add(clientUUID2);

    HazelcastClientWrapper hzClientWrapper = mock(HazelcastClientWrapper.class);
    when(hzClientWrapper.getClientUUID()).thenReturn(clientUUID1);
    when(hzClientWrapper.getConnectedClients()).thenReturn(connectedClients);
    when(hzClientWrapper.getReplicatedMap(WORKER_UUIDS)).thenReturn(modifiableWorkerMap);

    CeWorkerFactory ceWorkerFactory = mock(CeWorkerFactory.class);
    when(ceWorkerFactory.getWorkerUUIDs()).thenReturn(ImmutableSet.of("a10", "a11"));
    CeDistributedInformation ceDistributedInformation = new CeDistributedInformationImpl(hzClientWrapper, ceWorkerFactory);

    ceDistributedInformation.broadcastWorkerUUIDs();
    assertThat(modifiableWorkerMap).containsExactly(
      entry(clientUUID1, ImmutableSet.of("a10", "a11"))
    );
    ceDistributedInformation.stop();
  }

  @Test
  public void stop_must_remove_local_workerUUIDs() {
    Set<String> connectedClients = new HashSet<>();
    connectedClients.add(clientUUID1);
    connectedClients.add(clientUUID2);
    connectedClients.add(clientUUID3);
    Map modifiableWorkerMap = new HashMap();
    modifiableWorkerMap.putAll(workerMap);

    HazelcastClientWrapper hzClientWrapper = mock(HazelcastClientWrapper.class);
    when(hzClientWrapper.getClientUUID()).thenReturn(clientUUID1);
    when(hzClientWrapper.getConnectedClients()).thenReturn(connectedClients);
    when(hzClientWrapper.getReplicatedMap(WORKER_UUIDS)).thenReturn(modifiableWorkerMap);

    CeDistributedInformation ceDistributedInformation = new CeDistributedInformationImpl(hzClientWrapper, mock(CeWorkerFactory.class));
    ceDistributedInformation.stop();
    assertThat(modifiableWorkerMap).containsExactly(
      entry(clientUUID2, ImmutableSet.of("3")),
      entry(clientUUID3, ImmutableSet.of("4", "5", "6"))
    );
  }
}

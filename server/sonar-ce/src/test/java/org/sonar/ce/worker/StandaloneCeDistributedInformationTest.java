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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.sonar.server.computation.taskprocessor.CeWorkerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class StandaloneCeDistributedInformationTest {
  @Test
  public void broadcastWorkerUUIDs_must_retrieve_from_ceworkerfactory() {
    CeWorkerFactory ceWorkerFactory = mock(CeWorkerFactory.class);
    StandaloneCeDistributedInformation ceCluster = new StandaloneCeDistributedInformation(ceWorkerFactory);

    ceCluster.broadcastWorkerUUIDs();
    verify(ceWorkerFactory).getWorkerUUIDs();
  }

  @Test
  public void getWorkerUUIDs_must_be_retrieved_from_ceworkerfactory() {
    CeWorkerFactory ceWorkerFactory = mock(CeWorkerFactory.class);
    Set<String> workerUUIDs = new HashSet<>();
    workerUUIDs.addAll(Arrays.asList("1", "2", "3"));
    when(ceWorkerFactory.getWorkerUUIDs()).thenReturn(workerUUIDs);
    StandaloneCeDistributedInformation ceCluster = new StandaloneCeDistributedInformation(ceWorkerFactory);

    ceCluster.broadcastWorkerUUIDs();
    assertThat(ceCluster.getWorkerUUIDs()).isEqualTo(workerUUIDs);
  }
}

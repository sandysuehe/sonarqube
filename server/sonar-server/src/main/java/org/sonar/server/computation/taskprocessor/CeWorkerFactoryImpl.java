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

package org.sonar.server.computation.taskprocessor;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.sonar.ce.log.CeLogging;
import org.sonar.server.computation.queue.InternalCeQueue;

import static java.util.Collections.unmodifiableSet;

public class CeWorkerFactoryImpl implements CeWorkerFactory {
  private final Set<String> chaningCallbackUUIDs = new HashSet<>();
  private final InternalCeQueue queue;
  private final CeLogging ceLogging;
  private final CeTaskProcessorRepository taskProcessorRepository;

  public CeWorkerFactoryImpl(InternalCeQueue queue, CeLogging ceLogging, CeTaskProcessorRepository taskProcessorRepository) {
    this.queue = queue;
    this.ceLogging = ceLogging;
    this.taskProcessorRepository = taskProcessorRepository;
  }

  @Override
  public CeWorker create() {
    String uuid = UUID.randomUUID().toString();
    chaningCallbackUUIDs.add(uuid);
    return new CeWorkerImpl(queue, ceLogging, taskProcessorRepository, uuid);
  }

  @Override
  public Set<String> getWorkerUUIDs() {
    return unmodifiableSet(chaningCallbackUUIDs);
  }
}

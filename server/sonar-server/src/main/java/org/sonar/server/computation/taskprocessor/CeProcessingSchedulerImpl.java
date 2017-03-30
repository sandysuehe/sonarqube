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

import com.google.common.util.concurrent.ListenableScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.picocontainer.Startable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.server.computation.configuration.CeConfiguration;

import static com.google.common.util.concurrent.Futures.addCallback;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class CeProcessingSchedulerImpl implements CeProcessingScheduler, Startable {
  private static final Logger LOG = Loggers.get(CeProcessingSchedulerImpl.class);

  private final CeProcessingSchedulerExecutorService executorService;
  private final CeWorker worker;

  private final long delayBetweenTasks;
  private final TimeUnit timeUnit;
  private final ChainingCallback[] chainingCallbacks;

  public CeProcessingSchedulerImpl(CeConfiguration ceConfiguration,
    CeProcessingSchedulerExecutorService processingExecutorService, CeWorker worker, ChainingCallbackFactory ceChainingCallbackFactory) {
    this.executorService = processingExecutorService;
    this.worker = worker;

    this.delayBetweenTasks = ceConfiguration.getQueuePollingDelay();
    this.timeUnit = MILLISECONDS;

    int workerCount = ceConfiguration.getWorkerCount();
    this.chainingCallbacks = new ChainingCallback[workerCount];
    for (int i = 0; i < workerCount; i++) {
      chainingCallbacks[i] = ceChainingCallbackFactory.create(worker, executorService, delayBetweenTasks, timeUnit);
    }
  }

  @Override
  public void start() {
    // nothing to do at component startup, startScheduling will be called by CeQueueInitializer
  }

  @Override
  public void startScheduling() {
    for (ChainingCallback chainingCallback : chainingCallbacks) {
      ListenableScheduledFuture<Boolean> future = executorService.schedule(worker, delayBetweenTasks, timeUnit);
      addCallback(future, chainingCallback, executorService);
    }
  }

  @Override
  public void stop() {
    for (ChainingCallback chainingCallback : chainingCallbacks) {
      chainingCallback.stop();
    }
  }
}

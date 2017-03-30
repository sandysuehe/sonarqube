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

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

public class ChainingCallback implements FutureCallback<Boolean> {
  private static final Logger LOG = Loggers.get(ChainingCallback.class);

  private final AtomicBoolean keepRunning = new AtomicBoolean(true);
  private final long delayBetweenTasks;
  private final TimeUnit timeUnit;
  private final CeProcessingSchedulerExecutorService executorService;
  private final CeWorker worker;
  private final String uuid;

  @CheckForNull
  private ListenableFuture<Boolean> workerFuture;

  public ChainingCallback(CeWorker worker, CeProcessingSchedulerExecutorService executorService,
    long delayBetweenTasks, TimeUnit timeUnit, String uuid) {
    this.worker = worker;
    this.executorService = executorService;
    this.delayBetweenTasks = delayBetweenTasks;
    this.timeUnit = timeUnit;
    this.uuid = uuid;
  }

  @Override
  public void onSuccess(@Nullable Boolean result) {
    if (result != null && result) {
      chainWithoutDelay();
    } else {
      chainWithDelay();
    }
  }

  @Override
  public void onFailure(Throwable t) {
    if (t instanceof Error) {
      LOG.error("Compute Engine execution failed. Scheduled processing interrupted.", t);
    } else {
      chainWithoutDelay();
    }
  }

  private void chainWithoutDelay() {
    if (keepRunning()) {
      workerFuture = executorService.submit(worker);
    }
    addCallback();
  }

  private void chainWithDelay() {
    if (keepRunning()) {
      workerFuture = executorService.schedule(worker, delayBetweenTasks, timeUnit);
    }
    addCallback();
  }

  private void addCallback() {
    if (workerFuture != null && keepRunning()) {
      Futures.addCallback(workerFuture, this, executorService);
    }
  }

  public String getUuid() {
    return uuid;
  }

  private boolean keepRunning() {
    return keepRunning.get();
  }

  public void stop() {
    this.keepRunning.set(false);
    if (workerFuture != null) {
      workerFuture.cancel(false);
    }
  }
}

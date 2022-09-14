/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.kubernetes.client.informers.impl.cache;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.api.model.ListOptionsBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.internal.WatchConnectionManager;
import io.fabric8.kubernetes.client.informers.impl.ListerWatcher;
import io.fabric8.kubernetes.client.utils.Utils;
import io.fabric8.kubernetes.client.utils.internal.ExponentialBackoffIntervalCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Reflector<T extends HasMetadata, L extends KubernetesResourceList<T>> {

  private static final Logger log = LoggerFactory.getLogger(Reflector.class);

  private volatile String lastSyncResourceVersion;
  private final ListerWatcher<T, L> listerWatcher;
  private final SyncableStore<T> store;
  private final ReflectorWatcher watcher;
  private volatile boolean running;
  private volatile boolean watching;
  private volatile CompletableFuture<Watch> watchFuture;
  private volatile CompletableFuture<?> reconnectFuture;
  private final CompletableFuture<Void> stopFuture = new CompletableFuture<>();
  private final ExponentialBackoffIntervalCalculator retryIntervalCalculator;

  public Reflector(ListerWatcher<T, L> listerWatcher, SyncableStore<T> store) {
    this.listerWatcher = listerWatcher;
    this.store = store;
    this.watcher = new ReflectorWatcher();
    this.retryIntervalCalculator = new ExponentialBackoffIntervalCalculator(listerWatcher.getWatchReconnectInterval(),
        WatchConnectionManager.BACKOFF_MAX_EXPONENT);
  }

  public CompletableFuture<Void> start() {
    return start(false); // start without reconnecting
  }

  public CompletableFuture<Void> start(boolean reconnect) {
    this.running = true;
    CompletableFuture<Void> result = listSyncAndWatch(reconnect);
    if (!reconnect) {
      result.whenComplete((v, t) -> {
        if (t != null) {
          stopFuture.completeExceptionally(t);
        }
      });
    }
    return result;
  }

  public void stop() {
    running = false;
    stopFuture.complete(null);
    Future<?> future = reconnectFuture;
    if (future != null) {
      future.cancel(true);
    }
    stopWatcher();
  }

  private synchronized void stopWatcher() {
    Optional.ofNullable(watchFuture).ifPresent(theFuture -> {
      watchFuture = null;
      theFuture.cancel(true);
      theFuture.whenComplete((w, t) -> {
        if (w != null) {
          stopWatch(w);
        }
      });
    });
  }

  /**
   * <br>
   * Starts the watch with a fresh store state.
   * <br>
   * Should be called only at start and when HttpGone is seen.
   *
   * @return a future that completes when the list and watch are established
   */
  public CompletableFuture<Void> listSyncAndWatch(boolean reconnect) {
    if (!running) {
      return CompletableFuture.completedFuture(null);
    }
    Set<String> nextKeys = new ConcurrentSkipListSet<>();
    CompletableFuture<Void> theFuture = processList(nextKeys, null).thenCompose(result -> {
      store.retainAll(nextKeys);
      final String latestResourceVersion = result.getMetadata().getResourceVersion();
      lastSyncResourceVersion = latestResourceVersion;
      log.debug("Listing items ({}) for {} at v{}", nextKeys.size(), this, latestResourceVersion);
      return startWatcher(latestResourceVersion);
    }).thenAccept(w -> {
      if (w != null) {
        if (running) {
          if (log.isDebugEnabled()) {
            log.debug("Watch started for {}", Reflector.this);
          }
          watching = true;
        } else {
          stopWatch(w);
        }
      }
    });
    if (reconnect) {
      theFuture.whenComplete((v, t) -> {
        if (t != null) {
          log.warn("listSyncAndWatch failed for {}, will retry", Reflector.this, t);
          reconnect();
        } else {
          retryIntervalCalculator.resetReconnectAttempts();
        }
      });
    }
    return theFuture;
  }

  protected void reconnect() {
    if (!running) {
      return;
    }
    // this can be run in the scheduler thread because
    // any further operations will happen on the io thread
    reconnectFuture = Utils.schedule(Runnable::run, () -> listSyncAndWatch(true),
        retryIntervalCalculator.nextReconnectInterval(), TimeUnit.MILLISECONDS);
  }

  private CompletableFuture<L> processList(Set<String> nextKeys, String continueVal) {
    CompletableFuture<L> futureResult = listerWatcher
        .submitList(
            new ListOptionsBuilder().withLimit(listerWatcher.getLimit()).withContinue(continueVal)
                .build());

    return futureResult.thenCompose(result -> {
      result.getItems().forEach(i -> {
        String key = store.getKey(i);
        nextKeys.add(key);
      });
      store.update(result.getItems());
      String nextContinueVal = result.getMetadata().getContinue();
      if (Utils.isNotNullOrEmpty(nextContinueVal)) {
        return processList(nextKeys, nextContinueVal);
      }
      return CompletableFuture.completedFuture(result);
    });
  }

  private void stopWatch(Watch w) {
    log.debug("Stopping watcher for {} at v{}", this, lastSyncResourceVersion);
    w.close();
    watchStopped(); // proactively report as stopped
  }

  private synchronized CompletableFuture<Watch> startWatcher(final String latestResourceVersion) {
    if (!running) {
      return CompletableFuture.completedFuture(null);
    }
    log.debug("Starting watcher for {} at v{}", this, latestResourceVersion);
    // there's no need to stop the old watch, that will happen automatically when this call completes
    watchFuture = listerWatcher.submitWatch(
        new ListOptionsBuilder().withResourceVersion(latestResourceVersion)
            .withTimeoutSeconds(null)
            .build(),
        watcher);
    return watchFuture;
  }

  private synchronized void watchStopped() {
    watching = false;
  }

  public String getLastSyncResourceVersion() {
    return lastSyncResourceVersion;
  }

  public boolean isRunning() {
    return running;
  }

  public boolean isWatching() {
    return watching;
  }

  class ReflectorWatcher implements Watcher<T> {

    @Override
    public void eventReceived(Action action, T resource) {
      if (action == null) {
        throw new KubernetesClientException("Unrecognized event for " + Reflector.this);
      }
      if (resource == null) {
        throw new KubernetesClientException("Unrecognized resource for " + Reflector.this);
      }
      if (log.isDebugEnabled()) {
        log.debug("Event received {} {} resourceVersion v{} for {}", action.name(),
            resource.getKind(),
            resource.getMetadata().getResourceVersion(), Reflector.this);
      }
      switch (action) {
        case ERROR:
          throw new KubernetesClientException("ERROR event");
        case ADDED:
          store.add(resource);
          break;
        case MODIFIED:
          store.update(resource);
          break;
        case DELETED:
          store.delete(resource);
          break;
      }
      lastSyncResourceVersion = resource.getMetadata().getResourceVersion();
    }

    @Override
    public void onClose(WatcherException exception) {
      // this close was triggered by an exception,
      // not the user, it is expected that the watch retry will handle this
      watchStopped();
      if (exception.isHttpGone()) {
        if (log.isDebugEnabled()) {
          log.debug("Watch restarting due to http gone for {}", Reflector.this);
        }
        // start a whole new list/watch cycle
        reconnect();
      } else {
        running = false; // shouldn't happen, but it means the watch won't restart
        stopFuture.completeExceptionally(exception);
        log.warn("Watch closing with exception for {}", Reflector.this, exception);
      }
    }

    @Override
    public void onClose() {
      watchStopped();
      log.debug("Watch gracefully closed for {}", Reflector.this);
    }

    @Override
    public boolean reconnecting() {
      return true;
    }
  }

  ReflectorWatcher getWatcher() {
    return watcher;
  }

  @Override
  public String toString() {
    return listerWatcher.getApiEndpointPath();
  }

  public CompletableFuture<Void> getStopFuture() {
    return stopFuture;
  }

}

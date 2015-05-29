/*
 * Copyright 2014-2015 Open Networking Laboratory
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
package org.onosproject.net.intent.impl;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.SetMultimap;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.onosproject.core.ApplicationId;
import org.onosproject.event.Event;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ElementId;
import org.onosproject.net.HostId;
import org.onosproject.net.Link;
import org.onosproject.net.LinkKey;
import org.onosproject.net.NetworkResource;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.host.HostEvent;
import org.onosproject.net.host.HostListener;
import org.onosproject.net.host.HostService;
import org.onosproject.net.intent.Intent;
import org.onosproject.net.intent.IntentService;
import org.onosproject.net.intent.Key;
import org.onosproject.net.intent.PartitionEvent;
import org.onosproject.net.intent.PartitionEventListener;
import org.onosproject.net.intent.PartitionService;
import org.onosproject.net.link.LinkEvent;
import org.onosproject.net.resource.link.LinkResourceEvent;
import org.onosproject.net.resource.link.LinkResourceListener;
import org.onosproject.net.resource.link.LinkResourceService;
import org.onosproject.net.topology.TopologyEvent;
import org.onosproject.net.topology.TopologyListener;
import org.onosproject.net.topology.TopologyService;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.Multimaps.synchronizedSetMultimap;
import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.net.LinkKey.linkKey;
import static org.onosproject.net.link.LinkEvent.Type.LINK_REMOVED;
import static org.onosproject.net.link.LinkEvent.Type.LINK_UPDATED;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Entity responsible for tracking installed flows and for monitoring topology
 * events to determine what flows are affected by topology changes.
 */
@Component(immediate = true)
@Service
public class ObjectiveTracker implements ObjectiveTrackerService {

    private final Logger log = getLogger(getClass());

    private final SetMultimap<LinkKey, Key> intentsByLink =
            //TODO this could be slow as a point of synchronization
            synchronizedSetMultimap(HashMultimap.<LinkKey, Key>create());

    private final SetMultimap<ElementId, Key> intentsByDevice =
            synchronizedSetMultimap(HashMultimap.<ElementId, Key>create());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LinkResourceService resourceManager;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL_UNARY,
               policy = ReferencePolicy.DYNAMIC)
    protected IntentService intentService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PartitionService partitionService;

    private ExecutorService executorService =
            newSingleThreadExecutor(groupedThreads("onos/intent", "objectivetracker"));
    private ScheduledExecutorService executor = Executors
            .newScheduledThreadPool(1);

    private TopologyListener listener = new InternalTopologyListener();
    private LinkResourceListener linkResourceListener =
            new InternalLinkResourceListener();
    private DeviceListener deviceListener = new InternalDeviceListener();
    private HostListener hostListener = new InternalHostListener();
    private PartitionEventListener partitionListener = new InternalPartitionListener();
    private TopologyChangeDelegate delegate;

    protected final AtomicBoolean updateScheduled = new AtomicBoolean(false);

    @Activate
    public void activate() {
        topologyService.addListener(listener);
        resourceManager.addListener(linkResourceListener);
        deviceService.addListener(deviceListener);
        hostService.addListener(hostListener);
        partitionService.addListener(partitionListener);
        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        topologyService.removeListener(listener);
        resourceManager.removeListener(linkResourceListener);
        deviceService.removeListener(deviceListener);
        hostService.removeListener(hostListener);
        partitionService.removeListener(partitionListener);
        log.info("Stopped");
    }

    protected void bindIntentService(IntentService service) {
        if (intentService == null) {
            intentService = service;
        }
     }

    protected void unbindIntentService(IntentService service) {
        if (intentService == service) {
            intentService = null;
        }
    }

    @Override
    public void setDelegate(TopologyChangeDelegate delegate) {
        checkNotNull(delegate, "Delegate cannot be null");
        checkArgument(this.delegate == null || this.delegate == delegate,
                      "Another delegate already set");
        this.delegate = delegate;
    }

    @Override
    public void unsetDelegate(TopologyChangeDelegate delegate) {
        checkArgument(this.delegate == delegate, "Not the current delegate");
        this.delegate = null;
    }

    @Override
    public void addTrackedResources(Key intentKey,
                                    Collection<NetworkResource> resources) {
        for (NetworkResource resource : resources) {
            if (resource instanceof Link) {
                intentsByLink.put(linkKey((Link) resource), intentKey);
            } else if (resource instanceof ElementId) {
                intentsByDevice.put((ElementId) resource, intentKey);
            }
        }
    }

    @Override
    public void removeTrackedResources(Key intentKey,
                                       Collection<NetworkResource> resources) {
        for (NetworkResource resource : resources) {
            if (resource instanceof Link) {
                intentsByLink.remove(linkKey((Link) resource), intentKey);
            } else if (resource instanceof ElementId) {
                intentsByDevice.remove((ElementId) resource, intentKey);
            }
        }
    }

    // Internal re-actor to topology change events.
    private class InternalTopologyListener implements TopologyListener {
        @Override
        public void event(TopologyEvent event) {
            executorService.execute(new TopologyChangeHandler(event));
        }
    }

    // Re-dispatcher of topology change events.
    private class TopologyChangeHandler implements Runnable {

        private final TopologyEvent event;

        TopologyChangeHandler(TopologyEvent event) {
            this.event = event;
        }

        @Override
        public void run() {
            // If there is no delegate, why bother? Just bail.
            if (delegate == null) {
                return;
            }

            if (event.reasons() == null || event.reasons().isEmpty()) {
                delegate.triggerCompile(Collections.emptySet(), true);

            } else {
                Set<Key> toBeRecompiled = new HashSet<>();
                boolean recompileOnly = true;

                // Scan through the list of reasons and keep accruing all
                // intents that need to be recompiled.
                for (Event reason : event.reasons()) {
                    if (reason instanceof LinkEvent) {
                        LinkEvent linkEvent = (LinkEvent) reason;
                        if (linkEvent.type() == LINK_REMOVED
                                || (linkEvent.type() == LINK_UPDATED &&
                                        linkEvent.subject().isDurable())) {
                            final LinkKey linkKey = linkKey(linkEvent.subject());
                            synchronized (intentsByLink) {
                                Set<Key> intentKeys = intentsByLink.get(linkKey);
                                log.debug("recompile triggered by LinkDown {} {}", linkKey, intentKeys);
                                toBeRecompiled.addAll(intentKeys);
                            }
                        }
                        recompileOnly = recompileOnly &&
                                (linkEvent.type() == LINK_REMOVED ||
                                (linkEvent.type() == LINK_UPDATED &&
                                linkEvent.subject().isDurable()));
                    }
                }
                delegate.triggerCompile(toBeRecompiled, !recompileOnly);
            }
        }
    }

    /**
     * Internal re-actor to resource available events.
     */
    private class InternalLinkResourceListener implements LinkResourceListener {
        @Override
        public void event(LinkResourceEvent event) {
            executorService.execute(new ResourceAvailableHandler(event));
        }
    }

    /*
     * Re-dispatcher of resource available events.
     */
    private class ResourceAvailableHandler implements Runnable {

        private final LinkResourceEvent event;

        ResourceAvailableHandler(LinkResourceEvent event) {
            this.event = event;
        }

        @Override
        public void run() {
            // If there is no delegate, why bother? Just bail.
            if (delegate == null) {
                return;
            }

            delegate.triggerCompile(Collections.emptySet(), true);
        }
    }

    //TODO consider adding flow rule event tracking

    private void updateTrackedResources(ApplicationId appId, boolean track) {
        if (intentService == null) {
            log.warn("Intent service is not bound yet");
            return;
        }
        intentService.getIntents().forEach(intent -> {
            if (intent.appId().equals(appId)) {
                Key key = intent.key();
                Collection<NetworkResource> resources = Lists.newArrayList();
                intentService.getInstallableIntents(key).stream()
                        .map(installable -> installable.resources())
                        .forEach(resources::addAll);
                if (track) {
                    addTrackedResources(key, resources);
                } else {
                    removeTrackedResources(key, resources);
                }
            }
        });
    }

    /*
     * Re-dispatcher of device and host events.
     */
    private class DeviceAvailabilityHandler implements Runnable {

        private final ElementId id;
        private final boolean available;

        DeviceAvailabilityHandler(ElementId id, boolean available) {
            this.id = checkNotNull(id);
            this.available = available;
        }

        @Override
        public void run() {
            // If there is no delegate, why bother? Just bail.
            if (delegate == null) {
                return;
            }

            // TODO should we recompile on available==true?
            delegate.triggerCompile(intentsByDevice.get(id), available);
        }
    }


    private class InternalDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
            DeviceEvent.Type type = event.type();
            if (type == DeviceEvent.Type.PORT_ADDED ||
                type == DeviceEvent.Type.PORT_UPDATED ||
                type == DeviceEvent.Type.PORT_REMOVED) {
                // skip port events for now
                return;
            }
            DeviceId id = event.subject().id();
            // TODO we need to check whether AVAILABILITY_CHANGED means up or down
            boolean available = (type == DeviceEvent.Type.DEVICE_AVAILABILITY_CHANGED ||
                                 type == DeviceEvent.Type.DEVICE_ADDED ||
                                 type == DeviceEvent.Type.DEVICE_UPDATED);
            executorService.execute(new DeviceAvailabilityHandler(id, available));

        }
    }

    private class InternalHostListener implements HostListener {
        @Override
        public void event(HostEvent event) {
            HostId id = event.subject().id();
            HostEvent.Type type = event.type();
            boolean available = (type == HostEvent.Type.HOST_ADDED);
            executorService.execute(new DeviceAvailabilityHandler(id, available));
        }
    }

    protected void doIntentUpdate() {
        updateScheduled.set(false);
        if (intentService == null) {
            log.warn("Intent service is not bound yet");
            return;
        }
        try {
            //FIXME very inefficient
            for (Intent intent : intentService.getIntents()) {
                try {
                    if (intentService.isLocal(intent.key())) {
                        log.warn("intent {}, old: {}, new: {}",
                                 intent.key(), intentsByDevice.values().contains(intent.key()), true);
                        addTrackedResources(intent.key(), intent.resources());
                        intentService.getInstallableIntents(intent.key()).stream()
                                .forEach(installable ->
                                                 addTrackedResources(intent.key(), installable.resources()));
                    } else {
                        log.warn("intent {}, old: {}, new: {}",
                                 intent.key(), intentsByDevice.values().contains(intent.key()), false);
                        removeTrackedResources(intent.key(), intent.resources());
                        intentService.getInstallableIntents(intent.key()).stream()
                                .forEach(installable ->
                                                 removeTrackedResources(intent.key(), installable.resources()));
                    }
                } catch (NullPointerException npe) {
                    log.warn("intent error {}", intent.key(), npe);
                }
            }
        } catch (Exception e) {
            log.warn("Exception caught during update task", e);
        }
    }

    private void scheduleIntentUpdate(int afterDelaySec) {
        if (updateScheduled.compareAndSet(false, true)) {
            executor.schedule(this::doIntentUpdate, afterDelaySec, TimeUnit.SECONDS);
        }
    }

    private final class InternalPartitionListener implements PartitionEventListener {
        @Override
        public void event(PartitionEvent event) {
            log.warn("got message {}", event.subject());
            scheduleIntentUpdate(1);
        }
    }
}

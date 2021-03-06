/*
Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ea.orbit.actors.runtime;

import com.ea.orbit.actors.IActor;
import com.ea.orbit.actors.IActorObserver;
import com.ea.orbit.actors.IAddressable;
import com.ea.orbit.actors.IRemindable;
import com.ea.orbit.actors.annotation.StatelessWorker;
import com.ea.orbit.actors.cluster.INodeAddress;
import com.ea.orbit.actors.providers.IActorClassFinder;
import com.ea.orbit.actors.providers.IInvokeHookProvider;
import com.ea.orbit.actors.providers.IInvokeListenerProvider;
import com.ea.orbit.actors.providers.ILifetimeProvider;
import com.ea.orbit.actors.providers.IOrbitProvider;
import com.ea.orbit.actors.providers.IStorageProvider;
import com.ea.orbit.concurrent.ExecutorUtils;
import com.ea.orbit.concurrent.Task;
import com.ea.orbit.exception.UncheckedException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.MapMaker;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class Execution implements IRuntime
{
    public static boolean traceEnabled = false;

    private static final Logger logger = LoggerFactory.getLogger(Execution.class);
    private final String runtimeIdentity;
    private IActorClassFinder finder;
    private Map<Class<?>, InterfaceDescriptor> descriptorMapByInterface = new HashMap<>();
    private Map<Integer, InterfaceDescriptor> descriptorMapByInterfaceId = new HashMap<>();
    private Map<EntryKey, ReferenceEntry> localActors = new ConcurrentHashMap<>();
    private Map<EntryKey, IActorObserver> observerInstances = new MapMaker().weakValues().makeMap();
    // from implementation to reference
    private Map<IActorObserver, IActorObserver> observerReferences = new MapMaker().weakKeys().makeMap();

    private Hosting hosting;
    private Messaging messaging;
    private ExecutionSerializer<Object> executionSerializer;
    private int maxQueueSize = 10000;
    private Timer timer = new Timer("Orbit stage timer");
    private Clock clock = Clock.systemUTC();
    private long cleanupIntervalMillis = TimeUnit.MINUTES.toMillis(5);
    private AtomicLong messagesReceived = new AtomicLong();
    private AtomicLong messagesHandled = new AtomicLong();
    private AtomicLong refusedExecutions = new AtomicLong();
    private ExecutorService executor;
    private ActorFactoryGenerator dynamicReferenceFactory = new ActorFactoryGenerator();

    private List<IOrbitProvider> orbitProviders = new ArrayList<>();

    private final WeakReference<IRuntime> cachedRef = new WeakReference<>(this);
    private IInvokeHookProvider invokeHook;

    public Execution()
    {
        // the last runtime created will be the default.
        Runtime.runtimeCreated(cachedRef);

        final UUID uuid = UUID.randomUUID();
        final String encoded = Base64.getEncoder().encodeToString(
                ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array());
        runtimeIdentity = "Orbit[" + encoded.substring(0, encoded.length() - 2) + "]";
    }

    public void setClock(final Clock clock)
    {
        this.clock = clock;
    }

    public void setExecutor(final ExecutorService executor)
    {
        this.executor = executor;
    }

    public ExecutorService getExecutor()
    {
        return executor;
    }

    public boolean canActivateActor(String interfaceName, int interfaceId)
    {
        Class<IActor> aInterface = classForName(interfaceName);
        final InterfaceDescriptor descriptor = getDescriptor(aInterface);
        if (descriptor == null || descriptor.cannotActivate)
        {
            return false;
        }
        if (descriptor.concreteClassName == null)
        {
            final Class<?> concreteClass = finder.findActorImplementation(aInterface);
            descriptor.cannotActivate = concreteClass == null;
            descriptor.concreteClassName = concreteClass != null ? concreteClass.getName() : null;
        }
        return !descriptor.cannotActivate;
    }

    public void registerFactory(ActorFactory<?> factory)
    {
        // TODO: will enable caching the reference factory
    }

    private static class InterfaceDescriptor
    {
        ActorFactory<?> factory;
        ActorInvoker<Object> invoker;
        boolean cannotActivate;
        String concreteClassName;
        boolean isObserver;

        @Override
        public String toString()
        {
            return this.concreteClassName;
        }
    }

    private class ReferenceEntry
    {
        ActorReference<?> reference;
        InterfaceDescriptor descriptor;
        boolean statelessWorker;
        Activation singleActivation;

        ConcurrentLinkedDeque<Activation> statelessActivations;

        boolean removable;

        public void pushActivation(final Activation activation)
        {
            if (!statelessWorker)
            {
                if (singleActivation != null)
                {
                    logger.error("There should be only one single activation! Reference: {}", reference);
                }
                singleActivation = activation;
            }
            else
            {
                statelessActivations.offerLast(activation);
            }
        }

        public Activation popActivation()
        {
            Activation activation;
            if (!statelessWorker)
            {
                activation = singleActivation;
                singleActivation = null;
                return (activation != null) ? activation : new Activation(this, null);
            }
            activation = statelessActivations.pollLast();
            return (activation != null) ? activation : new Activation(this, null);
        }

        public Activation peekOldActivation()
        {
            if (!statelessWorker)
            {
                return singleActivation;
            }
            else
            {
                return statelessActivations.peekFirst();
            }
        }

        public Task<?> cleanup(final EntryKey key, long cutOut)
        {
            if (localActors.get(key) != this)
            {
                logger.warn("Error during cleanup: the ActivationEntry changed. This should not be possible. {}", key);
                return Task.done();
            }
            if (!statelessWorker)
            {
                if (singleActivation != null)
                {
                    if (singleActivation.instance != null)
                    {
                        if (singleActivation.lastAccess > cutOut)
                        {
                            // has been used recently enough. not disposing.
                            return Task.done();
                        }
                        // TODO deactivation code
                        if (singleActivation.instance instanceof OrbitActor)
                        {
                            try
                            {

                                bind();
                                OrbitActor<?> orbitActor = (OrbitActor<?>) singleActivation.instance;
                                Task.allOf(getAllProviders(ILifetimeProvider.class).stream().map(v -> v.preDeactivation(orbitActor)))
                                        .thenCompose(() -> orbitActor.deactivateAsync())
                                        .thenCompose(() -> Task.allOf(getAllProviders(ILifetimeProvider.class).stream().map(v -> v.postDeactivation(orbitActor))))
                                        .thenRun(() -> {
                                            singleActivation.instance = null;
                                            localActors.remove(key);
                                        });

                            }
                            catch (Exception e)
                            {
                                if (logger.isErrorEnabled())
                                {
                                    logger.error("Error during the clean up. " + key, e);
                                }
                            }
                        }
                        singleActivation.instance = null;
                    }
                }
                localActors.remove(key);
                return Task.done();
            }
            else
            {
                int count = statelessActivations.size();
                List<Task<?>> futures = new ArrayList<>();
                for (int i = 0; i < count; i++)
                {
                    Activation activation = statelessActivations.pollFirst();
                    if (activation == null)
                    {
                        break;
                    }
                    if (activation.lastAccess > cutOut)
                    {
                        // return it
                        statelessActivations.addLast(activation);
                    }
                    else
                    {
                        if (activation.instance instanceof OrbitActor)
                        {
                            try
                            {
                                bind();
                                OrbitActor<?> orbitActor = (OrbitActor<?>) activation.instance;
                                Task.allOf(getAllProviders(ILifetimeProvider.class).stream().map(v -> v.preDeactivation(orbitActor)))
                                        .thenCompose(() -> orbitActor.deactivateAsync())
                                        .thenCompose(() -> Task.allOf(getAllProviders(ILifetimeProvider.class).stream().map(v -> v.postDeactivation(orbitActor))))
                                        .thenRun(() -> {
                                            activation.instance = null;
                                        });
                            }
                            catch (Exception e)
                            {
                                if (logger.isErrorEnabled())
                                {
                                    logger.error("Error during the clean up. " + key, e);
                                }
                                activation.instance = null;
                            }
                        }
                    }
                }
                if (futures.size() > 0)
                {
                    return Task.allOf(futures);
                }
                // TODO figure out how to safely remove the entry
            }
            return Task.done();
        }
    }

    private static class EntryKey
    {
        int interfaceId;
        Object id;

        private EntryKey(final int interfaceId, final Object id)
        {
            this.interfaceId = interfaceId;
            this.id = id;
        }

        @Override
        public boolean equals(final Object o)
        {
            if (this == o) return true;
            if (!(o instanceof EntryKey)) return false;

            final EntryKey entryKey = (EntryKey) o;

            if (interfaceId != entryKey.interfaceId) return false;
            if (id != null ? !id.equals(entryKey.id) : entryKey.id != null) return false;

            return true;
        }

        @Override
        public int hashCode()
        {
            int result = interfaceId;
            result = 31 * result + (id != null ? id.hashCode() : 0);
            return result;
        }

        @Override
        public String toString()
        {
            return "EntryKey{" +
                    "interfaceId=" + interfaceId +
                    ", id=" + id +
                    '}';
        }
    }

    private class Activation
    {
        ReferenceEntry entry;
        long lastAccess = clock.millis();
        Object instance;

        public Activation(final ReferenceEntry entry, final Object instance)
        {
            this.entry = entry;
            this.instance = instance;
        }

        // gets or creates the instance
        public Object getOrCreateInstance() throws IllegalAccessException, InstantiationException, ExecutionException, InterruptedException
        {
            if (instance == null)
            {
                Object newInstance = classForName(entry.descriptor.concreteClassName).newInstance();
                if (newInstance instanceof OrbitActor)
                {
                    final OrbitActor<?> orbitActor = (OrbitActor<?>) newInstance;
                    orbitActor.reference = entry.reference;

                    orbitActor.stateProvider = getFirstProvider(IStorageProvider.class);

                    Task.allOf(getAllProviders(ILifetimeProvider.class).stream().map(v -> v.preActivation(orbitActor))).join();

                    if (orbitActor.stateProvider != null)
                    {
                        try
                        {
                            orbitActor.readState();
                        }
                        catch (Exception ex)
                        {
                            if (logger.isErrorEnabled())
                            {
                                logger.error("Error reading actor state for: " + entry.reference, ex);
                            }
                            throw ex;
                        }
                    }
                    instance = newInstance;

                    orbitActor.activateAsync().join();
                    Task.allOf(getAllProviders(ILifetimeProvider.class).stream().map(v -> v.postActivation(orbitActor))).join();
                }

            }
            return instance;
        }
    }


    public void setOrbitProviders(List<IOrbitProvider> orbitProviders)
    {
        this.orbitProviders = orbitProviders;
    }

    @SuppressWarnings("unchecked")
    public <T extends IOrbitProvider> T getFirstProvider(Class<T> itemType)
    {
        return (orbitProviders == null) ? null :
                (T) orbitProviders.stream().filter(p -> itemType.isInstance(p)).findFirst().orElse(null);

    }

    @SuppressWarnings("unchecked")
    public <T extends IOrbitProvider> List<T> getAllProviders(Class<T> itemType)
    {
        return orbitProviders == null ? Collections.emptyList()
                : (List<T>) orbitProviders.stream().filter(p -> itemType.isInstance(p)).collect(Collectors.toList());
    }

    public void setHosting(final Hosting hosting)
    {
        this.hosting = hosting;
    }

    public void setMessaging(final Messaging messaging)
    {
        this.messaging = messaging;
    }

    public Task<?> stop()
    {
        timer.cancel();
        return Task.allOf(orbitProviders.stream().map(v -> v.stop()));
    }

    /**
     * Installs this observer into this node.
     * Can called several times the object is registered only once.
     *
     * @param iClass   hint to the framework about which IActorObserver interface this object represents.
     *                 Can be null if there are no ambiguities.
     * @param observer the object to install
     * @param <T>      The type of reference class returned.
     * @return a remote reference that can be sent to actors.
     */
    public <T extends IActorObserver> T getObjectReference(final Class<T> iClass, final T observer)
    {
        return getObserverReference(iClass, observer, null);
    }

    /**
     * Installs this observer into this node with the given id.
     * If called twice for the same observer, the ids must match.
     * Usually it's recommended to let the framework choose the id;
     *
     * @param iClass   hint to the framework about which IActorObserver interface this object represents.
     *                 Can be null if there are no ambiguities.
     * @param observer the object to install
     * @param id       can be null, in this case the framework will choose an id.
     * @param <T>      The type of reference class returned.
     * @return a remote reference that can be sent to actors.
     * @throws java.lang.IllegalArgumentException if called twice with the same observer and different ids
     */
    @SuppressWarnings("unchecked")
    public <T extends IActorObserver> T getObserverReference(Class<T> iClass, final T observer, String id)
    {
        final IActorObserver ref = observerReferences.get(observer);
        if (ref != null)
        {
            if (id != null && !id.equals(((ActorReference<?>) ref).id))
            {
                throw new IllegalArgumentException("Called twice with different ids: " + id + " != " + ((ActorReference<?>) ref).id);
            }
            return (T) ref;
        }
        return createObjectReference(iClass, observer, id);
    }

    @SuppressWarnings("unchecked")
    private <T extends IActorObserver> T createObjectReference(final Class<T> iClass, final T observer, String objectId)
    {

        ActorFactory<?> factory;
        if (iClass == null)
        {
            factory = findFactoryFor(IActorObserver.class, observer);
        }
        else
        {
            factory = getDescriptor(iClass).factory;
        }
        if (factory == null)
        {
            throw new UncheckedException("Can't find factory for " + observer.getClass());
        }
        final String id = objectId != null ? objectId : UUID.randomUUID().toString();

        EntryKey key = new EntryKey(factory.getInterfaceId(), id);
        final IActorObserver existingObserver = observerInstances.get(key);
        if (existingObserver == null)
        {
            final ActorReference<T> reference = (ActorReference<T>) factory.createReference(id);
            if (objectId == null)
            {
                reference.address = messaging.getNodeAddress();
            }
            reference.runtime = Execution.this;
            observerInstances.putIfAbsent(key, observer);
            observerReferences.putIfAbsent(observer, (IActorObserver) reference);
            return (T) reference;
        }
        else if (observer != existingObserver)
        {
            throw new IllegalArgumentException("ActorObserver id clashes with a pre existing observer: " + id);
        }
        return (T) observerReferences.get(observer);
    }

    public Registration registerTimer(final OrbitActor<?> actor,
                                      final Callable<Task<?>> taskCallable,
                                      final long dueTime, final long period,
                                      final TimeUnit timeUnit)
    {
        // TODO: handle deactivation.
        final TimerTask timerTask = new TimerTask()
        {
            boolean canceled;

            @Override
            public void run()
            {
                // TODO decide if it's necessary to change the key here for the actor activation?
                executionSerializer.offerJob(actor,
                        () -> {
                            bind();
                            try
                            {
                                if (!canceled)
                                {
                                    return taskCallable.call();
                                }
                            }
                            catch (Exception ex)
                            {
                                logger.warn("Error calling timer", ex);
                            }
                            return Task.done();
                        }, 1000);
            }

            @Override
            public boolean cancel()
            {
                canceled = true;
                return super.cancel();
            }
        };
        timer.schedule(timerTask, timeUnit.toMillis(dueTime), timeUnit.toMillis(period));
        return () -> timerTask.cancel();
    }

    public void bind()
    {
        Runtime.setRuntime(this.cachedRef);
    }

    public void bind(Object object)
    {
        if (!(object instanceof ActorReference))
        {
            throw new IllegalArgumentException("Must be a reference");
        }
        ((ActorReference<?>) object).runtime = this;
    }

    @Override
    public Clock clock()
    {
        return clock;
    }

    @Override
    public Task<?> registerReminder(final IRemindable actor, final String reminderName, final long dueTime, final long period, final TimeUnit timeUnit)
    {
        return getReference(IReminderController.class, "0").registerOrUpdateReminder(actor, reminderName, new Date(clock.millis() + timeUnit.toMillis(dueTime)), period, timeUnit);
    }

    @Override
    public Task<?> unregisterReminder(final IRemindable actor, final String reminderName)
    {
        return getReference(IReminderController.class, "0").unregisterReminder(actor, reminderName);
    }

    @Override
    public String runtimeIdentity()
    {
        return runtimeIdentity;
    }

    public void start()
    {
        finder = getFirstProvider(IActorClassFinder.class);
        if (finder == null)
        {
            finder = new ActorClassFinder();
            finder.start().join();
        }

        getDescriptor(IHosting.class);
        createObjectReference(IHosting.class, hosting, "");

        if (executor == null)
        {
            executor = ExecutorUtils.newScalingThreadPool(1000);
        }
        executionSerializer = new ExecutionSerializer<>(executor);

        invokeHook = getFirstProvider(IInvokeHookProvider.class);

        orbitProviders.forEach(v -> v.start());
        // schedules the cleanup
        timer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                ForkJoinTask.adapt(() -> activationCleanup(true)).fork();
            }
        }, cleanupIntervalMillis, cleanupIntervalMillis);

        // TODO move this logic the messaging class
        // schedules the message cleanup
        timer.schedule(new TimerTask()
        {
            @Override
            public void run()
            {
                messaging.timeoutCleanup();
            }
        }, 5000, 5000);
    }

    private <T> Class<T> classForName(final String className)
    {
        return classForName(className, false);
    }

    @SuppressWarnings("unchecked")
    private <T> Class<T> classForName(final String className, boolean ignoreException)
    {
        try
        {
            return (Class<T>) Class.forName(className);
        }
        catch (Error | Exception ex)
        {
            if (!ignoreException)
            {
                throw new Error("Error loading class: " + className, ex);
            }
        }
        return null;
    }

    private ActorFactory<?> findFactoryFor(final Class<?> baseInterface, final Object instance)
    {
        for (Class<?> aInterface : instance.getClass().getInterfaces())
        {
            if (baseInterface.isAssignableFrom(aInterface))
            {
                ActorFactory<?> factory = getDescriptor(aInterface).factory;
                if (factory != null)
                {
                    return factory;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private InterfaceDescriptor getDescriptor(final Class<?> aInterface)
    {
        InterfaceDescriptor interfaceDescriptor = descriptorMapByInterface.get(aInterface);
        if (interfaceDescriptor == null)
        {
            if (aInterface == IActor.class || aInterface == IActorObserver.class || !aInterface.isInterface())
            {
                return null;
            }

            interfaceDescriptor = new InterfaceDescriptor();
            interfaceDescriptor.isObserver = IActorObserver.class.isAssignableFrom(aInterface);
            interfaceDescriptor.factory = dynamicReferenceFactory.getFactoryFor(aInterface);
            interfaceDescriptor.invoker = (ActorInvoker<Object>) interfaceDescriptor.factory.getInvoker();
            descriptorMapByInterface.put(aInterface, interfaceDescriptor);
            descriptorMapByInterfaceId.put(interfaceDescriptor.factory.getInterfaceId(), interfaceDescriptor);
        }
        return interfaceDescriptor;
    }

    private InterfaceDescriptor getDescriptor(final int interfaceId)
    {
        return descriptorMapByInterfaceId.get(interfaceId);
    }

    public void onMessageReceived(final INodeAddress from,
                                  final boolean oneway, final int messageId, final int interfaceId, final int methodId,
                                  final Object key, final Object[] params)
    {
        EntryKey entryKey = new EntryKey(interfaceId, key);
        if (logger.isDebugEnabled())
        {
            logger.debug("onMessageReceived for: " + entryKey);
        }
        messagesReceived.incrementAndGet();
        if (!executionSerializer.offerJob(entryKey,
                () -> handleOnMessageReceived(entryKey, from, oneway, messageId, interfaceId, methodId, key, params), maxQueueSize))
        {
            refusedExecutions.incrementAndGet();
            if (logger.isErrorEnabled())
            {
                logger.error("Execution refused: " + key + ":" + interfaceId + ":" + methodId + ":" + messageId);
            }
            if (!oneway)
            {
                messaging.sendResponse(from, MessageDefinitions.ERROR_RESPONSE, messageId, "Execution refused");
            }
        }
    }

    // this method is executed serially by entryKey
    private Task<?> handleOnMessageReceived(final EntryKey entryKey, final INodeAddress from,
                                            final boolean oneway, final int messageId, final int interfaceId,
                                            final int methodId, final Object key,
                                            final Object[] params)
    {
        messagesHandled.incrementAndGet();
        final InterfaceDescriptor descriptor = getDescriptor(interfaceId);
        if (descriptor.isObserver)
        {
            final IActorObserver observer = observerInstances.get(entryKey);
            if (observer == null)
            {
                if (!oneway)
                {
                    messaging.sendResponse(from, MessageDefinitions.ERROR_RESPONSE, messageId, "Observer no longer present");
                }
                return Task.done();
            }
            final Task<?> task = descriptor.invoker.safeInvoke(observer, methodId, params);
            return task.whenComplete((r, e) ->
                    sendResponseAndLogError(oneway, from, messageId, (Object) r, e));
        }

        ReferenceEntry entry = localActors.get(entryKey);


        if (logger.isDebugEnabled())
        {
            logger.debug("handleOnMessageReceived for: " + descriptor + ":" + key);
        }

        if (entry == null)
        {
            // TODO check if this is the activation node. Otherwise forward to the activation node.
            entry = new ReferenceEntry();
            entry.descriptor = descriptor;
            entry.statelessWorker = descriptor.factory.getInterface().isAnnotationPresent(StatelessWorker.class);
            if (entry.statelessWorker)
            {
                entry.statelessActivations = new ConcurrentLinkedDeque<>();
            }
            entry.reference = (ActorReference<?>) descriptor.factory.createReference(key != null ? String.valueOf(key) : null);
            entry.reference.runtime = this;
            entry.removable = true;

            ReferenceEntry old = localActors.putIfAbsent(entryKey, entry);
            if (old != null)
            {
                // this should be impossible if all accesses are serial.
                logger.error("Unexpected state: Non serial access to entry!");
                entry = old;
            }
        }

        final ReferenceEntry theEntry = entry;
        if (!entry.statelessWorker)
        {
            return executeMessage(theEntry, oneway, descriptor, methodId, params, from, messageId);
        }
        else
        {
            if (!executionSerializer.offerJob(null,
                    () -> executeMessage(theEntry, oneway, descriptor, methodId, params, from, messageId),
                    maxQueueSize))
            {
                refusedExecutions.incrementAndGet();
                if (logger.isErrorEnabled())
                {
                    logger.info("Execution refused: " + key + ":" + interfaceId + ":" + methodId + ":" + messageId);
                }
                if (!oneway)
                {
                    messaging.sendResponse(from, MessageDefinitions.ERROR_RESPONSE, messageId, "Execution refused");
                }
            }
            return Task.done();
        }

    }

    static class MessageContext
    {
        ReferenceEntry theEntry;
        int methodId;
        INodeAddress from;
        long traceId;
        public static final AtomicLong counter = new AtomicLong(0L);

        public MessageContext(final ReferenceEntry theEntry, final int methodId, final INodeAddress from)
        {
            traceId = counter.incrementAndGet();
            this.theEntry = theEntry;
            this.methodId = methodId;
            this.from = from;
        }
    }

    @Override
    public ActorReference getCurrentActivation()
    {
        MessageContext current = currentMessage.get();
        if (current == null)
        {
            return null;
        }
        return current.theEntry.reference;
    }

    @Override
    public long getCurrentTraceId()
    {
        MessageContext current = currentMessage.get();
        if (current == null)
        {
            return 0;
        }
        return current.traceId;
    }

    ThreadLocal<MessageContext> currentMessage = new ThreadLocal<>();

    private Task<?> executeMessage(
            final ReferenceEntry theEntry,
            final boolean oneway,
            final InterfaceDescriptor descriptor,
            final int methodId,
            final Object[] params,
            final INodeAddress from,
            final int messageId)
    {
        try
        {

            currentMessage.set(new MessageContext(theEntry, methodId, from));
            Activation activation = theEntry.popActivation();
            activation.lastAccess = clock.millis();
            Task<?> future;
            try
            {
                bind();
                future = descriptor.invoker.safeInvoke(activation.getOrCreateInstance(), methodId, params);
                return future.whenComplete((r, e) -> {
                    sendResponseAndLogError(oneway, from, messageId, r, e);
                });
            }
            finally
            {
                // we don't need to unset the Runtime, @see Runtime.setRuntime:
                theEntry.pushActivation(activation);
            }
        }
        catch (Exception ex)
        {
            sendResponseAndLogError(oneway, from, messageId, null, ex);
        }
        return Task.done();
    }

    protected void sendResponseAndLogError(boolean oneway, final INodeAddress from, int messageId, Object result, Throwable exception)
    {
        if (exception != null && logger.isErrorEnabled())
        {
            logger.error("Unknown application error. ", exception);
        }

        if (!oneway)
        {
            try
            {
                if (exception == null)
                {
                    messaging.sendResponse(from, MessageDefinitions.NORMAL_RESPONSE, messageId, result);
                }
                else
                {
                    messaging.sendResponse(from, MessageDefinitions.EXCEPTION_RESPONSE, messageId, exception);
                }
            }
            catch (Exception ex2)
            {
                if (logger.isErrorEnabled())
                {
                    logger.error("Error sending method result", ex2);
                }
                try
                {
                    messaging.sendResponse(from, MessageDefinitions.EXCEPTION_RESPONSE, messageId, ex2);
                }
                catch (Exception ex3)
                {
                    if (logger.isErrorEnabled())
                    {
                        logger.error("Failed twice sending result. ", ex2);
                    }
                    try
                    {
                        messaging.sendResponse(from, MessageDefinitions.ERROR_RESPONSE, messageId, "failed twice sending result");
                    }
                    catch (Exception ex4)
                    {
                        logger.error("Failed sending exception. ", ex4);
                    }
                }
            }
        }
    }


    @SuppressWarnings({ "unchecked" })
    <T> T createReference(final INodeAddress a, final Class<T> iClass, String id)
    {
        final InterfaceDescriptor descriptor = getDescriptor(iClass);
        ActorReference<?> reference = (ActorReference<?>) descriptor.factory.createReference("");
        reference.address = a;
        reference.runtime = this;
        reference.id = id;
        return (T) reference;
    }


    @SuppressWarnings("unchecked")
    public <T extends IActor> T getReference(final Class<T> iClass, final Object id)
    {
        final InterfaceDescriptor descriptor = getDescriptor(iClass);
        ActorReference<?> reference = (ActorReference<?>) descriptor.factory.createReference(id != null ? String.valueOf(id) : null);
        reference.runtime = this;
        return (T) reference;
    }


    /**
     * Returns an observer reference to an observer in another node.
     * <p/>
     * Should only be used if the application knows for sure that an observer with the given id
     * indeed exists on that other node.
     * <p/>
     * This is a low level use of orbit-actors, recommended only for IOrbitProviders.
     *
     * @param address the other node address.
     * @param iClass  the IObserverClass
     * @param id      the id, must not be null
     * @param <T>     the IActorObserver sub interface
     * @return a remote reference to the observer
     */
    @SuppressWarnings("unchecked")
    public <T extends IActorObserver> T getRemoteObserverReference(INodeAddress address, final Class<T> iClass, final Object id)
    {
        if (id == null)
        {
            throw new IllegalArgumentException("Null id for " + iClass);
        }
        if (iClass == null)
        {
            throw new IllegalArgumentException("Null class");
        }
        final InterfaceDescriptor descriptor = getDescriptor(iClass);
        ActorReference<?> reference = (ActorReference<?>) descriptor.factory.createReference(String.valueOf(id));
        reference.runtime = this;
        reference.address = address;
        return (T) reference;
    }

    public Task<?> sendMessage(IAddressable toReference, boolean oneWay, final int methodId, final Object[] params)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug("sending message to " + toReference);
        }
        ActorReference<?> actorReference = (ActorReference<?>) toReference;
        INodeAddress toNode = actorReference.address;
        if (toNode == null)
        {
            // TODO: Ensure that both paths encode exception the same way.
            return hosting.locateActor(actorReference)
                    .thenCompose(x -> messaging.sendMessage(x, oneWay, actorReference._interfaceId(), methodId, actorReference.id, params));
        }
        return messaging.sendMessage(toNode, oneWay, actorReference._interfaceId(), methodId, actorReference.id, params);
    }

    public Task<?> invoke(IAddressable toReference, Method m, boolean oneWay, final int methodId, final Object[] params)
    {
        final ActorReference source = getCurrentActivation();
        final long traceId = getCurrentTraceId();
        if (traceEnabled)
        {
            if (source != null)
            {
                String sourceInterface = ActorReference.getInterfaceClass(source).getName();
                String targetInterface = ActorReference.getInterfaceClass((ActorReference) toReference).getName();
                for (IInvokeListenerProvider v : getAllProviders(IInvokeListenerProvider.class))
                {
                    v.preInvoke(traceId, sourceInterface, String.valueOf(ActorReference.getId(source)), targetInterface, String.valueOf(ActorReference.getId((ActorReference) toReference)), methodId, params);
                }
            }
        }
        if (invokeHook == null)
        {
            return sendMessage(toReference, oneWay, methodId, params).whenComplete((r, e) -> {
                if (traceEnabled)
                {
                    for (IInvokeListenerProvider v : getAllProviders(IInvokeListenerProvider.class))
                    {
                        v.postInvoke(traceId, r);
                    }
                }
            });
        }
        else
        {
            return invokeHook.invoke(this, toReference, m, oneWay, methodId, params).whenComplete((r, e) -> {
                if (traceEnabled)
                {
                    for (IInvokeListenerProvider v : getAllProviders(IInvokeListenerProvider.class))
                    {
                        v.postInvoke(traceId, r);
                    }
                }
            });
        }
    }

    public void activationCleanup(final boolean block)
    {

        long cutOut = clock.millis() - TimeUnit.MINUTES.toMillis(10);
        final List<CompletableFuture<?>> futures = block ? new ArrayList<>() : null;
        for (Iterator<Map.Entry<EntryKey, ReferenceEntry>> iterator = localActors.entrySet().iterator(); iterator.hasNext(); )
        {
            Map.Entry<EntryKey, ReferenceEntry> mEntry = iterator.next();
            final ReferenceEntry entry = mEntry.getValue();
            if (!entry.removable)
            {
                continue;
            }
            Activation act = entry.peekOldActivation();
            if (act == null)
            {
                continue;
            }
            if (act.lastAccess < cutOut)
            {
                CompletableFuture<Object> future = new CompletableFuture<>();
                final Supplier<Task<?>> task = () -> {
                    try
                    {
                        final Task<?> res = entry.cleanup(mEntry.getKey(), cutOut);
                        if (res != null && !res.isDone())
                        {
                            res.whenComplete((r, e) -> {
                                if (e != null)
                                {
                                    future.completeExceptionally(e);
                                }
                                else
                                {
                                    future.complete(r);
                                }
                            });
                        }
                        else
                        {
                            future.complete(null);
                        }
                        return res;
                    }
                    catch (Error | RuntimeException ex)
                    {
                        future.completeExceptionally(ex);
                        throw ex;
                    }
                    catch (Throwable ex)
                    {
                        future.completeExceptionally(ex);
                        throw new UncheckedException(ex);
                    }
                };
                if (executionSerializer.offerJob(mEntry.getKey(), task, maxQueueSize) && block)
                {
                    futures.add(future);
                }
            }
        }
        if (block)
        {
            Task.allOf(futures).join();
        }
    }
}

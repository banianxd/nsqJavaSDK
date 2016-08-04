package com.youzan.nsq.client;

import com.youzan.nsq.client.core.KeyedPooledConnectionFactory;
import com.youzan.nsq.client.core.NSQConnection;
import com.youzan.nsq.client.core.NSQSimpleClient;
import com.youzan.nsq.client.core.command.*;
import com.youzan.nsq.client.entity.Address;
import com.youzan.nsq.client.entity.NSQConfig;
import com.youzan.nsq.client.entity.NSQMessage;
import com.youzan.nsq.client.entity.Response;
import com.youzan.nsq.client.exception.NSQException;
import com.youzan.nsq.client.exception.NSQInvalidDataNodeException;
import com.youzan.nsq.client.exception.NSQNoConnectionException;
import com.youzan.nsq.client.network.frame.ErrorFrame;
import com.youzan.nsq.client.network.frame.MessageFrame;
import com.youzan.nsq.client.network.frame.NSQFrame;
import com.youzan.nsq.client.network.frame.NSQFrame.FrameType;
import com.youzan.util.ConcurrentSortedSet;
import com.youzan.util.IOUtil;
import com.youzan.util.NamedThreadFactory;
import io.netty.channel.ChannelFuture;
import org.apache.commons.pool2.impl.GenericKeyedObjectPool;
import org.apache.commons.pool2.impl.GenericKeyedObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <pre>
 * Expose to Client Code. Connect to one cluster(includes many brokers).
 * </pre>
 * 
 * @author <a href="mailto:my_email@email.exmaple.com">zhaoxi (linzuxiong)</a>
 */
public class ConsumerImplV2 implements Consumer {

    private static final Logger logger = LoggerFactory.getLogger(ConsumerImplV2.class);
    private volatile boolean started = false;
    private final AtomicBoolean closing = new AtomicBoolean(false);

    private final NSQSimpleClient simpleClient;
    private final NSQConfig config;
    private final GenericKeyedObjectPoolConfig poolConfig;
    private final KeyedPooledConnectionFactory factory;
    private GenericKeyedObjectPool<Address, NSQConnection> bigPool = null;

    private final AtomicInteger receiving = new AtomicInteger(0);
    private final AtomicInteger success = new AtomicInteger(0);
    private final AtomicInteger total = new AtomicInteger(0);

    /*-
     * =========================================================================
     * 
     * =========================================================================
     */
    private final ConcurrentMap<Address, HashSet<NSQConnection>> holdingConnections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors
            .newSingleThreadScheduledExecutor(new NamedThreadFactory(this.getClass().getName(), Thread.NORM_PRIORITY));

    /*-
     * =========================================================================
     *                          Client delegate to me
     * =========================================================================
     */
    private final MessageHandler handler;
    private final int WORKER_SIZE = Runtime.getRuntime().availableProcessors() * 4;
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKER_SIZE,
            new NamedThreadFactory(this.getClass().getName() + "-ClientBusiness", Thread.MAX_PRIORITY));

    private final Rdy DEFAULT_RDY;
    private final Rdy MEDIUM_RDY;
    private final Rdy LOW_RDY;
    private volatile Rdy currentRdy;
    private volatile boolean autoFinish = true;

    /**
     * @param config
     *            NSQConfig
     * @param handler
     *            the client code sets it
     */
    public ConsumerImplV2(NSQConfig config, MessageHandler handler) {
        this.config = config;
        this.handler = handler;

        this.poolConfig = new GenericKeyedObjectPoolConfig();
        this.simpleClient = new NSQSimpleClient(config.getLookupAddresses());
        this.factory = new KeyedPooledConnectionFactory(this.config, this);

        int messagesPerBatch = config.getRdy();
        DEFAULT_RDY = new Rdy(Math.max(messagesPerBatch, 1));
        MEDIUM_RDY = new Rdy(Math.max((int) (messagesPerBatch * 0.3D), 1));
        LOW_RDY = new Rdy(1);
        currentRdy = DEFAULT_RDY;
    }

    @Override
    public void subscribe(String... topics) {
    }

    @Override
    public void start() throws NSQException {
        if (this.config.getConsumerName() == null || this.config.getConsumerName().isEmpty()) {
            throw new IllegalArgumentException("Consumer Name is blank! Please check it!");
        }
        if (!this.started) {
            this.started = true;
            // setting all of the configs
            this.poolConfig.setLifo(false);
            this.poolConfig.setFairness(true);
            this.poolConfig.setTestOnBorrow(false);
            this.poolConfig.setTestOnReturn(true);
            this.poolConfig.setTestWhileIdle(true);
            this.poolConfig.setJmxEnabled(false);
            //
            this.poolConfig.setMinEvictableIdleTimeMillis(-1);
            this.poolConfig.setSoftMinEvictableIdleTimeMillis(-1);
            this.poolConfig.setTimeBetweenEvictionRunsMillis(-1);
            //
            this.poolConfig.setMinIdlePerKey(this.config.getThreadPoolSize4IO());
            this.poolConfig.setMaxIdlePerKey(this.config.getThreadPoolSize4IO());
            this.poolConfig.setMaxTotalPerKey(this.config.getThreadPoolSize4IO());
            // aquire connection waiting time underlying the inner network
            this.poolConfig.setMaxWaitMillis(50);
            this.poolConfig.setBlockWhenExhausted(true);
            this.simpleClient.start();
            createBigPool();
            // POST
            connect();
            keepConnecting();
            logger.info("The consumer is started.");
        }
    }

    /**
     * new instance without performing to connect
     */
    private void createBigPool() {
        this.bigPool = new GenericKeyedObjectPool<>(this.factory, this.poolConfig);
    }

    /**
     * schedule action
     */
    private void keepConnecting() {
        final int delay = _r.nextInt(60); // seconds
        scheduler.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    connect();
                } catch (Exception e) {
                    logger.error("Exception", e);
                }
            }
        }, delay, _INTERVAL_IN_SECOND, TimeUnit.SECONDS);
    }

    /**
     * Connect to all the brokers with the config, making sure the new are OK
     * and the old are clear.
     */
    private void connect() throws NSQException {
        final Set<Address> broken = new HashSet<>();
        for (final HashSet<NSQConnection> connections : holdingConnections.values()) {
            for (final NSQConnection c : connections) {
                try {
                    if (!c.isConnected()) {
                        c.close();
                        broken.add(c.getAddress());
                    }
                } catch (Exception e) {
                    logger.error("Exception occurs while detecting broken connections!", e);
                }
            }
        }
        // JDK7
        final String topic = config.getTopic();
        final ConcurrentSortedSet<Address> newNodes = simpleClient.getDataNodes(topic);
        final Set<Address> oldAddresses = this.holdingConnections.keySet();
        final Set<Address> newDataNodes = newNodes.newSortedSet();
        final Set<Address> oldDataNodes = new TreeSet<>(oldAddresses);
        logger.debug("Prepare to connect new data-nodes(NSQd): {} , old data-nodes(NSQd): {}", newDataNodes,
                oldDataNodes);
        if (newDataNodes.isEmpty() && oldDataNodes.isEmpty()) {
            return;
        }
        if (newDataNodes.isEmpty()) {
            logger.error("Get the current new DataNodes (NSQd). It will create a new pool next time!");
        }
        /*-
         * =====================================================================
         *                                Step 1:
         *                    以newDataNodes为主的差集: 新建Brokers
         * =====================================================================
         */
        final Set<Address> except1 = new HashSet<>(newDataNodes);
        except1.removeAll(oldDataNodes);
        if (except1.isEmpty()) {
            // logger.debug("No need to create new NSQd connections!");
        } else {
            newConnections(except1);
        }
        /*-
         * =====================================================================
         *                                Step 2:
         *                    以oldDataNodes为主的差集: 删除Brokers
         * =====================================================================
         */
        final Set<Address> except2 = new HashSet<>(oldDataNodes);
        except2.removeAll(newDataNodes);
        if (except2.isEmpty()) {
            // logger.debug("No need to destory old NSQd connections!");
        } else {
            for (Address address : except2) {
                if (address == null) {
                    return;
                }
                bigPool.clear(address);
                if (holdingConnections.containsKey(address)) {
                    final Set<NSQConnection> conns = holdingConnections.get(address);
                    if (conns != null) {
                        for (NSQConnection c : conns) {
                            try {
                                backoff(c);
                            } catch (Exception e) {
                                logger.error("It can not backoff the connection!", e);
                            } finally {
                                IOUtil.closeQuietly(c);
                            }
                        }
                    }
                }
                holdingConnections.remove(address);
            }
        }

        /*-
         * =====================================================================
         *                                Step 3:
         *                          干掉Broken Brokers.
         * =====================================================================
         */
        for (Address address : broken) {
            if (address == null) {
                continue;
            }
            try {
                clearDataNode(address);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        }

        // JDK8
        /*
        broken.parallelStream().forEach((address) -> {
            if (address == null) {
                return;
            }
            try {
                clearDataNode(address);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        });
        */
        /*-
         * =====================================================================
         *                                Step 4:
         *                          Clean up local resources
         * =====================================================================
         */
        broken.clear();
        except1.clear();
        except2.clear();
    }

    /**
     * @param address
     *            the data-node(NSQd)'s address
     */
    @Override
    public void clearDataNode(Address address) {
        if (address == null) {
            return;
        }
        holdingConnections.remove(address);
        factory.clear(address);
        bigPool.clear(address);
    }

    /**
     * @param brokers
     *            the data-node(NSQd)'s addresses
     */
    private void newConnections(final Set<Address> brokers) {
        for (Address address : brokers) {
            try {
                newConnections4OneBroker(address);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        }
    }

    /**
     * @param address
     *            the broker address
     * @throws NSQException
     */
    private void newConnections4OneBroker(Address address) throws NSQException, Exception {
        if (address == null) {
            logger.error("Your input address is blank!");
            return;
        }
        bigPool.clear(address);
        bigPool.preparePool(address);
        // create new pool(connect to one broker)
        final List<NSQConnection> okConns = new ArrayList<>(config.getThreadPoolSize4IO());
        for (int i = 0; i < config.getThreadPoolSize4IO(); i++) {
            NSQConnection newConn = null;
            try {
                newConn = bigPool.borrowObject(address);
                initConn(newConn); // subscribe
                if (!holdingConnections.containsKey(address)) {
                    // JDK7
                    holdingConnections.putIfAbsent(address, new HashSet<NSQConnection>());
                }
                holdingConnections.get(address).add(newConn);
                okConns.add(newConn);
            } catch (Exception e) {
                logger.error("Address: {} . Exception: {}", address, e);
                if (newConn != null) {
                    IOUtil.closeQuietly(newConn);
                    bigPool.returnObject(address, newConn);
                    if (holdingConnections.get(address) != null) {
                        holdingConnections.get(address).remove(newConn);
                    }
                }
                if (e instanceof NSQException) {
                    throw (NSQException) e;
                }
            }
        }
        // finally
        for (NSQConnection c : okConns) {
            try {
                // long connected
                bigPool.returnObject(c.getAddress(), c);
            } catch (Exception e) {
                logger.error("Exception", e);
            }
        }
        if (okConns.size() == config.getThreadPoolSize4IO()) {
            logger.info("Having created a pool for one broker ( {} connections to 1 broker ), it felt good.",
                    okConns.size());
        } else {
            logger.info("Want the pool size {} , actually the size {}", config.getThreadPoolSize4IO(), okConns.size());
        }
        okConns.clear();
    }

    /**
     * @param newConn
     * @throws TimeoutException
     * @throws NSQException
     */
    private void initConn(NSQConnection newConn) throws TimeoutException, NSQException {
        final NSQFrame frame = newConn.commandAndGetResponse(new Sub(config.getTopic(), config.getConsumerName()));
        if (frame != null && frame.getType() == FrameType.ERROR_FRAME) {
            final ErrorFrame err = (ErrorFrame) frame;
            logger.error("Address: {} got one error {} , that is {}", newConn.getAddress(), err, err.getError());
            switch (err.getError()) {
                case E_FAILED_ON_NOT_LEADER: {
                }
                case E_FAILED_ON_NOT_WRITABLE: {
                }
                case E_TOPIC_NOT_EXIST: {
                    clearDataNode(newConn.getAddress());
                    logger.error("Adress: {} , Frame: {}", newConn.getAddress(), frame);
                    throw new NSQInvalidDataNodeException();
                }
                default: {
                    throw new NSQException("Unkown response error!");
                }
            }
        }
        currentRdy = DEFAULT_RDY;
        newConn.command(currentRdy);
    }

    @Override
    public void incoming(final NSQFrame frame, final NSQConnection conn) throws NSQException {
        if (frame != null && frame.getType() == FrameType.MESSAGE_FRAME) {
            receiving.incrementAndGet();
            final MessageFrame msg = (MessageFrame) frame;
            final NSQMessage message = new NSQMessage(msg.getTimestamp(), msg.getAttempts(), msg.getMessageID(),
                    msg.getMessageBody(), conn.getAddress(), Integer.valueOf(conn.getId()));
            processMessage(message, conn);
            return;
        }
        simpleClient.incoming(frame, conn);
    }

    protected void processMessage(final NSQMessage message, final NSQConnection conn) {
        if (handler == null) {
            logger.error("No MessageHandler then drop the message {}", message);
            return;
        }
        try {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        consume(message, conn);
                        success.incrementAndGet();
                    } catch (Exception e) {
                        IOUtil.closeQuietly(conn);
                        logger.error("Exception", e);
                    }
                }
            });
        } catch (RejectedExecutionException re) {
            try {
                backoff(conn);
                conn.command(new ReQueue(message.getMessageID(), 3));
                logger.info("Do a re-queue. MessageID:{}", message.getMessageID());
                resumeRateLimiting(conn, 0);
            } catch (Exception e) {
                logger.error("I can not handle it MessageID:{}, {}", message.getMessageID(), e);
            }
        }
        total.incrementAndGet();
        resumeRateLimiting(conn, 1000);
    }

    private void resumeRateLimiting(final NSQConnection conn, final int delayInMillisecond) {
        if (executor instanceof ThreadPoolExecutor) {
            if (delayInMillisecond <= 0) {
                final ThreadPoolExecutor pools = (ThreadPoolExecutor) executor;
                final double threshold = pools.getActiveCount() / (1.0D * pools.getPoolSize());
                logger.info("Current status is not good. threshold: {}", threshold);
                if (threshold >= 0.9D) {
                    currentRdy = LOW_RDY;
                } else if (threshold >= 0.8D) {
                    currentRdy = MEDIUM_RDY;
                } else {
                    currentRdy = DEFAULT_RDY;
                }
                // Ignore the data-race
                conn.command(currentRdy);
            } else {
                if (currentRdy != DEFAULT_RDY) {
                    final ThreadPoolExecutor pools = (ThreadPoolExecutor) executor;
                    final double threshold = pools.getActiveCount() / (1.0D * pools.getPoolSize());
                    logger.info("Current threshold state: {}", threshold);
                    scheduler.schedule(new Runnable() {
                        @Override
                        public void run() {
                            // restore the state
                            if (threshold <= 0.3D) {
                                currentRdy = DEFAULT_RDY;
                                conn.command(currentRdy);
                            }
                        }
                    }, delayInMillisecond, TimeUnit.MILLISECONDS);
                }
            }
        } else {
            logger.error("Initing the executor is wroing.");
        }
        assert currentRdy != null;
    }

    /**
     * @param message
     * @param conn
     */
    private void consume(final NSQMessage message, final NSQConnection conn) {
        boolean ok = false;
        int c = 0;
        while (c++ < 2) {
            try {
                handler.process(message);
                ok = true;
                break;
            } catch (Exception e) {
                ok = false;
                logger.error("CurrentRetries: {} , Exception: {}", c, e);
            }
        }
        // The client commands ReQueue into NSQd.
        final Integer timeout = message.getNextConsumingInSecond();
        // It is too complex.
        NSQCommand cmd = null;
        if (autoFinish) {
            // Either Finish or ReQueue
            if (ok) {
                // Finish
                cmd = new Finish(message.getMessageID());
            } else {
                if (timeout != null) {
                    // ReQueue
                    cmd = new ReQueue(message.getMessageID(), timeout.intValue());
                    logger.info("Do a re-queue. MessageID: {}", message.getMessageID());
                } else {
                    // Finish
                    cmd = new Finish(message.getMessageID());
                }
            }
        } else {
            // Client code does finish explicitly.
            // Maybe ReQueue, but absolutely no Finish
            if (!ok) {
                if (timeout != null) {
                    // ReQueue
                    cmd = new ReQueue(message.getMessageID(), timeout.intValue());
                    logger.info("Do a re-queue. MessageID: {}", message.getMessageID());
                }
            } else {
                // ignore actions
                cmd = null;
            }
        }
        if (cmd != null) {
            conn.command(cmd);
        }
        // Post
        if (message.getReadableAttempts() > 10) {
            logger.error("{} , Processing 10 times is still a failure!", message);
        }
        if (!ok) {
            logger.error("{} , exception occurs but you don't catch it! Please check it right now!!!", message);
        }
    }

    @Override
    public void backoff(NSQConnection conn) {
        simpleClient.backoff(conn);
    }

    @Override
    public void close() {
        closing.set(true);
        started = false;

        cleanClose();
        if (factory != null) {
            factory.close();
        }
        if (bigPool != null) {
            bigPool.close();
        }
        IOUtil.closeQuietly(simpleClient);
    }

    private void cleanClose() {
        for (HashSet<NSQConnection> conns : holdingConnections.values()) {
            for (final NSQConnection c : conns) {
                try {
                    backoff(c);
                    final NSQFrame frame = c.commandAndGetResponse(Close.getInstance());
                    if (frame != null && frame.getType() == FrameType.ERROR_FRAME) {
                        final Response err = ((ErrorFrame) frame).getError();
                        if (err != null) {
                            logger.error(err.getContent());
                        }
                    }
                } catch (Exception e) {
                    logger.error("Exception", e);
                } finally {
                    IOUtil.closeQuietly(c);
                }
            }
        }

        holdingConnections.clear();
    }

    @Override
    public boolean validateHeartbeat(NSQConnection conn) {
        currentRdy = DEFAULT_RDY;
        final ChannelFuture future = conn.command(currentRdy);
        if (future.awaitUninterruptibly(50, TimeUnit.MILLISECONDS)) {
            return future.isSuccess();
        }
        return false;
    }

    @Override
    public void finish(NSQMessage message) throws NSQException {
        final HashSet<NSQConnection> connections = holdingConnections.get(message.getAddress());
        if (connections != null) {
            for (NSQConnection c : connections) {
                if (c.getId() == message.getConnectionID().intValue()) {
                    if (c.isConnected()) {
                        c.command(new Finish(message.getMessageID()));
                        // It is OK.
                        return;
                    }
                    break;
                }
            }
        }
        throw new NSQNoConnectionException(
                "The connection is broken so that cann't retry. Please wait next consuming.");
    }

    @Override
    public void setAutoFinish(boolean autoFinish) {
        this.autoFinish = autoFinish;
    }

}

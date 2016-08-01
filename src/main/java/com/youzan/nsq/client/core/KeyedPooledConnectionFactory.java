/**
 * 
 */
package com.youzan.nsq.client.core;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.pool2.BaseKeyedPooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.youzan.nsq.client.entity.Address;
import com.youzan.nsq.client.entity.NSQConfig;
import com.youzan.nsq.client.exception.NSQNoConnectionException;
import com.youzan.nsq.client.network.netty.NSQClientInitializer;
import com.youzan.util.IOUtil;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.Future;

/**
 * <pre>
 * It is a big pool that consists of some sub-pools. 
 * Just handle TCP-Connection Object.
 * </pre>
 * 
 * @author <a href="mailto:my_email@email.exmaple.com">zhaoxi (linzuxiong)</a>
 */
public class KeyedPooledConnectionFactory extends BaseKeyedPooledObjectFactory<Address, NSQConnection> {

    private static final Logger logger = LoggerFactory.getLogger(KeyedPooledConnectionFactory.class);

    private final AtomicInteger idGenerator = new AtomicInteger(0);

    /**
     * Connection/Pool configurations
     */
    private final NSQConfig config;
    /**
     * Because of the protocol initialization
     */
    private final Client client;

    private final EventLoopGroup eventLoopGroup;
    private final ConcurrentMap<Address, Bootstrap> bootstraps = new ConcurrentHashMap<>();

    public KeyedPooledConnectionFactory(NSQConfig config, Client client) {
        this.config = config;
        this.client = client;
        this.eventLoopGroup = new NioEventLoopGroup(config.getThreadPoolSize4IO());
    }

    @Override
    public NSQConnection create(Address addr) throws Exception {
        logger.debug("Begin to create a connection, the address is {}", addr);
        final Bootstrap bootstrap;
        if (bootstraps.containsKey(addr)) {
            bootstrap = bootstraps.get(addr);
        } else {
            bootstrap = new Bootstrap();
            bootstraps.putIfAbsent(addr, bootstrap);
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.group(eventLoopGroup);
            bootstrap.channel(NioSocketChannel.class);
            bootstrap.handler(new NSQClientInitializer());
        }
        final ChannelFuture future = bootstrap.connect(addr.getHost(), addr.getPort());

        // Wait until the connection attempt succeeds or fails.
        if (!future.awaitUninterruptibly(config.getConnectTimeoutInMillisecond(), TimeUnit.MILLISECONDS)) {
            throw new NSQNoConnectionException(future.cause());
        }
        final Channel channel = future.channel();
        if (!future.isSuccess()) {
            if (channel != null) {
                channel.close();
            }
            throw new NSQNoConnectionException("Connect " + addr + " is wrong.", future.cause());
        }

        final NSQConnection conn = new NSQConnectionImpl(addr, channel, config, idGenerator.incrementAndGet());
        // Netty async+sync programming
        channel.attr(NSQConnection.STATE).set(conn);
        channel.attr(Client.STATE).set(client);
        try {
            conn.init();
        } catch (Exception e) {
            IOUtil.closeQuietly(conn);
            throw new NSQNoConnectionException("Creating a connection and having a negotiation fails!", e);
        }

        if (!conn.isConnected()) {
            IOUtil.closeQuietly(conn);
            throw new NSQNoConnectionException("Pool failed in connecting to NSQd!");
        }
        return conn;
    }

    @Override
    public PooledObject<NSQConnection> wrap(NSQConnection conn) {
        return new DefaultPooledObject<>(conn);
    }

    @Override
    public boolean validateObject(Address addr, PooledObject<NSQConnection> p) {
        final NSQConnection conn = p.getObject();
        // another implementation : use client.heartbeat,or called
        // client.validateConnection
        if (null != conn && conn.isConnected()) {
            return client.validateHeartbeat(conn);
        }
        logger.debug("Validate {} connection! Conn is false.", addr);
        return false;
    }

    @Override
    public void destroyObject(Address addr, PooledObject<NSQConnection> p) throws Exception {
        p.getObject().close();
    }

    public void clear(Address addr) {
        bootstraps.remove(addr);
    }

    public void close() {
        if (bootstraps != null) {
            bootstraps.clear();
        }
        if (eventLoopGroup != null && !eventLoopGroup.isShuttingDown()) {
            Future<?> future = eventLoopGroup.shutdownGracefully(1, 2, TimeUnit.SECONDS);
        }
    }
}

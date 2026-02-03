package com.rcszh.nettydemo.tcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Netty TCP 服务（Spring 生命周期托管）。
 *
 * <p>启动时绑定端口并开始监听；停止时优雅关闭 boss/worker 线程组。</p>
 */
@Component
public class NettyTcpServer implements SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(NettyTcpServer.class);

    private final NettyServerProperties properties;
    private final TcpServerInitializer initializer;

    private final CompletableFuture<Integer> boundPortFuture = new CompletableFuture<>();

    private volatile boolean running;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * 构造 TCP 服务。
     *
     * @param properties   服务配置
     * @param initializer  pipeline 初始化器
     */
    public NettyTcpServer(NettyServerProperties properties, TcpServerInitializer initializer) {
        this.properties = properties;
        this.initializer = initializer;
    }

    /**
     * 启动服务并绑定端口（同步绑定）。
     */
    @Override
    public void start() {
        if (running) {
            return;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            ChannelFuture bindFuture = bootstrap.bind(properties.getPort()).syncUninterruptibly();
            serverChannel = bindFuture.channel();

            int boundPort = ((InetSocketAddress) serverChannel.localAddress()).getPort();
            boundPortFuture.complete(boundPort);
            running = true;

            log.info("Netty TCP 服务启动成功：端口={}", boundPort);
        } catch (Exception e) {
            boundPortFuture.completeExceptionally(e);
            log.error("Netty TCP 服务启动失败", e);
            stop();
        }
    }

    /**
     * 停止服务并释放资源（同步关闭）。
     */
    @Override
    public void stop() {
        if (!running && bossGroup == null && workerGroup == null) {
            return;
        }

        running = false;
        if (serverChannel != null) {
            serverChannel.close().syncUninterruptibly();
            serverChannel = null;
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            bossGroup = null;
        }
    }

    /**
     * 停止服务（带回调）。
     */
    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    /**
     * 当前是否处于运行中。
     */
    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * 是否随 Spring 容器自动启动。
     */
    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 等待绑定成功并返回实际监听端口。
     */
    public int awaitBoundPort(Duration timeout) {
        try {
            return boundPortFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Netty TCP 服务在指定时间内未完成端口绑定：timeout=" + timeout, e);
        }
    }
}

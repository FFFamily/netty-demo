package com.rcszh.nettydemo.tcp.handler;

import com.rcszh.nettydemo.tcp.NettyServerProperties;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;

/**
 * TCP 二进制数据处理器：将收到的数据按十六进制（hex）打印。
 *
 * <p>该处理器刻意保持“无业务语义”：只负责记录/（可选）回显，不做协议解析。
 * 如果需要按消息边界处理（例如 Modbus RTU），请在它之前加分包解码器（如 {@code ModbusRtuFrameDecoder}）。</p>
 */
@Component
@ChannelHandler.Sharable
public class TcpBinaryLoggingHandler extends SimpleChannelInboundHandler<ByteBuf> {
    private static final Logger log = LoggerFactory.getLogger(TcpBinaryLoggingHandler.class);

    private final NettyServerProperties properties;

    /**
     * 构造二进制日志处理器。
     *
     * @param properties 服务配置
     */
    public TcpBinaryLoggingHandler(NettyServerProperties properties) {
        this.properties = properties;
    }

    /**
     * 连接建立回调。
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("TCP 连接建立：远端={}", remote(ctx));
    }

    /**
     * 连接断开回调。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("TCP 连接断开：远端={}", remote(ctx));
    }

    /**
     * 处理入站二进制消息（ByteBuf）。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
        int len = msg.readableBytes();
        // 限制日志输出长度，避免二进制流/大包刷爆日志。
        int dumpLen = Math.min(len, 4096);
        String hex = ByteBufUtil.hexDump(msg, msg.readerIndex(), dumpLen);
        log.info("TCP 收到二进制数据：远端={} 字节数={} 十六进制{}={}",
                remote(ctx),
                len,
                len > dumpLen ? "（前 " + dumpLen + " 字节）" : "",
                hex);

        if (properties.isRespondEnabled()) {
            // 默认回显字节；真实协议请替换为协议响应（例如 Modbus RTU 从站响应）。
            ctx.writeAndFlush(msg.retainedDuplicate());
        }
    }

    /**
     * 异常处理：记录并关闭连接。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("TCP 异常：远端={}", remote(ctx), cause);
        ctx.close();
    }

    private String remote(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress a) {
            return a.getAddress().getHostAddress() + ":" + a.getPort();
        }
        return String.valueOf(ctx.channel().remoteAddress());
    }
}

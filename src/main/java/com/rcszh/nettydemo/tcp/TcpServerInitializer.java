package com.rcszh.nettydemo.tcp;

import com.rcszh.nettydemo.tcp.decoder.JsonDecoder;
import com.rcszh.nettydemo.tcp.handler.TcpServerHandler;
import com.rcszh.nettydemo.tcp.handler.TcpBinaryLoggingHandler;
import com.rcszh.nettydemo.tcp.codec.AutoProtocolSwitchingDecoder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;
import org.springframework.stereotype.Component;

/**
 * TCP Channel 初始化：根据配置组装 Netty pipeline（分包/解码/业务处理）。
 */
@Component
public class TcpServerInitializer extends ChannelInitializer<SocketChannel> {

    private final NettyServerProperties properties;
    private final TcpServerHandler handler;
    private final TcpBinaryLoggingHandler binaryHandler;

    /**
     * 构造 pipeline 初始化器。
     *
     * @param properties    服务配置
     * @param handler       JSON 文本处理器
     * @param binaryHandler 二进制处理器
     */
    public TcpServerInitializer(NettyServerProperties properties, TcpServerHandler handler, TcpBinaryLoggingHandler binaryHandler) {
        this.properties = properties;
        this.handler = handler;
        this.binaryHandler = binaryHandler;
    }

    /**
     * 为每条新连接初始化 pipeline。
     */
    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new IdleStateHandler(properties.getReaderIdleSeconds(), 0, 0));

        switch (properties.getFraming()) {
            case AUTO -> ch.pipeline()
                    // 按连接自动识别协议；无法判断时默认按 Modbus RTU 处理。
                    .addLast(new AutoProtocolSwitchingDecoder(properties, handler, binaryHandler));

            case RAW -> ch.pipeline()
                    // 不做分包/解码，直接把 ByteBuf 交给二进制处理器（常用于二进制协议）。
                    .addLast(binaryHandler);

            case JSON_OBJECT -> {
                String delimiterStr = "";

                ch.pipeline()
                        // JSON 流分包：从 TCP 字节流中识别完整 JSON 对象/数组。
//                    .addLast(new JsonObjectDecoder(properties.getMaxFrameLength()))
//                     .addLast(new StringDecoder(CharsetUtil.UTF_8))
//                    .addLast(new StringEncoder(CharsetUtil.UTF_8))
                        .addLast(new JsonDecoder(properties.getMaxFrameLength()))

                        .addLast(handler);
            }


            case LINE -> ch.pipeline()
                    // 按换行分包（\\n/\\r\\n）。
                    .addLast(new LineBasedFrameDecoder(properties.getMaxFrameLength()))
                    .addLast(new StringDecoder(CharsetUtil.UTF_8))
                    .addLast(new StringEncoder(CharsetUtil.UTF_8))
                    // 出站：追加换行，便于对端按行读取响应。
                    .addLast(new com.rcszh.nettydemo.tcp.codec.LineDelimiterEncoder())
                    .addLast(handler);

            case LENGTH_FIELD -> ch.pipeline()
                    // 入站：4 字节大端长度字段（偏移=0），解码后剥离长度字段再交给下游。
                    .addLast(new LengthFieldBasedFrameDecoder(
                            properties.getMaxFrameLength(),
                            0,
                            4,
                            0,
                            4
                    ))
                    .addLast(new StringDecoder(CharsetUtil.UTF_8))
                    // 出站：字符串编码为字节后追加 4 字节长度字段。
                    .addLast(new LengthFieldPrepender(4))
                    .addLast(new StringEncoder(CharsetUtil.UTF_8))
                    .addLast(handler);

            case MODBUS_RTU -> ch.pipeline()
                    .addLast(new com.rcszh.nettydemo.tcp.codec.ModbusRtuFrameDecoder(properties.getMaxFrameLength()))
                    .addLast(binaryHandler);
        }
    }
}

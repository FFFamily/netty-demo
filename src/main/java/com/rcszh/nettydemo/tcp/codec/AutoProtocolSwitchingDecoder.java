package com.rcszh.nettydemo.tcp.codec;

import com.rcszh.nettydemo.tcp.NettyServerProperties;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;

import java.util.List;

/**
 * 自动协议识别（best-effort）：按连接探测一次并切换 pipeline。
 *
 * <p>当前支持：
 * <ul>
 *   <li>JSON：首个非空白字符是 '{' / '[' -&gt; {@link StringEncoder} + JSON 业务处理器（支持无分包 JSON 流）</li>
 *   <li>Modbus RTU：从 readerIndex 起始处可校验出合法 CRC16 -&gt; {@link ModbusRtuFrameDecoder}</li>
 * </ul>
 *
 * <p>如果在缓冲到一定字节数后仍无法判断，则默认按 Modbus RTU 处理。</p>
 */
public final class AutoProtocolSwitchingDecoder extends ByteToMessageDecoder {

    private static final int DEFAULT_DETECT_BYTES = 64;

    private final NettyServerProperties properties;
    private final ChannelHandler jsonHandler;
    private final ChannelHandler binaryHandler;
    private final int detectBytes;

    /**
     * 构造自动协议探测器（使用默认探测字节数）。
     *
     * @param properties     服务配置
     * @param jsonHandler    JSON 处理器（通常是 {@code TcpServerHandler}）
     * @param binaryHandler  二进制处理器（通常是 {@code TcpBinaryLoggingHandler}）
     */
    public AutoProtocolSwitchingDecoder(NettyServerProperties properties, ChannelHandler jsonHandler, ChannelHandler binaryHandler) {
        this(properties, jsonHandler, binaryHandler, DEFAULT_DETECT_BYTES);
    }

    /**
     * 构造自动协议探测器。
     *
     * @param properties     服务配置
     * @param jsonHandler    JSON 处理器（通常是 {@code TcpServerHandler}）
     * @param binaryHandler  二进制处理器（通常是 {@code TcpBinaryLoggingHandler}）
     * @param detectBytes    无法判断时最多缓冲多少字节后默认走 Modbus RTU
     */
    public AutoProtocolSwitchingDecoder(NettyServerProperties properties,
                                        ChannelHandler jsonHandler,
                                        ChannelHandler binaryHandler,
                                        int detectBytes) {
        if (properties == null || jsonHandler == null || binaryHandler == null) {
            throw new IllegalArgumentException("properties/jsonHandler/binaryHandler 不能为空");
        }
        if (detectBytes <= 0) {
            throw new IllegalArgumentException("detectBytes 必须大于 0");
        }
        this.properties = properties;
        this.jsonHandler = jsonHandler;
        this.binaryHandler = binaryHandler;
        this.detectBytes = detectBytes;
    }

    /**
     * 读取入站字节并尝试判断协议类型；一旦判断成功，会替换自身为对应协议的解码器/处理器链。
     */
    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (!in.isReadable()) {
            return;
        }

        if (looksLikeJson(in)) {
            switchToJson(ctx, in);
            return;
        }

        if (looksLikeModbusRtu(in)) {
            switchToModbusRtu(ctx, in);
            return;
        }

        // 还不足以判断：继续缓冲一些字节；仍无法判断则默认走 Modbus RTU。
        if (in.readableBytes() >= detectBytes) {
            switchToModbusRtu(ctx, in);
        }
    }

    private void switchToJson(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in) {
        ByteBuf pending = in.readRetainedSlice(in.readableBytes());

        ChannelPipeline p = ctx.pipeline();
        String selfName = ctx.name();

        // JSON 处理器本身支持无分包 JSON 流；这里只需要保留出站编码器。
        p.replace(selfName, "stringEncoder", new StringEncoder(CharsetUtil.UTF_8));
        p.addAfter("stringEncoder", "tcpJsonHandler", jsonHandler);

        // 重新注入已读取的字节，让新的处理链继续解码。
        p.fireChannelRead(pending);
    }

    private void switchToModbusRtu(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in) {
        ByteBuf pending = in.readRetainedSlice(in.readableBytes());

        ChannelPipeline p = ctx.pipeline();
        String selfName = ctx.name();

        p.replace(selfName, "modbusRtuFrameDecoder", new ModbusRtuFrameDecoder(properties.getMaxFrameLength()));
        p.addAfter("modbusRtuFrameDecoder", "tcpBinaryHandler", binaryHandler);

        p.fireChannelRead(pending);
    }

    private static boolean looksLikeJson(ByteBuf in) {
        int i = in.readerIndex();
        int end = in.writerIndex();
        while (i < end) {
            int b = in.getUnsignedByte(i);
            if (b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                i++;
                continue;
            }
            return b == '{' || b == '[';
        }
        return false;
    }

    private static boolean looksLikeModbusRtu(ByteBuf in) {
        // 只有在 readerIndex 起始位置能校验出“像样”的 CRC 时，才认为可能是 RTU。
        if (in.readableBytes() < 5) {
            return false;
        }
        int start = in.readerIndex();
        int unitId = in.getUnsignedByte(start);
        if (unitId > 247) {
            return false;
        }

        int function = in.getUnsignedByte(start + 1);

        // 异常响应：addr + (func|0x80) + exCode + CRC(2)
        if ((function & 0x80) != 0) {
            return in.readableBytes() >= 5 && hasValidCrc(in, start, 5);
        }

        // 常见定长请求/响应（很多请求是 8 字节）
        if (in.readableBytes() >= 8 && hasValidCrc(in, start, 8)) {
            return true;
        }

        // 读响应：addr + func + byteCount + data + CRC(2)
        if (in.readableBytes() >= 3) {
            int byteCount = in.getUnsignedByte(start + 2);
            int len = 5 + byteCount;
            if (len >= 5 && in.readableBytes() >= len && hasValidCrc(in, start, len)) {
                return true;
            }
        }

        // 写多个请求：addr + func + start(2) + qty(2) + byteCount + data + CRC(2)
        if ((function == 0x0F || function == 0x10) && in.readableBytes() >= 7) {
            int byteCount = in.getUnsignedByte(start + 6);
            int len = 9 + byteCount;
            return len >= 9 && in.readableBytes() >= len && hasValidCrc(in, start, len);
        }

        return false;
    }

    private static boolean hasValidCrc(ByteBuf in, int index, int len) {
        int expected = crc16Modbus(in, index, len - 2);
        int lo = in.getUnsignedByte(index + len - 2);
        int hi = in.getUnsignedByte(index + len - 1);
        int actual = lo | (hi << 8); // Modbus RTU CRC 为小端
        return expected == actual;
    }

    private static int crc16Modbus(ByteBuf buf, int index, int length) {
        int crc = 0xFFFF;
        for (int i = 0; i < length; i++) {
            crc ^= buf.getUnsignedByte(index + i);
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ 0xA001;
                } else {
                    crc = (crc >>> 1);
                }
            }
        }
        return crc & 0xFFFF;
    }
}

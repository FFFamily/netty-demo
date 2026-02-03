package com.rcszh.nettydemo.tcp.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.TooLongFrameException;

import java.util.ArrayList;
import java.util.List;

/**
 * Modbus RTU 分包解码器：通过 CRC16（多项式 0xA001）从字节流中识别完整 RTU 帧。
 *
 * <p>说明：Modbus RTU 通常跑在串口上，靠“帧间隔静默”来区分帧边界。
 * 该解码器用于“RTU over TCP”的场景：对端把 RTU 帧直接拼接在 TCP 流里连续发送。</p>
 */
public final class ModbusRtuFrameDecoder extends ByteToMessageDecoder {

    private final int maxFrameLength;

    /**
     * 构造 Modbus RTU 分包解码器。
     *
     * @param maxFrameLength 单帧最大长度（字节）
     */
    public ModbusRtuFrameDecoder(int maxFrameLength) {
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("maxFrameLength 必须大于 0");
        }
        this.maxFrameLength = maxFrameLength;
    }

    /**
     * 从入站缓冲区中尽可能多地解析出完整 RTU 帧，并输出到 {@code out}。
     */
    @Override
    protected void decode(io.netty.channel.ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        // 尽可能多地解析出完整帧。
        while (true) {
            if (in.readableBytes() < 5) {
                return; // 最小 RTU 帧长度（异常响应）
            }

            int start = in.readerIndex();
            int readable = in.readableBytes();
            if (readable > maxFrameLength) {
                // 如果缓冲区超过上限，通过丢弃多余字节尝试重新同步，避免畸形流导致内存无限增长。
                int drop = readable - maxFrameLength;
                in.skipBytes(drop);
                start = in.readerIndex();
                readable = in.readableBytes();
            }

            int function = in.getUnsignedByte(start + 1);

            List<Integer> candidates = new ArrayList<>(4);
            // 异常响应：addr + (func|0x80) + exCode + CRC(2)
            if ((function & 0x80) != 0) {
                candidates.add(5);
            } else {
                // 常见请求/响应是定长 8 字节（01/02/03/04 请求、05/06 请求/响应等）
                candidates.add(8);

                // 读响应：addr + func + byteCount + data(byteCount) + CRC(2)
                if (readable >= 3) {
                    int byteCount = in.getUnsignedByte(start + 2);
                    int len = 5 + byteCount;
                    candidates.add(len);
                }

                // 写多个请求（0x0F/0x10）：
                // addr + func + start(2) + qty(2) + byteCount + data(byteCount) + CRC(2)
                if ((function == 0x0F || function == 0x10) && readable >= 7) {
                    int byteCount = in.getUnsignedByte(start + 6);
                    int len = 9 + byteCount;
                    candidates.add(len);
                }
            }

            Integer matchedLen = null;
            for (Integer len : candidates) {
                if (len == null) {
                    continue;
                }
                if (len < 5 || len > maxFrameLength) {
                    continue;
                }
                if (readable < len) {
                    continue;
                }
                if (hasValidCrc(in, start, len)) {
                    matchedLen = len;
                    break;
                }
            }

            if (matchedLen != null) {
                if (matchedLen > maxFrameLength) {
                    throw new TooLongFrameException("Modbus RTU 帧过长：" + matchedLen);
                }
                out.add(in.readRetainedSlice(matchedLen));
                continue;
            }

            // 没有任何候选长度匹配：丢弃 1 字节尝试重新同步到下一个帧边界。
            in.skipBytes(1);
        }
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

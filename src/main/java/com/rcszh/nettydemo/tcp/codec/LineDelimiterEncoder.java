package com.rcszh.nettydemo.tcp.codec;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;

import java.util.List;

/**
 * 行分隔编码器：为出站字符串追加换行符，便于对端按行分包。
 */
public class LineDelimiterEncoder extends MessageToMessageEncoder<String> {

    /**
     * 将出站字符串追加换行符（如果已经有换行则不重复追加）。
     */
    @Override
    protected void encode(ChannelHandlerContext ctx, String msg, List<Object> out) {
        if (msg == null) {
            return;
        }
        if (msg.endsWith("\n")) {
            out.add(msg);
            return;
        }
        out.add(msg + "\n");
    }
}

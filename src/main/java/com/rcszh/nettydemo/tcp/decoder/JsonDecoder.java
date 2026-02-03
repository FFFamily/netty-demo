package com.rcszh.nettydemo.tcp.decoder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;

import java.util.List;

public class JsonDecoder extends JsonObjectDecoder {
    public JsonDecoder(int maxObjectLength) {
        super(maxObjectLength);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        ByteBuf frame = in.retainedDuplicate();
        final String content = frame.toString(CharsetUtil.UTF_8);
        out.add(content);
//        super.decode(ctx, in, out);
        in.skipBytes(in.readableBytes());
    }
}

package com.rcszh.nettydemo.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcszh.nettydemo.tcp.handler.TcpServerHandler;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TcpProtocolEmbeddedChannelTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final NettyServerProperties properties = respondEnabledProps();

    @Test
    void ping_should_return_pong() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4),
                new StringDecoder(CharsetUtil.UTF_8),
                new LengthFieldPrepender(4),
                new StringEncoder(CharsetUtil.UTF_8),
                new TcpServerHandler(properties, objectMapper)
        );

        String reqJson = "{\"requestId\":\"t1\",\"action\":\"PING\",\"data\":{}}";
        ByteBuf in = frame(reqJson);
        ch.writeInbound(in);

        ByteBuf outLen = ch.readOutbound();
        ByteBuf outPayload = ch.readOutbound();
        try {
            String respJson = unframe(outLen, outPayload);
            JsonNode resp = objectMapper.readTree(respJson);
            assertEquals("t1", resp.path("requestId").asText());
            assertEquals(0, resp.path("code").asInt());
            assertEquals("PONG", resp.path("data").path("action").asText());
        } finally {
            ReferenceCountUtil.release(outLen);
            ReferenceCountUtil.release(outPayload);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void invalid_json_should_return_400() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(
                new LengthFieldBasedFrameDecoder(1024 * 1024, 0, 4, 0, 4),
                new StringDecoder(CharsetUtil.UTF_8),
                new LengthFieldPrepender(4),
                new StringEncoder(CharsetUtil.UTF_8),
                new TcpServerHandler(properties, objectMapper)
        );

        ch.writeInbound(frame("not-a-json"));

        ByteBuf outLen = ch.readOutbound();
        ByteBuf outPayload = ch.readOutbound();
        try {
            String respJson = unframe(outLen, outPayload);
            JsonNode resp = objectMapper.readTree(respJson);
            assertEquals(400, resp.path("code").asInt());
        } finally {
            ReferenceCountUtil.release(outLen);
            ReferenceCountUtil.release(outPayload);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void json_object_framing_should_work_without_length_prefix() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(
                new io.netty.handler.codec.json.JsonObjectDecoder(1024 * 1024),
                new StringDecoder(CharsetUtil.UTF_8),
                new StringEncoder(CharsetUtil.UTF_8),
                new TcpServerHandler(properties, objectMapper)
        );

        // No 4-byte length prefix, raw JSON only.
        ByteBuf raw = Unpooled.wrappedBuffer(
                "{\"requestId\":\"t2\",\"action\":\"PING\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)
        );
        ch.writeInbound(raw);

        ByteBuf out = ch.readOutbound();
        try {
            String respJson = out.toString(StandardCharsets.UTF_8);
            JsonNode resp = objectMapper.readTree(respJson);
            assertEquals("t2", resp.path("requestId").asText());
            assertEquals(0, resp.path("code").asInt());
            assertEquals("PONG", resp.path("data").path("action").asText());
        } finally {
            ReferenceCountUtil.release(out);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void json_stream_without_framing_should_work_with_fragmented_bytes() throws Exception {
        EmbeddedChannel ch = new EmbeddedChannel(
                // 只加出站编码器，不加任何入站分包/解码，模拟“未分包”的 JSON 字节流。
                new StringEncoder(CharsetUtil.UTF_8),
                new TcpServerHandler(properties, objectMapper)
        );

        String json = "{\"requestId\":\"t4\",\"action\":\"PING\",\"data\":{}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 0, 10));
        // 第一段不是完整 JSON，不应有响应
        ByteBuf out1 = ch.readOutbound();
        assertEquals(null, out1);

        ch.writeInbound(Unpooled.wrappedBuffer(bytes, 10, bytes.length - 10));
        ByteBuf out = ch.readOutbound();
        try {
            String respJson = out.toString(StandardCharsets.UTF_8);
            JsonNode resp = objectMapper.readTree(respJson);
            assertEquals("t4", resp.path("requestId").asText());
            assertEquals("PONG", resp.path("data").path("action").asText());
        } finally {
            ReferenceCountUtil.release(out);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void modbus_rtu_framing_should_split_concatenated_frames() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new com.rcszh.nettydemo.tcp.codec.ModbusRtuFrameDecoder(1024)
        );

        ByteBuf raw = Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(
                "01020100003079e2010206000080008000a8b9"
        ));
        ch.writeInbound(raw);

        ByteBuf f1 = ch.readInbound();
        ByteBuf f2 = ch.readInbound();
        try {
            assertEquals("01020100003079e2", ByteBufUtil.hexDump(f1));
            assertEquals("010206000080008000a8b9", ByteBufUtil.hexDump(f2));
        } finally {
            ReferenceCountUtil.release(f1);
            ReferenceCountUtil.release(f2);
            ch.finishAndReleaseAll();
        }
    }

    @Test
    void auto_framing_should_accept_json_or_modbus_defaulting_to_modbus() throws Exception {
        NettyServerProperties p = respondEnabledProps();
        p.setMaxFrameLength(1024 * 1024);

        EmbeddedChannel chJson = new EmbeddedChannel(
                new com.rcszh.nettydemo.tcp.codec.AutoProtocolSwitchingDecoder(
                        p,
                        new TcpServerHandler(p, objectMapper),
                        new HexCollectingHandler()
                )
        );
        chJson.writeInbound(Unpooled.wrappedBuffer(
                "{\"requestId\":\"t3\",\"action\":\"PING\",\"data\":{}}".getBytes(StandardCharsets.UTF_8)
        ));
        ByteBuf jsonOut = chJson.readOutbound();
        assertNotNull(jsonOut); // should respond with JSON (encoded by StringEncoder)
        ReferenceCountUtil.release(jsonOut);
        chJson.finishAndReleaseAll();

        HexCollectingHandler collector = new HexCollectingHandler();
        EmbeddedChannel chModbus = new EmbeddedChannel(
                new com.rcszh.nettydemo.tcp.codec.AutoProtocolSwitchingDecoder(
                        p,
                        new TcpServerHandler(p, objectMapper),
                        collector
                )
        );
        chModbus.writeInbound(Unpooled.wrappedBuffer(ByteBufUtil.decodeHexDump(
                "01020100003079e2010206000080008000a8b9"
        )));
        // Collector sees two framed RTU messages (hex)
        assertEquals("01020100003079e2", collector.hex.get(0));
        assertEquals("010206000080008000a8b9", collector.hex.get(1));
        chModbus.finishAndReleaseAll();
    }

    private static ByteBuf frame(String json) {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        return Unpooled.buffer(4 + payload.length)
                .writeInt(payload.length)
                .writeBytes(payload);
    }

    private static String unframe(ByteBuf lenBuf, ByteBuf payloadBuf) {
        int len = lenBuf.readInt();
        byte[] payload = new byte[len];
        payloadBuf.readBytes(payload);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static NettyServerProperties respondEnabledProps() {
        NettyServerProperties p = new NettyServerProperties();
        p.setRespondEnabled(true);
        return p;
    }

    private static final class HexCollectingHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final java.util.List<String> hex = new java.util.ArrayList<>();

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
            hex.add(ByteBufUtil.hexDump(msg));
        }
    }
}

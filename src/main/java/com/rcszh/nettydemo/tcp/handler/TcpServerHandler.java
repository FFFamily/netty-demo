package com.rcszh.nettydemo.tcp.handler;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.json.async.NonBlockingJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.rcszh.nettydemo.tcp.NettyServerProperties;
import com.rcszh.nettydemo.tcp.model.TcpRequest;
import com.rcszh.nettydemo.tcp.model.TcpResponse;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/**
 * TCP 文本（JSON）请求处理器。
 *
 * <p>该处理器支持“无分包”的 JSON 流：可直接消费 TCP 入站的 {@link ByteBuf} 分片，增量解析出完整 JSON 消息。</p>
 *
 * <p>同时也兼容上游已分包/解码后的 {@link String} 消息（例如：length-field / line / JsonObjectDecoder 等）。</p>
 */
@Component
@ChannelHandler.Sharable
public class TcpServerHandler extends ChannelInboundHandlerAdapter {
    private static final Logger log = LoggerFactory.getLogger(TcpServerHandler.class);

    private static final AttributeKey<JsonStreamState> JSON_STREAM_STATE_KEY =
            AttributeKey.valueOf("tcpJsonStreamState");

    private final NettyServerProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 构造 JSON 请求处理器。
     *
     * @param properties   服务配置
     * @param objectMapper JSON 序列化/反序列化
     */
    public TcpServerHandler(NettyServerProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 连接建立回调。
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.info("TCP 连接建立：远端={}", remote(ctx));
        ensureJsonState(ctx);
    }

    /**
     * 连接断开回调。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        log.info("TCP 连接断开：远端={}", remote(ctx));
    }

    /**
     * 处理入站消息：
     *
     * <ul>
     *   <li>如果上游已经把消息解码成 {@link String}：按“单条完整 JSON”处理</li>
     *   <li>如果上游未分包：接收 {@link ByteBuf} 分片并增量解析出完整 JSON</li>
     * </ul>
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof String s) {
            handleJsonString(ctx, s);
            return;
        }
        if (msg instanceof ByteBuf buf) {
            try {
                handleJsonBytes(ctx, buf);
            } finally {
                // 本 Handler 消费了 ByteBuf，必须释放引用计数。
                buf.release();
            }
            return;
        }
        ctx.fireChannelRead(msg);
    }

    /**
     * 处理读空闲事件：长时间未收到对端数据则断开连接。
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent e && e.state() == IdleState.READER_IDLE) {
            log.info("TCP 读空闲超时，关闭连接：远端={}", remote(ctx));
            ctx.close();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    /**
     * 异常处理：记录并关闭连接。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("TCP 异常：远端={}", remote(ctx), cause);
        ctx.close();
    }

    /**
     * 处理“已经是完整字符串”的 JSON 消息（上游已分包/解码）。
     */
    private void handleJsonString(ChannelHandlerContext ctx, String msg) {
        if (msg == null) {
            return;
        }
        int bytes = msg.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > properties.getMaxFrameLength()) {
            log.warn("TCP 收到 JSON 超长消息，已丢弃：远端={} 字节数={} 上限={}",
                    remote(ctx), bytes, properties.getMaxFrameLength());
            return;
        }

        log.info("TCP 收到消息：远端={} 字节数={} 内容={}", remote(ctx), bytes, safeSnippet(msg));
        try {
            TcpRequest req = objectMapper.readValue(msg, TcpRequest.class);
            onParsedRequest(ctx, req);
        } catch (Exception e) {
            onJsonParseError(ctx, msg, e);
        }
    }

    /**
     * 处理“未分包”的 JSON 字节流分片：增量解析出完整 JSON 后再执行业务逻辑。
     */
    private void handleJsonBytes(ChannelHandlerContext ctx, ByteBuf buf) {
        if (!buf.isReadable()) {
            return;
        }

        JsonStreamState state = ensureJsonState(ctx);
        byte[] chunk = ByteBufUtil.getBytes(buf);

        state.pendingBytes += chunk.length;
        if (state.pendingBytes > properties.getMaxFrameLength()) {
            log.warn("TCP JSON 字节流累计超过上限，关闭连接：远端={} 已累计字节数={} 上限={}",
                    remote(ctx), state.pendingBytes, properties.getMaxFrameLength());
            ctx.close();
            return;
        }

        try {
            if (state.parser.getNonBlockingInputFeeder().needMoreInput()) {
                state.parser.feedInput(chunk, 0, chunk.length);
            } else {
                // 正常情况下解析器应当需要更多输入；若不需要，先消费现有 token 再继续。
                drainParser(ctx, state);
                if (state.parser.getNonBlockingInputFeeder().needMoreInput()) {
                    state.parser.feedInput(chunk, 0, chunk.length);
                }
            }
            drainParser(ctx, state);
        } catch (Exception e) {
            // 无分包流里出现非法 JSON 时，无法可靠定位边界；关闭连接更清晰。
            log.warn("TCP JSON 字节流解析失败，关闭连接：远端={} 异常={}",
                    remote(ctx), exceptionLabel(e));
            log.debug("TCP JSON 字节流解析异常详情：远端={}", remote(ctx), e);
            ctx.close();
        }
    }

    /**
     * 从非阻塞解析器中尽可能多地读取 token，并在检测到一个完整根 JSON 值时触发处理。
     */
    private void drainParser(ChannelHandlerContext ctx, JsonStreamState state) throws Exception {
        while (true) {
            JsonToken t = state.parser.nextToken();
            if (t == null || t == JsonToken.NOT_AVAILABLE) {
                return;
            }

            if (!state.inRoot) {
                state.inRoot = true;
                state.depth = 0;
                state.tokenBuffer = new TokenBuffer(objectMapper, false);
            }

            state.tokenBuffer.copyCurrentEvent(state.parser);

            if (t == JsonToken.START_OBJECT || t == JsonToken.START_ARRAY) {
                state.depth++;
                continue;
            }
            if (t == JsonToken.END_OBJECT || t == JsonToken.END_ARRAY) {
                state.depth--;
                if (state.depth == 0) {
                    onFullJsonValue(ctx, state);
                }
                continue;
            }

            // 标量根节点：单 token 即为完整 JSON 值。
            if (state.depth == 0) {
                onFullJsonValue(ctx, state);
            }
        }
    }

    /**
     * 处理一个完整的根 JSON 值：解析成 {@link TcpRequest} 并执行业务逻辑。
     */
    private void onFullJsonValue(ChannelHandlerContext ctx, JsonStreamState state) throws Exception {
        TokenBuffer buf = Objects.requireNonNull(state.tokenBuffer);

        // 重置状态，准备接收下一条消息。
        state.inRoot = false;
        state.depth = 0;
        state.tokenBuffer = null;
        state.pendingBytes = 0;

        try (JsonParser p = buf.asParser(objectMapper)) {
            TcpRequest req = objectMapper.readValue(p, TcpRequest.class);
            onParsedRequest(ctx, req);
        } catch (Exception e) {
            onJsonParseError(ctx, "<stream-json>", e);
            ctx.close();
        }
    }

    /**
     * 解析出请求对象后的统一处理入口：记录日志、做业务处理并（可选）响应。
     */
    private void onParsedRequest(ChannelHandlerContext ctx, TcpRequest req) throws Exception {
        String requestId = normalizeRequestId(req == null ? null : req.getRequestId());
        String action = req == null ? null : req.getAction();

        log.info("TCP 解析成功：远端={} 请求ID={} 动作={} 是否有data={}",
                remote(ctx),
                requestId,
                action,
                req != null && req.getData() != null);

        if (properties.isRespondEnabled()) {
            TcpResponse resp = handle(req, requestId);
            ctx.writeAndFlush(objectMapper.writeValueAsString(resp));
        }
    }

    /**
     * JSON 解析失败的统一处理入口（中文日志 + 可选错误响应）。
     */
    private void onJsonParseError(ChannelHandlerContext ctx, String payload, Exception e) {
        log.warn("TCP 请求非法（非 JSON 或字段不符合约定）：远端={} 内容={} 异常={}",
                remote(ctx), safeSnippet(payload), exceptionLabel(e));
        log.debug("TCP 请求解析异常详情：远端={} 内容={}", remote(ctx), safeSnippet(payload), e);

        if (properties.isRespondEnabled()) {
            String requestId = UUID.randomUUID().toString();
            TcpResponse resp = TcpResponse.error(requestId, 400, "请求 JSON 不合法");
            ctx.writeAndFlush(writeQuietly(resp));
        }
    }

    /**
     * 业务处理：根据 action 返回响应对象。
     */
    private TcpResponse handle(TcpRequest req, String requestId) {
        String action = req == null ? null : req.getAction();
        if (action == null || action.isBlank()) {
            return TcpResponse.error(requestId, 400, "缺少字段：action");
        }

        if ("PING".equalsIgnoreCase(action)) {
            ObjectNode data = objectMapper.createObjectNode();
            data.put("action", "PONG");
            return TcpResponse.ok(requestId, data);
        }

        ObjectNode data = objectMapper.createObjectNode();
        data.put("echoAction", action);
        JsonNode echoData = req.getData();
        if (echoData != null) {
            data.set("echoData", echoData);
        }
        return TcpResponse.ok(requestId, data);
    }

    /**
     * 获取/初始化每个连接的 JSON 流式解析状态。
     */
    private JsonStreamState ensureJsonState(ChannelHandlerContext ctx) {
        JsonStreamState existing = ctx.channel().attr(JSON_STREAM_STATE_KEY).get();
        if (existing != null) {
            return existing;
        }
        JsonStreamState created = new JsonStreamState(objectMapper);
        ctx.channel().attr(JSON_STREAM_STATE_KEY).set(created);
        return created;
    }

    /**
     * 规范化 requestId：为空则生成 UUID。
     */
    private String normalizeRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return requestId;
    }

    /**
     * 获取远端地址字符串（ip:port）。
     */
    private String remote(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress a) {
            return a.getAddress().getHostAddress() + ":" + a.getPort();
        }
        return String.valueOf(ctx.channel().remoteAddress());
    }

    /**
     * 安全截断日志内容，避免超长/多行刷屏。
     */
    private String safeSnippet(String s) {
        if (s == null) {
            return "";
        }
        int max = 4096;
        String trimmed = s.length() <= max ? s : s.substring(0, max) + "...";
        return trimmed.replaceAll("\\s+", " ");
    }

    /**
     * 将响应对象序列化为 JSON 字符串（失败时返回兜底错误 JSON）。
     */
    private String writeQuietly(TcpResponse resp) {
        try {
            return objectMapper.writeValueAsString(resp);
        } catch (Exception ignored) {
            return "{\"code\":500,\"message\":\"服务端内部错误\"}";
        }
    }

    /**
     * 将常见异常类型转换为中文标签，避免控制台被英文/类名刷屏。
     */
    private String exceptionLabel(Throwable t) {
        if (t == null) {
            return "未知异常";
        }
        if (t instanceof com.fasterxml.jackson.core.JsonParseException) {
            return "JSON 语法错误";
        }
        if (t instanceof com.fasterxml.jackson.databind.exc.MismatchedInputException) {
            return "JSON 结构或字段不匹配";
        }
        if (t instanceof IllegalArgumentException) {
            return "参数非法";
        }
        return t.getClass().getSimpleName();
    }

    /**
     * 每条连接的 JSON 流式解析状态（不可跨连接复用）。
     */
    private static final class JsonStreamState {
        private final NonBlockingJsonParser parser;
        private TokenBuffer tokenBuffer;
        private boolean inRoot;
        private int depth;
        private int pendingBytes;

        /**
         * 构造 JSON 流式解析状态。
         *
         * @param objectMapper ObjectMapper（用于创建非阻塞解析器）
         */
        private JsonStreamState(ObjectMapper objectMapper) {
            try {
                this.parser = (NonBlockingJsonParser) objectMapper.getFactory().createNonBlockingByteArrayParser();
            } catch (IOException e) {
                throw new IllegalStateException("创建 JSON 非阻塞解析器失败", e);
            }
        }
    }
}

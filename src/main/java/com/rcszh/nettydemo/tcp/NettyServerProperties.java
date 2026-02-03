package com.rcszh.nettydemo.tcp;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Netty TCP 服务配置（Spring Boot 配置绑定：{@code netty.tcp.*}）。
 */
@ConfigurationProperties(prefix = "netty.tcp")
public class NettyServerProperties {

    public enum Framing {
        /**
         * 自动识别协议（按连接 best-effort）。如果无法判断，则默认按 {@link #MODBUS_RTU} 处理。
         */
        AUTO,
        /**
         * 不分包、不解码：业务处理器直接收到 {@code ByteBuf}（按 socket 到达的分片）。
         *
         * <p>适用：二进制协议且分包由其他层处理，或你只是想打印原始字节流。</p>
         */
        RAW,
        /**
         * 4 字节大端长度字段 + payload。
         */
        LENGTH_FIELD,
        /**
         * JSON 流：通过大括号/中括号计数识别 JSON 对象/数组边界。
         */
        JSON_OBJECT,
        /**
         * 按换行分隔（\\n 或 \\r\\n）。
         */
        LINE,
        /**
         * Modbus RTU 分包（CRC16）：从 TCP 字节流中解出完整 RTU 帧。
         *
         * <p>适用：对端把 RTU 报文“套”在 TCP 上直接发送（一些网关常见）。</p>
         */
        MODBUS_RTU
    }

    /**
     * 监听端口。设置为 0 表示由系统分配临时端口（常用于测试）。
     */
    private int port = 9000;

    /**
     * 入站 TCP 字节流的分包方式。
     */
    private Framing framing = Framing.LENGTH_FIELD;

    /**
     * 单条消息最大长度（字节）。
     */
    private int maxFrameLength = 1024 * 1024; // 1048576 字节

    /**
     * 读空闲超时（秒）：在该时间内未收到任何入站数据则断开连接。
     */
    private int readerIdleSeconds = 60;

    /**
     * 是否响应对端。
     *
     * <p>false 表示只接收并打印，不回写任何数据。</p>
     */
    private boolean respondEnabled = true;

    /**
     * 获取监听端口。
     */
    public int getPort() {
        return port;
    }

    /**
     * 设置监听端口。
     */
    public void setPort(int port) {
        this.port = port;
    }

    /**
     * 获取分包方式。
     */
    public Framing getFraming() {
        return framing;
    }

    /**
     * 设置分包方式。
     */
    public void setFraming(Framing framing) {
        this.framing = framing;
    }

    /**
     * 获取单条消息最大长度（字节）。
     */
    public int getMaxFrameLength() {
        return maxFrameLength;
    }

    /**
     * 设置单条消息最大长度（字节）。
     */
    public void setMaxFrameLength(int maxFrameLength) {
        this.maxFrameLength = maxFrameLength;
    }

    /**
     * 获取读空闲超时（秒）。
     */
    public int getReaderIdleSeconds() {
        return readerIdleSeconds;
    }

    /**
     * 设置读空闲超时（秒）。
     */
    public void setReaderIdleSeconds(int readerIdleSeconds) {
        this.readerIdleSeconds = readerIdleSeconds;
    }

    /**
     * 是否启用响应回写。
     */
    public boolean isRespondEnabled() {
        return respondEnabled;
    }

    /**
     * 设置是否启用响应回写。
     */
    public void setRespondEnabled(boolean respondEnabled) {
        this.respondEnabled = respondEnabled;
    }
}

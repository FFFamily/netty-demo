package com.rcszh.nettydemo.tcp.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * TCP 响应对象（JSON 序列化）。
 */
public class TcpResponse {
    private String requestId;
    private int code;
    private String message;
    private JsonNode data;
    private Instant serverTime = Instant.now();

    /**
     * 构造成功响应。
     *
     * @param requestId 请求 ID
     * @param data      业务数据
     * @return 响应
     */
    public static TcpResponse ok(String requestId, JsonNode data) {
        TcpResponse r = new TcpResponse();
        r.setRequestId(requestId);
        r.setCode(0);
        r.setMessage("成功");
        r.setData(data);
        return r;
    }

    /**
     * 构造失败响应。
     *
     * @param requestId 请求 ID
     * @param code      错误码
     * @param message   错误信息
     * @return 响应
     */
    public static TcpResponse error(String requestId, int code, String message) {
        TcpResponse r = new TcpResponse();
        r.setRequestId(requestId);
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    /**
     * 请求 ID。
     */
    public String getRequestId() {
        return requestId;
    }

    /**
     * 设置请求 ID。
     */
    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    /**
     * 业务状态码（0=成功）。
     */
    public int getCode() {
        return code;
    }

    /**
     * 设置业务状态码。
     */
    public void setCode(int code) {
        this.code = code;
    }

    /**
     * 提示信息。
     */
    public String getMessage() {
        return message;
    }

    /**
     * 设置提示信息。
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * 业务数据。
     */
    public JsonNode getData() {
        return data;
    }

    /**
     * 设置业务数据。
     */
    public void setData(JsonNode data) {
        this.data = data;
    }

    /**
     * 服务端时间（UTC）。
     */
    public Instant getServerTime() {
        return serverTime;
    }

    /**
     * 设置服务端时间。
     */
    public void setServerTime(Instant serverTime) {
        this.serverTime = serverTime;
    }
}

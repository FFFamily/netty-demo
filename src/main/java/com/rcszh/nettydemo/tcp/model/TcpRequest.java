package com.rcszh.nettydemo.tcp.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * TCP 请求对象（JSON 反序列化）。
 */
public class TcpRequest {
    private String requestId;
    private String action;
    private JsonNode data;

    /**
     * 请求 ID（可选）。
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
     * 动作/指令（必填）。
     */
    public String getAction() {
        return action;
    }

    /**
     * 设置动作/指令。
     */
    public void setAction(String action) {
        this.action = action;
    }

    /**
     * 业务数据（任意 JSON）。
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
}

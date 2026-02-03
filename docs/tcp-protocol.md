# TCP 协议（可配置分包）

## 传输层

- 协议：TCP
- 长连接：支持（客户端可复用连接发送多条请求）
- 编码：UTF-8
- 粘包/拆包：由 `netty.tcp.framing` 决定（见下文）

## 分包方式（netty.tcp.framing）

### auto（推荐：多协议兼容）

按连接自动识别（best-effort）：

- 如果首个非空白字符是 `{` / `[`：按 JSON（`json-object`）处理
- 否则如果能在报文起始位置校验出合法 Modbus RTU CRC：按 `modbus-rtu` 处理
- 如果短时间内无法判断：默认按 `modbus-rtu` 处理

注意：这是“按连接”选择一次协议；同一个 TCP 连接里混发 JSON 和 Modbus 不支持（可通过不同连接/不同端口解决）。

### 0) raw

不做分包、不做解码：直接把 TCP 流里的二进制数据以 `ByteBuf` 形式交给业务处理器（本项目默认会按 hex 打印前 4096 字节）。

适用：对端不是发 JSON，而是发二进制协议（如 PLC/网关/设备透传），你需要先确认真实协议再解析。

### 1) length-field（默认）

每条消息 = `length(4 bytes) + payload(length bytes)`：

- `length`：32-bit big-endian（网络字节序），表示后续 `payload` 的字节长度
- `payload`：UTF-8 编码的 JSON 文本

适用：对端能按协议补齐长度字段；最稳定、最通用。

### 2) json-object

对端直接发送 JSON（不带长度、不带换行），服务端通过括号计数识别完整 JSON 对象/数组边界（Netty `JsonObjectDecoder`）。

适用：厂商说“透传”，直接把 JSON 原文丢进 TCP 流里。

### 3) line

对端每条消息以换行结束（`\n` 或 `\r\n`），服务端按行读取（Netty `LineBasedFrameDecoder`）。

适用：对端能保证每条消息有明确换行分隔。

### 4) modbus-rtu

按 Modbus RTU 的 CRC16 来识别完整帧（支持多帧粘在一起的场景）。

适用：对端把 Modbus RTU 报文直接“套”在 TCP 上发送（一些网关会这么做）。

## 帧格式（length-field）

每条消息 = `length(4 bytes) + payload(length bytes)`：

- `length`：32-bit big-endian（网络字节序），表示后续 `payload` 的字节长度
- `payload`：UTF-8 编码的 JSON 文本

服务端限制单条消息最大长度：`netty.tcp.max-frame-length`（默认 1048576）。

## 请求 JSON

```json
{
  "requestId": "可选，建议传；不传服务端会生成",
  "action": "必填，例如 PING / ORDER_CREATE / ...",
  "data": { "任意 JSON 对象/数组/值，按 action 自定义" }
}
```

## 响应 JSON

说明：本项目可配置为“只接收不响应”（`netty.tcp.respond-enabled=false`）。此时服务端不会回写任何数据。

```json
{
  "requestId": "与请求对应（或服务端生成）",
  "code": 0,
  "message": "OK",
  "data": { "按 action 返回的业务数据" },
  "serverTime": "2026-02-03T01:23:45.678Z"
}
```

- `code=0`：成功
- `code!=0`：失败（本 Demo 中，JSON 不合法或缺字段会返回 `400`）

## 内置 action（Demo）

- `PING`：返回 `data.action = "PONG"`
- 其他：返回 `echoAction`、`echoData`（用于联调）

## 客户端示例

### Java（Socket）

核心逻辑：先写 4 字节长度，再写 JSON 字节；读的时候同理。

```java
byte[] payload = json.getBytes(StandardCharsets.UTF_8);
out.writeInt(payload.length);
out.write(payload);
out.flush();

int len = in.readInt();
byte[] buf = in.readNBytes(len);
String respJson = new String(buf, StandardCharsets.UTF_8);
```

### Python

```python
import json, socket, struct

payload = json.dumps({"action": "PING"}).encode("utf-8")
frame = struct.pack(">I", len(payload)) + payload

with socket.create_connection(("127.0.0.1", 9000)) as s:
    s.sendall(frame)
    (n,) = struct.unpack(">I", s.recv(4))
    data = b""
    while len(data) < n:
        data += s.recv(n - len(data))
    print(data.decode("utf-8"))
```

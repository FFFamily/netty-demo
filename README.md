# netty-demo (TCP)

基于 Netty 的 TCP 服务端：接受其他服务器发来的 TCP 请求（length-prefixed），返回 JSON 响应。

## 运行

```bash
./mvnw spring-boot:run
```

或打包后运行：

```bash
./mvnw -DskipTests package
java -jar target/netty-demo-0.0.1-SNAPSHOT.jar
```

默认监听端口：`9000`（可配置，见下文）。

## 配置

在 `src/main/resources/application.properties` 中：

- `netty.tcp.port`：监听端口（`0` 表示随机端口，常用于测试）
- `netty.tcp.framing`：分包方式：`auto` / `length-field` / `json-object` / `line` / `raw` / `modbus-rtu`
- `netty.tcp.max-frame-length`：单条消息最大字节数（默认 1 MiB）
- `netty.tcp.reader-idle-seconds`：读空闲超时（秒），超时会断开连接
- `netty.tcp.respond-enabled`：是否响应对端（false=只接收并打印日志）

## 协议说明

见 `docs/tcp-protocol.md`。

## 快速自测（单元测试）

项目内提供基于 Netty `EmbeddedChannel` 的协议测试（不需要真的起端口监听）：
`src/test/java/com/rcszh/nettydemo/tcp/TcpProtocolEmbeddedChannelTest.java`。

```bash
./mvnw -Dmaven.repo.local=.m2repo test
```
# netty-demo

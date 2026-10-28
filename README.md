# YKClash Android

基于 [clash-rs](https://github.com/CHKayanami/clash-rs) 的 Android 原生代理客户端，使用 Kotlin 构建界面，通过 Android VPN 接管流量。

支持 **Android 13 及以上**，提供浅色与深色主题。

## 支持协议

Shadowsocks、VMess、VLESS、Trojan、AnyTLS、Hysteria2、TUIC、ShadowQUIC、SSH、HTTP / HTTPS、SOCKS5、ShadowQuic。

支持节点链接导入和手动编辑；TLS、Reality、WebSocket、HTTP/2、gRPC、XHTTP 等传输选项按协议提供。

## 主要功能

- 远程订阅、本地 YAML 配置及单节点导入。
- 规则、全局、直连模式，代理组切换和节点延迟测试。
- 按应用分流，支持 IPv4 / IPv6。
- 按模块全局覆写，可视化编辑 DNS2、规则、代理组、嗅探和规则提供者。
- 实时速度、累计流量、连接详情和历史日志查询。
- 开机启动、系统始终开启 VPN，以及最终运行 YAML 查看和导出。

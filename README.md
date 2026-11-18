# root_hot_point · 热点管家

给被厂商阉割热点的 OPPO 手表（ColorOS Watch / Android 11）恢复完整热点功能的 root 应用。
自带手表适配的管理面板、后台保活与开机自启。已在 **OWW212 / OWW221**（BCM43013 + bcmdhd）真机验证：
WPA2 握手、DHCP、NAT 转发、手机实际上网全链路通过。

![platform](https://img.shields.io/badge/platform-ColorOS%20Watch%20(Android%2011)-orange)
![root](https://img.shields.io/badge/root-Magisk%20%2F%20Kitsune-red)
![license](https://img.shields.io/badge/license-MIT-blue)

## 为什么需要它

这些手表的热点不是"设置里藏了个开关"，而是被三层阉割：

1. **设置 UI 移除**——系统设置里没有热点入口；
2. **vendor HAL 隐藏 AP 能力**——`IWifiChip` 声明的 interface combinations 只有 STA+P2P，
   框架 `startTetheredHotspot()` 在 `HalDeviceManager` 处直接失败（`Requested type not supported by combo`）；
3. **bcmdhd 主网卡数据通路陷阱**——即使把 wlan0 强切 AP 模式（hostapd 能 `AP-ENABLED`、手机能关联），
   管理帧走固件 SME 正常、**数据帧被驱动静默丢弃**：EAPOL 四次握手 M1 发不出，手机报"密码错误"
   （deauth reason 15）。必须创建**独立 AP 虚拟接口**（ap0）才有活的数据通路。

此外还有两个 Android 通用坑，本工程一并处理：

- **策略路由**：默认路由在 netd 的 per-network 表里（主表没有），转发包和 dnsmasq 回包都需要显式 `ip rule`；
- **netd 防火墙**：框架 tethering 未开启时 `tetherctrl_FORWARD` 丢弃一切转发，ACCEPT 必须插到 FORWARD 链头部。

## 工作原理

```
热点管家 App (cn.classfruit.hotpoint)
   │  su -c
   ▼
/data/local/tmp/dgsh/hotspot.sh  ← 随 APK assets 部署
   │  1. cmd wifi set-wifi-enabled disabled   (单射频：STA/AP 互斥)
   │  2. insmod bcmdhd.ko op_mode=15          (让 wiphy 声明 AP；仅在需要时重载)
   │  3. wiphy addap ap0                      (tools/wiphy.c，裸 nl80211 建 AP vif)
   │  4. hostapd -dd -f hapd.log hapd.conf    (WPA2-PSK / 开放，ap0)
   │  5. ip addr / ip rule / iptables         (网关、子网路由、头部 FORWARD、MASQUERADE)
   │  6. dnsmasq                              (DHCP + DNS，ap0)
   │  7. /sys/power/wake_lock                 (内核级 wakelock，ROM 会丢应用级)
   │  8. guard 循环                           (root 侧保活，绕开 BmManager 闹钟限制)
   ▼
手机等客户端 → ap0 → NAT → rmnet_data0 (eSIM) / 其他上游
```

App 侧前台服务每 4 秒轮询 `hotspot.sh status`（hostapd_cli + /proc/net/arp + 网卡计数），
负责看门狗重启、STA 冲突抑制、无设备自动关闭、通知栏与 UI 状态广播。

## 功能

- 大圆钮一键开/关热点，状态/失败原因/上游网络/本次流量实时显示
- 管理面板：SSID、密码、WPA2/开放、隐藏 SSID、信道 1/6/11、最大连接数、无设备自动关闭（永不/5/10/30 分钟）、开机自启
- 设备列表：MAC + IP，单台踢人（hostapd disassociate）
- 保活四重：前台服务 + START_STICKY + root guard 循环（进程被强杀 30s 内拉回）+ deviceidle 白名单；内核 wakelock
- 开机自启：BOOT_COMPLETED / MY_PACKAGE_REPLACED，prefs 存设备加密存储（FBE 解锁前可读）
- 通知栏常驻 + 「关闭」动作
- 关于页：作者身份与头像（运行时从个人主页 URL 拉取并缓存）

## 兼容性

| 项目 | 要求 |
| --- | --- |
| 设备 | OPPO Watch S (OWW212)、OWW221 等 BCM43013/bcmdhd 机型 |
| 系统 | ColorOS Watch，Android 11 (API 30) |
| root | Magisk / Kitsune Mask（需对本应用授权） |
| 上游 | eSIM 数据连接（或任何有默认路由的上游）；**热点与 Wi-Fi 不能同开**（单射频） |
| 频段 | 2.4GHz（芯片限制） |

其他 bcmdhd 机型理论可用（路径一致即可）；非 bcmdhd 机型需要改 `hotspot.sh` 的驱动/接口部分。

## 构建

需要 Android SDK（compileSdk 37）、AGP 9.3.3、Gradle 9.5（wrapper 已带）、JDK 25 工具链
（AGP 9 要求；可用 Android Studio 自带 JBR，见 `org.gradle.java.installations.paths`）。

```bash
./gradlew :app:assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk (debug 签名，便于 adb install -r 升级)
```

`app/src/main/assets/wiphy` 是预编译的静态 armv7 二进制；如需重编：

```bash
$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe \
    --target=armv7a-linux-androideabi21 -static -O2 tools/wiphy.c -o app/src/main/assets/wiphy
```

## 安装与授权

```bash
adb install -r app-release.apk
# 查 uid
adb shell dumpsys package cn.classfruit.hotpoint | grep userId
# 预授 root（免手表上弹授权框；Kitsune/Magisk 的 policies 表无 package_name 列）
adb shell su -c 'magisk --sqlite "INSERT INTO policies (uid,policy,until,logging,notification) VALUES(<uid>,2,0,1,0)"'
```

首次开启热点时引擎会自动部署脚本与 wiphy 到 `/data/local/tmp/dgsh/`。
默认 SSID `OWW212-Hotspot`、密码 `88888888`，请在设置里修改。

## 目录

```
app/src/main/java/cn/classfruit/hotpoint/   App（纯框架 API，无 androidx 依赖）
app/src/main/assets/hotspot.sh              root 热点引擎（start/stop/status/kick/guard）
app/src/main/assets/wiphy                   预编译 nl80211 工具
tools/wiphy.c                               上述工具源码与构建说明
```

## 已知限制

- 单射频：开热点自动关 Wi-Fi；ROM 在引擎接管射频后不允许 STA 再起来（框架建客户端接口失败回退）
- 充电全屏动画 / PIN 锁屏会遮挡界面，需解锁后操作
- 驱动重载（op_mode=15）失败的最坏情形是 Wi-Fi 暂不可用，重启恢复
- 手表无浏览器时关于页链接仅提示

## 许可

MIT © 2026 ClassFruit · <https://classfruit.cn>
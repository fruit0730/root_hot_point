#!/system/bin/sh
# dgsh hotspot engine for OWW212 (BCM43013 + bcmdhd).
# The vendor HAL hides AP from the framework, and mode-switching the primary
# wlan0 to AP leaves its data path dead, so we create a real AP virtual
# interface (ap0) via nl80211 and run hostapd/dnsmasq/NAT on it. Root required.
# Usage: hotspot.sh start <ssid> <sec 0|1> <passphrase> <channel> <hidden 0|1> <maxsta>
#        hotspot.sh stop | status | kick <mac> | guard
DIR=/data/local/tmp/dgsh
HAPD=/vendor/bin/hw/hostapd
CLI=/vendor/bin/hostapd_cli
DNSMASQ=/system/bin/dnsmasq
WIPHY=$DIR/wiphy
SUBNET=192.168.43
GW=$SUBNET.1
IFACE=$(cat "$DIR/apiface" 2>/dev/null)
if [ -z "$IFACE" ] || ! ip link show "$IFACE" >/dev/null 2>&1; then IFACE=wlan0; fi

cli() { $CLI -p "$DIR/ctrl" -i "$IFACE" "$@" 2>/dev/null; }

kill_pidfile() {
    if [ -f "$DIR/$1.pid" ]; then
        p=$(cat "$DIR/$1.pid")
        if grep -q "$2" "/proc/$p/cmdline" 2>/dev/null; then
            kill "$p" 2>/dev/null
        fi
        rm -f "$DIR/$1.pid"
    fi
}

do_stop() {
    rm -f "$DIR/want"
    kill_pidfile wd guard
    echo dgsh_ap > /sys/power/wake_unlock 2>/dev/null
    kill_pidfile hapd hostapd
    kill_pidfile dnsmasq dnsmasq
    for p in $(ps -A -o PID,ARGS | grep "$DIR/hapd.conf" | grep -v grep | awk '{print $1}'); do
        kill "$p" 2>/dev/null
    done
    sleep 1
    ip addr del $GW/24 dev $IFACE 2>/dev/null
    ip rule del iif $IFACE 2>/dev/null
    ip rule del to $SUBNET.0/24 2>/dev/null
    ip rule del from $SUBNET.0/24 2>/dev/null
    iptables -t nat -D POSTROUTING -j MASQUERADE 2>/dev/null
    iptables -D FORWARD -i $IFACE -j ACCEPT 2>/dev/null
    iptables -D FORWARD -o $IFACE -j ACCEPT 2>/dev/null
}

ensure_ap_iface() {
    if [ "$IFACE" != "wlan0" ] && ip link show "$IFACE" >/dev/null 2>&1; then
        return 0
    fi
    # firmware only downloads once the primary netdev is up
    ip link set wlan0 up 2>/dev/null
    sleep 3
    if $WIPHY addap ap0 >/dev/null 2>&1 && ip link show ap0 >/dev/null 2>&1; then
        IFACE=ap0
        echo ap0 > "$DIR/apiface"
        return 0
    fi
    # boot-default op_mode lacks the AP bit: reload the module with it
    ip link set wlan0 down 2>/dev/null
    rmmod bcmdhd 2>/dev/null
    insmod /vendor/lib/modules/bcmdhd.ko op_mode=15 2>/dev/null
    sleep 2
    ip link set wlan0 up 2>/dev/null
    sleep 3
    if $WIPHY addap ap0 >/dev/null 2>&1 && ip link show ap0 >/dev/null 2>&1; then
        IFACE=ap0
        echo ap0 > "$DIR/apiface"
        return 0
    fi
    return 1
}

case "$1" in
start)
    ssid="$2"; sec="$3"; pass="$4"; chan="${5:-6}"; hidden="${6:-0}"; maxsta="${7:-8}"
    do_stop
    # single radio: keep STA down while we own the AP
    cmd wifi set-wifi-enabled disabled >/dev/null 2>&1
    sleep 1
    if ! ensure_ap_iface; then
        echo '{"ok":false,"error":"cannot create AP interface"}'
        exit 1
    fi
    mkdir -p "$DIR/ctrl"
    chmod 777 "$DIR" "$DIR/ctrl"
    {
        echo "interface=$IFACE"
        echo "driver=nl80211"
        echo "ssid=$ssid"
        echo "hw_mode=g"
        echo "channel=$chan"
        echo "ieee80211n=1"
        echo "wmm_enabled=1"
        echo "ignore_broadcast_ssid=$hidden"
        echo "max_num_sta=$maxsta"
        echo "ctrl_interface=$DIR/ctrl"
        if [ "$sec" = "1" ]; then
            echo "wpa=2"
            echo "wpa_key_mgmt=WPA-PSK"
            echo "rsn_pairwise=CCMP"
            echo "wpa_passphrase=$pass"
        fi
    } > "$DIR/hapd.conf"
    chmod 644 "$DIR/hapd.conf"
    rm -f "$DIR/ctrl/$IFACE"
    : > "$DIR/hapd.log"
    chmod 666 "$DIR/hapd.log"
    nohup $HAPD -dd -f "$DIR/hapd.log" "$DIR/hapd.conf" >/dev/null 2>&1 &
    echo $! > "$DIR/hapd.pid"
    i=0
    while [ $i -lt 20 ]; do
        cli ping | grep -q PONG && break
        sleep 0.5
        i=$((i + 1))
    done
    if ! cli ping | grep -q PONG; then
        do_stop
        echo '{"ok":false,"error":"hostapd did not come up (see logcat -s hostapd)"}'
        exit 1
    fi
    ip addr add $GW/24 dev $IFACE 2>/dev/null
    ip link set $IFACE up
    echo 1 > /proc/sys/net/ipv4/ip_forward
    iptables -t nat -D POSTROUTING -j MASQUERADE 2>/dev/null
    iptables -t nat -A POSTROUTING -j MASQUERADE
    # netd's tetherctrl_FORWARD drops all forwarding while framework tethering
    # is off, so our accepts must sit ahead of netd's chain jumps
    iptables -D FORWARD -i $IFACE -j ACCEPT 2>/dev/null
    iptables -I FORWARD 1 -i $IFACE -j ACCEPT 2>/dev/null
    iptables -D FORWARD -o $IFACE -j ACCEPT 2>/dev/null
    iptables -I FORWARD 1 -o $IFACE -j ACCEPT 2>/dev/null
    # Android keeps the default route in a per-network table, not the main one;
    # forwarded client packets need an explicit rule into that table
    rt=$(ip route show table all 2>/dev/null | grep '^default' | grep -v dummy0 | head -1)
    tbl=$(echo "$rt" | sed 's/.*table \([^ ]*\).*/\1/')
    case "$tbl" in
        "" | *" "*) ;;
        *)
            ip rule del iif $IFACE 2>/dev/null
            ip rule add iif $IFACE lookup "$tbl" pref 17000 2>/dev/null
            ;;
    esac
    # netd's local-traffic rules (pref 22000) would send our DHCP/DNS replies
    # out the cellular default route; pin the hotspot subnet to the main table
    ip rule del to $SUBNET.0/24 2>/dev/null
    ip rule add to $SUBNET.0/24 lookup main pref 15000 2>/dev/null
    ip rule del from $SUBNET.0/24 2>/dev/null
    ip rule add from $SUBNET.0/24 lookup main pref 15000 2>/dev/null
    rm -f "$DIR/dnsmasq.leases"
    nohup $DNSMASQ --keep-in-foreground --no-resolv --no-poll --bind-interfaces \
        --interface=$IFACE --except-interface=lo \
        --dhcp-range=$SUBNET.2,$SUBNET.254,255.255.255.0,1h \
        --dhcp-leasefile="$DIR/dnsmasq.leases" \
        --pid-file="$DIR/dnsmasq.pid" \
        --server=223.5.5.5 --server=119.29.29.29 \
        >/dev/null 2>&1 &
    # this ROM silently drops app-level wakelocks; hold a kernel one instead
    echo dgsh_ap > /sys/power/wake_lock 2>/dev/null
    # root-side keepalive: BmManager blocks the app's wakeup alarms, so a root
    # loop outside the app cgroup revives the service if the process is killed
    touch "$DIR/want"
    nohup sh "$0" guard >/dev/null 2>&1 &
    echo $! > "$DIR/wd.pid"
    echo '{"ok":true}'
    ;;
stop)
    do_stop
    echo '{"ok":true}'
    ;;
kick)
    cli disassociate "$2" >/dev/null 2>&1
    echo '{"ok":true}'
    ;;
guard)
    while [ -f "$DIR/want" ]; do
        if ! ps -A -o NAME | grep -q cn.classfruit.h; then
            am start-foreground-service -n cn.classfruit.hotpoint/.HotspotService \
                -a cn.classfruit.hotpoint.action.START >/dev/null 2>&1
        fi
        sleep 30
    done
    ;;
status)
    alive=0
    state=DISABLED
    ssid=""
    if cli ping | grep -q PONG; then
        alive=1
        st=$(cli status | grep '^state=' | cut -d= -f2)
        [ "$st" = "ENABLED" ] && state=ENABLED || state=ENABLING
        ssid=$(cli status | grep '^ssid\[0\]=' | cut -d= -f2)
        cli all_sta > "$DIR/sta.tmp"
    else
        : > "$DIR/sta.tmp"
    fi
    up=$(ip route show table all 2>/dev/null | grep '^default' | grep -v dummy0 | head -1 | sed 's/.*dev \([^ ]*\).*/\1/')
    sta=$(cmd wifi status 2>/dev/null | head -1 | grep -c '^Wifi is enabled')
    tx=$(cat /sys/class/net/$IFACE/statistics/tx_bytes 2>/dev/null || echo 0)
    rx=$(cat /sys/class/net/$IFACE/statistics/rx_bytes 2>/dev/null || echo 0)
    awk -v alive=$alive -v state="$state" -v ssid="$ssid" -v up="$up" -v sta="$sta" -v tx="$tx" -v rx="$rx" '
        NR == FNR {
            if ($1 ~ /^[0-9]+\./ && $4 ~ /:/) ip[tolower($4)] = $1
            next
        }
        /^[0-9a-fA-F][0-9a-fA-F]:/ {
            mac = tolower($1)
            if (n++) buf = buf ","
            buf = buf "{\"mac\":\"" mac "\",\"ip\":\"" (mac in ip ? ip[mac] : "") "\"}"
        }
        END {
            printf "{\"ok\":true,\"alive\":%d,\"state\":\"%s\",\"ssid\":\"%s\",\"upstream\":\"%s\",\"sta\":%d,\"tx\":%s,\"rx\":%s,\"clients\":[%s]}\n", alive, state, ssid, up, sta, tx, rx, buf
        }
    ' /proc/net/arp "$DIR/sta.tmp"
    ;;
*)
    echo '{"ok":false,"error":"usage: start|stop|status|kick|guard"}'
    ;;
esac
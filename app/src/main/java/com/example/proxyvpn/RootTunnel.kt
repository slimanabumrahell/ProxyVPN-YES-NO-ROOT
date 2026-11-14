package com.example.proxyvpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object RootTunnel {
    val active = MutableStateFlow(false)
    val status = MutableStateFlow("")

    private const val DIR = "/data/local/tmp"
    private const val BIN = "$DIR/hevtun"
    private const val CONF = "$DIR/hevtun.yml"
    private const val LOG = "$DIR/hevtun.log"
    private const val PID = "$DIR/hevtun.pid"
    private const val STOP = "$DIR/proxyvpn_stop.sh"
    private const val START = "$DIR/proxyvpn_start.sh"
    private const val TUN = "tun0"
    private const val TABLE = "2022"
    private const val MARK = "0x2022"
    private const val CHAIN = "PROXYVPN"
    private const val FW_CHAIN = "PROXYVPN_FW"

    private fun su(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    private fun yml(c: ProxyConfig): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: $TUN")
        appendLine("  mtu: 8500")
        appendLine("  ipv4: 198.18.0.1")
        appendLine("socks5:")
        appendLine("  address: ${c.host}")
        appendLine("  port: ${c.port}")
        appendLine("  udp: 'udp'")
        if (c.user.isNotEmpty()) {
            appendLine("  username: '${c.user.replace("'", "''")}'")
            appendLine("  password: '${c.pass.replace("'", "''")}'")
        }
        appendLine("misc:")
        appendLine("  log-level: warn")
    }

    private fun stopScript(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("if [ -f $PID ]; then kill \$(cat $PID) 2>/dev/null; rm -f $PID; fi")
        appendLine("sleep 1")
        appendLine("ip rule del pref 10000 2>/dev/null")
        appendLine("ip rule del pref 10010 2>/dev/null")
        appendLine("ip route flush table $TABLE 2>/dev/null")
        appendLine("iptables -t mangle -D OUTPUT -j $CHAIN 2>/dev/null")
        appendLine("iptables -t mangle -F $CHAIN 2>/dev/null")
        appendLine("iptables -t mangle -X $CHAIN 2>/dev/null")
        appendLine("iptables -D OUTPUT -j $FW_CHAIN 2>/dev/null")
        appendLine("iptables -F $FW_CHAIN 2>/dev/null")
        appendLine("iptables -X $FW_CHAIN 2>/dev/null")
        appendLine("ip6tables -D OUTPUT -m owner ! --uid-owner 0 -j REJECT 2>/dev/null")
        appendLine("echo STOPPED")
    }

    private fun startScript(nlib: String, uids: List<Int>): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("sh $STOP >/dev/null 2>&1")
        appendLine("cp $nlib/libhevtun.so $BIN && chmod 755 $BIN || exit 1")
        appendLine("$BIN $CONF >$LOG 2>&1 &")
        appendLine("echo \$! > $PID")
        appendLine("i=0")
        appendLine("while [ ! -d /sys/class/net/$TUN ] && [ \$i -lt 20 ]; do sleep 1; i=\$((i+1)); done")
        appendLine("ip rule add pref 10000 to 192.168.0.0/16 lookup main")
        appendLine("ip rule add pref 10010 fwmark $MARK lookup $TABLE")
        appendLine("ip route add default dev $TUN table $TABLE")
        appendLine("iptables -t mangle -N $CHAIN")
        appendLine("iptables -t mangle -A $CHAIN -m owner --uid-owner 0 -j RETURN")
        uids.forEach { appendLine("iptables -t mangle -A $CHAIN -m owner --uid-owner $it -j RETURN") }
        appendLine("iptables -t mangle -A $CHAIN -j MARK --set-mark $MARK")
        appendLine("iptables -t mangle -A OUTPUT -j $CHAIN")
        appendLine("iptables -N $FW_CHAIN")
        appendLine("iptables -A $FW_CHAIN -m mark --mark $MARK ! -o $TUN ! -o lo ! -d 192.168.0.0/16 -j REJECT")
        appendLine("iptables -A OUTPUT -j $FW_CHAIN")
        appendLine("ip6tables -A OUTPUT -m owner ! --uid-owner 0 -j REJECT 2>/dev/null")
        appendLine("if [ -d /sys/class/net/$TUN ]; then echo OK; else echo NOTUN; fi")
    }

    fun start(ctx: Context, c: ProxyConfig): Boolean {
        status.value = "جاري تشغيل وضع الروت..."
        val uids = c.excluded.mapNotNull { pkg ->
            try { ctx.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Exception) { null }
        }
        val dir = ctx.cacheDir
        val cfgF = File(dir, "hevtun.yml").apply { writeText(yml(c)) }
        val stopF = File(dir, "stop.sh").apply { writeText(stopScript()) }
        val startF = File(dir, "start.sh").apply {
            writeText(startScript(ctx.applicationInfo.nativeLibraryDir, uids))
        }
        val (code, out) = su(
            "cp ${cfgF.absolutePath} $CONF && cp ${stopF.absolutePath} $STOP && " +
                "cp ${startF.absolutePath} $START && sh $START"
        )
        val ok = code == 0 && out.contains("OK")
        active.value = ok
        status.value = if (ok) "وضع الروت شغّال" else "فشل: ${out.takeLast(200)}"
        if (ok) ctx.startForegroundService(Intent(ctx, RootService::class.java))
        return ok
    }

    fun stop(ctx: Context): Boolean {
        val stopF = File(ctx.cacheDir, "stop.sh").apply { writeText(stopScript()) }
        val (code, out) = su("cp ${stopF.absolutePath} $STOP && sh $STOP")
        active.value = false
        status.value = if (code == 0) "متوقف، تم استرجاع الإعدادات" else "تحذير: ${out.takeLast(200)}"
        ctx.stopService(Intent(ctx, RootService::class.java))
        return code == 0
    }
}

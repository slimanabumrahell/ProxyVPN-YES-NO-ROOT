package com.example.proxyvpn

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedReader
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLEncoder

object RootTunnel {
    val active = MutableStateFlow(false)
    val status = MutableStateFlow("")

    private const val DIR = "/data/local/tmp"
    private const val BIN = "$DIR/hevtun"
    private const val GOST_BIN = "$DIR/gost"
    private const val CONF = "$DIR/hevtun.yml"
    private const val LOG = "$DIR/hevtun.log"
    private const val GOST_LOG = "$DIR/gost.log"
    private const val PID = "$DIR/hevtun.pid"
    private const val GOST_PID = "$DIR/gost.pid"
    private const val STOP = "$DIR/proxyvpn_stop.sh"
    private const val START = "$DIR/proxyvpn_start.sh"
    private const val TUN = "tun0"
    private const val TABLE = "2022"
    private const val MARK = "0x2022"
    private const val CHAIN = "PROXYVPN"
    private const val FW_CHAIN = "PROXYVPN_FW"
    private const val LOCAL_PORT = 1081

    private fun su(cmd: String): Pair<Int, String> {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    // hev يتصل دائماً بـ gost المحلي كـ SOCKS5 بدون مصادقة
    private fun yml(): String = buildString {
        appendLine("tunnel:")
        appendLine("  name: $TUN")
        appendLine("  mtu: 8500")
        appendLine("  ipv4: 198.18.0.1")
        appendLine("socks5:")
        appendLine("  address: 127.0.0.1")
        appendLine("  port: $LOCAL_PORT")
        appendLine("  udp: 'udp'")
        appendLine("misc:")
        appendLine("  log-level: warn")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // gost: يستقبل SOCKS5 محلي ويحوّله للبروكسي الحقيقي بالنوع المستعمل
    private fun gostArgs(c: ProxyConfig): String {
        val scheme = when (c.type) {
            "socks4" -> "socks4"
            "http" -> "http"
            "https" -> "https"
            else -> "socks5"
        }
        val auth = if (c.user.isNotEmpty()) "${enc(c.user)}:${enc(c.pass)}@" else ""
        return "-L socks5://127.0.0.1:$LOCAL_PORT -F $scheme://$auth${c.host}:${c.port}"
    }

    // ---------- كشف تلقائي لنوع البروكسي ----------

    private fun readFully(i: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = i.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException()
            off += n
        }
    }

    private fun probe(host: String, port: Int, check: (OutputStream, InputStream) -> Boolean): Boolean =
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5000)
                s.soTimeout = 5000
                check(s.getOutputStream(), s.getInputStream())
            }
        } catch (_: Exception) {
            false
        }

    // يرجع "socks5" أو "socks4" أو "http" أو null
    fun detectType(host: String, port: Int): String? {
        val isSocks5 = probe(host, port) { o, i ->
            // greeting: version 5، طريقتين (بدون مصادقة و user/pass)
            o.write(byteArrayOf(5, 2, 0, 2)); o.flush()
            val r = ByteArray(2); readFully(i, r)
            r[0] == 5.toByte()
        }
        if (isSocks5) return "socks5"

        val isSocks4 = probe(host, port) { o, i ->
            // CONNECT إلى 1.1.1.1:80 بنسخة SOCKS4
            o.write(byteArrayOf(4, 1, 0, 80, 1, 1, 1, 1, 0)); o.flush()
            val r = ByteArray(8); readFully(i, r)
            r[0] == 0.toByte()
        }
        if (isSocks4) return "socks4"

        val isHttp = probe(host, port) { o, i ->
            o.write("CONNECT 1.1.1.1:443 HTTP/1.1\r\nHost: 1.1.1.1:443\r\n\r\n".toByteArray())
            o.flush()
            val line = BufferedReader(InputStreamReader(i)).readLine() ?: ""
            line.startsWith("HTTP/1")
        }
        return if (isHttp) "http" else null
    }

    private fun stopScript(): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("if [ -f $PID ]; then kill \$(cat $PID) 2>/dev/null; rm -f $PID; fi")
        appendLine("if [ -f $GOST_PID ]; then kill \$(cat $GOST_PID) 2>/dev/null; rm -f $GOST_PID; fi")
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

    private fun startScript(nlib: String, uids: List<Int>, gostArgs: String): String = buildString {
        appendLine("#!/system/bin/sh")
        appendLine("sh $STOP >/dev/null 2>&1")
        appendLine("cp $nlib/libgost.so $GOST_BIN && chmod 755 $GOST_BIN || exit 1")
        appendLine("cp $nlib/libhevtun.so $BIN && chmod 755 $BIN || exit 1")
        // gost أولاً (يعمل كـ root فيستثنى من التوجيه)
        appendLine("$GOST_BIN $gostArgs >$GOST_LOG 2>&1 &")
        appendLine("echo \$! > $GOST_PID")
        appendLine("sleep 2")
        appendLine("if [ ! -d /proc/\$(cat $GOST_PID) ]; then echo GOSTFAIL; exit 1; fi")
        // ثم hev
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

        // إذا الكشف التلقائي مفعّل، نحدد النوع قبل تشغيل أي شي
        val detected: String = if (c.autoDetect) {
            status.value = "جاري كشف نوع البروكسي..."
            detectType(c.host, c.port) ?: run {
                status.value = "فشل: ما قدرت أعرف نوع البروكسي (SOCKS5 / SOCKS4 / HTTP)"
                return false
            }
        } else c.type
        val eff = c.copy(type = detected)

        val uids = c.excluded.mapNotNull { pkg ->
            try { ctx.packageManager.getApplicationInfo(pkg, 0).uid } catch (_: Exception) { null }
        }
        val dir = ctx.cacheDir
        val cfgF = File(dir, "hevtun.yml").apply { writeText(yml()) }
        val stopF = File(dir, "stop.sh").apply { writeText(stopScript()) }
        val startF = File(dir, "start.sh").apply {
            writeText(startScript(ctx.applicationInfo.nativeLibraryDir, uids, gostArgs(eff)))
        }
        val (code, out) = su(
            "cp ${cfgF.absolutePath} $CONF && cp ${stopF.absolutePath} $STOP && " +
                "cp ${startF.absolutePath} $START && sh $START"
        )
        val ok = code == 0 && out.contains("OK")
        active.value = ok
        if (ok) {
            val label = if (c.autoDetect) "النوع المكتشف: ${detected.uppercase()}" else "النوع: ${detected.uppercase()}"
            status.value = "وضع الروت شغّال ($label)"
            ctx.startForegroundService(Intent(ctx, RootService::class.java))
        } else {
            val gostLog = su("tail -n 3 $GOST_LOG 2>/dev/null").second.trim()
            val reason = if (out.contains("GOSTFAIL")) "gost ما اشتغل" else out.takeLast(120)
            status.value = "فشل: $reason" + if (gostLog.isNotEmpty()) "\ngost: ${gostLog.takeLast(160)}" else ""
        }
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

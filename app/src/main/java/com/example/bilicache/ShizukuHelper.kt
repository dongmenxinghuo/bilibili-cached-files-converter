package com.example.bilicache

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object ShizukuHelper {

    const val REQUEST_CODE = 1001

    fun isBinderAlive(): Boolean = try {
        Shizuku.pingBinder()
    } catch (_: Throwable) { false }

    fun hasPermission(): Boolean {
        if (!isBinderAlive()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
    }

    fun requestPermission() {
        if (!isBinderAlive()) return
        if (hasPermission()) return
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) {}
    }

    /** 执行 shell 命令，返回 stdout。 */
    fun exec(cmd: String, timeoutMs: Long = 60_000): String {
        return try {
            val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val out = StringBuilder()
            val t = Thread {
                BufferedReader(InputStreamReader(proc.inputStream)).use { r ->
                    r.forEachLine { out.appendLine(it) }
                }
            }
            val tErr = Thread {
                BufferedReader(InputStreamReader(proc.errorStream)).use { r ->
                    r.forEachLine { /* 丢弃 stderr */ }
                }
            }
            t.start(); tErr.start()
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) proc.destroy()
            t.join(500); tErr.join(500)
            out.toString()
        } catch (e: Throwable) {
            ""
        }
    }

    /** 用 shell 权限把文件复制到应用私有目录。 */
    fun copyToApp(src: String, dst: java.io.File): Boolean {
        val esc = { s: String -> "'" + s.replace("'", "'\\''") + "'" }
        val cmd = "cp ${esc(src)} ${esc(dst.absolutePath)} && chmod 644 ${esc(dst.absolutePath)}"
        exec(cmd, 300_000)
        return dst.exists() && dst.length() > 0
    }
}

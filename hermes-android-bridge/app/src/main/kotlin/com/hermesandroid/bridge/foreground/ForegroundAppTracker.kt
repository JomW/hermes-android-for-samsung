package com.hermesandroid.bridge.foreground

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import com.hermesandroid.bridge.service.BridgeAccessibilityService

/**
 * 前台应用真值获取器。
 *
 * 数据源优先级:
 *   1. UsageStats (系统级, 最可靠, 不依赖无障碍服务是否被冻结)
 *   2. 无障碍事件流缓存 (WINDOW_STATE_CHANGED, 5 分钟内有效)
 *   3. 无障碍窗口列表兜底 (过滤 TYPE_APPLICATION 取最大 layer, 修复旧实现
 *      取 firstRoot 拿到最底层窗口/陈旧快照的问题)
 *
 * 背景: 三星 OneUI 的系统级冻结会让 AccessibilityService 的窗口快照卡死在
 * 旧窗口 (如 launcher), 导致旧版 /current_app 永远误报。UsageStats 由系统
 * 直接维护, 冻结无影响。
 */
object ForegroundAppTracker {

    // ── 事件流缓存 (由 BridgeAccessibilityService.onAccessibilityEvent 写入) ──
    @Volatile
    var lastEventPkg: String? = null
        private set
    @Volatile
    var lastEventCls: String? = null
        private set
    @Volatile
    var lastEventTs: Long = 0L      // SystemClock.uptimeMillis()

    private const val EVENT_CACHE_VALID_MS = 5 * 60_000L  // 事件流缓存有效期 5 分钟

    fun onWindowStateChanged(pkg: String?, cls: String?) {
        if (pkg != null && pkg != lastEventPkg) {
            lastEventPkg = pkg
            lastEventCls = cls
            lastEventTs = SystemClock.uptimeMillis()
        }
    }

    /** 距上次无障碍事件多少毫秒 (-1 = 从未收到事件, 用于僵尸状态诊断) */
    fun lastEventAgoMs(): Long {
        if (lastEventTs == 0L) return -1L
        return SystemClock.uptimeMillis() - lastEventTs
    }

    /**
     * 获取当前前台应用。
     * @return map: package / className / source(usage|event|window|none) / ts /
     *              screenOn / locked
     */
    fun current(context: Context): Map<String, Any?> {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val screenOn = pm.isInteractive
        val lockState = detectLockState(context, screenOn)
        if (lockState != null) {
            return lockState
        }

        // 1. UsageStats 系统级真值
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            // UsageEvents() 构造是 package-private, 只能用 queryEvents 返回值
            val events = usm.queryEvents(now - 3 * 60_000L, now)
            if (events != null) {
                var lastPkg: String? = null
                var lastCls: String? = null
                var lastTs = 0L
                val e = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(e)
                    val t = e.eventType
                    // MOVE_TO_FOREGROUND(1) / ACTIVITY_RESUMED(5, API 29+)
                    if (t == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                        t == UsageEvents.Event.ACTIVITY_RESUMED
                    ) {
                        lastPkg = e.packageName
                        lastCls = e.className
                        lastTs = e.timeStamp
                    }
                }
                if (lastPkg != null) {
                    return mapOf(
                        "package" to lastPkg,
                        "className" to (lastCls ?: ""),
                        "source" to "usage",
                        "ts" to lastTs,
                        "screenOn" to screenOn,
                        "locked" to false,
                    )
                }
            }
        } catch (_: SecurityException) {
            // PACKAGE_USAGE_STATS 未授权 → 降级
        } catch (_: Exception) {
            // 其他异常 → 降级
        }

        // 2. 无障碍事件流缓存 (5 分钟内)
        if (lastEventPkg != null && lastEventAgoMs() < EVENT_CACHE_VALID_MS) {
            return mapOf(
                "package" to lastEventPkg!!,
                "className" to (lastEventCls ?: ""),
                "source" to "event",
                "ts" to lastEventTs,
                "screenOn" to screenOn,
                "locked" to false,
            )
        }

        // 3. 无障碍窗口列表兜底: 只取 TYPE_APPLICATION 且最大 layer 的窗口
        try {
            val svc = BridgeAccessibilityService.instance
            val windows = svc?.windows ?: emptyList()
            var best: AccessibilityWindowInfo? = null
            for (w in windows) {
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    if (best == null || w.layer > best.layer) {
                        best?.recycle()
                        best = w
                    } else {
                        w.recycle()
                    }
                } else {
                    w.recycle()
                }
            }
            val root = best?.root
            val pkg = root?.packageName?.toString() ?: "unknown"
            val cls = root?.className?.toString() ?: "unknown"
            root?.recycle()
            best?.recycle()
            return mapOf(
                "package" to pkg,
                "className" to cls,
                "source" to "window",
                "ts" to 0L,
                "screenOn" to screenOn,
                "locked" to false,
            )
        } catch (_: Exception) {
            return mapOf(
                "package" to "unknown",
                "className" to "",
                "source" to "none",
                "ts" to 0L,
                "screenOn" to screenOn,
                "locked" to false,
            )
        }
    }

    /**
     * 锁屏/灭屏检测。返回 null 表示未锁屏, 否则返回锁屏状态 map。
     * 约定: 灭屏或 keyguard 显示时 package 报 com.android.systemui (与旧版兼容,
     * phone_check.py 用该包名判定 locked)。
     */
    private fun detectLockState(context: Context, screenOn: Boolean): Map<String, Any?>? {
        if (!screenOn) {
            return mapOf(
                "package" to "com.android.systemui",
                "className" to "ScreenOff",
                "source" to "power",
                "ts" to 0L,
                "screenOn" to false,
                "locked" to true,
            )
        }
        // 亮屏但可能显示锁屏: 找 Keyguard 类名的窗口 (状态栏也是 systemui 包名, 但类名不含 Keyguard)
        try {
            val svc = BridgeAccessibilityService.instance
            val windows = svc?.windows ?: emptyList()
            for (w in windows) {
                val root = w.root
                val pkg = root?.packageName?.toString() ?: ""
                val cls = root?.className?.toString() ?: ""
                root?.recycle()
                w.recycle()
                if (pkg == "com.android.systemui" && cls.contains("Keyguard", ignoreCase = true)) {
                    return mapOf(
                        "package" to "com.android.systemui",
                        "className" to cls,
                        "source" to "keyguard",
                        "ts" to 0L,
                        "screenOn" to true,
                        "locked" to true,
                    )
                }
            }
        } catch (_: Exception) {
            // 窗口列表不可用时忽略, 交给上层判定
        }
        return null
    }

    /** 最近活跃时间 (UsageStats 最近一次前台事件, epoch ms; 无权限返回 0) */
    fun lastActivityTs(context: Context): Long {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 24 * 3600_000L, now) ?: return 0L
            var last = 0L
            val e = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    last = e.timeStamp
                }
            }
            return last
        } catch (_: Exception) {
            return 0L
        }
    }

    /** 最近使用应用列表 (UsageStats, 按 lastTimeUsed 倒序) */
    fun recentApps(context: Context, limit: Int = 20): List<Map<String, Any?>> {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val now = System.currentTimeMillis()
            val begin = now - 24 * 3600_000L  // 最近 24h
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, now)
            return stats
                .filter { it.lastTimeUsed > 0 }
                .sortedByDescending { it.lastTimeUsed }
                .take(limit)
                .map {
                    mapOf(
                        "package" to it.packageName,
                        "lastTimeUsed" to it.lastTimeUsed,
                        "totalTimeInForegroundMs" to it.totalTimeInForeground,
                    )
                }
        } catch (_: Exception) {
            return emptyList()
        }
    }
}

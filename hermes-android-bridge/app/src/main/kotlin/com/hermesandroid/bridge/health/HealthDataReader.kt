package com.hermesandroid.bridge.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 健康数据读取器: 通过 Health Connect 直接读取三星健康同步过来的原始数据。
 *
 * 替代旧方案"抢屏打开三星健康 → 截图 → VLM 识别"——不打断用户、零误识别。
 * 数据流: 三星健康 App → (用户授权同步) → Health Connect → 本 App (只读)
 *
 * 说明: 三星健康的"能量得分/睡眠得分"是专有指标, Health Connect 只有原始数据
 * (步数/睡眠时长与阶段/心率采样/卡路里), 得分类指标由 PC 端按需推算或放弃。
 */
object HealthDataReader {

    private val REQUIRED_PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    private fun shortName(permission: String): String {
        return permission.substringAfterLast('.')
    }

    suspend fun read(context: Context): Map<String, Any?> {
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val missing = REQUIRED_PERMISSIONS - granted
            if (missing.isNotEmpty()) {
                return mapOf(
                    "available" to true,
                    "permissionsGranted" to false,
                    "missingPermissions" to missing.map { shortName(it) },
                    "hint" to "请在手机 设置→健康连接(Health Connect)→应用权限→Hermes Bridge 中授予读取权限",
                )
            }

            val now = Instant.now()
            val zone = ZoneId.systemDefault()
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()
            val day24hStart = now.minus(24, ChronoUnit.HOURS)

            // 步数 (今日 0 点起)
            val steps = try {
                val resp = client.readRecords(
                    ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(todayStart, now))
                )
                resp.records.sumOf { it.count }
            } catch (_: Exception) { null }

            // 睡眠 (最近 24h, 取最近一条)
            var sleepMinutes: Long? = null
            var sleepStart: String? = null
            var sleepEnd: String? = null
            try {
                val resp = client.readRecords(
                    ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(day24hStart, now))
                )
                val sleep = resp.records.maxByOrNull { it.endTime }
                if (sleep != null) {
                    sleepMinutes = Duration.between(sleep.startTime, sleep.endTime).toMinutes()
                    sleepStart = sleep.startTime.toString()
                    sleepEnd = sleep.endTime.toString()
                }
            } catch (_: Exception) { /* 睡眠数据可能为空 */ }

            // 心率 (最近 24h 采样点)
            var heartRate: Map<String, Any?>? = null
            try {
                val resp = client.readRecords(
                    ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(day24hStart, now))
                )
                val samples = resp.records.flatMap { it.samples }
                if (samples.isNotEmpty()) {
                    // 1.1.0 API: Sample.beatsPerMinute 是 Long (无 Measurement<Double>)
                    val vals = samples.map { it.beatsPerMinute.toDouble() }
                    heartRate = mapOf(
                        "count" to vals.size,
                        "min" to vals.min(),
                        "avg" to Math.round(vals.average() * 10) / 10.0,
                        "max" to vals.max(),
                    )
                }
            } catch (_: Exception) { /* 心率可能无采样 */ }

            // 活跃卡路里 (今日)
            var kcal: Double? = null
            try {
                val resp = client.readRecords(
                    ReadRecordsRequest(ActiveCaloriesBurnedRecord::class, TimeRangeFilter.between(todayStart, now))
                )
                // 1.1.0 API: 属性名是 inKilocalories (@get:JvmName 重命名为 getKilocalories)
                kcal = resp.records.sumOf { it.energy.inKilocalories }
            } catch (_: Exception) { /* 卡路里可能为空 */ }

            mapOf(
                "available" to true,
                "permissionsGranted" to true,
                "steps" to steps,
                "sleepMinutes" to sleepMinutes,
                "sleepStart" to sleepStart,
                "sleepEnd" to sleepEnd,
                "heartRate" to heartRate,
                "activeCaloriesKcal" to kcal,
                "note" to "能量得分/睡眠得分为三星专有指标, Health Connect 仅提供原始数据",
            )
        } catch (e: Exception) {
            mapOf(
                "available" to false,
                "error" to "${e.javaClass.simpleName}: ${e.message?.take(120) ?: ""}",
            )
        }
    }
}

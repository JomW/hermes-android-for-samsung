package com.hermesandroid.bridge.health

import java.time.Duration
import java.time.Instant

/**
 * 睡眠分段聚合器(纯函数, 可单测)。
 *
 * 三星健康会把同一次夜间睡眠拆成多条 SleepSessionRecord 同步(中途短暂苏醒即断段)。
 * 本聚合器把相邻间隔 < [MERGE_GAP_MIN] 分钟的段视为同一次睡眠事件, 合并成一个 Span;
 * 跨越多段的睡眠取各段净时长之和(剔除中间清醒), 而不是把整段跨度当成睡眠时长。
 */
object SleepAggregator {

    /** 相邻段间隔小于该值(分钟)视为同一次睡眠事件的连续段。 */
    const val MERGE_GAP_MIN = 150L

    /** 一段合并后的睡眠区间: start/end 为完整跨度, minutes 为各段净睡眠时长之和。 */
    data class Span(val start: Instant, val end: Instant, val minutes: Long)

    /**
     * @param records 各 SleepSessionRecord 的起止时间(通常已按 startTime 升序, 这里内部再排一次)
     * @return 合并后的睡眠 span 列表(按 start 升序);空入参返回空列表
     */
    fun merge(records: List<Pair<Instant, Instant>>): List<Span> {
        if (records.isEmpty()) return emptyList()
        val sorted = records.sortedBy { it.first }
        val spans = mutableListOf<Span>()
        for ((start, end) in sorted) {
            val segMin = Duration.between(start, end).toMinutes()
            val last = spans.lastOrNull()
            if (last != null && Duration.between(last.end, start).toMinutes() < MERGE_GAP_MIN) {
                spans[spans.size - 1] = Span(
                    last.start, maxOf(last.end, end), last.minutes + segMin
                )
            } else {
                spans.add(Span(start, end, segMin))
            }
        }
        return spans
    }
}

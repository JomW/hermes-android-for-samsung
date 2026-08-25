package com.hermesandroid.bridge.health

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * SleepAggregator 聚合逻辑单元测试。
 *
 * 回归场景(2026-08-25 Jom 实况): 三星健康把一次夜间睡眠拆成两段,
 * 03:09~04:49 与 06:36~11:00, 间隔 107 分钟。旧逻辑(阈值60min + 只取maxEnd)
 * 只报出 264min, 漏掉首段 100min。新逻辑应聚合为 03:09~11:00 / 净睡眠 364min。
 */
class SleepAggregatorTest {

    private fun instant(h: Int, m: Int) = Instant.parse("2026-08-25T%02d:%02d:00Z".format(h, m))

    @Test
    fun `合并间隔107分钟的拆段睡眠, 净时长为各段之和`() {
        val seg1 = instant(3, 9) to instant(4, 49)   // 100 min
        val seg2 = instant(6, 36) to instant(11, 0)  // 264 min
        val result = SleepAggregator.merge(listOf(seg2, seg1))  // 乱序也应正确

        assertEquals(1, result.size)
        assertEquals(result[0].start, instant(3, 9))
        assertEquals(result[0].end, instant(11, 0))
        assertEquals(364, result[0].minutes)  // 100 + 264, 不含中间醒着
    }

    @Test
    fun `间隔超过150分钟视为两次独立睡眠`() {
        val seg1 = instant(3, 9) to instant(4, 49)  // 100 min
        val seg2 = instant(9, 0) to instant(13, 0)  // 240 min, 间隔 >150
        val result = SleepAggregator.merge(listOf(seg1, seg2))

        assertEquals(2, result.size)
        assertEquals(100, result[0].minutes)
        assertEquals(240, result[1].minutes)
    }

    @Test
    fun `空列表返回空`() {
        assertEquals(0, SleepAggregator.merge(emptyList()).size)
    }

    @Test
    fun `相邻段间隔恰好150分钟不合并`() {
        val seg1 = instant(0, 0) to instant(1, 0)   // 60 min
        val seg2 = instant(3, 30) to instant(4, 0)  // 30 min, 间隔恰好150
        val result = SleepAggregator.merge(listOf(seg1, seg2))

        assertEquals(2, result.size)
    }
}

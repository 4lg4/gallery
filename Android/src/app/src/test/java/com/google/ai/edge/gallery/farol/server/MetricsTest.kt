/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.farol.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [Metrics] using a deterministic fake clock so assertions on uptime and
 * timestamps don't depend on wall-clock timing.
 */
class MetricsTest {

  // ── initial state ─────────────────────────────────────────────────────────

  @Test
  fun `initial snapshot has zero counts`() {
    val clock = FakeClock(1_000L)
    val m = Metrics(clock)
    val snap = m.snapshot("test-model")

    assertEquals(0L, snap.totalRequests)
    assertEquals(0L, snap.totalErrors)
    assertEquals(0L, snap.perEndpoint[FarolEndpoint.CHAT.key])
    assertEquals(0L, snap.perEndpoint[FarolEndpoint.CAPTION.key])
    assertEquals(0L, snap.perEndpoint[FarolEndpoint.VQA.key])
    assertEquals(-1L, snap.lastRequestDurationMs)
    assertEquals(-1L, snap.lastRequestAt)
  }

  @Test
  fun `initial snapshot uptime is approximately zero`() {
    val clock = FakeClock(5_000L)
    val m = Metrics(clock)
    val snap = m.snapshot("test-model")
    // uptime = clock() - startedAt; both calls happen at the same tick
    assertEquals(0L, snap.uptimeMs)
  }

  @Test
  fun `snapshot includes model name`() {
    val m = Metrics(FakeClock(0L))
    assertEquals("my-model", m.snapshot("my-model").model)
  }

  // ── count increments ──────────────────────────────────────────────────────

  @Test
  fun `record increments totalRequests`() {
    val m = Metrics(FakeClock(0L))
    m.record(FarolEndpoint.CHAT, durationMs = 10L, error = false)
    m.record(FarolEndpoint.VQA, durationMs = 20L, error = false)
    assertEquals(2L, m.snapshot("x").totalRequests)
  }

  @Test
  fun `record with error increments totalErrors and totalRequests`() {
    val m = Metrics(FakeClock(0L))
    m.record(FarolEndpoint.CHAT, durationMs = 5L, error = true)
    val snap = m.snapshot("x")
    assertEquals(1L, snap.totalRequests)
    assertEquals(1L, snap.totalErrors)
  }

  @Test
  fun `record without error does not increment totalErrors`() {
    val m = Metrics(FakeClock(0L))
    m.record(FarolEndpoint.CAPTION, durationMs = 5L, error = false)
    assertEquals(0L, m.snapshot("x").totalErrors)
  }

  @Test
  fun `record increments the correct perEndpoint bucket`() {
    val m = Metrics(FakeClock(0L))
    m.record(FarolEndpoint.CHAT, durationMs = 1L, error = false)
    m.record(FarolEndpoint.CHAT, durationMs = 2L, error = false)
    m.record(FarolEndpoint.CAPTION, durationMs = 3L, error = false)
    val snap = m.snapshot("x")
    assertEquals(2L, snap.perEndpoint[FarolEndpoint.CHAT.key])
    assertEquals(1L, snap.perEndpoint[FarolEndpoint.CAPTION.key])
    assertEquals(0L, snap.perEndpoint[FarolEndpoint.VQA.key])
  }

  // ── duration and timestamp ────────────────────────────────────────────────

  @Test
  fun `record updates lastRequestDurationMs to most recent value`() {
    val m = Metrics(FakeClock(0L))
    m.record(FarolEndpoint.CHAT, durationMs = 42L, error = false)
    m.record(FarolEndpoint.VQA, durationMs = 99L, error = false)
    assertEquals(99L, m.snapshot("x").lastRequestDurationMs)
  }

  @Test
  fun `record updates lastRequestAt using clock`() {
    val clock = FakeClock(1_000L)
    val m = Metrics(clock)
    clock.advance(500L)
    m.record(FarolEndpoint.CHAT, durationMs = 10L, error = false)
    assertEquals(1_500L, m.snapshot("x").lastRequestAt)
  }

  // ── uptime ────────────────────────────────────────────────────────────────

  @Test
  fun `uptime grows as clock advances`() {
    val clock = FakeClock(0L)
    val m = Metrics(clock)
    clock.advance(3_000L)
    val snap = m.snapshot("x")
    assertTrue(snap.uptimeMs >= 3_000L, "uptimeMs=${snap.uptimeMs}")
  }
}

// ── helpers ───────────────────────────────────────────────────────────────────

/**
 * Mutable fake clock for deterministic time assertions.
 *
 * @param initialMs Starting epoch-ms.
 */
private class FakeClock(private var nowMs: Long) : () -> Long {
  override fun invoke(): Long = nowMs
  fun advance(deltaMs: Long) { nowMs += deltaMs }
}

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

import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.Serializable

/**
 * Endpoint identifiers used as keys in [MetricsSnapshot.perEndpoint].
 */
enum class FarolEndpoint(val key: String) {
  CHAT("chat"),
  CAPTION("caption"),
  VQA("vqa"),
}

/**
 * Immutable snapshot of server metrics returned by GET /metrics.
 *
 * @property uptimeMs   Milliseconds since the server was started.
 * @property totalRequests Total number of inference requests that completed (success + error).
 * @property totalErrors   Total number of inference requests that completed with an error.
 * @property perEndpoint   Per-endpoint request counts keyed by [FarolEndpoint.key].
 * @property lastRequestDurationMs Wall-clock duration of the most recent request in ms, or -1 if
 *   none has completed yet.
 * @property lastRequestAt Epoch-ms timestamp of the most recent request completion, or -1 if none.
 * @property model Name of the loaded model at the time the snapshot was taken.
 */
@Serializable
data class MetricsSnapshot(
  val uptimeMs: Long,
  val totalRequests: Long,
  val totalErrors: Long,
  val perEndpoint: Map<String, Long>,
  val lastRequestDurationMs: Long,
  val lastRequestAt: Long,
  val model: String,
)

/**
 * Thread-safe server metrics collector.
 *
 * All mutation methods are safe to call from concurrent coroutines without external
 * synchronisation — each field uses a [java.util.concurrent.atomic] primitive.
 *
 * @param clock Injectable clock function returning epoch-ms; defaults to
 *   [System.currentTimeMillis].  Override in tests for deterministic uptime/timestamp assertions.
 */
class Metrics(private val clock: () -> Long = System::currentTimeMillis) {

  private val startedAt: Long = clock()

  private val totalRequests = AtomicLong(0L)
  private val totalErrors = AtomicLong(0L)

  private val perEndpointCounts: Map<FarolEndpoint, AtomicLong> =
    FarolEndpoint.values().associateWith { AtomicLong(0L) }

  private val lastRequestDurationMs = AtomicLong(-1L)
  private val lastRequestAt = AtomicLong(-1L)

  /**
   * Records a completed inference request for [endpoint].
   *
   * @param endpoint  Which endpoint handled the request.
   * @param durationMs Wall-clock duration of the engine call in milliseconds.
   * @param error Whether the request ended in an error (engine threw).
   */
  fun record(endpoint: FarolEndpoint, durationMs: Long, error: Boolean) {
    totalRequests.incrementAndGet()
    if (error) totalErrors.incrementAndGet()
    perEndpointCounts.getValue(endpoint).incrementAndGet()
    lastRequestDurationMs.set(durationMs)
    lastRequestAt.set(clock())
  }

  /**
   * Returns a consistent point-in-time snapshot.
   *
   * Note: individual atomic reads are not taken under a single lock, so concurrent [record] calls
   * may cause minor inconsistencies across fields (e.g. totalRequests incremented but
   * perEndpoint not yet).  This is acceptable for a lightweight telemetry endpoint.
   *
   * @param model Name of the currently loaded model to include in the snapshot.
   */
  fun snapshot(model: String): MetricsSnapshot = MetricsSnapshot(
    uptimeMs = clock() - startedAt,
    totalRequests = totalRequests.get(),
    totalErrors = totalErrors.get(),
    perEndpoint = FarolEndpoint.values().associate { it.key to perEndpointCounts.getValue(it).get() },
    lastRequestDurationMs = lastRequestDurationMs.get(),
    lastRequestAt = lastRequestAt.get(),
    model = model,
  )
}

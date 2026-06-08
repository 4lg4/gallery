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

package com.google.ai.edge.gallery.farol

import kotlin.test.Test
import kotlin.test.assertTrue

class RestartSchedulerTest {

  @Test
  fun `restartDelayMillis is positive`() {
    assertTrue(RestartScheduler.restartDelayMillis() > 0L)
  }

  @Test
  fun `restartDelayMillis is at least 1 second`() {
    // Must be long enough for the system to settle after the task is swiped away.
    assertTrue(RestartScheduler.restartDelayMillis() >= 1_000L)
  }

  @Test
  fun `restartDelayMillis is at most 10 seconds`() {
    // Should be short enough to feel instant to a user waiting for inference.
    assertTrue(RestartScheduler.restartDelayMillis() <= 10_000L)
  }
}

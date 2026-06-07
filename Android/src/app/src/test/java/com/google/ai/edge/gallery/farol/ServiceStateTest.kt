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

import com.google.ai.edge.gallery.farol.ServiceState.NotificationState
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ServiceStateTest {

  // ── keyFileProblem ────────────────────────────────────────────────────────

  @Test
  fun `keyFileProblem returns non-null when content is null (file missing)`() {
    assertNotNull(ServiceState.keyFileProblem(null))
  }

  @Test
  fun `keyFileProblem message mentions push-key when file is missing`() {
    val msg = ServiceState.keyFileProblem(null)!!
    assertContains(msg, "push-key.sh")
  }

  @Test
  fun `keyFileProblem returns non-null when content is empty string`() {
    assertNotNull(ServiceState.keyFileProblem(""))
  }

  @Test
  fun `keyFileProblem returns non-null when content is whitespace only`() {
    assertNotNull(ServiceState.keyFileProblem("   "))
  }

  @Test
  fun `keyFileProblem returns non-null when content is newline only`() {
    assertNotNull(ServiceState.keyFileProblem("\n"))
  }

  @Test
  fun `keyFileProblem returns null for a valid non-blank key`() {
    assertNull(ServiceState.keyFileProblem("sk-abc123"))
  }

  @Test
  fun `keyFileProblem returns null for a key with surrounding whitespace (caller is responsible for trim)`() {
    // ServiceState does not trim — the service trims before calling; whitespace alone = blank.
    // A key with leading/trailing space is still non-blank, so problem = null.
    assertNull(ServiceState.keyFileProblem(" sk-abc123 "))
  }

  // ── notificationText ─────────────────────────────────────────────────────

  @Test
  fun `notificationText for Loading contains 'loading'`() {
    val text = ServiceState.notificationText(NotificationState.Loading)
    assertContains(text, "loading")
  }

  @Test
  fun `notificationText for Serving contains port number`() {
    val text = ServiceState.notificationText(NotificationState.Serving(8080, "Gemma-4"))
    assertContains(text, "8080")
  }

  @Test
  fun `notificationText for Serving contains model name`() {
    val text = ServiceState.notificationText(NotificationState.Serving(8080, "Gemma-4-E4B-it"))
    assertContains(text, "Gemma-4-E4B-it")
  }

  @Test
  fun `notificationText for Error contains error message`() {
    val text = ServiceState.notificationText(NotificationState.Error("farol.key missing"))
    assertContains(text, "farol.key missing")
  }

  @Test
  fun `notificationText for Serving with different port reflects the port`() {
    val text = ServiceState.notificationText(NotificationState.Serving(9090, "Model"))
    assertContains(text, "9090")
  }

  @Test
  fun `notificationText Error with long message is preserved`() {
    val longMsg = "model not found at /sdcard/Android/data/com.example/files/path/to/model.litertlm"
    val text = ServiceState.notificationText(NotificationState.Error(longMsg))
    assertContains(text, longMsg)
  }

  // ── State identity ────────────────────────────────────────────────────────

  @Test
  fun `Serving data class equality holds for same port and model`() {
    val a = NotificationState.Serving(8080, "Gemma")
    val b = NotificationState.Serving(8080, "Gemma")
    assertEquals(a, b)
  }

  @Test
  fun `Error data class equality holds for same message`() {
    val a = NotificationState.Error("oops")
    val b = NotificationState.Error("oops")
    assertEquals(a, b)
  }
}

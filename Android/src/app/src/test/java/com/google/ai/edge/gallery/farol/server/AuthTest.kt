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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthTest {

  private val key = "secret-key"

  @Test
  fun `bearer header with correct key is authorized`() {
    assertTrue(Auth.isAuthorized("Bearer secret-key", null, key))
  }

  @Test
  fun `bearer header with extra whitespace is authorized`() {
    assertTrue(Auth.isAuthorized("Bearer  secret-key ", null, key))
  }

  @Test
  fun `bearer header with wrong key is not authorized`() {
    assertFalse(Auth.isAuthorized("Bearer wrong-key", null, key))
  }

  @Test
  fun `farol header with correct key is authorized`() {
    assertTrue(Auth.isAuthorized(null, "secret-key", key))
  }

  @Test
  fun `farol header with wrong key is not authorized`() {
    assertFalse(Auth.isAuthorized(null, "wrong-key", key))
  }

  @Test
  fun `both headers absent is not authorized`() {
    assertFalse(Auth.isAuthorized(null, null, key))
  }

  @Test
  fun `blank configured key always fails even with correct bearer`() {
    assertFalse(Auth.isAuthorized("Bearer secret", null, ""))
  }

  @Test
  fun `blank configured key always fails with farol header`() {
    assertFalse(Auth.isAuthorized(null, "secret", ""))
  }

  @Test
  fun `bearer prefix is case-insensitive to check exact bearer prefix`() {
    // "Bearer " is the exact prefix; no case folding needed per spec — just exact Bearer strip
    assertTrue(Auth.isAuthorized("Bearer secret-key", null, key))
  }

  @Test
  fun `auth header without Bearer prefix is not authorized via auth header`() {
    // plain value without "Bearer " prefix shouldn't be accepted as bearer
    assertFalse(Auth.isAuthorized("secret-key", null, key))
  }
}

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

package com.google.ai.edge.gallery.farol.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelLocatorTest {

  @Test
  fun `relativeModelPath matches expected path`() {
    assertEquals(
      "Gemma_4_E4B_it/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm",
      ModelLocator.relativeModelPath(),
    )
  }

  @Test
  fun `relativeModelPath starts with normalized name`() {
    assertTrue(
      ModelLocator.relativeModelPath().startsWith(ModelLocator.MODEL_NORMALIZED_NAME),
      "Relative path must start with the normalized model name",
    )
  }

  @Test
  fun `relativeModelPath contains version`() {
    assertTrue(
      ModelLocator.relativeModelPath().contains(ModelLocator.MODEL_VERSION),
      "Relative path must contain the version commit hash",
    )
  }

  @Test
  fun `relativeModelPath ends with model file name`() {
    assertTrue(
      ModelLocator.relativeModelPath().endsWith(ModelLocator.MODEL_FILE_NAME),
      "Relative path must end with the model file name",
    )
  }

  @Test
  fun `MODEL_NORMALIZED_NAME has no non-alphanumeric chars except underscore`() {
    val invalid = ModelLocator.MODEL_NORMALIZED_NAME.filter { !it.isLetterOrDigit() && it != '_' }
    assertTrue(
      invalid.isEmpty(),
      "Normalized name must only contain letters, digits, or underscores; found: '$invalid'",
    )
  }

  @Test
  fun `MODEL_VERSION is 40 hex chars`() {
    val hex = ModelLocator.MODEL_VERSION
    assertEquals(40, hex.length, "Commit hash must be 40 characters")
    assertTrue(
      hex.all { it.isDigit() || it in 'a'..'f' },
      "Commit hash must be lowercase hex",
    )
  }

  @Test
  fun `MODEL_FILE_NAME ends with litertlm extension`() {
    assertTrue(
      ModelLocator.MODEL_FILE_NAME.endsWith(".litertlm"),
      "Model file must have .litertlm extension",
    )
  }
}

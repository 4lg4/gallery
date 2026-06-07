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

import android.content.Context
import java.io.File

/**
 * Locates the FAROL v1 model file on the device using the same path convention as
 * [com.google.ai.edge.gallery.data.Model.getPath]:
 *
 *   `{externalFilesDir}/{normalizedName}/{version}/{fileName}`
 *
 * Constants below are sourced from `model_allowlists/1_0_15.json` (Gemma-4-E4B-it entry).
 * Update them when the allowlist is bumped.
 */
object ModelLocator {

  // ── FAROL v1 model constants ────────────────────────────────────────────────
  // Source: model_allowlists/1_0_15.json → name "Gemma-4-E4B-it"

  /** Human-readable model name, as it appears in the allowlist `name` field. */
  const val MODEL_NAME = "Gemma-4-E4B-it"

  /**
   * Normalized model name used as the directory component in the on-device path.
   *
   * Derived by replacing every non-alphanumeric character with `_`
   * (mirrors [com.google.ai.edge.gallery.data.Model.normalizedName]).
   */
  const val MODEL_NORMALIZED_NAME = "Gemma_4_E4B_it"

  /**
   * Commit hash pinning the exact model file version.
   *
   * Source: model_allowlists/1_0_15.json → `commitHash`.
   */
  const val MODEL_VERSION = "28299f30ee4d43294517a4ac93abd6163412f07f"

  /**
   * File name of the downloaded model artifact.
   *
   * Source: model_allowlists/1_0_15.json → `modelFile`.
   */
  const val MODEL_FILE_NAME = "gemma-4-E4B-it.litertlm"

  // ── Path helpers ────────────────────────────────────────────────────────────

  /**
   * Returns the relative path segment (below `externalFilesDir`) for the model file.
   *
   * Pure function — testable without a [Context].
   *
   *   `Gemma_4_E4B_it/28299f30ee4d43294517a4ac93abd6163412f07f/gemma-4-E4B-it.litertlm`
   */
  fun relativeModelPath(): String =
    "$MODEL_NORMALIZED_NAME/$MODEL_VERSION/$MODEL_FILE_NAME"

  /**
   * Returns the absolute [File] for the model, combining [Context.getExternalFilesDir] with
   * [relativeModelPath].
   *
   * Does NOT check whether the file exists — use [requireModel] for that.
   */
  fun locate(context: Context): File {
    val base = context.getExternalFilesDir(null)?.absolutePath
      ?: error("getExternalFilesDir returned null")
    return File(base, relativeModelPath())
  }

  /**
   * Returns the absolute [File] for the model, throwing [IllegalStateException] if the file is
   * not present.
   *
   * The error message includes the expected `adb push` destination path to help with manual model
   * provisioning.
   */
  fun requireModel(context: Context): File {
    val file = locate(context)
    if (!file.exists()) {
      throw IllegalStateException(
        "FAROL model not found at: ${file.absolutePath}\n" +
          "Push the model with:\n" +
          "  adb push gemma-4-E4B-it.litertlm ${file.absolutePath}"
      )
    }
    return file
  }
}

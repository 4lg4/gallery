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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoStartPolicyTest {

  // Intent action literals verified against AOSP:
  //   android.intent.action.BOOT_COMPLETED
  //   android.intent.action.MY_PACKAGE_REPLACED

  @Test
  fun `shouldStartService returns true for BOOT_COMPLETED`() {
    assertTrue(AutoStartPolicy.shouldStartService("android.intent.action.BOOT_COMPLETED"))
  }

  @Test
  fun `shouldStartService returns true for MY_PACKAGE_REPLACED`() {
    assertTrue(AutoStartPolicy.shouldStartService("android.intent.action.MY_PACKAGE_REPLACED"))
  }

  @Test
  fun `shouldStartService returns false for null action`() {
    assertFalse(AutoStartPolicy.shouldStartService(null))
  }

  @Test
  fun `shouldStartService returns false for unrelated action`() {
    assertFalse(AutoStartPolicy.shouldStartService("android.intent.action.ACTION_POWER_CONNECTED"))
  }

  @Test
  fun `shouldStartService returns false for empty string`() {
    assertFalse(AutoStartPolicy.shouldStartService(""))
  }

  @Test
  fun `shouldStartService returns false for PACKAGE_REPLACED (different from MY_PACKAGE_REPLACED)`() {
    // android.intent.action.PACKAGE_REPLACED is broadcast to all apps; MY_PACKAGE_REPLACED is
    // delivered only to the replaced app.  We only handle the latter.
    assertFalse(AutoStartPolicy.shouldStartService("android.intent.action.PACKAGE_REPLACED"))
  }
}

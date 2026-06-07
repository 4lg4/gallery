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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * FAROL — LAN LLM inference server foreground service.
 *
 * TODO: Task 2 — embed Ktor CIO server, bind engine.
 */
class FarolService : Service() {

  override fun onCreate() {
    super.onCreate()
    val manager = getSystemService(NotificationManager::class.java)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
      manager.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "FAROL server", NotificationManager.IMPORTANCE_LOW)
      )
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val notification =
      Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("FAROL server")
        .setSmallIcon(android.R.drawable.ic_menu_share)
        .build()
    startForeground(NOTIFICATION_ID, notification)
    // TODO: Task 2 — start Ktor server.
    return START_STICKY
  }

  override fun onDestroy() {
    super.onDestroy()
    // TODO: Task 2 — stop Ktor server.
  }

  companion object {
    private const val CHANNEL_ID = "farol_server"
    private const val NOTIFICATION_ID = 1001
  }
}

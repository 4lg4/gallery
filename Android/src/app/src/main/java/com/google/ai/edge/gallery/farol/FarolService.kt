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

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * FAROL — LAN LLM inference server foreground service.
 *
 * TODO: Task 2 — embed Ktor CIO server, create notification channel, bind engine.
 */
class FarolService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // TODO: Task 2 — start Ktor server and post foreground notification.
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // TODO: Task 2 — stop Ktor server.
    }
}

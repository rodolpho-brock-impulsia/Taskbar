/* Copyright 2026 Rodolpho Brock
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

package com.farmerbb.taskbar.farm.shell;

import java.io.IOException;

/**
 * The privileged session ended without answering: root was denied, or no root is
 * available at all.
 *
 * This is deliberately distinct from a command that ran and failed. A non-zero exit code
 * says the command did not work; it says nothing about whether we hold privilege, and it
 * must not make the caller give up on this shell.
 */
public class PrivilegedAccessDeniedException extends IOException {

    public PrivilegedAccessDeniedException(String message) {
        super(message);
    }
}

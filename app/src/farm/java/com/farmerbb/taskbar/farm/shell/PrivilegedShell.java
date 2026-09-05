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
 * Runs one command line with privilege and returns one result.
 *
 * Implementations block, so callers run them off the main thread. Keeping them blocking
 * is what lets every one of them be tested on the JVM, without a device.
 */
public interface PrivilegedShell {

    /**
     * @throws PrivilegedAccessDeniedException if privilege itself is unavailable
     * @throws IOException if the command could not be delivered or its answer read
     */
    CommandResult run(String command) throws IOException;

    /** Releases whatever the session holds. Safe to call more than once. */
    void close();

    /** Allows a remembered denial to be tried again. */
    void reset();
}

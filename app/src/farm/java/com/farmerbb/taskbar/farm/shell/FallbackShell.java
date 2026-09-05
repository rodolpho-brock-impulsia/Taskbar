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
 * Tries the primary shell and, once privilege is denied there, uses the secondary.
 *
 * The denial is remembered. Without that, every command reopened a `su` and the superuser
 * manager showed a toast per attempt — in a secondary profile, where direct `su` is
 * denied, one screen load was worth several toasts.
 *
 * A command that merely failed does not count as a denial and does not switch shells.
 */
public class FallbackShell implements PrivilegedShell {

    private final PrivilegedShell primary;
    private final PrivilegedShell secondary;

    private volatile boolean primaryDenied;

    public FallbackShell(PrivilegedShell primary, PrivilegedShell secondary) {
        this.primary = primary;
        this.secondary = secondary;
    }

    @Override
    public CommandResult run(String command) throws IOException {
        if(!primaryDenied) {
            try {
                return primary.run(command);
            } catch (PrivilegedAccessDeniedException denied) {
                primaryDenied = true;
            }
        }

        try {
            return secondary.run(command);
        } catch (PrivilegedAccessDeniedException denied) {
            // Only a denial from the secondary is relabelled. An IO error or a failed
            // command travels up as it is.
            throw new PrivilegedAccessDeniedException("Direct root unavailable; local ADB: "
                    + (denied.getMessage() != null ? denied.getMessage() : "unavailable"));
        }
    }

    @Override
    public void close() {
        primary.close();
        secondary.close();
    }

    /** An explicit refresh by the user is the moment to reconsider the primary shell. */
    @Override
    public void reset() {
        primaryDenied = false;
        primary.reset();
        secondary.reset();
    }

    /** Visible for tests and diagnostics: whether the primary has been given up on. */
    public boolean isPrimaryDenied() {
        return primaryDenied;
    }
}

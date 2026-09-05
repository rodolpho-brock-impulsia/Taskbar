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

import org.junit.Test;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

/**
 * Runs against a real `sh` on the build machine rather than the device's `su`. What is
 * under test is how the session frames a command, and that framing is the same for both.
 *
 * Each case here is a real hang that was found the hard way, not a hypothetical.
 */
public class SuSessionShellTest {

    private SuSessionShell localShell() {
        return new SuSessionShell(() -> new ProcessBuilder("sh").redirectErrorStream(true).start());
    }

    @Test
    public void outputAndExitCodeComeBack() throws IOException {
        SuSessionShell shell = localShell();
        try {
            CommandResult result = shell.run("echo hi; exit 0");
            assertThat(result.getExitCode()).isEqualTo(0);
            assertThat(result.getOutput()).isEqualTo("hi");
            assertThat(result.isSuccess()).isTrue();
        } finally {
            shell.close();
        }
    }

    @Test(timeout = 15000)
    public void aCommandThatExitsDoesNotTakeTheSessionWithIt() throws IOException {
        SuSessionShell shell = localShell();
        try {
            assertThat(shell.run("exit 3").getExitCode()).isEqualTo(3);
            // The session lasts the whole process. Had that exit ended it, this command
            // would open another one, asking for root again on the device.
            assertThat(shell.run("echo still alive").getOutput()).isEqualTo("still alive");
        } finally {
            shell.close();
        }
    }

    @Test(timeout = 15000)
    public void aCommandThatReadsStdinDoesNotSwallowTheEndMarker() throws IOException {
        SuSessionShell shell = localShell();
        try {
            // `cat` without a stdin of its own would read the pipe the commands arrive on
            // and swallow the marker, and the read of the answer would never finish.
            shell.run("cat");
            assertThat(shell.run("echo still alive").getOutput()).isEqualTo("still alive");
        } finally {
            shell.close();
        }
    }

    @Test(timeout = 15000)
    public void multiLineOutputKeepsItsLineBreaks() throws IOException {
        SuSessionShell shell = localShell();
        try {
            assertThat(shell.run("printf 'a\\nb\\n'").getOutput()).isEqualTo("a\nb");
        } finally {
            shell.close();
        }
    }

    @Test
    public void aProcessThatCannotStartReadsAsDenial() {
        SuSessionShell shell = new SuSessionShell(() -> {
            throw new IOException("su: not found");
        });

        try {
            shell.run("id");
            fail("Expected the missing su binary to be reported as a denial");
        } catch (IOException error) {
            assertThat(error).isInstanceOf(PrivilegedAccessDeniedException.class);
            assertThat(error).hasMessageThat().contains("su: not found");
        } finally {
            shell.close();
        }
    }

    @Test(timeout = 15000)
    public void aSessionThatNeverAnswersReadsAsDenial() {
        // `true` exits at once, so the session closes its output without ever writing the
        // marker. That is exactly what a refused root grant looks like from here.
        SuSessionShell shell = new SuSessionShell(
                () -> new ProcessBuilder("true").redirectErrorStream(true).start());

        try {
            shell.run("id");
            fail("Expected a session that closes without answering to be reported as a denial");
        } catch (IOException error) {
            assertThat(error).isInstanceOf(PrivilegedAccessDeniedException.class);
        } finally {
            shell.close();
        }
    }
}

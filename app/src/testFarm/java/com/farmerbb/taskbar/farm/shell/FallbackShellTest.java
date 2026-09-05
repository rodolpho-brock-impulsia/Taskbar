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
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.fail;

/**
 * The decision this class makes — give up on direct root, and remember it — is the reason
 * it exists apart from the shells themselves: it can be tested here, on the JVM, without
 * switching profiles on a device.
 */
public class FallbackShellTest {

    /** Records what it was asked to run and answers however the test says. */
    private static final class RecordingShell implements PrivilegedShell {

        final List<String> commands = new ArrayList<>();
        final IOException failure;
        final CommandResult reply;
        int resets;
        int closes;

        RecordingShell() {
            this(null, null);
        }

        RecordingShell(IOException failure) {
            this(failure, null);
        }

        RecordingShell(CommandResult reply) {
            this(null, reply);
        }

        private RecordingShell(IOException failure, CommandResult reply) {
            this.failure = failure;
            this.reply = reply;
        }

        @Override
        public CommandResult run(String command) throws IOException {
            commands.add(command);
            if(failure != null) throw failure;
            return reply != null ? reply : new CommandResult(0, "ran " + command);
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public void reset() {
            resets++;
        }
    }

    @Test
    public void thePrimaryIsUsedWhileItWorks() throws IOException {
        RecordingShell primary = new RecordingShell();
        RecordingShell secondary = new RecordingShell();
        FallbackShell shell = new FallbackShell(primary, secondary);

        assertThat(shell.run("id").getOutput()).isEqualTo("ran id");
        assertThat(secondary.commands).isEmpty();
        assertThat(shell.isPrimaryDenied()).isFalse();
    }

    @Test
    public void aDeniedPrimaryIsAbandonedAndNotRetried() throws IOException {
        RecordingShell primary = new RecordingShell(new PrivilegedAccessDeniedException("denied"));
        RecordingShell secondary = new RecordingShell();
        FallbackShell shell = new FallbackShell(primary, secondary);

        shell.run("first");
        shell.run("second");

        // The point of remembering: the second command must not reopen a `su`, because
        // each attempt costs the user a toast from the superuser manager.
        assertThat(primary.commands).containsExactly("first");
        assertThat(secondary.commands).containsExactly("first", "second").inOrder();
        assertThat(shell.isPrimaryDenied()).isTrue();
    }

    @Test
    public void aFailedCommandIsNotADenial() throws IOException {
        RecordingShell primary = new RecordingShell(new CommandResult(1, "no such user"));
        RecordingShell secondary = new RecordingShell();
        FallbackShell shell = new FallbackShell(primary, secondary);

        CommandResult result = shell.run("am switch-user 99999");

        assertThat(result.getExitCode()).isEqualTo(1);
        assertThat(shell.isPrimaryDenied()).isFalse();
        assertThat(secondary.commands).isEmpty();
    }

    @Test
    public void anIoErrorFromThePrimaryIsNotADenialEither() {
        RecordingShell primary = new RecordingShell(new IOException("broken pipe"));
        RecordingShell secondary = new RecordingShell();
        FallbackShell shell = new FallbackShell(primary, secondary);

        try {
            shell.run("id");
            fail("Expected the IO error to travel up rather than fall back");
        } catch (IOException error) {
            assertThat(error).isNotInstanceOf(PrivilegedAccessDeniedException.class);
            assertThat(error).hasMessageThat().isEqualTo("broken pipe");
        }

        assertThat(secondary.commands).isEmpty();
        assertThat(shell.isPrimaryDenied()).isFalse();
    }

    @Test
    public void bothDeniedReportsThatNeitherPathIsAvailable() {
        RecordingShell primary = new RecordingShell(new PrivilegedAccessDeniedException("no su"));
        RecordingShell secondary = new RecordingShell(new PrivilegedAccessDeniedException("no adb"));
        FallbackShell shell = new FallbackShell(primary, secondary);

        try {
            shell.run("id");
            fail("Expected a denial when neither shell is available");
        } catch (IOException error) {
            assertThat(error).isInstanceOf(PrivilegedAccessDeniedException.class);
            assertThat(error).hasMessageThat().contains("no adb");
        }
    }

    @Test
    public void resetGivesThePrimaryAnotherChance() throws IOException {
        RecordingShell primary = new RecordingShell(new PrivilegedAccessDeniedException("denied"));
        RecordingShell secondary = new RecordingShell();
        FallbackShell shell = new FallbackShell(primary, secondary);

        shell.run("first");
        assertThat(shell.isPrimaryDenied()).isTrue();

        shell.reset();

        assertThat(shell.isPrimaryDenied()).isFalse();
        assertThat(primary.resets).isEqualTo(1);
        assertThat(secondary.resets).isEqualTo(1);

        shell.run("second");
        assertThat(primary.commands).containsExactly("first", "second").inOrder();
    }

    @Test
    public void closingClosesBoth() {
        RecordingShell primary = new RecordingShell();
        RecordingShell secondary = new RecordingShell();

        new FallbackShell(primary, secondary).close();

        assertThat(primary.closes).isEqualTo(1);
        assertThat(secondary.closes).isEqualTo(1);
    }
}

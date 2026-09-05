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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Keeps a single `su` session alive for the life of the process.
 *
 * Opening one `su` per command makes the superuser manager show a toast on every grant.
 * Reusing one session asks for permission once.
 */
public class SuSessionShell implements PrivilegedShell {

    /** Lets tests supply a fake process instead of a real `su`. */
    public interface ProcessFactory {
        Process create() throws IOException;
    }

    private final ProcessFactory processFactory;
    private final Object lock = new Object();

    private Session session;

    public SuSessionShell() {
        this(() -> new ProcessBuilder("su").redirectErrorStream(true).start());
    }

    public SuSessionShell(ProcessFactory processFactory) {
        this.processFactory = processFactory;
    }

    @Override
    public CommandResult run(String command) throws IOException {
        synchronized(lock) {
            // A session that died mid-flight is worth reopening once. That is not a denial.
            if(session != null && session.isAlive()) {
                try {
                    return session.run(command);
                } catch (IOException error) {
                    // Covers both a denial and a broken pipe: the process can die between
                    // isAlive() and the write. Without clearing it here, every later
                    // command would reuse the dead session.
                    session.close();
                    session = null;
                }
            }

            Session fresh;
            try {
                fresh = new Session(processFactory.create());
            } catch (IOException error) {
                throw new PrivilegedAccessDeniedException(error.getMessage() != null
                        ? error.getMessage()
                        : "Could not open a root session.");
            }
            session = fresh;

            try {
                return fresh.run(command);
            } catch (PrivilegedAccessDeniedException denied) {
                // A session opened just now that did not answer is a denial.
                fresh.close();
                session = null;
                throw denied;
            }
        }
    }

    @Override
    public void close() {
        synchronized(lock) {
            if(session != null) session.close();
            session = null;
        }
    }

    @Override
    public void reset() {}

    private static final class Session {

        private final Process process;
        private final BufferedWriter stdin;
        private final BufferedReader stdout;

        /** Per-session nonce, so command output is never mistaken for the end marker. */
        private final String endMarker = "__SU_END_" + UUID.randomUUID().toString().replace("-", "") + "__";

        Session(Process process) {
            this.process = process;
            this.stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            this.stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        }

        boolean isAlive() {
            return process.isAlive();
        }

        /**
         * The command runs in a subshell with stdin closed, rather than loose in the session.
         *
         * One session serves the whole process and every command arrives through this same
         * stdin. Without the subshell, an `exit` inside the command ends the session before
         * the marker is printed. Without `< /dev/null`, anything in the command's tree that
         * reads stdin — a nested `su`, say — swallows that marker, and the read below waits
         * forever for a line nobody will write.
         */
        CommandResult run(String command) throws IOException {
            stdin.write("( ");
            stdin.write(command);
            stdin.write("\n) < /dev/null\n");
            stdin.write("printf '%s%s\\n' '" + endMarker + "' \"$?\"\n");
            stdin.flush();

            StringBuilder output = new StringBuilder();
            while(true) {
                String line = stdout.readLine();
                if(line == null) {
                    throw new PrivilegedAccessDeniedException("Root permission denied or unavailable.");
                }

                if(line.startsWith(endMarker)) {
                    String code = line.substring(endMarker.length()).trim();
                    try {
                        return new CommandResult(Integer.parseInt(code), trimTrailing(output));
                    } catch (NumberFormatException e) {
                        throw new IOException("Invalid exit code from the root session: " + code);
                    }
                }

                output.append(line).append('\n');
            }
        }

        void close() {
            try {
                stdin.write("exit\n");
                stdin.flush();
            } catch (IOException ignored) {}

            process.destroy();
        }

        private static String trimTrailing(StringBuilder builder) {
            int end = builder.length();
            while(end > 0 && Character.isWhitespace(builder.charAt(end - 1))) end--;
            return builder.substring(0, end);
        }
    }
}

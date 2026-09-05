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

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal ADB client that talks to the device's own `adbd` over loopback. This is the
 * path used when direct `su` is denied, which in a secondary profile is every time — so
 * without it the app only works for the admin.
 *
 * It keeps a single `shell:su` stream open and writes commands into it, rather than
 * opening `shell:su -c <cmd>` per call. One connection per command made the superuser
 * manager grant, and announce, access for each one: a single screen load fires nine
 * commands, so nine notices. With the session alive the grant happens once.
 *
 * Because the command travels through the stream's stdin instead of argv, this path also
 * has no command length limit.
 *
 * No ADB library is used. The project asks contributors not to add third-party
 * dependencies, so the handful of protocol messages needed here are written by hand.
 */
public class AdbLoopbackShell implements PrivilegedShell {

    private static final String LOOPBACK = "127.0.0.1";
    private static final int DEFAULT_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int LOCAL_ID = 1;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int HEADER_SIZE = 24;

    private static final int CNXN = adbCommand("CNXN");
    private static final int AUTH = adbCommand("AUTH");
    private static final int OPEN = adbCommand("OPEN");
    private static final int OKAY = adbCommand("OKAY");
    private static final int WRTE = adbCommand("WRTE");
    private static final int CLSE = adbCommand("CLSE");

    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    /** Injectable so the cap can be exercised without producing 16 MB of output. */
    private final int maxResponseBytes;

    private final Object lock = new Object();

    private Session session;

    public AdbLoopbackShell() {
        this(DEFAULT_PORT, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS, MAX_RESPONSE_BYTES);
    }

    public AdbLoopbackShell(int port, int connectTimeoutMs, int readTimeoutMs, int maxResponseBytes) {
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxResponseBytes = maxResponseBytes;
    }

    @Override
    public CommandResult run(String command) throws IOException {
        synchronized(lock) {
            // A session that dropped mid-flight is worth reopening once. Not a denial.
            if(session != null) {
                try {
                    return session.run(command);
                } catch (IOException error) {
                    session.close();
                    session = null;
                }
            }

            Session fresh = new Session(openStream(), maxResponseBytes);
            fresh.initialize();
            session = fresh;

            try {
                return fresh.run(command);
            } catch (IOException error) {
                fresh.close();
                session = null;
                throw error;
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

    /** Connects, performs the handshake, and opens the `su` stream. */
    private Stream openStream() throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(LOOPBACK, port), connectTimeoutMs);
            socket.setSoTimeout(readTimeoutMs);

            DataInputStream input = new DataInputStream(socket.getInputStream());
            OutputStream output = socket.getOutputStream();

            writeMessage(output, CNXN, VERSION, MAX_DATA, nullTerminated("host::"));
            Message connection = readMessage(input);
            if(connection.command == AUTH) {
                throw new PrivilegedAccessDeniedException("Local ADB requires authentication.");
            }
            if(connection.command != CNXN) {
                throw new IOException("Unexpected reply from local ADB while connecting.");
            }

            writeMessage(output, OPEN, LOCAL_ID, 0, nullTerminated("shell:su"));
            int remoteId = 0;
            while(remoteId == 0) {
                Message message = readMessage(input);
                if(message.command == OKAY) {
                    remoteId = message.arg0;
                } else if(message.command == AUTH) {
                    throw new PrivilegedAccessDeniedException("Local ADB asked for authentication.");
                } else if(message.command == CLSE) {
                    throw new PrivilegedAccessDeniedException("Local ADB refused to open a root shell.");
                }
            }

            return new Stream(socket, input, output, remoteId);
        } catch (IOException | RuntimeException error) {
            try {
                socket.close();
            } catch (IOException ignored) {}
            throw error;
        }
    }

    /** ADB service names travel NUL-terminated. */
    private static byte[] nullTerminated(String service) {
        return (service + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private static final class Stream {

        final Socket socket;
        final DataInputStream input;
        final OutputStream output;
        final int remoteId;

        Stream(Socket socket, DataInputStream input, OutputStream output, int remoteId) {
            this.socket = socket;
            this.input = input;
            this.output = output;
            this.remoteId = remoteId;
        }
    }

    private static final class Session {

        private final Stream stream;
        private final int maxResponseBytes;

        /** Per-session nonce, so command output is never mistaken for the end marker. */
        private final String endMarker = "__ADB_END_" + UUID.randomUUID().toString().replace("-", "") + "__";

        /**
         * The marker only counts when the exit code is stuck to it. The shell echoes what
         * it receives, and that echo contains the text of the printf — marker included.
         */
        private final Pattern endPattern = Pattern.compile(Pattern.quote(endMarker) + "(\\d+)");

        /** Leftover from the previous command: adbd delivers output in arbitrary chunks. */
        private final StringBuilder pending = new StringBuilder();

        Session(Stream stream, int maxResponseBytes) {
            this.stream = stream;
            this.maxResponseBytes = maxResponseBytes;
        }

        /**
         * This device's `su` opens an interactive shell: it echoes what it receives and
         * prints a prompt per command. Turning both off leaves the output clean. `stty`
         * may not exist, hence the discarded error.
         */
        void initialize() throws IOException {
            send("PS1=''; stty -echo 2>/dev/null");
            Matcher match = awaitMarker();
            // Drop the echo and the prompt produced before the echo was turned off.
            String leftover = pending.substring(match.end());
            pending.setLength(0);
            pending.append(leftover);
        }

        CommandResult run(String command) throws IOException {
            send(command);
            Matcher match = awaitMarker();

            int exitCode;
            try {
                exitCode = Integer.parseInt(match.group(1));
            } catch (NumberFormatException e) {
                throw new IOException("Invalid exit code from local ADB: " + match.group(1));
            }

            String output = trimTrailing(pending.substring(0, match.start()));
            String leftover = pending.substring(match.end());
            pending.setLength(0);
            pending.append(leftover);
            return new CommandResult(exitCode, output);
        }

        private void send(String command) throws IOException {
            String script = command + "\nprintf '%s%s\\n' '" + endMarker + "' \"$?\"\n";
            writeMessage(stream.output, WRTE, LOCAL_ID, stream.remoteId,
                    script.getBytes(StandardCharsets.UTF_8));
        }

        private Matcher awaitMarker() throws IOException {
            while(true) {
                Matcher matcher = endPattern.matcher(pending);
                if(matcher.find()) return matcher;

                Message message = readMessage(stream.input);
                if(message.command == WRTE) {
                    if(pending.length() + message.payload.length > maxResponseBytes) {
                        throw new IOException("Local ADB response exceeded the size limit.");
                    }
                    pending.append(new String(message.payload, StandardCharsets.UTF_8).replace("\r\n", "\n"));
                    writeMessage(stream.output, OKAY, LOCAL_ID, stream.remoteId, new byte[0]);
                } else if(message.command == CLSE) {
                    throw new IOException("The local ADB shell ended the session.");
                } else if(message.command == AUTH) {
                    throw new PrivilegedAccessDeniedException("Local ADB asked for authentication.");
                }
            }
        }

        void close() {
            try {
                writeMessage(stream.output, CLSE, LOCAL_ID, stream.remoteId, new byte[0]);
            } catch (IOException ignored) {}

            try {
                stream.socket.close();
            } catch (IOException ignored) {}
        }

        private static String trimTrailing(String value) {
            int end = value.length();
            while(end > 0 && Character.isWhitespace(value.charAt(end - 1))) end--;
            return value.substring(0, end);
        }
    }

    static final class Message {

        final int command;
        final int arg0;
        final byte[] payload;

        Message(int command, int arg0, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.payload = payload;
        }
    }

    static int adbCommand(String value) {
        return value.charAt(0)
                | (value.charAt(1) << 8)
                | (value.charAt(2) << 16)
                | (value.charAt(3) << 24);
    }

    private static int checksum(byte[] payload) {
        int total = 0;
        for(byte value : payload) total += value & 0xff;
        return total;
    }

    static void writeMessage(OutputStream output, int command, int arg0, int arg1, byte[] payload)
            throws IOException {
        byte[] header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command)
                .putInt(arg0)
                .putInt(arg1)
                .putInt(payload.length)
                .putInt(checksum(payload))
                .putInt(~command)
                .array();

        output.write(header);
        output.write(payload);
        output.flush();
    }

    static Message readMessage(DataInputStream input) throws IOException {
        byte[] headerBytes = new byte[HEADER_SIZE];
        input.readFully(headerBytes);

        ByteBuffer header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN);
        int command = header.getInt();
        int arg0 = header.getInt();
        header.getInt(); // arg1, unused on the messages we read
        int length = header.getInt();
        int expectedChecksum = header.getInt();
        int magic = header.getInt();

        if(magic != ~command || length < 0 || length > MAX_DATA) {
            throw new IOException("Invalid header received from local ADB.");
        }

        byte[] payload = new byte[length];
        input.readFully(payload);

        if(expectedChecksum != 0 && checksum(payload) != expectedChecksum) {
            throw new IOException("Invalid checksum received from local ADB.");
        }

        return new Message(command, arg0, payload);
    }
}

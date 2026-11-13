import org.boxtheta.javapower.Emulator;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.firmware.slof.SlofFirmware;
import org.boxtheta.javapower.papr.CharBackend;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.PciDevice;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

/**
 * Boots an image on javapower's PAPR machine as OpenComputers II's tier 5
 * computer sets it up: the bundled SLOF firmware, RAM in units of 256 MiB,
 * one or more processors, the hard drives as disks on the v-scsi bus in
 * order, and the console on stdio. Like OC2, it runs the first processor in
 * slices of a budget per tick (20 ticks a second), and can hold the console
 * to the baud rate of the computer's serial port. With --oc2 the machine also
 * has what OC2 gives it for the device bus, see below.
 *
 * <pre>
 * java -cp 'lib/*' PowerRun.java --disk power-bsd.img [options]
 *   --disk FILE            a SCSI disk, repeatable, in order (writes go to FILE)
 *   --ram MiB              guest RAM, a multiple of 256 (default 256)
 *   --cpus N               processors (default 1)
 *   --ips N                instructions per second of the first processor,
 *                          OC2's ppc64leCycleBudgetPerSecond (default
 *                          200000000); 0: as fast as it goes
 *   --baud N               hold console output to N baud, as OC2's serial
 *                          port does (OC2: 9600); default: not held
 *   --oc2                  add OC2's device bus, as PowerArchitecture does: the
 *                          high-level API on three more consoles, labelled
 *                          oc2.rpc.0, oc2.blob.0 and oc2.event.0, and the
 *                          mid-level API's registers as a PCI device in slot
 *                          31 (1234:4f43), emulated with two devices: SYSTEM
 *                          and a redstone interface REDSTN, labelled "door".
 *                          The consoles answer the high-level API for that
 *                          redstone interface ("redstone", "door") and a
 *                          device "test"
 *   --serial               add a serial interface card: one more console,
 *                          labelled oc2.serial.0, its cable looped back (what
 *                          the guest sends, it receives); with --oc2 also its
 *                          high-level API device "serial", address 7
 *   --send STRING          text to type once WAITFOR has been seen, repeatable;
 *                          "\n" is a newline; "@redstone SIDE LEVEL" instead
 *                          sets the signal the redstone interface receives,
 *                          "@sleep SEC" holds the script for that long
 *   --wait-for STRING      what to wait for before the next --send (default "# ");
 *                          repeatable, the last one applies to the remaining sends
 *   --timeout SEC          give up after SEC seconds of wall time
 * </pre>
 * Without --send, stdin is forwarded to the console. Exits 0 when the guest
 * powers the machine off, 1 otherwise.
 */
public final class PowerRun {
    private static final int TICKS_PER_SECOND = 20;
    /** How often the host's side is served; OC2 does it once a tick, 50 ms. */
    private static final int HOST_STEP_MILLIS = 10;

    public static void main(final String[] args) throws Exception {
        final List<Path> disks = new ArrayList<>();
        final Deque<String> sends = new ArrayDeque<>();
        final Deque<String> waitFors = new ArrayDeque<>();
        long ram = 256;
        int cpus = 1;
        long ips = 200_000_000L;
        int baud = 0;
        boolean oc2 = false;
        boolean serial = false;
        double timeout = -1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--disk" -> disks.add(Path.of(args[++i]));
                case "--ram" -> ram = Long.parseLong(args[++i]);
                case "--cpus" -> cpus = Integer.parseInt(args[++i]);
                case "--ips" -> ips = Long.parseLong(args[++i]);
                case "--baud" -> baud = Integer.parseInt(args[++i]);
                case "--oc2" -> oc2 = true;
                case "--serial" -> serial = true;
                case "--send" -> sends.add(args[++i].replace("\\n", "\n"));
                case "--wait-for" -> waitFors.add(args[++i]);
                case "--timeout" -> timeout = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        if (disks.isEmpty()) {
            throw new IllegalArgumentException("no --disk");
        }

        final Console console = new Console(baud);
        final MachineConfig config = new MachineConfig()
            .ram(ram << 20)
            .cpus(cpus)
            .firmware(SlofFirmware.image())
            .console(console)
            .logSink(entry -> { });
        for (final Path disk : disks) {
            config.addDisk(disk).persistent(true);
        }
        // As li.cil.oc2.common.vm.arch.PowerArchitecture: the device bus
        // consoles first, so they are vty@71000003 to 5, the mid-level API in
        // a PCI slot of its own, then what the cards add.
        Mlapi mlapi = null;
        Hlapi hlapi = null;
        if (oc2) {
            mlapi = new Mlapi();
            hlapi = new Hlapi(mlapi.redstone);
            config.addConsole(hlapi.rpc).name("oc2.rpc.0");
            config.addConsole(hlapi.blob).name("oc2.blob.0");
            config.addConsole(hlapi.event).name("oc2.event.0");
            config.addPciDevice(Mlapi.SLOT, mlapi.device);
        }
        Port serialPort = null;
        if (serial) {
            serialPort = new Port();
            final int unit = config.addConsole(serialPort).name("oc2.serial.0").unitAddress();
            if (hlapi != null) {
                hlapi.serialBase = Integer.toUnsignedLong(unit);
            }
        }

        final boolean scripted = !sends.isEmpty();
        if (!scripted) {
            final Thread stdin = new Thread(() -> forwardStdin(console), "stdin");
            stdin.setDaemon(true);
            stdin.start();
        }

        final long start = System.nanoTime();
        Machine.ShutdownReason reason = Machine.ShutdownReason.NONE;
        try (Emulator emulator = new Emulator(config)) {
            // The host's side: the script, the device bus and the card's
            // cable. On a thread of its own, as OC2 serves them whatever the
            // machine does: an idle guest takes long to finish a slice.
            final Mlapi theMlapi = mlapi;
            final Hlapi theHlapi = hlapi;
            final Port theSerialPort = serialPort;
            final double theTimeout = timeout;
            final AtomicBoolean timedOut = new AtomicBoolean();
            final long[] notBefore = {0};
            final Thread host = new Thread(() -> {
                try {
                    for (; ; ) {
                        if (theHlapi != null) {
                            theHlapi.pump();
                        }
                        if (theSerialPort != null) {
                            final byte[] sent = theSerialPort.take();
                            theSerialPort.send(sent, sent.length);
                        }

                        final String waitFor = waitFors.isEmpty() ? "# " : waitFors.peekFirst();
                        if (scripted && !sends.isEmpty() && System.nanoTime() >= notBefore[0]
                            && console.takeIfSeen(waitFor)) {
                            final String send = sends.removeFirst();
                            if (send.startsWith("@sleep ")) {
                                notBefore[0] = System.nanoTime()
                                    + (long) (Double.parseDouble(send.substring(7).trim()) * 1e9);
                            } else if (send.startsWith("@redstone ")) {
                                if (theMlapi == null) {
                                    throw new IllegalArgumentException("@redstone needs --oc2");
                                }
                                final String[] parts = send.trim().split("\\s+");
                                theMlapi.receive(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                            } else {
                                console.type(send);
                            }
                            if (waitFors.size() > 1) {
                                waitFors.removeFirst();
                            }
                        }

                        if (theTimeout >= 0 && System.nanoTime() - start > (long) (theTimeout * 1e9)) {
                            timedOut.set(true);
                            emulator.stop();
                            return;
                        }
                        Thread.sleep(HOST_STEP_MILLIS);
                    }
                } catch (final InterruptedException ignored) {
                } catch (final RuntimeException e) {
                    e.printStackTrace();
                    timedOut.set(true);
                    emulator.stop();
                }
            }, "host");
            host.setDaemon(true);
            host.start();

            final long slice = ips > 0 ? ips / TICKS_PER_SECOND : 50_000_000L;
            long tick = System.nanoTime();
            for (; ; ) {
                reason = emulator.run(slice);
                if (reason == Machine.ShutdownReason.POWER_OFF ||
                    reason == Machine.ShutdownReason.PANIC ||
                    reason == Machine.ShutdownReason.FATAL) {
                    break;
                }
                if (timedOut.get()) {
                    System.out.printf("%n[PowerRun: timeout, pc=%016x]%n", emulator.cpu().pc);
                    break;
                }
                if (ips > 0) {
                    tick += 1_000_000_000L / TICKS_PER_SECOND;
                    final long wait = tick - System.nanoTime();
                    if (wait > 0) {
                        Thread.sleep(wait / 1_000_000, (int) (wait % 1_000_000));
                    } else {
                        tick = System.nanoTime();
                    }
                }
            }
            host.interrupt();
        }
        System.out.flush();
        System.out.printf("%n[PowerRun: %s after %.1f s]%n", reason, (System.nanoTime() - start) / 1e9);
        System.exit(reason == Machine.ShutdownReason.POWER_OFF ? 0 : 1);
    }

    private static void forwardStdin(final Console console) {
        try {
            final InputStream in = System.in;
            final byte[] buffer = new byte[256];
            int n;
            while ((n = in.read(buffer)) > 0) {
                console.receive(buffer, n);
            }
        } catch (final Exception ignored) {
        }
    }

    /**
     * The console: guest output to stdout (and kept, to look for what the
     * script waits for), input from stdin or the script.
     */
    static final class Console implements CharBackend {
        private final int baud;
        private final ByteArrayOutputStream seen = new ByteArrayOutputStream();
        private volatile InputSink sink;

        Console(final int baud) {
            this.baud = baud;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) {
            System.out.write(b, off, len);
            System.out.flush();
            synchronized (seen) {
                seen.write(b, off, len);
            }
            if (baud > 0) {
                // 10 bits a byte on the line: start, 8 data, stop.
                final long nanos = len * 10L * 1_000_000_000L / baud;
                try {
                    Thread.sleep(nanos / 1_000_000, (int) (nanos % 1_000_000));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void start(final InputSink sink) {
            this.sink = sink;
        }

        /**
         * Whether the output since the last match contains the text; if so,
         * forgets the output up to it.
         */
        boolean takeIfSeen(final String text) {
            synchronized (seen) {
                final String output = seen.toString(StandardCharsets.ISO_8859_1);
                final int at = output.indexOf(text);
                if (at < 0) {
                    return false;
                }
                final byte[] rest = output.substring(at + text.length()).getBytes(StandardCharsets.ISO_8859_1);
                seen.reset();
                seen.write(rest, 0, rest.length);
                return true;
            }
        }

        void type(final String text) {
            final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            receive(bytes, bytes.length);
        }

        void receive(final byte[] bytes, final int length) {
            final InputSink current = sink;
            if (current != null) {
                current.receive(bytes, 0, length);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // A console of the machine other than the first, the host's end: what
    // the guest wrote waits here until taken, what is sent goes to the guest.

    static final class Port implements CharBackend {
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private volatile InputSink sink;

        @Override
        public void write(final byte[] b, final int off, final int len) {
            synchronized (written) {
                written.write(b, off, len);
            }
        }

        @Override
        public void start(final InputSink sink) {
            this.sink = sink;
        }

        /** What the guest wrote since the last call. */
        byte[] take() {
            synchronized (written) {
                final byte[] bytes = written.toByteArray();
                written.reset();
                return bytes;
            }
        }

        void send(final byte[] bytes, final int length) {
            final InputSink current = sink;
            if (current != null && length > 0) {
                current.receive(bytes, 0, length);
            }
        }
    }

    // ------------------------------------------------------------------ //
    // OC2's mid-level API, see its docs/mlapi.md: the register page, with its
    // enumeration window, the event queue and two devices, as BAR 0 of the
    // PCI device li.cil.oc2.common.vm.ppc.MlapiPort makes of it. Calls
    // complete at once, OC2 runs some in the next tick. The guest's
    // processors reach the registers on their own threads, the script and
    // the high-level API on the host's, so everything here is synchronized.

    static final class Mlapi {
        static final int SLOT = 31;
        private static final int VENDOR_ID = 0x1234, DEVICE_ID = 0x4f43, CLASS_CODE = 0xff0000;
        private static final int BAR_SIZE = 64 * 1024, PAGE_SIZE = 0x1000;

        private static final int OCSEL = 0, OCFUN = 1, OCDAT = 2, OCSTA = 3, OCEVC = 4, OCEVD = 5;
        private static final int BVER = 0x10, BCNT = 0x11, BSEL = 0x12, BCLS = 0x13, BATT = 0x14, BPRT = 0x15,
            BINT = 0x16, BNAM = 0x17, BID = 0x18, WINDOW_END = 0x20;
        private static final int AVAILABLE = 0x02, ERROR = 0x80;
        private static final int ENOD = 0x01, ENOF = 0x02, EARG = 0x05;
        private static final int EVQ = 0x01, EVI = 0x02, EVO = 0x40, EVP = 0x80;
        private static final int QUEUE_CAPACITY = 16, MAX_DATA = 256;

        /** An entry of the enumeration window: class, attributes, port, names and id. */
        private record Entry(int cls, int attributes, int port, List<String> names, String id) {
            byte[] nameBytes() {
                final ByteArrayOutputStream out = new ByteArrayOutputStream();
                for (final String name : names) {
                    out.writeBytes(name.getBytes(StandardCharsets.US_ASCII));
                    out.write(0);
                }
                return out.toByteArray();
            }
        }

        // The window itself, then the mid-level API devices, all at the
        // start of the page.
        private static final List<Entry> ENTRIES = List.of(
            new Entry(0x01, 0, BVER, List.of("SEDBUS"), ""),
            new Entry(0x80, 0, 0, List.of("SYSTEM"), "00000000-0000-0000-0000-000000000000"),
            new Entry(0x80, 1, 0, List.of("REDSTN", "door"), "6f1d7c2a-0d8e-4a43-9b61-5c3e2f7a9d10"));

        final Device device = new Device();
        final Redstone redstone = new Redstone();

        private int selected;
        private int function = -1;
        private final ByteArrayOutputStream arguments = new ByteArrayOutputStream();
        private final Deque<Integer> results = new ArrayDeque<>();
        private int error;

        private int control;
        private final Deque<int[]> events = new ArrayDeque<>();
        private int cursor;

        private int entry;
        private int nameCursor, idCursor;

        /** The signal the redstone interface receives changed. */
        synchronized void receive(final int side, final int level) {
            redstone.receive(side, level);
        }

        final class Device extends PciDevice {
            private Device() {
                super("oc2-mlapi", Mlapi.VENDOR_ID, Mlapi.DEVICE_ID, 1, CLASS_CODE);
                setSubsystem(Mlapi.VENDOR_ID, Mlapi.DEVICE_ID);
                setInterruptPin(1);
                addMemoryBar(0, BAR_SIZE, false, false, new Registers());
            }
        }

        private final class Registers implements MmioHandler {
            // Only single-byte accesses reach the registers; wider ones, and
            // accesses where there is no register, read all ones and write
            // nothing.
            @Override
            public long read(final long offset, final int size) {
                if (size != 1 || offset >= PAGE_SIZE) {
                    return size >= Long.BYTES ? -1L : (1L << (size * Byte.SIZE)) - 1;
                }
                synchronized (Mlapi.this) {
                    return load((int) offset) & 0xFF;
                }
            }

            @Override
            public void write(final long offset, final int size, final long value) {
                if (size != 1 || offset >= PAGE_SIZE) {
                    return;
                }
                synchronized (Mlapi.this) {
                    store((int) offset, (int) (value & 0xFF));
                }
            }
        }

        private Entry entry() {
            return entry < ENTRIES.size() ? ENTRIES.get(entry) : null;
        }

        private int load(final int offset) {
            final Entry e = entry();
            return switch (offset) {
                case OCSEL -> selected;
                case OCDAT -> error != 0 ? error : results.isEmpty() ? 0 : results.removeFirst();
                case OCSTA -> (error != 0 ? ERROR : 0) | (results.isEmpty() ? 0 : AVAILABLE);
                case OCEVC -> control | (events.isEmpty() ? 0 : EVP);
                case OCEVD -> readEvent();
                case BVER -> 1;
                case BCNT -> {
                    nameCursor = idCursor = 0;
                    yield ENTRIES.size();
                }
                case BSEL -> entry;
                case BCLS -> e != null ? e.cls() : 0;
                case BATT -> e != null ? e.attributes() : 0;
                case BPRT -> e != null ? e.port() : 0xFF;
                case BINT -> 0xFF; // the interrupt is the PCI device's
                case BNAM -> {
                    final byte[] names = e != null ? e.nameBytes() : new byte[0];
                    yield nameCursor < names.length ? names[nameCursor++] : 0;
                }
                case BID -> {
                    final String id = e != null ? e.id() : "";
                    yield idCursor < id.length() ? id.charAt(idCursor++) : 0;
                }
                default -> offset < WINDOW_END ? 0 : 0xFF;
            };
        }

        private void store(final int offset, final int value) {
            switch (offset) {
                case OCSEL -> {
                    selected = value;
                    begin(-1);
                }
                case OCFUN -> begin(value);
                case OCDAT -> {
                    if (function < 0) {
                        error = EARG;
                    } else if (arguments.size() == MAX_DATA) {
                        error = EARG;
                        function = -1;
                    } else {
                        arguments.write(value);
                    }
                }
                case OCSTA -> {
                    if (value == 0) {
                        begin(-1);
                    } else {
                        execute();
                    }
                }
                case OCEVC -> {
                    control = value & (EVQ | EVI);
                    if ((control & EVQ) == 0) {
                        events.clear();
                        cursor = 0;
                    }
                    updateInterrupt();
                }
                case BSEL -> {
                    entry = value;
                    nameCursor = idCursor = 0;
                }
                case BNAM -> nameCursor = 0;
                case BID -> idCursor = 0;
                default -> {
                }
            }
        }

        private void begin(final int function) {
            this.function = function;
            arguments.reset();
            results.clear();
            error = 0;
        }

        private void execute() {
            final byte[] args = arguments.toByteArray();
            final int called = function;
            begin(-1);
            if (called < 0) {
                error = ENOF;
                return;
            }
            final int[] result = switch (selected) {
                case 0 -> system(called, args);
                case 1 -> redstone.call(called, args);
                default -> new int[]{-ENOD};
            };
            if (result.length == 1 && result[0] < 0) {
                error = -result[0];
            } else {
                for (final int b : result) {
                    results.addLast(b & 0xFF);
                }
            }
        }

        /** SYSTEM: item names and ids, for one item. */
        private static int[] system(final int function, final byte[] args) {
            final String name = "minecraft:redstone";
            return switch (function) {
                case 1, 3, 5 -> {
                    if (args.length < 2) yield new int[]{-EARG};
                    final int id = (args[0] & 0xFF) | (args[1] & 0xFF) << 8;
                    if (function != 1 || id != 1) yield new int[]{-EARG};
                    yield name.chars().toArray();
                }
                case 2, 4, 6 -> {
                    String text = new String(args, StandardCharsets.US_ASCII).replace("\0", "");
                    if (!text.contains(":")) text = "minecraft:" + text;
                    if (function != 2 || !text.equals(name)) yield new int[]{-EARG};
                    yield new int[]{1, 0};
                }
                default -> new int[]{-ENOF};
            };
        }

        /** REDSTN: six sides; the device's own output counts towards its input. */
        final class Redstone {
            private final int[] received = new int[6];
            private final int[] output = new int[6];
            private final int[] input = new int[6];

            int[] call(final int function, final byte[] args) {
                // Under Mlapi.this, as every caller holds it.
                if (function < 1 || function > 3) return new int[]{-ENOF};
                if (args.length < (function == 3 ? 2 : 1) || (args[0] & 0xFF) > 5) return new int[]{-EARG};
                final int side = args[0] & 0xFF;
                return switch (function) {
                    case 1 -> new int[]{input[side]};
                    case 2 -> new int[]{output[side]};
                    default -> {
                        setOutput(side, args[1] & 0xFF);
                        yield new int[0];
                    }
                };
            }

            void receive(final int side, final int level) {
                received[side] = level;
                update(side);
            }

            java.util.function.BiConsumer<Integer, Integer> listener = (side, level) -> {};

            int input(final int side) {
                synchronized (Mlapi.this) {
                    return input[side];
                }
            }

            int output(final int side) {
                synchronized (Mlapi.this) {
                    return output[side];
                }
            }

            void setOutput(final int side, final int level) {
                synchronized (Mlapi.this) {
                    output[side] = Math.max(0, Math.min(level, 15));
                    update(side);
                }
            }

            private void update(final int side) {
                final int level = Math.max(received[side], output[side]);
                if (level != input[side]) {
                    input[side] = level;
                    queueEvent(1, 1, side | level << 8);
                    listener.accept(side, level);
                }
            }
        }

        private void queueEvent(final int device, final int code, final int value) {
            if ((control & EVQ) == 0) {
                return;
            }
            if (events.size() == QUEUE_CAPACITY) {
                control |= EVO;
                return;
            }
            events.addLast(new int[]{device, code, value & 0xFF, value >>> 8 & 0xFF});
            updateInterrupt();
        }

        private int readEvent() {
            if (events.isEmpty()) {
                return 0xFF;
            }
            final int value = events.peekFirst()[cursor];
            if (++cursor == 4) {
                events.removeFirst();
                cursor = 0;
                updateInterrupt();
            }
            return value;
        }

        private void updateInterrupt() {
            device.setIrq((control & EVI) != 0 && !events.isEmpty());
        }
    }

    // ------------------------------------------------------------------ //
    // OC2's high-level API, the host side, see its docs/hlapi.md: NUL framed
    // JSON requests on the console labelled oc2.rpc.0, payloads on
    // oc2.blob.0, events on oc2.event.0. Replies are sent at the host's next
    // step, as OC2 sends most a tick later.

    static final class Hlapi {
        private static final String REDSTONE_ID = "6f1d7c2a-0d8e-4a43-9b61-5c3e2f7a9d10";
        private static final String TEST_ID = "0b7e4d1c-2f3a-4e5b-8c6d-7e8f9a0b1c2d";
        private static final String SERIAL_ID = "3c9a5e2b-7d1f-4b6a-a8e0-1f2d3c4b5a69";
        private static final List<String> SIDES = List.of("down", "up", "back", "front", "left", "right");
        private static final Map<String, Integer> ABSOLUTE = Map.of("north", 3, "south", 2, "west", 4, "east", 5);
        private static final int MAX_REQUEST = 4096;
        private static final String OVERSIZED = "\u0000oversized";

        final Port rpc = new Port(), blob = new Port(), event = new Port();
        private final Mlapi.Redstone redstone;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final ByteArrayOutputStream blobIn = new ByteArrayOutputStream();
        private final Deque<String> requests = new ArrayDeque<>();
        private final ByteArrayOutputStream rpcOut = new ByteArrayOutputStream(),
            blobOut = new ByteArrayOutputStream(), eventOut = new ByteArrayOutputStream();
        private boolean oversized;
        /** The unit address of the serial interface card's console, 0 without one. */
        long serialBase;

        Hlapi(final Mlapi.Redstone redstone) {
            this.redstone = redstone;
            redstone.listener = (side, level) -> {
                final Map<String, Object> data = new LinkedHashMap<>();
                data.put("side", SIDES.get(side));
                data.put("value", (long) level);
                final Map<String, Object> message = message("redstoneChanged", 0, data);
                message.put("deviceId", REDSTONE_ID);
                queue(eventOut, frame(message));
            };
        }

        /** Called regularly, on the host's thread. */
        void pump() {
            for (final byte value : rpc.take()) {
                final int b = value & 0xFF;
                if (b == 0) {
                    if (oversized) {
                        requests.addLast(OVERSIZED);
                    } else if (request.size() > 0) {
                        requests.addLast(request.toString(StandardCharsets.UTF_8));
                    }
                    request.reset();
                    oversized = false;
                } else if (request.size() < MAX_REQUEST) {
                    request.write(b);
                } else {
                    oversized = true;
                }
            }
            final byte[] payload = blob.take();
            blobIn.write(payload, 0, payload.length);
            event.take(); // what the guest writes there is discarded
            while (!requests.isEmpty() && handle(requests.peekFirst())) {
                requests.removeFirst();
            }
            drain(rpc, rpcOut);
            drain(blob, blobOut);
            drain(event, eventOut);
        }

        private static void drain(final Port port, final ByteArrayOutputStream out) {
            final byte[] bytes;
            synchronized (out) {
                bytes = out.toByteArray();
                out.reset();
            }
            port.send(bytes, bytes.length);
        }

        // Also from a processor's thread: a mid-level API call that changes
        // the redstone signal queues the high-level API's event.
        private static void queue(final ByteArrayOutputStream out, final byte[] bytes) {
            synchronized (out) {
                out.write(bytes, 0, bytes.length);
            }
        }

        private static byte[] frame(final Object message) {
            if (DEBUG) {
                System.err.println("[hlapi] -> " + Json.write(message));
            }
            return ("\0" + Json.write(message) + "\0").getBytes(StandardCharsets.UTF_8);
        }

        private static Map<String, Object> message(final String type, final long id, final Object data) {
            final Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", type);
            message.put("id", id);
            message.put("gen", 0L);
            if (data != null) {
                message.put("data", data);
            }
            return message;
        }

        private void reply(final String type, final long id, final Object data) {
            queue(rpcOut, frame(message(type, id, data)));
        }

        private static final boolean DEBUG = System.getenv("POWERRUN_HLAPI_DEBUG") != null;

        /** Returns false while the payloads of the request have not all arrived. */
        private boolean handle(final String text) {
            if (DEBUG) {
                System.err.println("[hlapi] <- " + text);
            }
            if (text.equals(OVERSIZED)) {
                reply("error", 0, "message too large");
                return true;
            }
            final Object parsed;
            try {
                parsed = Json.parse(text);
            } catch (final RuntimeException e) {
                reply("error", 0, "malformed message");
                return true;
            }
            if (!(parsed instanceof final Map<?, ?> message) || !(message.get("type") instanceof final String type)) {
                reply("error", 0, "malformed message");
                return true;
            }
            final Object rawId = message.get("id");
            if (rawId != null && !(rawId instanceof Number)) {
                reply("error", 0, "malformed message");
                return true;
            }
            final long id = rawId == null ? 0 : ((Number) rawId).longValue();
            final List<byte[]> payloads = new ArrayList<>();
            if (message.get("blobs") instanceof final List<?> list && !list.isEmpty()) {
                if (!type.equals("invoke")) {
                    reply("error", 0, "malformed message");
                    return true;
                }
                int total = 0;
                for (final Object entry : list) {
                    if (!(entry instanceof final Map<?, ?> m) || !(m.get("length") instanceof final Number length)
                        || !(m.get("checksum") instanceof Number)) {
                        reply("error", 0, "malformed message");
                        return true;
                    }
                    total += length.intValue();
                }
                if (blobIn.size() < total) {
                    return false;
                }
                final byte[] bytes = blobIn.toByteArray();
                int offset = 0;
                for (final Object entry : list) {
                    final Map<?, ?> m = (Map<?, ?>) entry;
                    final int length = ((Number) m.get("length")).intValue();
                    final byte[] payload = java.util.Arrays.copyOfRange(bytes, offset, offset + length);
                    offset += length;
                    final CRC32 crc = new CRC32();
                    crc.update(payload);
                    if (crc.getValue() != ((Number) m.get("checksum")).longValue()) {
                        blobIn.reset();
                        reply("error", 0, "payload checksum mismatch");
                        return true;
                    }
                    payloads.add(payload);
                }
            }
            blobIn.reset(); // payload bytes not used by this request are discarded

            switch (type) {
                case "list" -> {
                    final List<Object> devices = new ArrayList<>(List.of(
                        Map.of("deviceId", REDSTONE_ID, "typeNames", List.of("redstone", "door")),
                        Map.of("deviceId", TEST_ID, "typeNames", List.of("test"))));
                    if (serialBase != 0) {
                        devices.add(Map.of("deviceId", SERIAL_ID, "typeNames", List.of("serial")));
                    }
                    reply("list", id, devices);
                }
                case "methods" -> {
                    final Object device = message.get("data");
                    if (REDSTONE_ID.equals(device)) {
                        reply("methods", id, List.of(
                            method("getRedstoneInput", "int", "Gets the received redstone signal for the specified side.",
                                param("side", "Side", "the side")),
                            method("getRedstoneOutput", "int", "Gets the emitted redstone signal for the specified side.",
                                param("side", "Side", "the side")),
                            method("setRedstoneOutput", "void", "Sets the emitted redstone signal for the specified side.",
                                param("side", "Side", "the side"), param("value", "int", "the signal strength"))));
                    } else if (TEST_ID.equals(device)) {
                        reply("methods", id, List.of(
                            method("echo", "byte[]", "Returns the bytes it was given, as a payload.",
                                param("data", "byte[]", "the bytes")),
                            method("fail", "void", "Always fails.")));
                    } else if (SERIAL_ID.equals(device) && serialBase != 0) {
                        reply("methods", id, List.of(
                            method("getAddress", "int", "Gets the address this card is set to."),
                            method("getBaseAddress", "long", "Gets where this card's serial port is found."),
                            method("getBaudRate", "int", "Gets the baud rate the serial port is configured to."),
                            method("getTxErrorCount", "int", "Gets how often sent data was corrupted.")));
                    } else {
                        reply("error", id, "unknown device");
                    }
                }
                case "invoke" -> invoke(id, message.get("data"), payloads);
                default -> reply("error", 0, "malformed message");
            }
            return true;
        }

        private static Map<String, Object> method(final String name, final String returnType, final String description,
                                                  final Map<?, ?>... parameters) {
            final Map<String, Object> method = new LinkedHashMap<>();
            method.put("name", name);
            method.put("returnType", returnType);
            method.put("description", description);
            method.put("parameters", List.of(parameters));
            return method;
        }

        private static Map<String, Object> param(final String name, final String type, final String description) {
            final Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", name);
            parameter.put("description", description);
            parameter.put("type", type);
            return parameter;
        }

        private static int side(final Object value) {
            if (value instanceof final Number number) {
                final int index = number.intValue();
                if (index >= 0 && index < SIDES.size()) return index;
            } else if (value instanceof final String name) {
                final String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (SIDES.contains(lower)) return SIDES.indexOf(lower);
                if (ABSOLUTE.containsKey(lower)) return ABSOLUTE.get(lower);
            }
            throw new IllegalArgumentException();
        }

        private void invoke(final long id, final Object data, final List<byte[]> payloads) {
            if (!(data instanceof final Map<?, ?> call)) {
                reply("error", 0, "malformed message");
                return;
            }
            final Object device = call.get("deviceId");
            final Object name = call.get("name");
            final List<?> params = call.get("parameters") instanceof final List<?> list ? list : List.of();
            try {
                if (REDSTONE_ID.equals(device)) {
                    switch (String.valueOf(name)) {
                        case "getRedstoneInput" -> {
                            if (params.size() != 1) throw new IllegalArgumentException();
                            reply("result", id, (long) redstone.input(side(params.get(0))));
                        }
                        case "getRedstoneOutput" -> {
                            if (params.size() != 1) throw new IllegalArgumentException();
                            reply("result", id, (long) redstone.output(side(params.get(0))));
                        }
                        case "setRedstoneOutput" -> {
                            if (params.size() != 2 || !(params.get(1) instanceof final Number level)) {
                                throw new IllegalArgumentException();
                            }
                            redstone.setOutput(side(params.get(0)), level.intValue());
                            reply("result", id, null);
                        }
                        default -> reply("error", id, "unknown method");
                    }
                } else if (TEST_ID.equals(device)) {
                    switch (String.valueOf(name)) {
                        case "echo" -> {
                            if (params.size() != 1 || !(params.get(0) instanceof final Map<?, ?> marker)
                                || !(marker.get("$blob") instanceof final Number index)
                                || index.intValue() < 0 || index.intValue() >= payloads.size()) {
                                reply("error", id, "invalid parameter signature");
                                return;
                            }
                            final byte[] payload = payloads.get(index.intValue());
                            final CRC32 crc = new CRC32();
                            crc.update(payload);
                            final Map<String, Object> message = message("result", id, Map.of("$blob", 0L));
                            message.put("blobs", List.of(Map.of("length", (long) payload.length, "checksum", crc.getValue())));
                            queue(rpcOut, frame(message));
                            queue(blobOut, payload);
                        }
                        case "fail" -> reply("error", id, "java.lang.IllegalStateException: always fails");
                        default -> reply("error", id, "unknown method");
                    }
                } else if (SERIAL_ID.equals(device) && serialBase != 0) {
                    if (!params.isEmpty()) throw new IllegalArgumentException();
                    switch (String.valueOf(name)) {
                        case "getAddress" -> reply("result", id, 7L);
                        case "getBaseAddress" -> reply("result", id, serialBase);
                        case "getBaudRate" -> reply("result", id, 9600L);
                        case "getTxErrorCount" -> reply("result", id, 0L);
                        default -> reply("error", id, "unknown method");
                    }
                } else {
                    reply("error", id, "unknown device");
                }
            } catch (final IllegalArgumentException e) {
                reply("error", id, "invalid parameter signature");
            }
        }
    }

    /** Just enough JSON: objects are maps, arrays lists, numbers Long or Double, null is NULL. */
    static final class Json {
        static final Object NULL = new Object();

        static String write(final Object value) {
            final StringBuilder out = new StringBuilder();
            write(value, out);
            return out.toString();
        }

        private static void write(final Object value, final StringBuilder out) {
            if (value == null || value == NULL) {
                out.append("null");
            } else if (value instanceof final Map<?, ?> map) {
                out.append('{');
                boolean first = true;
                for (final Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) out.append(',');
                    first = false;
                    write(String.valueOf(entry.getKey()), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            } else if (value instanceof final List<?> list) {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) out.append(',');
                    write(list.get(i), out);
                }
                out.append(']');
            } else if (value instanceof final String string) {
                out.append('"');
                for (final char c : string.toCharArray()) {
                    switch (c) {
                        case '"' -> out.append("\\\"");
                        case '\\' -> out.append("\\\\");
                        case '\n' -> out.append("\\n");
                        case '\r' -> out.append("\\r");
                        case '\t' -> out.append("\\t");
                        default -> {
                            if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                            else out.append(c);
                        }
                    }
                }
                out.append('"');
            } else if (value instanceof final Double d) {
                out.append(d == Math.rint(d) ? String.format(java.util.Locale.ROOT, "%.1f", d) : d.toString());
            } else {
                out.append(value);
            }
        }

        static Object parse(final String text) {
            final int[] pos = {0};
            final Object value = value(text, pos);
            skip(text, pos);
            if (pos[0] != text.length()) throw new IllegalArgumentException("trailing data");
            return value;
        }

        private static void skip(final String text, final int[] pos) {
            while (pos[0] < text.length() && Character.isWhitespace(text.charAt(pos[0]))) pos[0]++;
        }

        private static Object value(final String text, final int[] pos) {
            skip(text, pos);
            if (pos[0] >= text.length()) throw new IllegalArgumentException("end of input");
            final char c = text.charAt(pos[0]);
            if (c == '{') {
                final Map<String, Object> map = new LinkedHashMap<>();
                pos[0]++;
                skip(text, pos);
                if (text.startsWith("}", pos[0])) {
                    pos[0]++;
                    return map;
                }
                while (true) {
                    skip(text, pos);
                    if (!text.startsWith("\"", pos[0])) throw new IllegalArgumentException("key");
                    final String key = (String) value(text, pos);
                    skip(text, pos);
                    if (!text.startsWith(":", pos[0])) throw new IllegalArgumentException("colon");
                    pos[0]++;
                    map.put(key, value(text, pos));
                    skip(text, pos);
                    if (text.startsWith(",", pos[0])) pos[0]++;
                    else if (text.startsWith("}", pos[0])) { pos[0]++; return map; }
                    else throw new IllegalArgumentException("object");
                }
            } else if (c == '[') {
                final List<Object> list = new ArrayList<>();
                pos[0]++;
                skip(text, pos);
                if (text.startsWith("]", pos[0])) {
                    pos[0]++;
                    return list;
                }
                while (true) {
                    list.add(value(text, pos));
                    skip(text, pos);
                    if (text.startsWith(",", pos[0])) pos[0]++;
                    else if (text.startsWith("]", pos[0])) { pos[0]++; return list; }
                    else throw new IllegalArgumentException("array");
                }
            } else if (c == '"') {
                final StringBuilder out = new StringBuilder();
                pos[0]++;
                while (true) {
                    if (pos[0] >= text.length()) throw new IllegalArgumentException("string");
                    final char d = text.charAt(pos[0]++);
                    if (d == '"') return out.toString();
                    if (d != '\\') { out.append(d); continue; }
                    final char e = text.charAt(pos[0]++);
                    switch (e) {
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(text.substring(pos[0], pos[0] + 4), 16));
                            pos[0] += 4;
                        }
                        default -> out.append(e);
                    }
                }
            } else if (text.startsWith("true", pos[0])) {
                pos[0] += 4;
                return Boolean.TRUE;
            } else if (text.startsWith("false", pos[0])) {
                pos[0] += 5;
                return Boolean.FALSE;
            } else if (text.startsWith("null", pos[0])) {
                pos[0] += 4;
                return NULL;
            } else {
                final int start = pos[0];
                while (pos[0] < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos[0])) >= 0) pos[0]++;
                final String number = text.substring(start, pos[0]);
                if (number.isEmpty()) throw new IllegalArgumentException("value");
                try {
                    return number.matches("-?\\d+") ? (Object) Long.parseLong(number) : (Object) Double.parseDouble(number);
                } catch (final NumberFormatException ex) {
                    throw new IllegalArgumentException(ex);
                }
            }
        }
    }
}

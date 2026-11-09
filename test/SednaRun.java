import li.cil.sedna.Sedna;
import li.cil.sedna.api.Interrupt;
import li.cil.sedna.api.Sizes;
import li.cil.sedna.api.device.InterruptSource;
import li.cil.sedna.api.device.MemoryMappedDevice;
import li.cil.sedna.api.device.bus.DeviceClass;
import li.cil.sedna.api.device.bus.DeviceDescription;
import li.cil.sedna.api.device.bus.InterruptVectorMap;
import li.cil.sedna.api.devicetree.DeviceTree;
import li.cil.sedna.api.devicetree.DeviceTreeProvider;
import li.cil.sedna.api.memory.MemoryAccessException;
import li.cil.sedna.api.memory.MemoryMap;
import li.cil.sedna.device.bus.DeviceDescriptionRegistry;
import li.cil.sedna.device.bus.DeviceEnumerator;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOConsoleDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.device.virtio.VirtIONetworkDevice;
import li.cil.sedna.devicetree.DeviceTreeRegistry;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.memory.SimpleMemoryMap;
import li.cil.sedna.riscv.R5Board;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Boots an image on a Sedna R5Board, roughly as OpenComputers II sets it up:
 * sedna-fw as firmware, RAM, a UART console on stdio, virtio block devices,
 * a Goldfish RTC and the system controller with its interrupt wired up.
 *
 * <pre>
 * java -cp 'lib/*' SednaRun.java --firmware firmware.bin --disk sedna-freebsd.img [options]
 *   --memory MB[,MB...]    RAM, as one memory device per entry like the
 *                          memory sticks of an OC2 computer (default 32)
 *   --disk FILE            a virtio block device, repeatable, order is DT order
 *   --share DIR:TAG        a virtio 9p file system
 *   --net                  add a (disconnected) virtio network device
 *   --oc2                  add OC2's built-in devices, on OC2's interrupts: two
 *                          Goldfish RTCs, the RPC virtio console (3 ports),
 *                          a 9p file system tagged "builtin", and the
 *                          mid-level API (oc2,mlapi), emulated with two
 *                          devices: SYSTEM and a redstone interface REDSTN,
 *                          labelled "door"; the RPC console's ports answer
 *                          the high-level API for that redstone interface
 *                          ("redstone", "door") and a device "test"
 *   --builtin DIR          what the builtin file system holds (default: empty)
 *   --serial               add a serial interface card: a UART after the other
 *                          devices, its cable looped back (what the guest
 *                          sends, it receives); with --oc2 also its
 *                          high-level API device "serial", address 7
 *   --bootargs STRING      /chosen/bootargs
 *   --send STRING          text to type once WAITFOR has been seen, repeatable;
 *                          "\n" is a newline; "@redstone SIDE LEVEL" instead
 *                          sets the signal the redstone interface receives
 *   --wait-for STRING      what to wait for before the next --send (default "# ");
 *                          repeatable, the last one applies to the remaining sends
 *   --poweroff-after SEC   ask the guest to power off (syscon request) once
 *                          all input has been sent and SEC seconds passed
 *   --timeout SEC          give up after SEC seconds of wall time
 * </pre>
 * Without --send, stdin is forwarded to the console.
 */
public final class SednaRun {
    private static final int UART_IRQ = 1;
    private static final int SYSCON_IRQ = 2;
    private static final int FIRST_VIRTIO_IRQ = 3;

    public static void main(final String[] args) throws Exception {
        String firmware = null;
        String memoryMb = "32";
        final List<String> disks = new ArrayList<>();
        final List<String> shares = new ArrayList<>();
        boolean net = false;
        boolean oc2 = false;
        boolean serial = false;
        String builtinDir = null;
        String bootargs = null;
        final Deque<String> sends = new ArrayDeque<>();
        final Deque<String> waitFors = new ArrayDeque<>();
        double poweroffAfter = -1;
        double timeout = -1;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--firmware" -> firmware = args[++i];
                case "--memory" -> memoryMb = args[++i];
                case "--disk" -> disks.add(args[++i]);
                case "--share" -> shares.add(args[++i]);
                case "--net" -> net = true;
                case "--oc2" -> oc2 = true;
                case "--serial" -> serial = true;
                case "--builtin" -> builtinDir = args[++i];
                case "--bootargs" -> bootargs = args[++i];
                case "--send" -> sends.add(args[++i].replace("\\n", "\n"));
                case "--wait-for" -> waitFors.add(args[++i]);
                case "--poweroff-after" -> poweroffAfter = Double.parseDouble(args[++i]);
                case "--timeout" -> timeout = Double.parseDouble(args[++i]);
                default -> throw new IllegalArgumentException("unknown argument " + args[i]);
            }
        }
        if (firmware == null) {
            throw new IllegalArgumentException("--firmware is required");
        }

        Sedna.initialize();
        final R5Board board = new R5Board();

        // The first stick holds the firmware at the start of RAM, the others follow it.
        PhysicalMemory memory = null;
        for (final String mb : memoryMb.split(",")) {
            final PhysicalMemory stick = Memory.create(Integer.parseInt(mb.trim()) * 1024 * 1024);
            if (memory == null) {
                memory = stick;
                board.addDevice(board.getDefaultProgramStart(), stick);
            } else if (board.addDevice(stick).isEmpty()) {
                throw new IllegalStateException("no room for another " + mb + "MB of memory");
            }
        }

        final UART16550A uart = new UART16550A();
        final int firstFreeIrq;
        Mlapi mlapi = null;
        Hlapi hlapi = null;
        if (oc2) {
            // The mid-level API page first, at its fixed address, as
            // R5Architecture claims it before the other devices are placed.
            mlapi = new Mlapi();
            mlapi.getInterrupt().set(6, board.getInterruptController());
            if (!board.addDevice(0x10000000L, mlapi)) {
                throw new IllegalStateException("0x10000000 is taken, no room for the mid-level API");
            }
            // As li.cil.oc2.common.vm.BuiltinDevices, in the same order.
            final GoldfishRTC rtcHost = new GoldfishRTC(board.getCpu());
            rtcHost.getInterrupt().set(1, board.getInterruptController());
            board.addDevice(rtcHost);
            final GoldfishRTC rtcMinecraft = new GoldfishRTC(board.getCpu());
            rtcMinecraft.getInterrupt().set(2, board.getInterruptController());
            board.addDevice(rtcMinecraft);
            final VirtIOConsoleDevice rpc = new VirtIOConsoleDevice(board.getMemoryMap(),
                "oc2.rpc.0", "oc2.blob.0", "oc2.event.0");
            rpc.getInterrupt().set(3, board.getInterruptController());
            board.addDevice(rpc);
            hlapi = new Hlapi(rpc, mlapi.redstone);
            uart.getInterrupt().set(4, board.getInterruptController());
            board.addDevice(uart);
            final File builtin = builtinDir != null ? new File(builtinDir)
                : Files.createTempDirectory("sedna-builtin").toFile();
            final VirtIOFileSystemDevice vfs = new VirtIOFileSystemDevice(board.getMemoryMap(),
                "builtin", new HostFileSystem(builtin));
            vfs.getInterrupt().set(5, board.getInterruptController());
            board.addDevice(vfs);
            board.getSystemController().getInterrupt().set(7, board.getInterruptController());
            firstFreeIrq = 8;
        } else {
            uart.getInterrupt().set(UART_IRQ, board.getInterruptController());
            board.addDevice(uart);
            board.getSystemController().getInterrupt().set(SYSCON_IRQ, board.getInterruptController());
            board.addDevice(new GoldfishRTC(board.getCpu()));
            firstFreeIrq = FIRST_VIRTIO_IRQ;
        }
        board.setStandardOutputDevice(uart);

        int irq = firstFreeIrq;
        for (final String disk : disks) {
            final VirtIOBlockDevice device = new VirtIOBlockDevice(board.getMemoryMap(),
                ByteBufferBlockDevice.createFromFile(new File(disk), false));
            device.getInterrupt().set(irq++, board.getInterruptController());
            board.addDevice(device);
        }
        for (final String share : shares) {
            final int colon = share.lastIndexOf(':');
            final VirtIOFileSystemDevice device = new VirtIOFileSystemDevice(board.getMemoryMap(),
                share.substring(colon + 1), new HostFileSystem(new File(share.substring(0, colon))));
            device.getInterrupt().set(irq++, board.getInterruptController());
            board.addDevice(device);
        }
        if (net) {
            final VirtIONetworkDevice device = new VirtIONetworkDevice(board.getMemoryMap());
            device.getInterrupt().set(irq++, board.getInterruptController());
            board.addDevice(device);
        }
        // Cards come after the built-in devices, as in OC2.
        UART16550A serialUart = null;
        if (serial) {
            serialUart = new UART16550A();
            serialUart.getInterrupt().set(irq++, board.getInterruptController());
            final long base = board.addDevice(serialUart).orElseThrow();
            if (hlapi != null) {
                hlapi.serial = serialUart;
                hlapi.serialBase = base;
            }
        }
        final Deque<Byte> serialLoop = new ArrayDeque<>();

        // Firmware at the start of RAM, its region reserved in the device tree.
        final byte[] fw = Files.readAllBytes(new File(firmware).toPath());
        final PhysicalMemory ram = memory;
        for (int i = 0; i < fw.length; i++) {
            ram.store(i, fw[i], 0);
        }
        board.setFirmwareSize(regionSize(firmware, fw.length));
        if (bootargs != null) {
            board.setBootArguments(bootargs);
        }

        board.reset();
        board.initialize();
        board.setRunning(true);

        final InputStream stdin = System.in;
        final boolean scripted = !sends.isEmpty();
        final StringBuilder seen = new StringBuilder();
        final long start = System.nanoTime();
        long allSentAt = scripted ? -1 : start;
        boolean poweroffRequested = false;
        final int cyclesPerStep = board.getCpu().getFrequency() / 1000;

        while (board.isRunning() || board.isRestarting()) {
            if (board.isRestarting()) {
                // Reset by the guest: like OC2, rebuild the device tree and start over.
                System.out.println("\n[SednaRun: reset]");
                for (int i = 0; i < fw.length; i++) {
                    ram.store(i, fw[i], 0);
                }
                board.initialize();
            }
            board.step(cyclesPerStep);
            if (hlapi != null) {
                hlapi.pump();
            }
            if (serialUart != null) {
                int b;
                while ((b = serialUart.read()) >= 0) {
                    serialLoop.addLast((byte) b);
                }
                while (!serialLoop.isEmpty() && serialUart.canPutByte()) {
                    serialUart.putByte(serialLoop.removeFirst());
                }
            }

            int value;
            while ((value = uart.read()) >= 0) {
                System.out.write(value);
                if (scripted) {
                    seen.append((char) value);
                }
            }
            System.out.flush();

            final String waitFor = waitFors.isEmpty() ? "# " : waitFors.peekFirst();
            if (scripted && !sends.isEmpty() && seen.indexOf(waitFor) >= 0) {
                seen.setLength(0);
                if (waitFors.size() > 1) {
                    waitFors.removeFirst();
                }
                final String send = sends.removeFirst();
                if (send.startsWith("@redstone ")) {
                    if (mlapi == null) {
                        throw new IllegalArgumentException("@redstone needs --oc2");
                    }
                    final String[] parts = send.trim().split("\\s+");
                    mlapi.redstone.receive(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
                } else {
                    for (final byte b : send.getBytes(StandardCharsets.UTF_8)) {
                        while (!uart.canPutByte()) {
                            board.step(cyclesPerStep);
                        }
                        uart.putByte(b);
                    }
                }
                if (sends.isEmpty()) {
                    allSentAt = System.nanoTime();
                }
            } else if (!scripted) {
                while (stdin.available() > 0 && uart.canPutByte()) {
                    uart.putByte((byte) stdin.read());
                }
            }

            final long now = System.nanoTime();
            if (poweroffAfter >= 0 && !poweroffRequested && allSentAt >= 0
                && now - allSentAt > (long) (poweroffAfter * 1e9)) {
                poweroffRequested = true;
                System.out.println("\n[SednaRun: requesting poweroff: "
                    + board.getSystemController().requestPowerOff() + "]");
            }
            if (timeout >= 0 && now - start > (long) (timeout * 1e9)) {
                final var debug = board.getCpu().getDebugInterface();
                System.out.printf("%n[SednaRun: timeout, pc=%016x ra=%016x sp=%016x]%n",
                    debug.getProgramCounter(), debug.getRegister(1), debug.getRegister(2));
                System.out.flush();
                System.exit(2);
            }
        }
        System.out.println("\n[SednaRun: powered off after "
            + String.format("%.1f", (System.nanoTime() - start) / 1e9) + "s]");
    }

    private static int regionSize(final String firmware, final int fallback) throws Exception {
        final File properties = new File(firmware.replaceFirst("\\.bin$", ".properties"));
        if (!properties.isFile()) {
            return fallback;
        }
        final Properties p = new Properties();
        try (final InputStream stream = Files.newInputStream(properties.toPath())) {
            p.load(stream);
        }
        return Integer.parseInt(p.getProperty("regionSize"));
    }

    // ------------------------------------------------------------------ //
    // OC2's mid-level API, see docs/mlapi.md: the register page OC2 puts at
    // 0x10000000 on RISC-V, with its enumeration window, the event queue and
    // two devices. Calls complete at once, OC2 runs some in the next tick.

    static final class Mlapi implements MemoryMappedDevice, InterruptSource {
        private static final int OCSEL = 0, OCFUN = 1, OCDAT = 2, OCSTA = 3, OCEVC = 4, OCEVD = 5;
        private static final int BUSY = 0x01, AVAILABLE = 0x02, ERROR = 0x80;
        private static final int ENOD = 0x01, ENOF = 0x02, EARG = 0x05;
        private static final int EVQ = 0x01, EVI = 0x02, EVO = 0x40, EVP = 0x80;
        private static final int QUEUE_CAPACITY = 16, MAX_DATA = 256;

        static {
            DeviceTreeRegistry.putProvider(Mlapi.class, new DeviceTreeProvider() {
                @Override
                public Optional<String> getName(final li.cil.sedna.api.device.Device device) {
                    return Optional.of("oc2-mlapi");
                }

                @Override
                public void visit(final DeviceTree node, final MemoryMap memoryMap,
                                  final li.cil.sedna.api.device.Device device) {
                    node.addProp("compatible", "oc2,mlapi");
                }
            });
            DeviceDescriptionRegistry.putProvider(Registers.class, device -> List.of(
                new DeviceDescription(new DeviceClass(0x80), List.of("SYSTEM"),
                    "00000000-0000-0000-0000-000000000000", 0),
                new DeviceDescription(new DeviceClass(0x80), List.of("REDSTN", "door"),
                    "6f1d7c2a-0d8e-4a43-9b61-5c3e2f7a9d10", 1)));
        }

        private final Interrupt interrupt = new Interrupt();
        private final SimpleMemoryMap page = new SimpleMemoryMap();
        final Redstone redstone = new Redstone();

        private int selected;
        private int function = -1;
        private final ByteArrayOutputStream arguments = new ByteArrayOutputStream();
        private final Deque<Integer> results = new ArrayDeque<>();
        private int error;

        private int control;
        private final Deque<int[]> events = new ArrayDeque<>();
        private int cursor;

        Mlapi() {
            final Registers registers = new Registers();
            page.addDevice(0x00, registers);
            page.addDevice(0x10, new DeviceEnumerator(page, List.of(registers),
                id -> InterruptVectorMap.NO_VECTOR));
        }

        Interrupt getInterrupt() {
            return interrupt;
        }

        @Override
        public Iterable<Interrupt> getInterrupts() {
            return List.of(interrupt);
        }

        @Override
        public int getLength() {
            return 0x1000;
        }

        @Override
        public int getSupportedSizes() {
            return 1 << Sizes.SIZE_8_LOG2;
        }

        @Override
        public long load(final int offset, final int sizeLog2) throws MemoryAccessException {
            return page.load(offset, sizeLog2);
        }

        @Override
        public void store(final int offset, final long value, final int sizeLog2) throws MemoryAccessException {
            page.store(offset, value, sizeLog2);
        }

        /** Registers 0-7, what the enumeration window lists the devices at. */
        final class Registers implements MemoryMappedDevice {
            @Override
            public int getLength() {
                return 8;
            }

            @Override
            public int getSupportedSizes() {
                return 1 << Sizes.SIZE_8_LOG2;
            }

            @Override
            public long load(final int offset, final int sizeLog2) {
                return switch (offset) {
                    case OCSEL -> selected;
                    case OCDAT -> error != 0 ? error : results.isEmpty() ? 0 : results.removeFirst();
                    case OCSTA -> (error != 0 ? ERROR : 0) | (results.isEmpty() ? 0 : AVAILABLE);
                    case OCEVC -> control | (events.isEmpty() ? 0 : EVP);
                    case OCEVD -> readEvent();
                    default -> 0;
                };
            }

            @Override
            public void store(final int offset, final long longValue, final int sizeLog2) {
                final int value = (int) (longValue & 0xFF);
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
                    default -> {
                    }
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
                return input[side];
            }

            int output(final int side) {
                return output[side];
            }

            void setOutput(final int side, final int level) {
                output[side] = Math.max(0, Math.min(level, 15));
                update(side);
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
            if ((control & EVI) != 0 && !events.isEmpty()) {
                interrupt.raiseInterrupt();
            } else {
                interrupt.lowerInterrupt();
            }
        }
    }

    // ------------------------------------------------------------------ //
    // OC2's high-level API, the host side, see docs/hlapi.md: NUL framed
    // JSON requests on oc2.rpc.0, payloads on oc2.blob.0, events on
    // oc2.event.0. Replies are sent at once, OC2 sends most a tick later.

    static final class Hlapi {
        private static final String REDSTONE_ID = "6f1d7c2a-0d8e-4a43-9b61-5c3e2f7a9d10";
        private static final String TEST_ID = "0b7e4d1c-2f3a-4e5b-8c6d-7e8f9a0b1c2d";
        private static final String SERIAL_ID = "3c9a5e2b-7d1f-4b6a-a8e0-1f2d3c4b5a69";
        private static final List<String> SIDES = List.of("down", "up", "back", "front", "left", "right");
        private static final Map<String, Integer> ABSOLUTE = Map.of("north", 3, "south", 2, "west", 4, "east", 5);
        private static final int MAX_REQUEST = 4096;
        private static final String OVERSIZED = "\u0000oversized";

        private final VirtIOConsoleDevice.PortView rpc, blob, event;
        private final Mlapi.Redstone redstone;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final ByteArrayOutputStream blobIn = new ByteArrayOutputStream();
        private final Deque<String> requests = new ArrayDeque<>();
        private final Deque<Byte> rpcOut = new ArrayDeque<>(), blobOut = new ArrayDeque<>(), eventOut = new ArrayDeque<>();
        private boolean oversized;
        UART16550A serial;
        long serialBase;

        Hlapi(final VirtIOConsoleDevice console, final Mlapi.Redstone redstone) {
            rpc = console.getPort(0);
            blob = console.getPort(1);
            event = console.getPort(2);
            this.redstone = redstone;
            redstone.listener = (side, level) -> {
                final Map<String, Object> data = new LinkedHashMap<>();
                data.put("side", SIDES.get(side));
                data.put("value", (long) level);
                final Map<String, Object> message = message("redstoneChanged", 0, data);
                message.put("deviceId", REDSTONE_ID);
                if (event.isPortOpen()) {
                    queue(eventOut, frame(message));
                }
            };
        }

        void pump() {
            int b;
            while ((b = rpc.read()) >= 0) {
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
            while ((b = blob.read()) >= 0) {
                blobIn.write(b);
            }
            while (!requests.isEmpty() && handle(requests.peekFirst())) {
                requests.removeFirst();
            }
            drain(rpc, rpcOut);
            drain(blob, blobOut);
            drain(event, eventOut);
        }

        private static void drain(final VirtIOConsoleDevice.PortView port, final Deque<Byte> out) {
            // Flush every time: a full port buffer only goes out once the
            // guest has posted receive buffers again, and only on a flush.
            port.flush();
            while (!out.isEmpty() && port.canPutByte()) {
                port.putByte(out.removeFirst());
            }
            port.flush();
        }

        private static void queue(final Deque<Byte> out, final byte[] bytes) {
            for (final byte value : bytes) {
                out.addLast(value);
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

        private static final boolean DEBUG = System.getenv("SEDNARUN_HLAPI_DEBUG") != null;

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
                    if (serial != null) {
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
                    } else if (SERIAL_ID.equals(device) && serial != null) {
                        reply("methods", id, List.of(
                            method("getAddress", "int", "Gets the address this card is set to."),
                            method("getBaseAddress", "long", "Gets where this card's serial port registers are mapped."),
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
                } else if (SERIAL_ID.equals(device) && serial != null) {
                    if (!params.isEmpty()) throw new IllegalArgumentException();
                    switch (String.valueOf(name)) {
                        case "getAddress" -> reply("result", id, 7L);
                        case "getBaseAddress" -> reply("result", id, serialBase);
                        case "getBaudRate" -> reply("result", id, (long) serial.getBaudRate());
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

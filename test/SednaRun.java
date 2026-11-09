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
 *                          labelled "door"
 *   --builtin DIR          what the builtin file system holds (default: empty)
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
                        output[side] = Math.min(args[1] & 0xFF, 15);
                        update(side);
                        yield new int[0];
                    }
                };
            }

            void receive(final int side, final int level) {
                received[side] = level;
                update(side);
            }

            private void update(final int side) {
                final int level = Math.max(received[side], output[side]);
                if (level != input[side]) {
                    input[side] = level;
                    queueEvent(1, 1, side | level << 8);
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
}

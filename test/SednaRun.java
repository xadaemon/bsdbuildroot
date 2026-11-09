import li.cil.sedna.Sedna;
import li.cil.sedna.api.device.PhysicalMemory;
import li.cil.sedna.device.block.ByteBufferBlockDevice;
import li.cil.sedna.device.memory.Memory;
import li.cil.sedna.device.rtc.GoldfishRTC;
import li.cil.sedna.device.serial.UART16550A;
import li.cil.sedna.device.virtio.VirtIOBlockDevice;
import li.cil.sedna.device.virtio.VirtIOConsoleDevice;
import li.cil.sedna.device.virtio.VirtIOFileSystemDevice;
import li.cil.sedna.device.virtio.VirtIONetworkDevice;
import li.cil.sedna.fs.HostFileSystem;
import li.cil.sedna.riscv.R5Board;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
 *                          and a 9p file system tagged "builtin"
 *   --builtin DIR          what the builtin file system holds (default: empty)
 *   --bootargs STRING      /chosen/bootargs
 *   --send STRING          text to type once WAITFOR has been seen, repeatable;
 *                          "\n" is a newline
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
        if (oc2) {
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
                for (final byte b : sends.removeFirst().getBytes(StandardCharsets.UTF_8)) {
                    while (!uart.canPutByte()) {
                        board.step(cyclesPerStep);
                    }
                    uart.putByte(b);
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
}

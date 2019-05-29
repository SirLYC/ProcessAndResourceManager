import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

public class Shell {
    private static final StringBuilder errorMessageBuffer = new StringBuilder();
    private static final String PROCESS_INFO_FORMAT = "|%-10d|%-10d|%-20s|%-10s|%-10s|%-30s|";
    private static final String PROCESS_INFO_HEADER;
    private static final String PROCESS_INFO_SEP;
    private static final String RESOURCE_INFO_SEP;
    private static final String RESOURCE_INFO_HEADER;
    private static final String RESOURCE_INFO_FORMAT = "|%-15d|%-10d|";
    private static final String OCCUPIED_HEADER;
    private static final String USAGE_CR = "usage: cr [name] [priority](0|1|2)";
    private static String TAG = "shell>";
    private static CountDownLatch prepareCountDownLatch = new CountDownLatch(1);
    private static ProcessScheduler processScheduler = new ProcessScheduler();
    private static ResourceManager resourceManager = ResourceManager.instance;

    static {
        StringBuilder sb = new StringBuilder();
        sb.append("+");
        repeatAppendString(sb, "-", 10);
        sb.append("+");
        repeatAppendString(sb, "-", 10);
        sb.append("+");
        repeatAppendString(sb, "-", 20);
        sb.append("+");
        repeatAppendString(sb, "-", 10);
        sb.append("+");
        repeatAppendString(sb, "-", 10);
        sb.append("+");
        repeatAppendString(sb, "-", 30);
        sb.append("+");
        PROCESS_INFO_SEP = sb.toString();
        PROCESS_INFO_HEADER = PROCESS_INFO_SEP + "\n"
                + String.format("|%-10s|%-10s|%-20s|%-10s|%-10s|%-30s|",
                "pid", "ppid", "name", "priority", "state", "blocking reason") +
                "\n" + PROCESS_INFO_SEP;
        sb.delete(0, sb.length());
        sb.append("+");
        repeatAppendString(sb, "-", 15);
        sb.append("+");
        repeatAppendString(sb, "-", 10);
        sb.append("+");
        RESOURCE_INFO_SEP = sb.toString();
        RESOURCE_INFO_HEADER = RESOURCE_INFO_SEP + "\n"
                + String.format("|%-15s|%-10s|", "resource id", "state") +
                "\n" + RESOURCE_INFO_SEP;
        OCCUPIED_HEADER = RESOURCE_INFO_SEP + "\n"
                + String.format("|%-15s|%-10s|", "resource id", "count") +
                "\n" + RESOURCE_INFO_SEP;
    }

    private static void repeatAppendString(StringBuilder sb, String s, int count) {
        for (int i = 0; i < count; i++) {
            sb.append(s);
        }
    }

    static void writeToErrorBuffer(long pid, String name, Exception e) {
        synchronized (errorMessageBuffer) {
            errorMessageBuffer.append("process(pid =")
                    .append(pid)
                    .append(", name = ")
                    .append(name)
                    .append(") error:\n")
                    .append(e.getMessage());
        }
    }

    static void notifyPrepared() {
        prepareCountDownLatch.countDown();
    }

    public static void main(String... args) {
        System.out.println("Shell is starting...");
        new Thread(processScheduler).start();
        while (prepareCountDownLatch.getCount() > 0) {
            try {
                prepareCountDownLatch.await();
            } catch (InterruptedException e) {
                // do nothing
            }
        }
        System.out.println("Current time is: " + new Timestamp(System.currentTimeMillis()));
        Scanner scanner = new Scanner(System.in);
        boolean exit = false;
        while (!exit) {
            reportError();
            System.out.print(TAG);
            if (scanner.hasNextLine()) {
                String line = scanner.nextLine().trim();
                if (!line.isEmpty()) {
                    interpretCommand(line);
                }
            } else {
                exit = true;
                System.out.println("bye!");
            }
            reportError();
        }
    }

    private static void reportError() {
        synchronized (errorMessageBuffer) {
            if (errorMessageBuffer.length() != 0) {
                System.err.println(errorMessageBuffer.toString());
                errorMessageBuffer.delete(0, errorMessageBuffer.length());
            }
        }
    }

    private static void interpretCommand(String line) {
        String[] args = line.split("\\s");
        if (args.length <= 0) {
            return;
        }

        switch (args[0]) {
            case "cr":
                handleCreateProcess(args);
                break;
            case "pl":
                handleProcessList(args);
                break;
            case "pi":
                handleProcessInfo(args);
                break;
            case "kill":
                handleKillProcess(args);
                break;
            case "rs":
                handleResourceList(args);
                break;
            case "exit":
            case "quit":
            case "e":
            case "q":
                if (args.length > 1) {
                    System.err.println("usage: exit|quit");
                    break;
                }
                processScheduler.exit();
                System.out.println("bye");
                System.exit(0);
            default:
                System.err.println("Sorry, I cannot understand \"" + line + "\"");
        }
    }

    private static void handleResourceList(String... args) {
        if (args.length > 1) {
            System.err.println("usage: rs");
            return;
        }
        Map<Long, Integer> resourceState = resourceManager.getResourceState();
        printResourcesInfo(resourceState, RESOURCE_INFO_HEADER);
    }

    private static void handleKillProcess(String... args) {
        ProcessControlBlock processControlBlock = checkProcessIdCommand(args);
        if (processControlBlock == null) {
            return;
        }
        try {
            processScheduler.kill(processControlBlock.pid);
        } catch (IllegalArgumentException e) {
            System.err.println("process with pid: " + args[1] + " not exist");
        }
    }

    private static void handleCreateProcess(String... args) {
        if (args.length > 4) {
            System.err.println(USAGE_CR);
        }
        String name;
        Integer priority = null;
        if (args.length == 1) {
            priority = 1;
            name = null;
        } else if (args.length == 2) {
            priority = 1;
            name = args[1];
        } else {
            name = args[1];
            try {
                priority = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                // should be number
            }
            if (priority == null || priority < 0 || priority > 2) {
                System.err.println("wrong priority: " + args[2]);
                return;
            }
        }

        System.out.println("waiting for new process created...");
        long pid = processScheduler.createNewProcess(name, 0, priority);
        ProcessControlBlock processControlBlock = processScheduler.getProcessById(pid);
        System.out.println("process " + processControlBlock.name + ": " + processControlBlock.processState);
    }

    private static void handleProcessInfo(String... args) {
        ProcessControlBlock processControlBlock = checkProcessIdCommand(args);
        if (processControlBlock == null) {
            return;
        }

        Map<Long, Integer> occupiedResources = new HashMap<>(processControlBlock.occupiedResources);

        System.out.println(PROCESS_INFO_HEADER);
        System.out.println(String.format(PROCESS_INFO_FORMAT, processControlBlock.pid,
                processControlBlock.ppid,
                processControlBlock.name,
                processControlBlock.priority,
                processControlBlock.processState,
                processControlBlock.getBlockReason()));
        System.out.println(PROCESS_INFO_SEP + "\noccupied resources:");
        printResourcesInfo(occupiedResources, OCCUPIED_HEADER);
    }

    private static void printResourcesInfo(Map<Long, Integer> resources, String occupiedHeader) {
        System.out.println(occupiedHeader);
        resources.forEach((id, state) -> System.out.println(String.format(RESOURCE_INFO_FORMAT, id, state)));
        System.out.println(RESOURCE_INFO_SEP);
    }

    private static void handleProcessList(String... args) {
        if (args.length > 1) {
            System.err.println("usage: pl");
            return;
        }

        System.out.println(PROCESS_INFO_HEADER);
        for (ProcessControlBlock processControlBlock : processScheduler.getProcessList()) {
            System.out.println(String.format(PROCESS_INFO_FORMAT, processControlBlock.pid,
                    processControlBlock.ppid,
                    processControlBlock.name,
                    processControlBlock.priority,
                    processControlBlock.processState,
                    processControlBlock.getBlockReason()));
        }
        System.out.println(PROCESS_INFO_SEP);
    }

    private static ProcessControlBlock checkProcessIdCommand(String... args) {
        if (args.length > 2) {
            System.err.println("usage: " + args[0] + " <pid>");
            return null;
        }

        long pid;
        try {
            pid = Long.parseLong(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("Illegal pid: " + args[1]);
            return null;
        }

        ProcessControlBlock processControlBlock = processScheduler.getProcessById(pid);
        if (processControlBlock == null) {
            System.err.println("process with pid: " + args[1] + " not exist");
            return null;
        }
        return processControlBlock;
    }
}
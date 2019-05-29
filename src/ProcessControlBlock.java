import java.util.*;

class ProcessControlBlock {

    private static final Random r = new Random();
    /*--------------------- PCB info ---------------------*/
    final long pid;
    final String name;
    final Priority priority;
    // if is prod, just release(produce) resource without requesting
    private final boolean prod;
    // 3000 ~ 12000 instructions
    private final long totalInstructionCount = (r.nextInt(4) + 1) * 3000;
    long ppid;
    State processState = State.READY;
    Long eventId;
    /*--------------------- PCB info ---------------------*/
    Map<Long, Integer> occupiedResources = new HashMap<>();
    List<Long> childPids = new ArrayList<>();
    String blockReason;
    private long ip;
    // resource to request/release at the beginning of program
    private long[] resources = new long[]{
            r.nextInt(4), r.nextInt(4)
    };
    // request/release 1, 2 or 3
    private int[] resourcesCount = new int[]{
            1, r.nextInt(3) + 1
    };
    private int resourceIndex;


    ProcessControlBlock(long pid, long ppid, String name, Priority priority, boolean prod) {
        this.pid = pid;
        this.ppid = ppid;
        this.name = name;
        this.priority = priority;
        this.prod = prod;
    }

    long currentRId() {
        return resources[resourceIndex];
    }

    int currentRCount() {
        return resourcesCount[resourceIndex];
    }

    // fetch next instruction
    // return null if the end (ip > totalInstructionCount)
    Instruction nextInstruction() {
        ip++;
        if (prod) {
            // producer
            // just release(produce) 2 resources
            if (ip == 1) {
                return Instruction.Release;
            } else if (ip == 2) {
                resourceIndex = 1;
                return Instruction.Release;
            }
        } else {
            if (ip == 1) {
                // first instruction: request resources[0]
                return Instruction.Request;
            } else if (ip == 2) {
                // first instruction: request resources[1]
                resourceIndex = 1;
                return Instruction.Request;
            } else if (ip == totalInstructionCount - 1) {
                // last but one instruction: release resources[1]
                return Instruction.Release;
            } else if (ip == totalInstructionCount) {
                // last instruction: release resources[0]
                resourceIndex = 0;
                return Instruction.Release;
            }
        }

        if (ip > totalInstructionCount) {
            return null;
        } else {
            int p = r.nextInt(10000);
            if (4000 <= p && p < 4002) {
                // p = 1 / 5000
                return Instruction.Fork;
            } else if (700 <= p && p < 705) {
                // p = 1 / 2000
                return Instruction.IO;
            } else {
                // p = 9993 / 10000
                return Instruction.Computation;
            }
        }
    }

    String getBlockReason() {
        if (processState == State.BLOCKING) {
            return blockReason;
        } else {
            return "N/A";
        }
    }

    public enum State {
        READY, RUNNING, BLOCKING
    }

    public enum Priority {
        Init, User, System
    }
}

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class ProcessScheduler implements Runnable {
    private static final long MAX_CLOCKS = 30;
    // ready queue
    private final List<Long> readyProcessIdQueue = new ArrayList<>();
    private final ResourceManager resourceManager = ResourceManager.instance;
    private final Set<Long> allocatedPid = new HashSet<>();
    private final Set<Long> allocatedEventId = new HashSet<>();
    private final Map<Long, TimerTask> eventTask = new HashMap<>();
    private final Timer eventTimer = new Timer();
    private final Random r = new Random();
    // current running process
    private ProcessControlBlock runningProcess;
    // record instruction execution time
    // if equal to MAX_CLOCKS, should schedule
    private long currentClocks;
    // record all pcb in memory (running, ready or block)
    private Map<Long, ProcessControlBlock> presentPCBs = new HashMap<>();
    // record process that are waiting for IO event finish
    private Map<Long, Long> eventWaitingProcesses = new LinkedHashMap<>();
    private Lock lock = new ReentrantLock();
    private Condition newProcess = lock.newCondition();
    private volatile boolean exit = false;

    @Override
    public void run() {
        Shell.notifyPrepared();
        runProcess:
        while (!exit) {
            try {
                lock.lock();
                // no process using CPU now
                // try to schedule one
                if (runningProcess == null) {
                    schedule();
                }
                ProcessControlBlock runningProcess = this.runningProcess;
                if (runningProcess == null) {
                    try {
                        // block this thread until a new process is scheduled
                        newProcess.await();
                    } catch (InterruptedException e) {
                        continue;
                    }
                    runningProcess = this.runningProcess;
                }

                if (runningProcess == null) {
                    continue;
                }

                assert runningProcess.processState == ProcessControlBlock.State.RUNNING;

                // run process instruction

                // fetch instruction
                Instruction instruction = runningProcess.nextInstruction();
                // process end
                if (instruction == null) {
                    if (handleProcessExit(runningProcess)) {
                        schedule();
                    }
                    continue;
                }

                // translate and do instruction
                try {
                    instruction.run();
                } catch (Exception e) {
                    Shell.writeToErrorBuffer(runningProcess.pid, runningProcess.name, e);
                    handleProcessExit(runningProcess);
                    this.runningProcess = null;
                    schedule();
                    continue;
                }
                switch (instruction) {
                    case Computation:
                        // computation task
                        currentClocks++;
                        if (currentClocks >= MAX_CLOCKS) {
                            runningProcess.processState = ProcessControlBlock.State.READY;
//                            addToReadyQueue(runningProcess.pid, runningProcess.priority);
                            schedule();
                        }
                        break;
                    case Request:
                        if (resourceManager.requestResource(runningProcess, runningProcess.currentRId(), runningProcess.currentRCount())) {
                            // runningProcess state change: READY -> BLOCKING
                            schedule();
                        } else if (++currentClocks >= MAX_CLOCKS) {
                            // request success
                            // behave like computation instruction
                            runningProcess.processState = ProcessControlBlock.State.READY;
//                            addToReadyQueue(runningProcess.pid, runningProcess.priority);
                            schedule();
                        }
                        break;
                    case Release:
                        Set<Long> ids = resourceManager.releaseResource(runningProcess, runningProcess.currentRId(), runningProcess.currentRCount());
                        if (!ids.isEmpty()) {
                            for (Long id : ids) {
                                ProcessControlBlock pcb = presentPCBs.get(id);
                                addToReadyQueue(id, pcb.priority);
                            }
                        } else if (++currentClocks >= MAX_CLOCKS) {
                            runningProcess.processState = ProcessControlBlock.State.READY;
//                            addToReadyQueue(runningProcess.pid, runningProcess.priority);
                            schedule();
                        }
                        break;
                    case Fork:
                        // create sub process
                        createNewProcess(null, runningProcess.pid, runningProcess.priority.ordinal());
                        continue runProcess;
                    case IO:
                        // simulate IO:
                        // create a event for this process to waiting
                        long eventId = nextId(allocatedEventId);
                        eventWaitingProcesses.put(eventId, runningProcess.pid);
                        runningProcess.processState = ProcessControlBlock.State.BLOCKING;
                        runningProcess.blockReason = "waiting event " + eventId;
                        scheduleEvent(eventId);
                        schedule();
                        break;
                }
            } finally {
                lock.unlock();
            }
        }
    }

    private void scheduleEvent(long id) {
        // 2 ~ 5 seconds
        int sec = r.nextInt(4) + 2;
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                try {
                    lock.lock();
                    eventTask.remove(id);
                    Long pid = eventWaitingProcesses.remove(id);
                    ProcessControlBlock pcb;
                    if (pid != null && (pcb = presentPCBs.get(pid)) != null) {
                        pcb.processState = ProcessControlBlock.State.READY;
                        allocatedEventId.remove(pcb.eventId);
                        pcb.eventId = null;
                        addToReadyQueue(pid, pcb.priority);
                        if (runningProcess == null) {
                            schedule();
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
        };
        eventTask.put(id, timerTask);
        eventTimer.schedule(timerTask, sec * 1000);
    }

    /**
     * @return process id
     */
    long createNewProcess(String name, long ppid, int priority) {
        try {
            lock.lock();
            boolean prod = r.nextBoolean();
            ProcessControlBlock.Priority pri = ProcessControlBlock.Priority.values()[priority];
            long pid = nextId(allocatedPid);
            if (name == null) {
                name = pri.name().toLowerCase() + "-" + pid + "-" + ppid;
                if (prod) {
                    name = name + "(prod)";
                }
            }

            ProcessControlBlock parentProcess = presentPCBs.get(ppid);
            if (parentProcess == null) {
                ppid = 0;
            } else {
                parentProcess.childPids.add(pid);
            }
            ProcessControlBlock processControlBlock = new ProcessControlBlock(pid, ppid, name, pri, prod);
            presentPCBs.put(pid, processControlBlock);
            addToReadyQueue(pid, processControlBlock.priority);
            schedule();
            return pid;
        } finally {
            lock.unlock();
        }
    }

    private void schedule() {
        try {
            lock.lock();
            ProcessControlBlock lastRunningProcess = this.runningProcess;
            this.runningProcess = null;
            boolean empty = readyProcessIdQueue.isEmpty();
            if (empty && lastRunningProcess != null &&
                    lastRunningProcess.processState == ProcessControlBlock.State.READY) {
                currentClocks = 0;
                lastRunningProcess.processState = ProcessControlBlock.State.RUNNING;
                runningProcess = lastRunningProcess;
                return;
            } else if (empty) {
                currentClocks = 0;
                return;
            }
            if (lastRunningProcess != null && lastRunningProcess.processState == ProcessControlBlock.State.RUNNING) {
                lastRunningProcess.processState = ProcessControlBlock.State.READY;
            }
            // get first ready process
            Long next = readyProcessIdQueue.remove(0);
            ProcessControlBlock processControlBlock = presentPCBs.get(next);
            if (processControlBlock != null) {
                setRunningProcess(processControlBlock);
            }

            // note that first ready process may be process that are running just now
            if (lastRunningProcess != null && lastRunningProcess != processControlBlock) {
                switch (lastRunningProcess.processState) {
                    case RUNNING:
                        lastRunningProcess.processState = ProcessControlBlock.State.READY;
                    case READY:
                        addToReadyQueue(lastRunningProcess.pid, lastRunningProcess.priority);
                        break;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    private void addToReadyQueue(long pid, ProcessControlBlock.Priority processPriority) {
        int addPos = 0;
        for (int i = 0; i < readyProcessIdQueue.size(); i++) {
            long id = readyProcessIdQueue.get(i);
            ProcessControlBlock pcb = presentPCBs.get(id);
            // already in ready queue
            if (pcb != null && pcb.pid == pid) {
                return;
            }
            if (pcb != null && pcb.priority.ordinal() < processPriority.ordinal()) {
                addPos = i;
                break;
            }
            addPos++;
        }
        ProcessControlBlock processControlBlock = presentPCBs.get(pid);
        if (processControlBlock.processState != ProcessControlBlock.State.READY) {
            throw new IllegalStateException("process " + processControlBlock.pid + " state is not ready!!!");
        }
        readyProcessIdQueue.add(addPos, pid);
    }

    private void setRunningProcess(ProcessControlBlock processControlBlock) {
        try {
            lock.lock();
            runningProcess = processControlBlock;
            if (runningProcess != null) {
                // clear clock
                currentClocks = 0;
                runningProcess.processState = ProcessControlBlock.State.RUNNING;
                newProcess.signal();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean handleProcessExit(ProcessControlBlock pcb) {
        boolean isRunningProcess = clearProcessInfo(pcb);
        for (Long childPid : pcb.childPids) {
            ProcessControlBlock processControlBlock = presentPCBs.get(childPid);
            if (processControlBlock != null) {
                // orphan process
                // taken over by init process
                processControlBlock.ppid = 0;
            }
        }
        return isRunningProcess;
    }

    // error or finish
    // clear its info and release resources if not released yet
    private boolean clearProcessInfo(ProcessControlBlock pcb) {
        long pid = pcb.pid;
        boolean isRunningProcess = false;
        presentPCBs.remove(pid);
        readyProcessIdQueue.remove(pid);
        eventWaitingProcesses.remove(pid);
        allocatedPid.remove(pid);
        allocatedEventId.remove(pcb.eventId);
        if (runningProcess != null && runningProcess.pid == pid) {
            isRunningProcess = true;
            runningProcess = null;
        }
        if (runningProcess == pcb) runningProcess = null;
        eventWaitingProcesses.remove(pcb.eventId);
        TimerTask timerTask = eventTask.remove(pcb.eventId);
        if (timerTask != null) {
            timerTask.cancel();
        }
        Set<Long> ids = resourceManager.clearInfo(pcb);
        if (!ids.isEmpty()) {
            for (Long id : ids) {
                ProcessControlBlock processControlBlock = presentPCBs.get(id);
                addToReadyQueue(id, processControlBlock.priority);
            }
        }

        return isRunningProcess;
    }

    void kill(long pid) {
        try {
            lock.lock();
            ProcessControlBlock pcb = presentPCBs.get(pid);
            if (pcb == null) {
                throw new IllegalArgumentException("cannot find process with id " + pid);
            }
            if (killTree(pcb)) {
                schedule();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * @return true if one of the process killed is running process (need scheduling)
     */
    private boolean killTree(ProcessControlBlock pcb) {
        boolean isRunningProcess = clearProcessInfo(pcb);
        for (Long childPid : pcb.childPids) {
            ProcessControlBlock childProcess = presentPCBs.get(childPid);
            if (childProcess != null) {
                isRunningProcess |= killTree(childProcess);
            }
        }
        return isRunningProcess;
    }

    // help for generating unique id
    private long nextId(Set<Long> allocatedId) {
        try {
            lock.lock();
            if (allocatedId.isEmpty()) {
                allocatedId.add(1L);
                return 1;
            }
            long max = Collections.max(allocatedId);
            allocatedId.add(max + 1);
            return max + 1;
        } finally {
            lock.unlock();
        }
    }

    ProcessControlBlock getProcessById(long pid) {
        return presentPCBs.get(pid);
    }

    List<ProcessControlBlock> getProcessList() {
        List<ProcessControlBlock> result = new ArrayList<>(presentPCBs.values());
        result.sort(Comparator.comparingLong(value -> value.pid));
        return result;
    }

    void exit() {
        exit = true;
    }
}

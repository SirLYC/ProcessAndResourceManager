import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

class ResourceManager {

    static ResourceManager instance = new ResourceManager();
    private final Lock lock = new ReentrantLock();
    // map: resourceId -> state (available count)
    private Map<Long, Integer> resourceState = new HashMap<>();
    // map: resourceId -> requestProcessQueue
    private Map<Long, Queue<Node>> requestQueue = new HashMap<>();
    // map: processId -> requesting resourcesId (and this process is in resource's waiting queue)
    private Map<Long, Long> requestingResources = new HashMap<>();

    private ResourceManager() {
        resourceState.put(0L, 0);
        resourceState.put(1L, 1);
        resourceState.put(2L, 2);
        resourceState.put(3L, 3);
    }

    /**
     * @return true if pcb state change( which means this process is blocking,
     * And {@link ProcessScheduler} should call {@link ProcessScheduler#schedule()} )
     */
    boolean requestResource(ProcessControlBlock pcb, long resourceId, int n) {
        try {
            lock.lock();
            return !requestResourceForN(pcb, resourceId, n,
                    resourceState.getOrDefault(resourceId, 0), false);
        } finally {
            lock.unlock();
        }
    }

    /**
     * release resource for n
     *
     * @return a set of pcb ids that are in resource request waiting queue and can acquire resource they requested
     * in other words, these process state change from: block -> ready
     * so scheduler should add these process to ready queue
     */
    Set<Long> releaseResource(ProcessControlBlock pcb, long resourceId, int n) {
        try {
            lock.lock();
            Set<Long> ids = new LinkedHashSet<>();
            Integer count = pcb.occupiedResources.getOrDefault(resourceId, 0);
            count -= n;
            if (count <= 0) {
                pcb.occupiedResources.remove(resourceId);
            } else {
                pcb.occupiedResources.put(resourceId, count);
            }
            Integer left = resourceState.getOrDefault(resourceId, 0);
            left += n;
            resourceState.put(resourceId, left);
            Queue<Node> queue = requestQueue.get(resourceId);
            if (queue == null || queue.isEmpty()) {
                return ids;
            }
            // acquire from start
            Node node;
            while ((node = queue.peek()) != null) {
                if (requestResourceForN(node.pcb, resourceId, node.n, left, true)) {
                    left -= node.n;
                    ids.add(node.pcb.pid);
                    requestingResources.remove(node.pcb.pid);
                    queue.poll();
                } else {
                    break;
                }
            }
            return ids;
        } finally {
            lock.unlock();
        }
    }

    /**
     * acquire n resource or add to requesting queue of this resource (if not from waiting queue)
     *
     * @return true if acquire successfully
     */
    private boolean requestResourceForN(ProcessControlBlock pcb, long resourceId, int n, int available, boolean fromRequestQueue) {
        try {
            lock.lock();
            if (available < n) {
                if (!fromRequestQueue) {
                    // add to waiting queue
                    Queue<Node> queue = requestQueue.getOrDefault(resourceId, new ArrayDeque<>());
                    queue.offer(new Node(pcb, n));
                    requestQueue.put(resourceId, queue);
                    pcb.processState = ProcessControlBlock.State.BLOCKING;
                    pcb.blockReason = "waiting resource " + resourceId + "(" + n + ")";
                    requestingResources.put(pcb.pid, resourceId);
                    resourceState.put(resourceId, available);
                }
                return false;
            }
            if (fromRequestQueue) {
                pcb.processState = ProcessControlBlock.State.READY;
            }

            // resource available count -= n
            resourceState.put(resourceId, available - n);
            // record in pcb
            int count = pcb.occupiedResources.getOrDefault(resourceId, 0);
            count = count + n;
            pcb.occupiedResources.put(resourceId, count);
            return true;
        } finally {
            lock.unlock();
        }
    }

    Set<Long> clearInfo(ProcessControlBlock pcb) {
        try {
            lock.lock();
            Long rId = requestingResources.get(pcb.pid);
            if (rId != null) {
                Queue<Node> nodes = requestQueue.get(rId);
                Iterator<Node> iterator = nodes.iterator();
                while (iterator.hasNext()) {
                    Node node = iterator.next();
                    if (node.pcb == pcb) {
                        iterator.remove();
                        break;
                    }
                }
            }

            Set<Long> ids = new HashSet<>();
            Map<Long, Integer> occupiedResources = new HashMap<>(pcb.occupiedResources);
            occupiedResources.forEach((key, count) -> {
                if (count != null && count > 0) {
                    ids.addAll(releaseResource(pcb, key, count));
                }
            });
            requestingResources.remove(pcb.pid);
            return ids;
        } finally {
            lock.unlock();
        }
    }

    Map<Long, Integer> getResourceState() {
        return new HashMap<>(resourceState);
    }

    private static class Node {
        private ProcessControlBlock pcb;
        private int n;

        private Node(ProcessControlBlock pcb, int n) {
            this.pcb = pcb;
            this.n = n;
        }
    }
}

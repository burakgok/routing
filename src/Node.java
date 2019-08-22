
import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Halil Selman Atmaca
 *         Burak Gök
 *         Enes Varol
 */
public class Node implements Runnable {
    private final int address;
    private final Map<Integer, Neighbor> neighbors;
    private final Map<Integer, NodeInfo> nodes;
    
    /* Since 'nodes' is private, final and unexposed, it is used as the lock.
     * If it does not satisfy the aforementioned requirements in future, an
     * instance of the Object class satisfying the same req's should be used.
     */
    
    private final SerializedDatagram datagram;
    private final Timer timer;
    private static final int PING_PERIOD = 5_000,
                             TIMEOUT_PERIOD = 1_000,
                             LINK_LIFE = 15_000;
    
    private volatile boolean shouldTerminate = false;
    
    public Node(int address, List<Neighbor> neighbors) throws SocketException {
        timer = new Timer(String.format("%s (ping)", id(address)));
        datagram = new SerializedDatagram(address, 1024);
        
        this.address = address;
        this.neighbors = neighbors.stream()
            .collect(Collectors.toMap(n -> n.address, Function.identity()));
        nodes = neighbors.stream().map(NodeInfo::new)
            .collect(Collectors.toMap(n -> n.address, Function.identity()));
        logDistanceVector("init", null);
        
        neighborDistances = neighbors.stream()
            .collect(Collectors.toMap(n -> n.address, n -> n.distance));
    }
    
    /* Test Methods */
    public int getAddress() {
        return address;
    }
    public void broadcastDistanceVector() {
        synchronized (nodes) {
            neighbors().forEach(this::sendDistanceVector);
        }
    }
    private final Map<Integer, Double> neighborDistances;
    public void setNeighborDistance(int address, double distance) {
        synchronized (nodes) {
            neighborDistances.put(address, distance);
            neighbors.putIfAbsent(address, new Neighbor(address, Double.NaN));
        }
    }
    private double getDistance(Neighbor neighbor) {
        return neighborDistances.get(neighbor.address);
    }

    public void run() {
        timer.schedule(ping, TIMEOUT_PERIOD, PING_PERIOD - TIMEOUT_PERIOD);
        
        while (!shouldTerminate) {
            Message message;
            try {
                message = (Message) datagram.receive();
            } catch (ClassNotFoundException | IOException ex) {
                if (shouldTerminate) logTermination();
                else System.err.println(ex.getMessage());
                if (ex instanceof IOException) continue;
                else break;
            }
            
            if (message instanceof DistanceVector) {
                DistanceVector vector = (DistanceVector) message;
//                log(vector.source, address, "", vector.nodes);
                receivedDistanceVector(vector);
            }
            else if (message instanceof EchoRequest) {
                EchoRequest request = (EchoRequest) message;
//                log(request.source, address, "?");
                receivedEchoRequest(request);
            }
            else if (message instanceof EchoReply) {
                EchoReply reply = (EchoReply) message;
//                log(address, reply.source, "");
                receivedEchoReply(reply);
            }
        }
    }
    
    public void start() {
        new Thread(this, toString()).start();
    }
    public void terminate() {
        shouldTerminate = true;
        timer.cancel();
        datagram.close();
    }
    
    private void receivedEchoRequest(EchoRequest request) {
        synchronized (nodes) {
            Neighbor sender = getOrPut(neighbors, request.source,
                new Neighbor(request.source, Double.NaN));
            sender.lastEcho = System.currentTimeMillis();
            unicast(createEchoReply(), sender);
        }
    }
    
    private void receivedEchoReply(EchoReply reply) {
        synchronized (nodes) {
            Neighbor sender = getOrPut(neighbors, reply.source,
                new Neighbor(reply.source, Double.NaN));
            sender.lastEcho = System.currentTimeMillis();
        }
    }
    
    private void receivedDistanceVector(DistanceVector vector) {
        synchronized (nodes) {
            List<NodeInfo> updated = new ArrayList<>();
            List<NodeInfoBase> inform  = new ArrayList<>();
            
            /* When a disconnected neighbor node becomes reachable
             * again or a new node joins the network, it may send a
             * distance vector before an echo request/reply.
             */
            double reportedDistance = Stream.of(vector.nodes)
                .filter(n -> n.address == address).findFirst().get().distance;
            
            Neighbor sender = neighbors.getOrDefault(vector.source,
                new Neighbor(vector.source, reportedDistance));
            if (!putIfAbsent(neighbors, vector.source, sender)
                    && !Double.isFinite(sender.distance))
                sender.distance = reportedDistance;
            
            NodeInfo _sender = nodes.getOrDefault(vector.source,
                new NodeInfo(vector.source, reportedDistance));
            if (putIfAbsent(nodes, vector.source, _sender))
                updated.add(_sender);
            
            List<NodeInfoBase> updates = partition(update -> {
                NodeInfo node = nodes.get(update.address);
                return node != null && node.via == null;
            }, true, vector.nodes);

            for (NodeInfoBase update : updates) {
                if (update.address == address)
                    // Neighbor distances are measured by pinging.
                    continue;

                NodeInfo node = nodes.get(update.address);
                double newDistance = _sender.distance + update.distance;
                
                if (node == null) {
                    if (Double.isFinite(newDistance)) {
                        nodes.put(update.address, node =
                            new NodeInfo(update.address, newDistance, sender));
                        updated.add(node);
                    }
                } else {
                    if (newDistance < node.distance) {
                        node.via = sender;
                        updateByCheckingDescendants(node, newDistance, updated);
                        updated.add(node);
                    }
                    else if (node.distance != newDistance && node.via == sender
                            && !updated.contains(node)) {
                        updateByCheckingLinks(node, newDistance);
                        updated.add(node);
                    }
                    if (reportedDistance + node.distance < update.distance
                            && node.via != sender)
                        inform.add(update);
                }
            }

            if (!updated.isEmpty() | !inform.isEmpty()) {
                logDistanceVector(vector, updated, inform);
                broadcastDistanceVector(sender,
                    !updated.isEmpty(), !inform.isEmpty());
                filterNodes();
            }
        }
    }
    private void broadcastDistanceVector(
            Neighbor sender, boolean updated, boolean inform) {
        neighbors()
            .filter(n -> (updated && n != sender) || (inform && n == sender))
            .forEach(this::sendDistanceVector);
    }
    
    private final TimerTask ping = new TimerTask() {
        public void run() {
            long echoRequest;
            synchronized (nodes) {
                echoRequest = System.currentTimeMillis();
                multicast(createEchoRequest(), neighbors.values().stream()
                    .filter(neighbor -> Double.isFinite(getDistance(neighbor))));
            }
            sleep(TIMEOUT_PERIOD);

            synchronized (nodes) {
                // Detect unreachable neighbors
                List<NodeInfo> updated = new ArrayList<>();
                for (Iterator<Neighbor> iterator = neighbors.values().iterator();
                        iterator.hasNext();) {
                    Neighbor neighbor = iterator.next();
                    // TODO Round-trip time should be used for distance.
                    neighbor.distance = neighbor.lastEcho < echoRequest
                        ? Double.POSITIVE_INFINITY : getDistance(neighbor);
                    
                    NodeInfo node = nodes.get(neighbor.address);
                    if (node == null) {
                        if (Double.isFinite(neighbor.distance)) {
                            nodes.put(neighbor.address,
                                node = new NodeInfo(neighbor));
                            updated.add(node);
                        }
                    } else if ((node.via == null
                            && neighbor.distance != node.distance)
                            || (node.via != null
                            && neighbor.distance <= node.distance)) {
                        updateByCheckingDescendants(node, neighbor.distance,
                            updated);
                        node.via = null;
                        updated.add(node);
                    }
                    if (!Double.isFinite(neighbor.distance)
                            && echoRequest - neighbor.lastEcho > LINK_LIFE) {
                        logLinkExpiration(neighbor);
                        iterator.remove();
                    }
                }
                
                /* Detecting unreachable neighbors and broadcasting the
                 * distance vector should be atomic in order to avoid the
                 * count-to-infinity problem. No packet loss is assumed.
                 */
                if (!updated.isEmpty()) {
                    logDistanceVector("echo", updated);
                    neighbors().forEach(Node.this::sendDistanceVector);
                    filterNodes();
                }
            }
        }
    };
    
    private Stream<Neighbor> neighbors() { // Responsive neighbors
        return neighbors.values().stream()
            .filter(neighbor -> Double.isFinite(neighbor.distance));
    }
    private void updateByCheckingDescendants(NodeInfo node, double distance,
            List<NodeInfo> updated) {
        Neighbor neighbor = neighbors.get(node.address);
        if (neighbor == null || !Double.isFinite(node.distance)) {
            node.distance = distance;
            return; // If the distance of a neighbor is not finite, it cannot
        }           // have children in the forwarding tree.
        
        double delta = distance - node.distance;
        node.distance += delta;
        nodes.values().stream()
            .filter(n -> n.via == neighbor)
            .forEach(n -> {
                n.via = node.via != null ? node.via : neighbor;
                updateByCheckingLinks(n, n.distance + delta);
                updated.add(n);
            });
    }
    private void updateByCheckingLinks(NodeInfo node, double distance) {
        Neighbor neighbor = neighbors.get(node.address);
        if (neighbor != null && neighbor.distance <= distance) {
            node.distance = neighbor.distance;
            node.via = null;
        }
        else node.distance = distance;
    }
    private void filterNodes() {
        nodes.entrySet().removeIf(e -> !Double.isFinite(e.getValue().distance));
    }
    
    private void unicast(Message message, Neighbor destination) {
        datagram.send(message, destination.address);
    }
    private void multicast(Message message, Stream<Neighbor> destination) {
        datagram.send(message, destination.mapToInt(n -> n.address).toArray());
    }
    private void broadcast(Message message) {
        multicast(message, neighbors());
    }
    
    private void sendDistanceVector(Neighbor destination) {
        unicast(new DistanceVector(address, nodes.values().stream()
            .filter(node -> node.via != destination)
            .map(NodeInfoBase::new).toArray(NodeInfoBase[]::new)), destination);
    }
    private EchoRequest createEchoRequest() {
        return new EchoRequest(address);
    }
    private EchoReply createEchoReply() {
        return new EchoReply(address);
    }
    
    private static synchronized void log(int source, int destination,
            String operation, NodeInfoBase... vector) {
        System.out.format("%s -%s> %s", id(source), operation, id(destination));
        if (vector.length > 0)
            System.out.format(": %s", join("; ", vector));
        System.out.println();
    }
    private void logDistanceVector(String cause, List<NodeInfo> updated) {
        Program.log("%s: %s << %s", this, updated == null
            ? join("; ", nodes.values()) : join("; ", nodes.values().stream()
            .map(n -> String.format(updated.contains(n) ? "*%s" : "%s", n))),
            cause);
    }
    private void logDistanceVector(DistanceVector vector,
            List<NodeInfo> updated, List<NodeInfoBase> inform) {
        Program.log("%s: %s %c%c %s: %s", this,
            join("; ", nodes.values().stream()
                .map(n -> String.format(updated.contains(n) ? "*%s" : "%s", n))),
            !updated.isEmpty() ? '<' : '>', !inform.isEmpty() ? '>' : '<',
            id(vector.source), join("; ", Stream.of(vector.nodes)
                .map(n -> String.format(inform.contains(n) ? "*%s" : "%s", n))));
    }
    private void logLinkExpiration(Neighbor neighbor) {
        Program.log("%s -/-> %s", this, id(neighbor.address));
    }
    private void logTermination() {
        Program.log("%s << terminate", this);
    }
    
    public String toString() {
        return id(address);
    }
    private static String id(int address) {
        return Program.getIdentifier(address);
    }
    
    private static abstract class Message implements Serializable {
        final int source;
        
        public Message(int source) {
            this.source = source;
        }
    }
    
    public static class DistanceVector extends Message {
        final NodeInfoBase[] nodes;
        
        public DistanceVector(int source, NodeInfoBase[] nodes) {
            super(source);
            this.nodes = nodes;
        }
        
        public String toString() {
            return String.format("%s: %s", id(source), join("; ", nodes));
        }
    }
    
    private static class EchoRequest extends Message {
        public EchoRequest(int source) {
            super(source);
        }
    }
    
    private static class EchoReply extends Message {
        public EchoReply(int source) {
            super(source);
        }
    }
    
    public static class NodeInfoBase implements Serializable {
        int address;
        double distance;

        public NodeInfoBase(int address, double distance) {
            this.address = address;
            this.distance = distance;
        }
        public NodeInfoBase(NodeInfoBase info) {
            this(info.address, info.distance);
        }
        
        public String toString() {
            return String.format("%s (%s)", id(address), format(distance));
        }
    }
    
    public static class Neighbor extends NodeInfoBase {
        long lastEcho = System.currentTimeMillis();

        public Neighbor(int address, double distance) {
            super(address, distance);
        }
    }
    
    public static class NodeInfo extends NodeInfoBase {
        Neighbor via;

        public NodeInfo(int address, double distance, Neighbor via) {
            super(address, distance);
            this.via = via;
        }
        public NodeInfo(int address, double distance) {
            this(address, distance, null);
        }
        public NodeInfo(Neighbor info) {
            this(info.address, info.distance);
        }
        
        public String toString() {
            return via == null ? super.toString()
                : String.format("%s > %s (%s)", id(via.address), id(address),
                    format(distance));
        }
    }
    
    private static <K,V> V getOrPut(Map<K, V> map, K key, V value) {
        V v = map.get(key);
        if (v == null)
            map.put(key, v = value);
        return v;
    }
    private static <K,V> boolean putIfAbsent(Map<K, V> map, K key, V value) {
        V v = map.get(key);
        if (v == null)
            map.put(key, value);
        return v == null;
    }
    
    private static <T> List<T> partition(
            Predicate<? super T> predicate, boolean reverse, T... values) {
        return Stream.of(values)
            .collect(Collectors.partitioningBy(
                reverse ? predicate.negate() : predicate))
            .values().stream().flatMap(List::stream)
            .collect(Collectors.toList());
    }
    
    private static String format(double number) {
        return Double.isFinite(number)
            ? (number == (long) number
                ? String.format("%d", (long) number)
                : String.format("%s", number)) 
            : (Double.isInfinite(number) ? "Inf" : "NaN");
    }
    
    @SafeVarargs
    private static <T> String join(String delimiter, T... values) {
        return join(delimiter, Stream.of(values));
    }
    private static <T> String join(String delimiter, Collection<T> collection) {
        return join(delimiter, collection.stream());
    }
    private static <T> String join(String delimiter, Stream<T> stream) {
        return String.join(delimiter, stream.map(String::valueOf)
            .toArray(String[]::new));
    }
    
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            throw new RuntimeException(
                String.format("%s was interrupted unexpectedly.",
                    Thread.currentThread().getName()), ex);
        }
    }
}
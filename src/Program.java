
import java.io.IOException;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import java.awt.Color;
import javax.swing.text.Style;
import javax.swing.text.StyleConstants;

/**
 * @author Halil Selman Atmaca
 *         Burak Gök
 *         Enes Varol
 */
public class Program {
    private static final int BASE_PORT = 1000;
    private static final Map<Integer, String> ids = new HashMap<>();
    private static final Map<String, Node> nodes = new HashMap<>();
    private static final ReentrantLock global = new ReentrantLock(true);
    
    /**
     * @param args
     * src\Links
     * -N:20 -Conn:3,5
     */
    public static void main(String[] args) throws IOException, ParseException {
        Program program = new Program();
        program.initLayout();
        
        try {
            Path path = Paths.get(args[0]);
            List<String> lines = Files.readAllLines(path);
            program.initNodes(lines);
        }
        catch (InvalidPathException e) {
            int N = 10, L = 3, U = 5; // Defaults

            for (String arg : args) {
                int colon = arg.indexOf(':');
                String name = arg.substring(1, colon);
                String value = arg.substring(colon + 1);

                switch (name) {
                    case "N":
                        N = Integer.parseInt(value);
                        break;
                    case "Conn":
                        String[] bounds = value.split(",");
                        L = Integer.parseInt(bounds[0]);
                        U = Integer.parseInt(bounds[1]);
                        break;
                }
            }

            program.initNodes(N, L, U);
        }
    }
    
    public void initNodes(List<String> program)
            throws SocketException, ParseException {
        List<Link> links = new ArrayList<>();
        
        boolean defCompleted = false;
        for (String line : program) {
            if (!defCompleted) {
                if (!line.isBlank()) {
                    String[] tokens = line.split(" ");
                    double distance = Double.parseDouble(tokens[2]);
                    links.add(new Link(tokens[0], tokens[1], distance));
                } else {
                    defCompleted = true;
                    initialize(links);
                    start();
                }
            } else if (!line.isBlank())
                interpret(line);
        }
        
        if (!defCompleted) {
            initialize(links);
            start();
        }
    }
    
    public void initNodes(int N, int L, int U) throws SocketException {
        initialize(N, L, U);
        start();
    }
    
    private void start() {
        nodes.values().forEach(Node::start);
        nodes.values().forEach(Node::broadcastDistanceVector);
    }
    
    private static final String FP = "\\d+(?:\\.\\d+)?";
    private static final Pattern
        FLOW = compile("<<|<>|>>"),
        STYLED = compile("\\*(\\w+(?: > \\w+)? \\((?:%s|Inf)\\))", FP);
    
    public void initLayout() {
        InteractiveShell shell = new InteractiveShell(input -> {
            try {
                String[] lines = input.split("\\n");
                for (String line : lines)
                    if (!line.isBlank())
                        interpret(line);
            } catch (ParseException | SocketException ex) {
                System.err.println(ex.getMessage());
            } catch (RuntimeException ex) {
                System.out.println(ex.getMessage());
            }
        });
        
        Style updateStyle = shell.addStyle("update", null);
        StyleConstants.setForeground(updateStyle, Color.green);
        Style informStyle = shell.addStyle("inform", null);
        StyleConstants.setForeground(informStyle, Color.blue);
        
        shell.setRenderer((text, output) -> {
            Matcher m = FLOW.matcher(text);
            if (m.find()) {
                int[] offset = {0, m.end(), text.length()};
                
                for (int i = 0; i < 2; i++) {
                    String side = text.substring(offset[i], offset[i + 1]);
                    m = STYLED.matcher(side);
                    int last = 0;
                    
                    while (m.find(last)) {
                        if (m.start() > last)
                            output.append(side.substring(last, m.start()), null);
                        output.append(m.group(1),
                            i == 0 ? updateStyle : informStyle);
                        last = m.end();
                    }
                    if (last < side.length())
                        output.append(side.substring(last), null);
                }
            }
            else output.append(text, null);
        });
    }
    
    public static String getIdentifier(int address) {
        return ids.get(address);
    }
    
    public static void log(String format, Object... args) {
        global.lock();
        try {
            System.out.format(format, args);
            System.out.println();
        } finally {
            global.unlock();
        }
    }
    
    private static Pattern compile(String format, Object... args) {
        return Pattern.compile(String.format(format, args));
    }
    private static final Pattern
        LINE     = compile("([^/#]*)(?:(?://|#).*)?"),
        ENV_CMD  = compile("sleep (%s)", FP),
        LINK_CMD = compile("(\\w+) (\\w+) (%s|inf)", FP),
        NODE_CMD = compile("(\\w+) (leave|join((?: \\w+ %s)*))", FP),
        NEIGHBOR = compile(" (\\w+) (%s)", FP);
    
    private void interpret(String line) throws ParseException, SocketException {
        Matcher m = LINE.matcher(line);
        m.matches();
        String command = m.group(1).trim().replaceAll("\\s+", " ");
        if (command.isEmpty()) return;
        
        if ((m = ENV_CMD.matcher(command)).matches()) {
            Node.sleep((long) (Double.parseDouble(m.group(1)) * 1000));
        }
        else if ((m = LINK_CMD.matcher(command)).matches()) {
            Node node1 = nodes.get(m.group(1));
            Node node2 = nodes.get(m.group(2));
            if (node1 == null) raiseException("%s does not exist!", m.group(1));
            if (node2 == null) raiseException("%s does not exist!", m.group(2));
            
            double distance = !m.group(3).equals("inf")
                ? Double.parseDouble(m.group(3)) : Double.POSITIVE_INFINITY;
            link(node1, node2, distance);
        }
        else if ((m = NODE_CMD.matcher(command)).matches()) {
            String id = m.group(1);
            Node node = nodes.get(id);
            if (node != null && !m.group(2).equals("leave"))
                raiseException("%s already exists!", id);
            if (node == null && m.group(2).equals("leave"))
                raiseException("%s does not exist!", id);
            
            if (m.group(2).equals("leave"))
                terminate(node);
            else {
                m = NEIGHBOR.matcher(m.group(3));
                List args = new ArrayList();
                while (m.find()) {
                    Node neighbor = nodes.get(m.group(1));
                    if (neighbor == null)
                        raiseException("%s does not exist!", m.group(1));
                    double distance = Double.parseDouble(m.group(2));
                    args.add(neighbor);
                    args.add(distance);
                }
                instantiate(id, args.toArray());
            }
        } else throw new ParseException(
            String.format("Parsing Exception: %s", line), 0);
    }
    private static void raiseException(String format, Object... args) {
        throw new RuntimeException(String.format(format, args));
    }
    
    private void link(Node node1, Node node2, double distance) {
        System.out.println();
        node1.setNeighborDistance(node2.getAddress(), distance);
        node2.setNeighborDistance(node1.getAddress(), distance);
    }
    
    private void terminate(Node node) {
        System.out.println();
        node.terminate();
        nodes.remove(ids.get(node.getAddress()));
    }
    
    private void instantiate(String id, Object... args)
            throws SocketException {
        System.out.println();
        int address = BASE_PORT + ids.size();
        ids.put(address, id);
        List<Node.Neighbor> neighbors = new ArrayList<>(args.length / 2);
        for (int i = 0; i < args.length; i += 2) {
            Node neighbor = (Node) args[i];
            double distance = ((Number) args[i + 1]).doubleValue();
            neighbor.setNeighborDistance(address, distance);
            neighbors.add(new Node.Neighbor(neighbor.getAddress(), distance));
        }
        Node node = new Node(address, neighbors);
        nodes.put(id, node);
        node.start();
    }
    
    private void initialize(int N, int L, int U) throws SocketException {
        int[] ports = new int[N];
        Arrays.setAll(ports, i -> BASE_PORT + i);
        
        int[] conn = new int[N];
        Arrays.setAll(conn, i -> L + (int)(Math.random() * (U - L + 1)));
        
        int numConn = Arrays.stream(conn).sum();
        if (numConn % 2 == 1) {
            int delta = conn[conn.length - 1] == L ? 1 : -1;
            conn[conn.length - 1] += delta;
            numConn += delta;
        }
        //System.out.println(Arrays.toString(conn));
        
        List<Integer> pairs = new ArrayList<>(numConn);
        for (int i = 0; i < N; i++)
            pairs.addAll(Collections.nCopies(conn[i], i));
        
        Collections.shuffle(pairs);
        
        List<List<Node.Neighbor>> neighbors = new ArrayList<>(N);
        for (int i = 0; i < N; i++)
            neighbors.add(new ArrayList<>(conn[i]));
        
        while (!pairs.isEmpty()) {
            int n1 = pairs.get(pairs.size() - 1);
            int i = pairs.size() - 2;
            while (pairs.get(i) == n1)
                i--;
            int n2 = pairs.get(i);
            
            double distance = Math.random();
            neighbors.get(n1).add(new Node.Neighbor(ports[n2], distance));
            neighbors.get(n2).add(new Node.Neighbor(ports[n1], distance));
            
            pairs.remove(pairs.size() - 1);
            pairs.remove(i);
        }
        
        String[] _ids = IntStream.range(0, N)
            .mapToObj(i -> String.valueOf((char) (65 + i)))
            .toArray(String[]::new);
        for (int i = 0; i < N; i++)
            ids.put(ports[i], _ids[i]);
        for (int i = 0; i < N; i++)
            nodes.put(_ids[i], new Node(ports[i], neighbors.get(i)));
    }
    
    private void initialize(List<Link> links) throws SocketException {
        String[] _ids = links.stream()
            .flatMap(l -> Stream.of(l.node1, l.node2)).distinct().sorted()
            .toArray(String[]::new);
        int N = _ids.length;
        Map<String, Integer> indices = IntStream.range(0, N).boxed()
            .collect(Collectors.toMap(i -> _ids[i], Function.identity()));
        
        int[] ports = new int[N];
        Arrays.setAll(ports, i -> BASE_PORT + i);
        
        List<List<Node.Neighbor>> neighbors = new ArrayList<>(N);
        for (int i = 0; i < N; i++)
            neighbors.add(new ArrayList<>());
        
        for (Link link : links) {
            int n1 = indices.get(link.node1), n2 = indices.get(link.node2);
            neighbors.get(n1).add(new Node.Neighbor(ports[n2], link.distance));
            neighbors.get(n2).add(new Node.Neighbor(ports[n1], link.distance));
        }
        
        for (int i = 0; i < N; i++)
            ids.put(ports[i], _ids[i]);
        for (int i = 0; i < N; i++)
            nodes.put(_ids[i], new Node(ports[i], neighbors.get(i)));
    }
    
    private static class Link {
        String node1, node2;
        double distance;

        public Link(String node1, String node2, double distance) {
            this.node1 = node1;
            this.node2 = node2;
            this.distance = distance;
        }
    }
    
}

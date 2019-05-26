
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;

/**
 * @author Burak Gök
 */
public class SerializedDatagram {
    private final byte[] buffer;
    private final DatagramSocket socket;
    private final DatagramPacket packet;

    public SerializedDatagram(int port, int packetLength) throws SocketException {
        buffer = new byte[packetLength];
        socket = new DatagramSocket(port);
        packet = new DatagramPacket(buffer, buffer.length);
    }

    public Object receive() throws IOException, ClassNotFoundException {
        socket.receive(packet);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(buffer);
             ObjectInputStream ois = new ObjectInputStream(bais)) {
            return ois.readObject();
        }
    }

    public void send(Object message, int... ports) {
        byte[] bytes;
        while (true)
            try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                 ObjectOutputStream oos = new ObjectOutputStream(baos)) {
                oos.writeObject(message);
                oos.flush();
                bytes = baos.toByteArray();
                break;
            } catch (IOException ex) {
                System.out.println(ex.getMessage());
            }
        for (int port : ports)
            while (true)
                try {
                    DatagramPacket p = new DatagramPacket(bytes, bytes.length,
                        InetAddress.getLocalHost(), port);
                    socket.send(p);
                    break;
                } catch (IOException ex) {
                    System.out.println(ex.getMessage());
                }
    }
    
    public void close() {
        socket.close();
    }
    
}

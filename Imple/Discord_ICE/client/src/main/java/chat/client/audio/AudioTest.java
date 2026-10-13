package chat.client.audio;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramSocket;

/**
 * Prueba aislada del audio UDP (sin Ice, sin servidor).
 *
 * Uso: AudioTest <ipRemota> <puertoRemoto> <puertoLocal>
 *   Mismo PC (te oyes a ti mismo):  127.0.0.1 5000 5000
 *   PC A (IP 192.168.1.10):          192.168.1.20 5000 5000
 *   PC B (IP 192.168.1.20):          192.168.1.10 5000 5000
 *
 * Comandos: m = mute/unmute, q = salir
 */
public class AudioTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Uso: AudioTest <ipRemota> <puertoRemoto> <puertoLocal>");
            return;
        }
        String remoteIp = args[0];
        int remotePort = Integer.parseInt(args[1]);
        int localPort = Integer.parseInt(args[2]);

        // try-with-resources: el socket se cierra solo al final
        try (DatagramSocket socket = new DatagramSocket(localPort)) {

            // los dos usan el MISMO socket -> un solo puerto
            AudioReceiver receiver = new AudioReceiver(socket);
            AudioSender sender = new AudioSender(socket);
            sender.addTarget(remoteIp, remotePort);

            receiver.start();
            sender.start();
            System.out.println("[AUDIO] Escuchando en UDP " + socket.getLocalPort()
                    + ", enviando a " + remoteIp + ":" + remotePort);
            System.out.println("[AUDIO] m = mute/unmute, q = salir");

            // este hilo (main) queda leyendo el teclado; el audio corre en los otros dos hilos
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            boolean muted = false;
            String line;
            while ((line = in.readLine()) != null) {
                String cmd = line.trim();
                if (cmd.equals("q")) break;
                if (cmd.equals("m")) {
                    muted = !muted;
                    sender.setMuted(muted);
                    System.out.println(muted ? "[AUDIO] Micrófono silenciado" : "[AUDIO] Micrófono activo");
                }
            }

            sender.stop();
            receiver.stop();
        }
        System.out.println("[AUDIO] Fin de la prueba");
    }
}
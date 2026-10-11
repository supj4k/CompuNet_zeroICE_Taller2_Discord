package chat.client.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
/* resumen de concepto de lo que hace esta clase del receptor
┌──────────────────────────────────────────────┐
│  1. Esperar un paquete UDP  (socket.receive) │
│  2. Mandar esos bytes al parlante (write)    │
│  3. Repetir                                  │
└──────────────────────────────────────────────┘
 */
/**
 * RF-05: recibe paquetes UDP de audio y los reproduce por el parlante.
 * Corre en su propio hilo: start() / stop().
 */
public class AudioReceiver implements Runnable {

    private final DatagramSocket socket;   // compartido con AudioSender (un solo puerto)
    private volatile boolean running;      // volatile: lo cambia un hilo y lo lee otro
    private SourceDataLine speaker;        // el parlante
    private Thread thread;

    public AudioReceiver(DatagramSocket socket) {
        this.socket = socket;
    }

    /** Abre el parlante y arranca el hilo que recibe. */
    public synchronized void start() throws LineUnavailableException, SocketException {
        if (running) return;                                     // ya estaba corriendo

        speaker = AudioSystem.getSourceDataLine(AudioConfig.FORMAT);
        speaker.open(AudioConfig.FORMAT, AudioConfig.FRAME_BYTES * 8);  // colchón ~160 ms (jitter)
        speaker.start();

        socket.setSoTimeout(500);                                // receive() despierta cada 500 ms
        running = true;
        thread = new Thread(this, "audio-receiver");
        thread.setDaemon(true);                                  // no impide que el programa cierre
        thread.start();                                          // esto ejecuta run() en el hilo nuevo
    }

    /** Lo que hace el hilo: recibir y reproducir en bucle. */
    @Override
    public void run() {
        SourceDataLine line = speaker;                           // copia local: stop() pone el campo en null
        byte[] buf = new byte[AudioConfig.FRAME_BYTES * 2];      // un poco más grande por si acaso
        DatagramPacket packet = new DatagramPacket(buf, buf.length);

        while (running) {
            try {
                packet.setLength(buf.length);                    // reiniciar el tamaño antes de cada receive
                socket.receive(packet);                          // BLOQUEA hasta que llegue algo (o 500 ms)
                if (running) {
                    line.write(packet.getData(), 0, packet.getLength());   // al parlante
                }
            } catch (SocketTimeoutException e) {
                // no llegó nada en 500 ms: volvemos a revisar "running"
            } catch (IOException e) {
                if (!running) return;                            // el socket se cerró al colgar
            }
        }
    }

    /** Detiene el hilo y cierra el parlante. */
    public synchronized void stop() {
        running = false;
        if (speaker != null) {
            speaker.stop();
            speaker.flush();      // descarta el audio que quedó en el colchón
            speaker.close();
            speaker = null;
        }
    }
}
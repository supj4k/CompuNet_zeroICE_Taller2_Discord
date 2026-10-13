package chat.client.audio;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
/*
El emisor es el espejo del receptor:

┌───────────────────────────────────────────────────────────┐
│  1. Leer 20 ms del micrófono   (mic.read → 640 bytes)     │
│  2. Mandarlos por UDP a cada destino   (socket.send)      │
│  3. Repetir                                               │
└───────────────────────────────────────────────────────────┘

como funciona el emisor de audio (AudioSender), es como el espejo del receptor:
leer 20 ms del microfono -> mandarlos por UDP a cada destino -> repetir

1. TargetDataLine es el microfono: es lo contrario del SourceDataLine (parlante), de aca
   se SACAN bytes en vez de meterlos. Se abre con el mismo AudioFormat de AudioConfig,
   porque si no coinciden el otro lado lo escucha como ruido.

2. El microfono es el que marca el ritmo: mic.read(frame, 0, 640) se queda bloqueado hasta
   que hay 640 bytes, o sea 20 ms de audio grabado. Por eso no se necesita Thread.sleep,
   el bucle da una vuelta cada 20 ms solito porque asi de rapido el micro produce datos.
   Eso da 50 paquetes por segundo (1000 ms / 20 ms = 50).

3. Varios destinos: en vez de una sola IP se guarda una lista de destinos:
   - llamada 1 a 1 (RF-05, mio): la lista tiene 1 solo destino
   - llamada grupal mesh (RF-06, Emmanuel): tiene N-1 destinos, uno por cada participante
   asi Emmanuel puede reutilizar esta misma clase sin cambiarle nada.
   La lista es CopyOnWriteArrayList porque se modifica desde un hilo (cuando alguien entra
   o sale de la llamada) y se recorre desde otro hilo (el emisor), y esa lista es segura
   para eso sin tener que bloquear.

4. Si falla un envio se ignora: UDP no garantiza que llegue y no tiene sentido reintentar
   un pedazo de voz viejo, se pierde y seguimos. Si se pierde un paquete solo se pierden
   20 ms de voz que casi no se notan; con TCP en cambio todo se frenaria esperando la
   retransmision (esto es lo de la pregunta 1 del cuestionario).

5. Mute: cuando esta silenciado el microfono se sigue leyendo pero no se manda nada.
   Si se dejara de leer, el buffer del micro se llenaria y al quitar el mute el otro
   escucharia audio viejo y atrasado.
 */

/**
 * RF-05/06: captura el micrófono y envía cada bloque de 20 ms por UDP.
 * Puede mandar a VARIOS destinos (1 en llamada 1-1, N-1 en llamada grupal mesh).
 * Corre en su propio hilo: start() / stop().
 */
public class AudioSender implements Runnable {

    private final DatagramSocket socket;                                  // el MISMO del receptor
    private final List<InetSocketAddress> targets = new CopyOnWriteArrayList<>();  // a quién mando
    private volatile boolean running;
    private volatile boolean muted;
    private TargetDataLine mic;                                           // el micrófono
    private Thread thread;

    public AudioSender(DatagramSocket socket) {
        this.socket = socket;
    }

    /** Agrega un destino (IP + puerto UDP del otro cliente). */
    public void addTarget(String host, int port) {
        targets.add(new InetSocketAddress(host, port));
    }

    /** Quita un destino (por ejemplo, alguien sale de la llamada grupal). */
    public void removeTarget(String host, int port) {
        targets.remove(new InetSocketAddress(host, port));
    }

    /** Mute: se sigue leyendo el micrófono, pero no se envía. */
    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    /** Abre el micrófono y arranca el hilo que envía. */
    public synchronized void start() throws LineUnavailableException {
        if (running) return;

        mic = AudioSystem.getTargetDataLine(AudioConfig.FORMAT);
        mic.open(AudioConfig.FORMAT, AudioConfig.FRAME_BYTES * 4);   // buffer interno pequeño = poca latencia
        mic.start();

        running = true;
        thread = new Thread(this, "audio-sender");
        thread.setDaemon(true);
        thread.start();
    }

    /** Lo que hace el hilo: leer del micrófono y enviar en bucle. */
    @Override
    public void run() {
        TargetDataLine line = mic;                          // copia local, igual que en el receptor
        byte[] frame = new byte[AudioConfig.FRAME_BYTES];   // 640 bytes = 20 ms

        while (running) {
            int n = line.read(frame, 0, frame.length);      // BLOQUEA hasta tener 20 ms: marca el ritmo
            if (n <= 0 || muted) continue;                  // nada leído o silenciado: no se envía

            for (InetSocketAddress target : targets) {      // 1 destino (1-1) o varios (grupal)
                try {
                    socket.send(new DatagramPacket(frame, n, target));
                } catch (IOException e) {
                    if (!running) return;                   // se cerró al colgar
                    // si no: UDP, el paquete se pierde y seguimos (no se reintenta)
                }
            }
        }
    }

    /** Detiene el hilo, cierra el micrófono y olvida los destinos. */
    public synchronized void stop() {
        running = false;
        if (mic != null) {
            mic.stop();
            mic.close();       // esto también desbloquea el read() si estaba esperando
            mic = null;
        }
        targets.clear();
    }
}
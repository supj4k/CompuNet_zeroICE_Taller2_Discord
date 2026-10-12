package chat.client;

import ChatApp.ChatMessage;
import ChatApp.ClientCallback;
import ChatApp.FileChunk;
import ChatApp.Participant;
import ChatApp.VoiceEndpoint;
import chat.client.files.FileAssembler;
import com.zeroc.Ice.Current;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Objeto que el servidor invoca de vuelta. Corre en los hilos de Ice, no en el hilo
 * que lee el teclado, así que los mensajes llegan aunque el usuario esté escribiendo.
 * No debe lanzar excepciones ni hacer llamadas bloqueantes al servidor.
 */
public class ClientCallbackI implements ClientCallback {
    private final CallManager callManager; // se crea esta variable para llamar toda lo logica de llamadas
    private final FileAssembler files;
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    public ClientCallbackI(CallManager callManager, FileAssembler files) {
        this.callManager = callManager;
        this.files = files;
    }

    static String time(long epochMillis) {
        return TIME.format(Instant.ofEpochMilli(epochMillis));
    }

    // ---------- RF-01 (JD) ----------
    @Override
    public void onUserJoined(String nickname, Current current) {
        System.out.println("[+] " + nickname + " se conectó");
    }

    @Override
    public void onUserLeft(String nickname, Current current) {
        System.out.println("[-] " + nickname + " se desconectó");
    }

    // ---------- RF-02 (JD) ----------
    @Override
    public void onPrivateMessage(ChatMessage msg, Current current) {
        System.out.println("[" + time(msg.timestamp) + "] (privado) " + msg.sender + ": " + msg.text);
    }

    // ---------- RF-03 (Emmanuel) ----------
    @Override
    public void onRoomMessage(ChatMessage msg, Current current) {
        System.out.println("[" + time(msg.timestamp) + "] #" + msg.room + " " + msg.sender + ": " + msg.text);
    }

    // ---------- RF-04 (Andrés) ----------
    @Override
    public void onFileChunk(String sender, String room, FileChunk chunk, Current current) {
        String origen = room.isEmpty() ? sender : sender + " en #" + room;
        try {
            if (chunk.chunkIndex == 0) {
                System.out.println("[ARCHIVO] Recibiendo '" + chunk.fileName + "' de " + origen
                        + " (" + chunk.totalSize / 1024 + " KB)...");
            }
            java.nio.file.Path done = files.accept(chunk);
            if (done != null) {
                System.out.println("[ARCHIVO] Recibido '" + chunk.fileName + "' de " + origen
                        + " -> " + done.toAbsolutePath());
            }
        } catch (java.io.IOException e) {
            // regla de JD: el callback no lanza excepciones; avisamos y seguimos
            System.out.println("[ARCHIVO] Error recibiendo '" + chunk.fileName + "': " + e.getMessage());
        }
    }

    // ---------- RF-05 (Andrés) ----------
    @Override
    public void onIncomingCall(String callId, String caller, VoiceEndpoint callerEndpoint, Current current) {
        callManager.onIncomingCall(callId,caller,callerEndpoint);
    }

    @Override
    public void onCallAccepted(String callId, VoiceEndpoint calleeEndpoint, Current current) {
       callManager.onCallAccepted(callId,calleeEndpoint);
    }

    @Override
    public void onCallRejected(String callId, String by, Current current) {
        callManager.onCallRejected(callId,by);
    }

    @Override
    public void onCallEnded(String callId, String by, Current current) {
        callManager.onCallEnded(callId,by);
    }

    // ---------- RF-06 (Emmanuel) ----------
    @Override
    public void onParticipantJoined(String room, Participant p, Current current) {
        // TODO RF-06
    }

    @Override
    public void onParticipantLeft(String room, String nickname, Current current) {
        // TODO RF-06
    }
}

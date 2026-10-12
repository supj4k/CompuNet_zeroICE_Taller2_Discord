package chat.server;

import ChatApp.FileChunk;
import ChatApp.NotLoggedIn;
import ChatApp.UserNotFound;
import com.zeroc.Ice.Current;

/**
 * RF-04 (Andrés): transferencia de archivos en el servidor.
 *
 * El servidor NO arma ni guarda los archivos: solo hace de "cartero". Recibe cada chunk
 * (FileChunk de 64 KB) y lo reenvía al destinatario por callback (onFileChunk). Quien
 * rearma el archivo es el cliente que lo recibe (FileAssembler).
 * Así, aunque pase un archivo de 100 MB, el servidor solo tiene en memoria el chunk que va pasando.
 */
public class FileService {

    private final SessionRegistry sessions;

    public FileService(SessionRegistry sessions) {
        this.sessions = sessions;
    }

    /** Reenvía un chunk a un usuario (envío privado: room = ""). */
    public void sendToUser(String nickname, String to, FileChunk chunk, Current current)
            throws NotLoggedIn, UserNotFound {
        SessionRegistry.Session sender = sessions.require(nickname, current);
        String toNick = SessionRegistry.normalize(to);
        SessionRegistry.Session target = sessions.get(toNick);
        if (target == null) {
            throw new UserNotFound("El usuario '" + toNick + "' no existe o no está conectado", toNick);
        }
        if (target.nickname.equals(sender.nickname)) {
            throw new UserNotFound("No puedes enviarte un archivo a ti mismo", toNick);
        }

        // un solo log por archivo (en el primer chunk), no uno por chunk
        if (chunk.chunkIndex == 0) {
            System.out.println("[FILE] " + sender.nickname + " -> " + target.nickname + ": "
                    + chunk.fileName + " (" + chunk.totalSize + " bytes, " + chunk.totalChunks + " chunks)");
        }

        // asíncrono (regla de JD): no se bloquea un hilo del pool esperando al otro cliente
        final String from = sender.nickname;
        sessions.notify(target, cb -> cb.onFileChunkAsync(from, "", chunk));
    }

    // TODO RF-04 + RF-03: sendToRoom(nickname, room, chunk, current)
    //  Necesita del RoomManager de Emmanuel:
    //   1. validar que la sala existe y que el emisor es miembro (RoomNotFound / NotRoomMember)
    //   2. obtener los miembros de la sala
    //   3. reenviar el chunk a cada miembro MENOS al emisor, con room = nombre de la sala
}
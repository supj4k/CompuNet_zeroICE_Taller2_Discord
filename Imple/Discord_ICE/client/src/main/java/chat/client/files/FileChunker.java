package chat.client.files;

import ChatApp.FileChunk;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

/*
RF-04: como funciona el FileChunker (partir el archivo en pedazos)

por que hay que partir el archivo?
Ice tiene un limite de tamano por mensaje: Ice.MessageSizeMax, que por defecto es 1 MB.
Si se manda un archivo de 80 MB en una sola llamada, Ice lo rechaza con una
MemoryLimitException (esto es la pregunta 2 del cuestionario). La solucion es partirlo en
pedazos pequenos (chunks), mandarlos uno por uno y que el otro lado los vuelva a pegar.

ejemplo con foto.png de 150 KB:
  chunk 0: bytes      0 -  65 535  (64 KB)
  chunk 1: bytes 65 536 - 131 071  (64 KB)
  chunk 2: bytes 131 072 - 153 599 (22 KB)  <- el ultimo es mas pequeno

que lleva cada chunk (el FileChunk del contrato .ice):
  transferId  -> un UUID por archivo, si llegan dos archivos al tiempo se sabe que chunk es de cual
  fileName    -> para guardarlo con el nombre original
  totalSize   -> para verificar al final que no falte ni sobre nada ("sin truncamiento" del enunciado)
  chunkIndex  -> en que posicion va este pedazo
  totalChunks -> cuantos pedazos esperar, para saber cuando termino
  data        -> los bytes (sequence<byte> en el .ice, byte[] en Java)

por que 64 KB?
el enunciado dice entre 32 y 64 KB. Se escogio 64 KB porque son menos llamadas (1 MB = 16 chunks
y no 32) y sigue muy lejos del limite de 1 MB. Cada llamada RPC tiene un costo fijo (cabeceras,
ida y vuelta), entonces entre menos llamadas mejor.

decision de diseno: no cargar todo el archivo en memoria
lo facil seria leer el archivo completo a un byte[] gigante y despues partirlo, pero con un
archivo de 500 MB serian 500 MB de RAM. En cambio se lee de a 64 KB y se manda cada chunk apenas
se lee, asi en memoria nunca hay mas de un chunk. Por eso el FileChunker no devuelve una lista:
recibe un "que hacer con cada chunk" (ChunkSender) y la CLI le pasa "mandalo al server".

caso borde: archivo vacio
un archivo de 0 bytes daria 0 chunks y el que recibe se quedaria esperando para siempre,
por eso siempre se manda minimo un chunk aunque vaya vacio.
 */
/**
 * RF-04 (Andrés): parte un archivo en chunks de 64 KB para mandarlos por Ice.
 * Lee de a un chunk y lo entrega apenas lo lee: en memoria nunca hay más de 64 KB,
 * aunque el archivo pese cientos de MB.
 */
public final class FileChunker {

    /** 64 KB por chunk: dentro del rango del enunciado (32-64 KB) y muy por debajo de Ice.MessageSizeMax (1 MB). */
    public static final int CHUNK_SIZE = 64 * 1024;

    /** Qué hacer con cada chunk (en la CLI: mandarlo al server). Puede lanzar excepciones de Ice. */
    @FunctionalInterface
    public interface ChunkSender {
        void send(FileChunk chunk) throws Exception;
    }

    private FileChunker() {}

    /**
     * Lee el archivo, lo parte y le pasa cada chunk a "sender" en orden.
     * @return el transferId que identifica este envío
     */
    public static String split(Path file, ChunkSender sender) throws Exception {
        long totalSize = Files.size(file);
        // al menos 1 chunk: un archivo vacío también tiene que "llegar"
        int totalChunks = (int) Math.max(1, (totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
        String transferId = UUID.randomUUID().toString();
        String fileName = file.getFileName().toString();

        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[CHUNK_SIZE];
            for (int index = 0; index < totalChunks; index++) {
                // readNBytes llena el buffer completo (salvo en el último chunk)
                int n = in.readNBytes(buf, 0, CHUNK_SIZE);
                byte[] data = Arrays.copyOf(buf, n);   // copia del tamaño exacto (el último es más pequeño)
                sender.send(new FileChunk(transferId, fileName, totalSize, index, totalChunks, data));
            }
        }
        return transferId;
    }
}
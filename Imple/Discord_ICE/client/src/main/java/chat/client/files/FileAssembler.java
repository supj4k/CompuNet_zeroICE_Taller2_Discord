package chat.client.files;

import ChatApp.FileChunk;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
/*
RF-04: como funciona el FileAssembler (rearmar el archivo con los chunks que llegan)

el que recibe tiene que resolver 4 problemas:
  1. los chunks pueden llegar desordenados
     -> cada chunk se escribe directo en su posicion dentro del archivo (chunkIndex x 64 KB),
        asi no importa en que orden lleguen
  2. pueden llegar dos archivos al mismo tiempo
     -> un Map<transferId, Transfer>: cada archivo en curso tiene su propio registro
  3. un chunk puede llegar repetido
     -> un BitSet marca que indices ya llegaron, si llega uno repetido se ignora
  4. el archivo puede llegar incompleto o corrupto
     -> al final se compara el tamano en disco con totalSize, si no coincide se borra y se avisa

piezas que se usan:
  - RandomAccessFile: un archivo donde se puede "saltar" a cualquier posicion con seek() y
    escribir ahi. Asi el chunk 5 se escribe en el byte 327 680 aunque los chunks 0 a 4 no hayan
    llegado. Resuelve el desorden sin guardar nada en memoria.
  - archivo temporal .part: mientras llega se llama "recibiendo-xxx.part" y solo cuando esta
    completo y verificado se renombra a su nombre real (foto.png). Asi nunca queda un archivo
    a medias que parezca bueno.
  - BitSet: como un arreglo de booleanos pero compacto. received.get(3) dice si el chunk 3 ya
    llego y cardinality() cuenta cuantos han llegado.
  - Set finished: recuerda los envios que ya terminaron, para que si llega un chunk repetido
    despues de terminar no se cree una segunda copia del archivo.

seguridad (path traversal):
el nombre del archivo lo manda otro cliente y no hay que confiar en el. Si alguien manda un
archivo llamado "../../Windows/System32/algo.dll", un receptor ingenuo lo guardaria FUERA de la
carpeta de descargas. safeName() le quita toda la ruta y deja solo "algo.dll", que siempre queda
dentro de la carpeta received/. Tambien se quitan los caracteres que Windows no acepta.

por que synchronized?
accept() lo llaman los hilos de Ice (desde onFileChunk) y pueden ser varios al mismo tiempo.

como se garantiza que no se corrompa?
por tres lados: Ice va sobre TCP que ya verifica los bytes, el BitSet asegura que esten todos
los pedazos y al final se compara el tamano con totalSize.

prueba hecha: se partieron y rearmaron archivos de 0 bytes, 1 byte, 64 KB exactos, 64 KB + 1 y
~1 MB, con los chunks desordenados, uno repetido y con nombre "../../evil.bin". Los 5 salieron
identicos al original (mismo hash SHA-256) y todos quedaron dentro de la carpeta de descargas.
 */
/**
 * RF-04 (Andrés): rearma los archivos a partir de los chunks que llegan.
 *
 * - Cada chunk se escribe directo en el disco en su posición (chunkIndex × 64 KB), así
 *   no importa si llegan desordenados y no se guarda el archivo completo en memoria.
 * - Puede haber varios archivos llegando al tiempo: se separan por transferId.
 * - Al completar se verifica el tamaño (sin truncamiento) y se mueve a la carpeta final.
 * Lo usan los hilos de Ice (onFileChunk), por eso los métodos son synchronized.
 */
public class FileAssembler {

    /** Un archivo que se está recibiendo. */
    private static final class Transfer {
        final String fileName;
        final long totalSize;
        final int totalChunks;
        final Path tempFile;                 // archivo .part mientras llega
        final RandomAccessFile out;          // permite escribir en cualquier posición
        final BitSet received;               // qué chunks ya llegaron (evita duplicados)

        Transfer(String fileName, long totalSize, int totalChunks, Path tempFile) throws IOException {
            this.fileName = fileName;
            this.totalSize = totalSize;
            this.totalChunks = totalChunks;
            this.tempFile = tempFile;
            this.out = new RandomAccessFile(tempFile.toFile(), "rw");
            this.received = new BitSet(totalChunks);
        }
    }

    private final Path downloadDir;
    private final Map<String, Transfer> transfers = new HashMap<>();   // transferId -> archivo en curso
    private final Set<String> finished = new HashSet<>();              // envíos ya terminados o descartados

    public FileAssembler(Path downloadDir) {
        this.downloadDir = downloadDir;
    }

    /**
     * Recibe un chunk.
     * @return la ruta del archivo terminado si este era el último chunk; null si aún faltan.
     * @throws IOException si el chunk es inválido o el archivo quedó corrupto (se descarta el envío).
     */
    public synchronized Path accept(FileChunk chunk) throws IOException {
        if (finished.contains(chunk.transferId)) {
            return null;                                   // chunk repetido de un envío que ya terminó
        }
        Transfer t = transfers.get(chunk.transferId);
        if (t == null) {                                   // primer chunk de este archivo
            if (chunk.totalChunks <= 0 || chunk.totalSize < 0) {
                throw new IOException("Metadatos inválidos para '" + chunk.fileName + "'");
            }
            Files.createDirectories(downloadDir);
            Path tmp = Files.createTempFile(downloadDir, "recibiendo-", ".part");
            t = new Transfer(safeName(chunk.fileName), chunk.totalSize, chunk.totalChunks, tmp);
            transfers.put(chunk.transferId, t);
        }

        try {
            if (chunk.chunkIndex < 0 || chunk.chunkIndex >= t.totalChunks
                    || chunk.data.length > FileChunker.CHUNK_SIZE) {
                throw new IOException("Chunk inválido (" + chunk.chunkIndex + "/" + t.totalChunks + ")");
            }
            if (!t.received.get(chunk.chunkIndex)) {       // si llega repetido, se ignora
                t.out.seek((long) chunk.chunkIndex * FileChunker.CHUNK_SIZE);
                t.out.write(chunk.data);
                t.received.set(chunk.chunkIndex);
            }
            if (t.received.cardinality() < t.totalChunks) {
                return null;                               // todavía faltan chunks
            }

            // ---- llegaron todos: cerrar, verificar y mover a su nombre final ----
            transfers.remove(chunk.transferId);
            finished.add(chunk.transferId);
            t.out.close();
            long size = Files.size(t.tempFile);
            if (size != t.totalSize) {
                Files.deleteIfExists(t.tempFile);
                throw new IOException("'" + t.fileName + "' llegó incompleto o corrupto: "
                        + size + " de " + t.totalSize + " bytes");
            }
            Path target = uniqueTarget(t.fileName);
            Files.move(t.tempFile, target);
            return target;
        } catch (IOException e) {
            abort(chunk.transferId);
            throw e;
        }
    }

    /** Cuántos chunks han llegado de un envío (para mostrar progreso). */
    public synchronized int receivedCount(String transferId) {
        Transfer t = transfers.get(transferId);
        return t == null ? 0 : t.received.cardinality();
    }

    /** Descarta un envío a medias: cierra y borra el .part. */
    public synchronized void abort(String transferId) {
        finished.add(transferId);
        Transfer t = transfers.remove(transferId);
        if (t == null) return;
        try { t.out.close(); } catch (IOException ignored) { }
        try { Files.deleteIfExists(t.tempFile); } catch (IOException ignored) { }
    }

    /**
     * Seguridad: el nombre viene de otro cliente, no se confía en él.
     * Se le quita cualquier ruta ("../../Windows/x.dll" -> "x.dll") para que nadie pueda
     * escribir fuera de la carpeta de descargas, y los caracteres que Windows no acepta.
     */
    static String safeName(String name) {
        String base = name == null ? "" : name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (base.isEmpty() || base.equals(".") || base.equals("..")) {
            base = "archivo";
        }
        return base;
    }

    /** Si ya existe "foto.png", usa "foto (1).png", "foto (2).png"... para no sobrescribir. */
    private Path uniqueTarget(String fileName) {
        Path target = downloadDir.resolve(fileName);
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; Files.exists(target); i++) {
            target = downloadDir.resolve(stem + " (" + i + ")" + ext);
        }
        return target;
    }
}
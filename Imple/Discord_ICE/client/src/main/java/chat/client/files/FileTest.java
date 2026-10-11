package chat.client.files;

import ChatApp.FileChunk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/**
 * Prueba aislada del RF-04 (sin Ice, sin servidor): parte un archivo real, desordena los
 * chunks a proposito y lo rearma. Si el SHA-256 del original y el rearmado coinciden,
 * el archivo llego identico (sin corrupcion ni truncamiento).
 *
 * Uso: FileTest <rutaDelArchivo>
 */
public class FileTest {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: FileTest <rutaDelArchivo>");
            return;
        }
        Path original = Path.of(args[0]);
        if (!Files.isRegularFile(original)) {
            System.out.println("[FILE] No existe el archivo: " + original.toAbsolutePath());
            return;
        }

        // 1. partir (aqui los guardamos en una lista solo para poder desordenarlos)
        List<FileChunk> chunks = new ArrayList<>();
        String transferId = FileChunker.split(original, chunks::add);
        System.out.println("[FILE] " + original.getFileName() + ": " + Files.size(original) + " bytes -> "
                + chunks.size() + " chunks de hasta " + FileChunker.CHUNK_SIZE / 1024 + " KB (" + transferId + ")");

        // 2. desordenar a proposito: simula que llegan en cualquier orden por la red
        Collections.shuffle(chunks);

        // 3. rearmar
        FileAssembler assembler = new FileAssembler(Path.of("received-test"));
        Path rebuilt = null;
        for (FileChunk c : chunks) {
            Path done = assembler.accept(c);
            if (done != null) rebuilt = done;
        }

        // 4. comparar la "huella digital" de los dos archivos
        String h1 = sha256(original);
        String h2 = sha256(rebuilt);
        System.out.println("[FILE] Rearmado en: " + rebuilt.toAbsolutePath());
        System.out.println("[FILE] SHA-256 original : " + h1);
        System.out.println("[FILE] SHA-256 rearmado : " + h2);
        System.out.println(h1.equals(h2)
                ? "[FILE] OK: el archivo es identico"
                : "[FILE] ERROR: el archivo NO es identico");
    }

    static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }
}
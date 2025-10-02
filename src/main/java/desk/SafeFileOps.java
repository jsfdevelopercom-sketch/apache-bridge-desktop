package desk;

import java.nio.file.*;

public class SafeFileOps {

    public static void atomicReplace(Path source, Path target) throws Exception {
        Path tmp = Files.createTempFile(target.getParent(), "tmp_", ".tmp");
        Files.move(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void ensureDir(Path p) throws Exception {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
    }
}

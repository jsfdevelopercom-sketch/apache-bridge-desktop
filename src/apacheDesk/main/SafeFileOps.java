package main;



import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class SafeFileOps {
    private static final Logger log = LoggerFactory.getLogger(SafeFileOps.class);

    private SafeFileOps() {}

    public static File ensureFileExists(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) {
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                if (!parent.mkdirs()) {
                    throw new IOException("Failed to create parent dirs for: " + path);
                }
            }
            if (!f.createNewFile()) {
                throw new IOException("Could not create: " + path);
            }
        }
        if (!f.canWrite()) {
            throw new IOException("No write permission for: " + path);
        }
        return f;
    }

    public static void backupFile(File original) {
        try {
            if (!original.exists()) return;
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            Path backup = Paths.get(original.getParent(), original.getName() + ".bak." + ts);
            Files.copy(original.toPath(), backup, StandardCopyOption.COPY_ATTRIBUTES);
            log.info("Backup created: {}", backup);
        } catch (Exception e) {
            log.warn("Backup failed (continuing): {}", e.toString());
        }
    }

    public static void atomicReplace(File sourceTmp, File target) throws IOException {
        Path src = sourceTmp.toPath();
        Path tgt = target.toPath();
        Files.move(src, tgt, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void safeCopy(File src, File dst) throws IOException {
        try (FileChannel in = new FileInputStream(src).getChannel();
             FileChannel out = new FileOutputStream(dst).getChannel()) {
            long size = in.size();
            long transferred = 0;
            while (transferred < size) {
                transferred += in.transferTo(transferred, size - transferred, out);
            }
        }
    }
}

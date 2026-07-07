package com.magiccode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks file read operations so that EditFile and WriteFile can enforce
 * read-before-write consistency.
 */
public class FileStateCache {

    private final Map<String, FileEntry> cache = new ConcurrentHashMap<>();

    public record FileEntry(String content, long mtime) {}

    public void record(String absolutePath, String content, long mtime) {
        cache.put(absolutePath, new FileEntry(content, mtime));
    }

    public void update(String absolutePath, String content) {
        long mtime = System.currentTimeMillis();
        try {
            mtime = Files.getLastModifiedTime(Path.of(absolutePath)).toMillis();
        } catch (IOException ignored) {}
        cache.put(absolutePath, new FileEntry(content, mtime));
    }

    public String validate(String absolutePath) {
        FileEntry entry = cache.get(absolutePath);
        if (entry == null) {
            return "Error: file must be read with ReadFile before writing. " +
                   "Read the file first to ensure you have the latest content.";
        }
        try {
            long currentMtime = Files.getLastModifiedTime(Path.of(absolutePath)).toMillis();
            if (currentMtime != entry.mtime) {
                return "Error: file has been modified since it was last read. " +
                       "Re-read the file with ReadFile before writing.";
            }
        } catch (IOException e) {
            return "Error: unable to check file modification time: " + e.getMessage();
        }
        return null;
    }

    public void clear() {
        cache.clear();
    }
}

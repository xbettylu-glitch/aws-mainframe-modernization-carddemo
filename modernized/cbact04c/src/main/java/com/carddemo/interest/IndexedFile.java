package com.carddemo.interest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Stand-in for a VSAM KSDS backed by a text file of fixed-length records (one per line), as in
 * {@code app/data/ASCII}. The file is loaded on OPEN and, if records were rewritten, saved in key
 * order on CLOSE. Operations set a two-character COBOL file status instead of throwing.
 */
final class IndexedFile {

    enum OpenMode { INPUT, I_O }

    static final String OK = "00";
    static final String END_OF_FILE = "10";
    static final String RECORD_NOT_FOUND = "23";
    static final String PERMANENT_ERROR = "30";
    static final String FILE_NOT_FOUND = "35";
    static final String NOT_OPEN = "42";
    static final String READ_NOT_ALLOWED = "47";
    static final String REWRITE_NOT_ALLOWED = "49";

    private final Path path;
    private final int recordLength;
    private final Function<String, String> primaryKey;
    private final Function<String, String> alternateKey;

    private TreeMap<String, String> records;
    private Map<String, String> byAlternateKey;
    private Iterator<String> cursor;
    private OpenMode mode;
    private boolean dirty;
    private String status = OK;

    IndexedFile(Path path, int recordLength, Function<String, String> primaryKey) {
        this(path, recordLength, primaryKey, null);
    }

    IndexedFile(Path path, int recordLength, Function<String, String> primaryKey,
            Function<String, String> alternateKey) {
        this.path = path;
        this.recordLength = recordLength;
        this.primaryKey = primaryKey;
        this.alternateKey = alternateKey;
    }

    String status() {
        return status;
    }

    boolean isOpen() {
        return mode != null;
    }

    void open(OpenMode openMode) {
        if (!Files.isRegularFile(path)) {
            status = FILE_NOT_FOUND;
            return;
        }
        TreeMap<String, String> loaded = new TreeMap<>();
        Map<String, String> alternate = new HashMap<>();
        try {
            for (String raw : readRecords(path, recordLength)) {
                if (loaded.putIfAbsent(primaryKey.apply(raw), raw) != null) {
                    throw new IllegalStateException("duplicate key '" + primaryKey.apply(raw) + "' in " + path);
                }
                if (alternateKey != null) {
                    // Alternate index without duplicates: the first record for a key wins.
                    alternate.putIfAbsent(alternateKey.apply(raw), primaryKey.apply(raw));
                }
            }
        } catch (IOException e) {
            status = PERMANENT_ERROR;
            return;
        }
        records = loaded;
        byAlternateKey = alternate;
        cursor = new ArrayList<>(records.values()).iterator();
        mode = openMode;
        dirty = false;
        status = OK;
    }

    /** Sequential {@code READ ... NEXT}; returns {@code null} at end of file (status 10). */
    String readNext() {
        if (!isOpen()) {
            status = READ_NOT_ALLOWED;
            return null;
        }
        if (!cursor.hasNext()) {
            status = END_OF_FILE;
            return null;
        }
        status = OK;
        return cursor.next();
    }

    /** Random {@code READ} by primary key; returns {@code null} if not found (status 23). */
    String read(String key) {
        if (!isOpen()) {
            status = READ_NOT_ALLOWED;
            return null;
        }
        String raw = records.get(key);
        status = raw == null ? RECORD_NOT_FOUND : OK;
        return raw;
    }

    /** Random {@code READ ... KEY IS} alternate key. */
    String readByAlternateKey(String key) {
        if (!isOpen()) {
            status = READ_NOT_ALLOWED;
            return null;
        }
        String primary = byAlternateKey.get(key);
        return read(primary == null ? "\0" : primary);
    }

    /** {@code REWRITE} of an existing record, located by the primary key inside it. */
    void rewrite(String raw) {
        if (mode != OpenMode.I_O) {
            status = REWRITE_NOT_ALLOWED;
            return;
        }
        String key = primaryKey.apply(raw);
        if (!records.containsKey(key)) {
            status = RECORD_NOT_FOUND;
            return;
        }
        records.put(key, raw);
        dirty = true;
        status = OK;
    }

    void close() {
        if (!isOpen()) {
            status = NOT_OPEN;
            return;
        }
        mode = null;
        if (dirty) {
            try {
                writeRecords(path, records.values());
            } catch (IOException e) {
                status = PERMANENT_ERROR;
                return;
            }
        }
        status = OK;
    }

    static List<String> readRecords(Path file, int recordLength) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        List<String> out = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = content.indexOf('\n', start);
            if (end < 0) {
                end = content.length();
            }
            String line = content.substring(start, end);
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.length() < recordLength) {
                line = line + " ".repeat(recordLength - line.length());
            } else if (line.length() > recordLength) {
                line = line.substring(0, recordLength);
            }
            out.add(line);
            start = end + 1;
        }
        return out;
    }

    private static void writeRecords(Path file, Iterable<String> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (String row : rows) {
            sb.append(row).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}

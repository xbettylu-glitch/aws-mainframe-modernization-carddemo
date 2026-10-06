package com.carddemo.interest;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Fixed-length sequential output file (RECFM=F): records are written back to back. */
final class SequentialOutputFile {

    private final Path path;
    private OutputStream out;
    private String status = IndexedFile.OK;

    SequentialOutputFile(Path path) {
        this.path = path;
    }

    String status() {
        return status;
    }

    boolean isOpen() {
        return out != null;
    }

    void open() {
        try {
            out = new BufferedOutputStream(Files.newOutputStream(path));
            status = IndexedFile.OK;
        } catch (IOException e) {
            status = IndexedFile.PERMANENT_ERROR;
        }
    }

    void write(String record) {
        if (out == null) {
            status = "48";
            return;
        }
        try {
            out.write(record.getBytes(StandardCharsets.ISO_8859_1));
            status = IndexedFile.OK;
        } catch (IOException e) {
            status = IndexedFile.PERMANENT_ERROR;
        }
    }

    void close() {
        if (out == null) {
            status = IndexedFile.NOT_OPEN;
            return;
        }
        try {
            out.close();
            status = IndexedFile.OK;
        } catch (IOException e) {
            status = IndexedFile.PERMANENT_ERROR;
        } finally {
            out = null;
        }
    }
}

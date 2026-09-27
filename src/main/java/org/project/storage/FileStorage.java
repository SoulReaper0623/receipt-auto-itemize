package org.project.storage;

/** Where uploaded receipt files live (local disk today, e.g. S3 later). */
public interface FileStorage {

    /** Stores the bytes under a server-generated key and returns that key. */
    String save(String key, byte[] content);

    byte[] load(String key);
}

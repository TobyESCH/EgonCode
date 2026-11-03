package dev.egoncode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.*;

final class Store {
    private final Path home;
    Store() { this(Path.of(System.getProperty("user.home"), ".egoncode")); }
    Store(Path home) { this.home = home; }
    Path home() { return home; }

    Map<String,Object> read(String name) throws IOException {
        Path file = home.resolve(name);
        if (!Files.exists(file)) return new LinkedHashMap<>();
        return new LinkedHashMap<>(Json.map(Json.parse(Files.readString(file))));
    }

    void write(String name, Object value, boolean secret) throws IOException {
        Path file = home.resolve(name);
        Files.createDirectories(file.getParent());
        Path temp = Files.createTempFile(file.getParent(), ".egon-", ".tmp");
        try {
            Files.writeString(temp, Json.write(value), StandardCharsets.UTF_8);
            if (secret) ownerOnly(temp);
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            if (secret) ownerOnly(file);
        } finally { Files.deleteIfExists(temp); }
    }

    private void ownerOnly(Path file) {
        try { Files.setPosixFilePermissions(file, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
        catch (UnsupportedOperationException | IOException ignored) { /* Windows ACLs are inherited from user profile. */ }
    }

    String projectKey(Path root) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(root.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 12);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

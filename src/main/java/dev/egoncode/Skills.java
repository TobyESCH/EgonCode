package dev.egoncode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

final class Skills {
    private final Path root;
    private final Store store;
    Skills(Path root, Store store) { this.root=root; this.store=store; }
    Map<String,Path> available() throws IOException {
        Map<String,Path> found=new TreeMap<>();
        for (Path parent : List.of(store.home().resolve("skills"),root.resolve(".claude/skills"),
                root.resolve(".agents/skills"),root.resolve(".egoncode/skills"))) {
            if (!Files.isDirectory(parent)) continue;
            try (DirectoryStream<Path> entries=Files.newDirectoryStream(parent)) {
                for (Path folder : entries) {
                    Path file=folder.resolve("SKILL.md");
                    if (Files.isRegularFile(file)) found.put(folder.getFileName().toString(),file);
                }
            }
        }
        return found;
    }
    String load(String name) throws IOException {
        Path file=available().get(name);
        if (file==null) throw new IOException("Skill not found: " + name);
        if (Files.size(file)>100_000) throw new IOException("Skill exceeds 100 KB");
        return Files.readString(file);
    }
    String names() throws IOException { return String.join(", ",available().keySet()); }
    String command(String name, String arguments) throws IOException {
        if (!name.matches("[a-zA-Z0-9_-]+")) return null;
        for (Path file : List.of(root.resolve(".egoncode/commands/"+name+".md"),store.home().resolve("commands/"+name+".md"))) {
            if (Files.isRegularFile(file) && Files.size(file)<100_000) return Files.readString(file).replace("$ARGUMENTS",arguments);
        }
        return null;
    }
}

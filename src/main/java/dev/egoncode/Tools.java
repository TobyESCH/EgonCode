package dev.egoncode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class Tools {
    enum Mode { PLAN, EDIT, AUTO }
    interface Approval { boolean allow(String description); }
    private final Path root;
    private final Approval approval;
    private final Skills skills;
    private final Store store;
    private Mode mode=Mode.EDIT;
    private static final int MAX_OUTPUT = 30_000;
    private static final Set<String> SKIP = Set.of(".git","node_modules","build","dist","target",".next",".egoncode");

    Tools(Path root, Approval approval) throws IOException { this(root,approval,new Store()); }
    Tools(Path root, Approval approval, Store store) throws IOException {
        this.root = root.toRealPath(); this.approval = approval; this.store=store; this.skills = new Skills(this.root,store);
    }
    void mode(Mode next) { mode=Objects.requireNonNull(next); }
    Mode mode() { return mode; }
    void planMode(boolean enabled) { mode=enabled?Mode.PLAN:Mode.EDIT; }
    boolean planMode() { return mode==Mode.PLAN; }
    boolean autoMode() { return mode==Mode.AUTO; }
    Path root() { return root; }
    String gitDiff() throws IOException, InterruptedException { return command("git diff --no-ext-diff -- .",false); }

    static Map<String,Object> function(String name, String description, String... fields) {
        Map<String,Object> properties = new LinkedHashMap<>();
        for (String field : fields) properties.put(field, Json.obj("type","string"));
        return Json.obj("type","function","name",name,"description",description,"strict",true,
                "parameters",Json.obj("type","object","properties",properties,"required",Arrays.asList(fields),"additionalProperties",false));
    }
    List<Map<String,Object>> definitions() {
        List<Map<String,Object>> result = new ArrayList<>();
        result.add(function("list_files","List project files under a directory. path may be .", "path"));
        result.add(function("read_file","Read a UTF-8 project file. path is relative to project root.", "path"));
        result.add(function("search","Regex search in project text files; returns matching lines and paths.", "pattern"));
        result.add(function("load_skill","Load instructions from an available SKILL.md by name.", "name"));
        result.add(function("git_status","Show current Git status and diff summary."));
        if (!planMode()) {
            result.add(function("write_file","Create or replace a project text file; auto mode approves project edits.", "path","content"));
            result.add(function("replace_in_file","Replace one exact unique text occurrence in a file; auto mode approves project edits.", "path","old_text","new_text"));
            result.add(function("run_command","Run a shell command in the project root; auto mode approves common checks, other commands ask. 60-second timeout.", "command"));
        }
        return result;
    }

    String execute(String rawName, Map<String,Object> args, Session session) {
        String name = rawName.contains(".") ? rawName.substring(rawName.lastIndexOf('.') + 1) : rawName;
        try {
            return switch (name) {
                case "list_files" -> list(resolve(Json.get(args,"path")));
                case "read_file" -> read(resolve(Json.get(args,"path")));
                case "search" -> search(Json.get(args,"pattern"));
                case "load_skill" -> skills.load(Json.get(args,"name"));
                case "git_status" -> command("git status --short", false) + "\n" + command("git diff --stat", false);
                case "write_file" -> write(resolve(Json.get(args,"path")), Json.get(args,"content"), session);
                case "replace_in_file" -> replace(resolve(Json.get(args,"path")), Json.get(args,"old_text"), Json.get(args,"new_text"), session);
                case "run_command" -> run(Json.get(args,"command"));
                default -> "Unknown tool: " + name;
            };
        } catch (Exception e) { return "Tool error: " + e.getMessage(); }
    }

    private Path resolve(String value) throws IOException {
        if (value.isBlank()) value = ".";
        Path target = root.resolve(value).normalize();
        if (!target.startsWith(root)) throw new IOException("Path is outside project");
        Path existing = target;
        while (!Files.exists(existing)) existing = existing.getParent();
        if (!existing.toRealPath().startsWith(root)) throw new IOException("Symlink leaves project");
        Path file = target.getFileName();
        if (file != null && (file.toString().equals(".env") || file.toString().startsWith(".env.")))
            throw new IOException("Environment files are protected");
        return target;
    }
    private String list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) throw new IOException("Not a directory");
        StringBuilder out = new StringBuilder();
        try (Stream<Path> stream = Files.walk(directory, 3)) {
            stream.filter(p -> !p.equals(directory)).filter(p -> !skip(p)).limit(300)
                    .forEach(p -> out.append(root.relativize(p)).append(Files.isDirectory(p) ? "/" : "").append('\n'));
        }
        return out.toString();
    }
    private boolean skip(Path path) {
        for (Path part : root.relativize(path)) if (SKIP.contains(part.toString())) return true;
        return false;
    }
    private String read(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Not a regular file");
        if (Files.size(file) > 200_000) throw new IOException("File exceeds 200 KB limit");
        byte[] bytes = Files.readAllBytes(file);
        for (byte b : bytes) if (b == 0) throw new IOException("Binary file");
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private String search(String expression) throws IOException {
        Pattern pattern = Pattern.compile(expression);
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.walk(root, 8)) {
            for (Path p : files.filter(Files::isRegularFile).filter(p -> !Files.isSymbolicLink(p)).filter(p -> !skip(p)).toList()) {
                if (Files.size(p) > 200_000) continue;
                List<String> lines;
                try { lines = Files.readAllLines(p); } catch (Exception ignored) { continue; }
                for (int i=0; i<lines.size(); i++) if (pattern.matcher(lines.get(i)).find()) {
                    out.append(root.relativize(p)).append(':').append(i+1).append(':').append(lines.get(i)).append('\n');
                    if (out.length() > MAX_OUTPUT) return out.substring(0,MAX_OUTPUT) + "\n[truncated]";
                }
            }
        }
        return out.toString();
    }
    private String write(Path file, String content, Session session) throws IOException {
        if (planMode()) return "Denied: plan mode";
        ensureWritable(file);
        String before=Files.exists(file)?read(file):"";
        String diff="[diff] " + root.relativize(file) + "\n" + Diff.preview(before,content);
        if (!approved("write:" + file, "Write " + root.relativize(file) + "?\n" + diff)) return "Denied by user";
        checkpoint(file, session);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
        session.event("write", root.relativize(file).toString());
        return "Wrote " + root.relativize(file) + "\n" + diff;
    }
    private String replace(Path file, String oldText, String newText, Session session) throws IOException {
        if (planMode()) return "Denied: plan mode";
        ensureWritable(file);
        if (oldText.isEmpty()) return "Tool error: old_text must not be empty";
        String content = read(file);
        int first = content.indexOf(oldText);
        if (first < 0 || content.indexOf(oldText,first+1) >= 0) return "Tool error: old_text must occur exactly once";
        String updated=content.substring(0,first) + newText + content.substring(first+oldText.length());
        String diff="[diff] " + root.relativize(file) + "\n" + Diff.preview(content,updated);
        if (!approved("write:" + file, "Replace text in " + root.relativize(file) + "?\n" + diff)) return "Denied by user";
        checkpoint(file, session);
        Files.writeString(file, updated, StandardCharsets.UTF_8);
        session.event("edit", root.relativize(file).toString());
        return "Updated " + root.relativize(file) + "\n" + diff;
    }
    private void checkpoint(Path file, Session session) throws IOException {
        if (!Files.exists(file)) return;
        Path backup = store.home().resolve("checkpoints").resolve(store.projectKey(root)).resolve(session.id).resolve(root.relativize(file));
        Files.createDirectories(backup.getParent());
        if (!Files.exists(backup)) Files.copy(file, backup);
    }
    private String run(String shell) throws IOException, InterruptedException {
        if (planMode()) return "Denied: plan mode";
        if (shell.isBlank()) return "Tool error: empty command";
        if (!approved("command:" + shell, "Run command: " + shell + " ?")) return "Denied by user";
        return command(shell, true);
    }
    private String command(String shell, boolean limit) throws IOException, InterruptedException {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder pb = windows ? new ProcessBuilder("cmd.exe","/c",shell) : new ProcessBuilder("sh","-c",shell);
        pb.directory(root.toFile()); pb.redirectErrorStream(true);
        for (String secret : List.of("OPENAI_API_KEY","ANTHROPIC_API_KEY","EGON_LOCAL_API_KEY")) pb.environment().remove(secret);
        Path outputFile = Files.createTempFile("egon-command-", ".log");
        try {
            pb.redirectOutput(outputFile.toFile());
            Process process = pb.start();
            boolean done = process.waitFor(60, TimeUnit.SECONDS);
            if (!done) { process.destroyForcibly(); return "Command timed out after 60 seconds"; }
            byte[] bytes;
            try (InputStream source = Files.newInputStream(outputFile)) { bytes = source.readNBytes(MAX_OUTPUT); }
            String output = new String(bytes, StandardCharsets.UTF_8);
            return "exit=" + process.exitValue() + "\n" + output + (Files.size(outputFile) > bytes.length ? "\n[truncated]" : "");
        } finally { Files.deleteIfExists(outputFile); }
    }
    private boolean approved(String key, String description) {
        try {
            Map<String,Object> policy=store.read("permissions.json");
            String kind=key.startsWith("command:")?"command":"write";
            String rule=Json.get(policy,kind);
            if (rule.equals("deny")) return false;
            if (rule.equals("allow")) return true;
        } catch (IOException ignored) { /* Fail closed to an approval prompt. */ }
        if (autoMode() && (key.startsWith("write:") ||
                key.startsWith("command:") && autoCommand(key.substring("command:".length())))) {
            System.out.println("[auto] Approved " + (key.startsWith("write:")?"edit " + root.relativize(Path.of(key.substring(6))):
                    "command " + key.substring(8)));
            return true;
        }
        return approval.allow(description);
    }
    private void ensureWritable(Path file) throws IOException {
        for (Path part : root.relativize(file))
            if (part.toString().equals(".git") || part.toString().equals(".egoncode"))
                throw new IOException("Protected project metadata path");
    }
    static boolean autoCommand(String command) {
        String clean=command.trim().replaceAll("\\s+"," ");
        return clean.matches("pwd|ls|ls -la|git status|git status --short|git diff|git diff --stat|git diff --check|" +
                "npm test|npm run (test|build|lint|typecheck)|pnpm (test|build|lint|typecheck)|" +
                "pnpm run (test|build|lint|typecheck)|yarn (test|build|lint|typecheck)|" +
                "cargo (test|check)|go test \\./\\.\\.\\.|pytest|python -m pytest|mvn test|gradle test|\\./gradlew test|dotnet test");
    }
}

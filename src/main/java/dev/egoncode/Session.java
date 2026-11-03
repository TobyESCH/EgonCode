package dev.egoncode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

final class Session {
    final String id;
    final String project;
    String provider;
    String model;
    final List<Map<String,Object>> history = new ArrayList<>();
    final List<Map<String,Object>> events = new CopyOnWriteArrayList<>();

    Session(String id, Path root, String provider, String model) {
        this.id = id; this.project = root.toAbsolutePath().normalize().toString();
        this.provider = provider; this.model = model;
    }

    static Session create(Path root, String provider, String model) {
        return new Session(UUID.randomUUID().toString(), root, provider, model);
    }

    @SuppressWarnings("unchecked")
    static Session load(Store store, String id, Path root) throws IOException {
        Map<String,Object> data = store.read("sessions/" + id + ".json");
        if (data.isEmpty()) throw new IOException("Session not found: " + id);
        if (!root.toAbsolutePath().normalize().toString().equals(Json.get(data, "project")))
            throw new IOException("Session belongs to another project");
        Session session = new Session(id, root, Json.get(data,"provider"), Json.get(data,"model"));
        for (Object item : Json.list(data.get("history"))) session.history.add(new LinkedHashMap<>(Json.map(item)));
        for (Object item : Json.list(data.get("events"))) session.events.add(new LinkedHashMap<>(Json.map(item)));
        return session;
    }

    void event(String type, Object detail) {
        events.add(Json.obj("at", Instant.now().toString(), "type", type, "detail", detail));
    }

    void save(Store store) throws IOException {
        store.write("sessions/" + id + ".json", Json.obj("id",id,"project",project,"provider",provider,
                "model",model,"history",history,"events",events), false);
    }
}

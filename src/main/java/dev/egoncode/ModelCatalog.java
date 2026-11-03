package dev.egoncode;

import java.net.URI;
import java.net.http.*;
import java.util.*;

final class ModelCatalog {
    record Model(String id, String name) {}
    private final ChatGptAuth auth;
    ModelCatalog(ChatGptAuth auth) { this.auth=auth; }

    List<Model> list(String provider) throws Exception {
        if (provider.equals("chatgpt")) {
            List<Model> result=new ArrayList<>();
            for (Map<String,Object> item : auth.models()) result.add(new Model(Json.get(item,"slug"),Json.get(item,"display_name")));
            return result;
        }
        String base; String key; String header;
        switch (provider) {
            case "openai" -> { base="https://api.openai.com/v1"; key=required("OPENAI_API_KEY"); header="Authorization"; }
            case "anthropic" -> { base="https://api.anthropic.com/v1"; key=required("ANTHROPIC_API_KEY"); header="x-api-key"; }
            case "local" -> { base=required("EGON_LOCAL_URL").replaceAll("/+$",""); key=System.getenv().getOrDefault("EGON_LOCAL_API_KEY",""); header="Authorization"; }
            default -> throw new IllegalArgumentException("Unknown provider: " + provider);
        }
        HttpRequest.Builder request=HttpRequest.newBuilder(URI.create(base+"/models")).GET();
        if (!key.isBlank()) request.header(header,header.equals("Authorization")?"Bearer "+key:key);
        if (provider.equals("anthropic")) request.header("anthropic-version","2023-06-01");
        HttpResponse<String> response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        if (response.statusCode()/100!=2) throw new IllegalStateException("Model list failed: HTTP "+response.statusCode());
        List<Model> result=new ArrayList<>();
        for (Object raw : Json.list(Json.map(Json.parse(response.body())).get("data"))) {
            Map<String,Object> item=Json.map(raw);
            String id=Json.get(item,"id");
            if (id.isBlank()) continue;
            if (provider.equals("openai") && !(id.startsWith("gpt-") || id.startsWith("o"))) continue;
            result.add(new Model(id,Json.get(item,"display_name").isBlank()?id:Json.get(item,"display_name")));
        }
        return result;
    }

    Model recommended(String provider, List<Model> models) {
        if (models.isEmpty()) return null;
        Model best=models.getFirst();
        for (Model model : models) if (score(provider,model.id())>score(provider,best.id())) best=model;
        return best;
    }
    private int score(String provider, String id) {
        String name=id.toLowerCase(Locale.ROOT);
        if (provider.equals("anthropic")) {
            if (name.contains("sonnet")) return 100;
            if (name.contains("opus")) return 70;
            if (name.contains("haiku")) return 50;
        } else {
            if (name.equals("gpt-6.1-sol")) return 120;
            if (name.contains("sol")) return 110;
            if (name.contains("codex")) return 100;
            if (name.contains("astra")) return 90;
            if (name.contains("luna")) return 60;
        }
        return 1;
    }
    private static String required(String key) {
        String value=System.getenv(key);
        if (value==null || value.isBlank()) throw new IllegalStateException("Set "+key+" to list models");
        return value;
    }
}

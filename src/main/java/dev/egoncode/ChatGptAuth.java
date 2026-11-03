package dev.egoncode;

import com.sun.net.httpserver.HttpServer;
import java.math.BigInteger;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Sign in with ChatGPT public-client flow; no Codex credential reuse. */
final class ChatGptAuth {
    private static final String AUTH = "https://auth.openai.com";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String SCOPE = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final Store store;
    private final HttpClient http = HttpClient.newHttpClient();
    ChatGptAuth(Store store) { this.store = store; }

    Map<String,Object> active() throws Exception {
        Map<String,Object> data = store.read("auth.json");
        String id = Json.get(data,"active");
        Map<String,Object> account = Json.map(Json.map(data.get("accounts")).get(id));
        if (account.isEmpty()) throw new IllegalStateException("No ChatGPT account. Run /login first.");
        if (!Json.get(account,"scope").contains("chatgpt.tokens.use.direct"))
            throw new IllegalStateException("ChatGPT plan usage was not authorized. Run /login again.");
        if (Json.number(account.get("expires_at"),0) - 90 < Instant.now().getEpochSecond()) account = refresh(id, account);
        return account;
    }

    synchronized Map<String,Object> refresh(String id, Map<String,Object> previous) throws Exception {
        Map<String,Object> all = store.read("auth.json");
        Map<String,Object> current = Json.map(Json.map(all.get("accounts")).get(id));
        if (Json.number(current.get("expires_at"),0) - 90 >= Instant.now().getEpochSecond()) return current;
        Map<String,String> params = Map.of("grant_type","refresh_token","client_id",id,
                "refresh_token",Json.get(current,"refresh_token"),"resource",RESOURCE);
        Map<String,Object> tokens = form(AUTH + "/api/accounts/oauth/token",params);
        current.putAll(tokens);
        current.put("expires_at",Instant.now().getEpochSecond() + Json.number(tokens.get("expires_in"),3600));
        if (!tokens.containsKey("refresh_token")) current.put("refresh_token",previous.get("refresh_token"));
        store.write("auth.json",all,true);
        return current;
    }

    void login(boolean newAccount) throws Exception {
        Map<String,Object> all = store.read("auth.json");
        String host = Json.get(all,"host_id");
        if (host.isBlank()) { host = "urn:uuid:" + UUID.randomUUID(); all.put("host_id",host); store.write("auth.json",all,true); }
        String savedClient = newAccount ? "" : Json.get(all,"active");
        String initialClient = savedClient.isBlank() ? "dynamic_agent_client" : savedClient;
        String state = random(); String nonce = random(); String verifier = random() + random();
        String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
        CompletableFuture<Map<String,String>> callback = new CompletableFuture<>();
        server.createContext("/auth/callback",exchange -> {
            try {
                Map<String,String> query = query(exchange.getRequestURI().getRawQuery());
                String response = "EgonCode sign-in received. Return to your terminal.";
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","text/plain; charset=utf-8");
                exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes);
                callback.complete(query);
            } catch (Exception e) { callback.completeExceptionally(e); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            Map<String,String> params = new LinkedHashMap<>();
            params.put("client_id",initialClient); params.put("ext_agent_host_id",host);
            if (savedClient.isBlank()) params.put("agent_name_hint","EgonCode");
            params.put("response_type","code"); params.put("redirect_uri",redirect); params.put("scope",SCOPE);
            params.put("resource",RESOURCE); params.put("state",state); params.put("nonce",nonce);
            params.put("code_challenge_method","S256"); params.put("code_challenge",challenge);
            String url = AUTH + "/api/accounts/authorize?" + encode(params);
            System.out.println("Open this URL to continue with ChatGPT:\n" + url);
            if (!Browser.open(url)) System.out.println("Press Ctrl+O in the dashboard or copy the URL to open sign-in.");
            Map<String,String> response = callback.get(5,TimeUnit.MINUTES);
            if (!state.equals(response.get("state"))) throw new SecurityException("OAuth state mismatch");
            if (response.containsKey("error")) throw new IllegalStateException("Sign-in declined: " + response.get("error"));
            String issued = savedClient.isBlank() ? response.get("client_id") : savedClient;
            if (issued == null || issued.isBlank() || issued.equals("dynamic_agent_client"))
                throw new IllegalStateException("Sign-in did not issue a client ID");
            if (!savedClient.isBlank() && response.containsKey("client_id") && !savedClient.equals(response.get("client_id")))
                throw new SecurityException("Client ID changed during sign-in");
            Map<String,Object> tokens = form(AUTH + "/api/accounts/oauth/token",Map.of(
                    "grant_type","authorization_code","client_id",issued,"code",response.get("code"),
                    "code_verifier",verifier,"redirect_uri",redirect,"resource",RESOURCE));
            Map<String,Object> claims = verifyIdToken(Json.get(tokens,"id_token"),issued,nonce);
            if (!Json.get(tokens,"scope").contains("chatgpt.tokens.use.direct"))
                throw new IllegalStateException("Plan usage permission was not granted");
            Map<String,Object> accounts = new LinkedHashMap<>(Json.map(all.get("accounts")));
            Map<String,Object> old = Json.map(accounts.get(issued));
            if (!old.isEmpty() && !Json.get(old,"subject").equals(Json.get(claims,"sub")))
                throw new SecurityException("Different account returned for saved registration");
            Map<String,Object> record = new LinkedHashMap<>(tokens);
            record.put("client_id",issued); record.put("subject",claims.get("sub"));
            record.put("email",claims.getOrDefault("email",""));
            record.put("expires_at",Instant.now().getEpochSecond() + Json.number(tokens.get("expires_in"),3600));
            accounts.put(issued,record); all.put("accounts",accounts); all.put("active",issued);
            store.write("auth.json",all,true);
            System.out.println("Connected ChatGPT account " + Json.get(record,"email"));
        } finally { server.stop(0); }
    }

    List<Map<String,Object>> models() throws Exception {
        String token = Json.get(active(),"access_token");
        HttpRequest request = HttpRequest.newBuilder(URI.create(RESOURCE + "/models"))
                .header("Authorization","Bearer " + token).GET().build();
        HttpResponse<String> response = http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("Model list failed: HTTP " + response.statusCode());
        List<Map<String,Object>> models = new ArrayList<>();
        for (Object item : Json.list(Json.map(Json.parse(response.body())).get("models"))) {
            Map<String,Object> model = Json.map(item);
            if (Json.get(model,"visibility").equals("list")) models.add(model);
        }
        return models;
    }

    void accounts() throws Exception {
        Map<String,Object> all=store.read("auth.json");
        String selected=Json.get(all,"active");
        for (var entry : Json.map(all.get("accounts")).entrySet()) {
            Map<String,Object> account=Json.map(entry.getValue());
            System.out.println((entry.getKey().equals(selected)?"* ":"  ") + entry.getKey() + " " + Json.get(account,"email"));
        }
    }
    void select(String clientId) throws Exception {
        Map<String,Object> all=store.read("auth.json");
        if (!Json.map(all.get("accounts")).containsKey(clientId)) throw new IllegalArgumentException("Unknown account ID");
        all.put("active",clientId); store.write("auth.json",all,true);
        System.out.println("Selected " + clientId + ". Use /login if its session expired or was signed out.");
    }
    void logout() throws Exception {
        Map<String,Object> all=store.read("auth.json");
        String id=Json.get(all,"active");
        if (id.isBlank()) return;
        Map<String,Object> account=Json.map(Json.map(all.get("accounts")).get(id));
        String refresh=Json.get(account,"refresh_token");
        boolean revoked=false;
        if (!refresh.isBlank()) {
            try {
                Map<String,Object> discovery=Json.map(Json.parse(http.send(HttpRequest.newBuilder(URI.create(AUTH+"/.well-known/openid-configuration")).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
                String endpoint=Json.get(discovery,"revocation_endpoint");
                if (!endpoint.startsWith(AUTH+"/")) throw new SecurityException("Unexpected revocation endpoint");
                HttpRequest request=HttpRequest.newBuilder(URI.create(endpoint)).header("Content-Type","application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(encode(Map.of("token",refresh,"token_type_hint","refresh_token","client_id",id)))).build();
                revoked=http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode()==200;
            } catch (Exception ignored) { /* Local sign-out still clears credentials. */ }
        }
        account.remove("access_token"); account.remove("refresh_token"); account.remove("id_token");
        account.remove("expires_at"); all.put("active","");
        store.write("auth.json",all,true);
        System.out.println(revoked?"Signed out and revoked the session.":"Signed out locally. Remote revocation was not confirmed; disconnect the app in ChatGPT settings if needed.");
    }

    private Map<String,Object> form(String url, Map<String,String> params) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("Content-Type","application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(params))).build();
        HttpResponse<String> response = http.send(request,HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("OAuth HTTP " + response.statusCode() + ": " + response.body());
        return Json.map(Json.parse(response.body()));
    }

    private Map<String,Object> verifyIdToken(String jwt, String client, String nonce) throws Exception {
        String[] parts = jwt.split("\\."); if (parts.length != 3) throw new SecurityException("Invalid ID token");
        Map<String,Object> header = Json.map(Json.parse(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8)));
        Map<String,Object> claims = Json.map(Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]),StandardCharsets.UTF_8)));
        String alg = Json.get(header,"alg");
        if (!alg.equals("RS256") && !alg.equals("ES256")) throw new SecurityException("Unexpected ID token algorithm");
        HttpRequest discoveryRequest = HttpRequest.newBuilder(URI.create(AUTH + "/.well-known/openid-configuration")).GET().build();
        Map<String,Object> discovery = Json.map(Json.parse(http.send(discoveryRequest,HttpResponse.BodyHandlers.ofString()).body()));
        String jwksUri = Json.get(discovery,"jwks_uri");
        if (!jwksUri.startsWith(AUTH + "/")) throw new SecurityException("Unexpected JWKS host");
        Map<String,Object> jwks = Json.map(Json.parse(http.send(HttpRequest.newBuilder(URI.create(jwksUri)).GET().build(),HttpResponse.BodyHandlers.ofString()).body()));
        PublicKey key = null;
        for (Object item : Json.list(jwks.get("keys"))) {
            Map<String,Object> jwk = Json.map(item);
            if (Json.get(jwk,"kid").equals(Json.get(header,"kid"))) { key = publicKey(jwk); break; }
        }
        if (key == null) throw new SecurityException("ID token key not found");
        Signature sig = Signature.getInstance(alg.equals("RS256") ? "SHA256withRSA" : "SHA256withECDSA");
        sig.initVerify(key); sig.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        byte[] signature = Base64.getUrlDecoder().decode(parts[2]);
        if (alg.equals("ES256")) signature = joseToDer(signature);
        if (!sig.verify(signature)) throw new SecurityException("ID token signature invalid");
        validateClaims(claims,client,nonce,Instant.now().getEpochSecond());
        return claims;
    }

    static void validateClaims(Map<String,Object> claims,String client,String nonce,long now) {
        if (!AUTH.equals(Json.get(claims,"iss"))) throw new SecurityException("ID token issuer invalid");
        Object audience=claims.get("aud");
        boolean matches=client.equals(audience) ||
                (audience instanceof List<?> values && values.stream().anyMatch(client::equals));
        if (!matches || (!Json.get(claims,"azp").isBlank() && !client.equals(Json.get(claims,"azp"))))
            throw new SecurityException("ID token audience invalid");
        if (!nonce.equals(Json.get(claims,"nonce"))) throw new SecurityException("ID token nonce invalid");
        if (Json.number(claims.get("exp"),0)<=now) throw new SecurityException("ID token expired");
        if (Json.get(claims,"sub").isBlank()) throw new SecurityException("ID token subject missing");
    }

    private PublicKey publicKey(Map<String,Object> jwk) throws Exception {
        KeyFactory factory;
        if (Json.get(jwk,"kty").equals("RSA")) {
            factory = KeyFactory.getInstance("RSA");
            return factory.generatePublic(new RSAPublicKeySpec(unsigned(Json.get(jwk,"n")),unsigned(Json.get(jwk,"e"))));
        }
        if (Json.get(jwk,"kty").equals("EC") && Json.get(jwk,"crv").equals("P-256")) {
            factory = KeyFactory.getInstance("EC");
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec params = parameters.getParameterSpec(ECParameterSpec.class);
            return factory.generatePublic(new ECPublicKeySpec(new java.security.spec.ECPoint(unsigned(Json.get(jwk,"x")),unsigned(Json.get(jwk,"y"))),params));
        }
        throw new SecurityException("Unsupported JWK type");
    }
    private static BigInteger unsigned(String value) { return new BigInteger(1,Base64.getUrlDecoder().decode(value)); }
    private static byte[] joseToDer(byte[] raw) {
        if (raw.length != 64) throw new SecurityException("Invalid ES256 signature");
        byte[] r = new BigInteger(1,Arrays.copyOfRange(raw,0,32)).toByteArray();
        byte[] s = new BigInteger(1,Arrays.copyOfRange(raw,32,64)).toByteArray();
        byte[] result = new byte[6+r.length+s.length]; int i=0;
        result[i++]=0x30; result[i++]=(byte)(4+r.length+s.length);
        result[i++]=0x02; result[i++]=(byte)r.length; System.arraycopy(r,0,result,i,r.length); i+=r.length;
        result[i++]=0x02; result[i++]=(byte)s.length; System.arraycopy(s,0,result,i,s.length);
        return result;
    }
    private static String random() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String encode(Map<String,String> values) {
        List<String> parts = new ArrayList<>();
        values.forEach((k,v) -> parts.add(URLEncoder.encode(k,StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v,StandardCharsets.UTF_8)));
        return String.join("&",parts);
    }
    private static Map<String,String> query(String raw) {
        Map<String,String> out = new HashMap<>();
        if (raw == null) return out;
        for (String part : raw.split("&")) { String[] pair=part.split("=",2); out.put(URLDecoder.decode(pair[0],StandardCharsets.UTF_8), URLDecoder.decode(pair.length>1?pair[1]:"",StandardCharsets.UTF_8)); }
        return out;
    }
}

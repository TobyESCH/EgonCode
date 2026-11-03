package dev.egoncode;

import java.net.http.HttpHeaders;
import java.util.*;

/** Only provider-supplied API rate-limit headers; never estimates plan balances. */
final class RateLimits {
    private RateLimits() {}

    static Map<String,Object> capture(HttpHeaders headers,String provider) {
        boolean anthropic=provider.equals("anthropic");
        Map<String,Object> result=new LinkedHashMap<>();
        for (String kind:anthropic
                ?List.of("requests","tokens","input-tokens","output-tokens")
                :List.of("requests","tokens")) {
            for (String field:List.of("limit","remaining","reset")) {
                String key=kind+"-"+field;
                String header=anthropic?"anthropic-ratelimit-"+kind+"-"+field:"x-ratelimit-"+field+"-"+kind;
                headers.firstValue(header).ifPresent(value -> result.put(key,value));
            }
        }
        return result;
    }

    static List<String> lines(Map<String,Object> data) {
        List<String> lines=new ArrayList<>();
        for (String kind:List.of("requests","tokens","input-tokens","output-tokens")) {
            String remaining=Json.get(data,kind+"-remaining");
            if (remaining.isBlank()) continue;
            String limit=Json.get(data,kind+"-limit"), reset=Json.get(data,kind+"-reset");
            String label=switch (kind) {
                case "requests" -> "Requests";
                case "input-tokens" -> "Input tokens";
                case "output-tokens" -> "Output tokens";
                default -> "Tokens";
            };
            lines.add(label+": "+remaining+(limit.isBlank()?"":" / "+limit)+(reset.isBlank()?"":"  resets "+reset));
        }
        return lines;
    }

    static Map<String,Object> latest(Session session) {
        for (int i=session.events.size()-1;i>=0;i--) {
            Map<String,Object> event=session.events.get(i);
            if (Json.get(event,"type").equals("rate_limits")) return Json.map(event.get("detail"));
        }
        return Map.of();
    }
}

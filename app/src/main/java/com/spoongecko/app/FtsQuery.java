package com.spoongecko.app;

final class FtsQuery {

    private FtsQuery() {}

    static String match(String input) {
        if (input == null) return "\"\"";
        String cleaned = input.replaceAll("[^\\p{L}\\p{N}]", " ").trim();
        if (cleaned.isEmpty()) return "\"\"";
        String[] tokens = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (token.isEmpty()) continue;
            if (sb.length() > 0) sb.append(" OR ");
            sb.append(token).append('*');
        }
        return sb.length() == 0 ? "\"\"" : sb.toString();
    }
}

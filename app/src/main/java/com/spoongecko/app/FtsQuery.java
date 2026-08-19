package com.spoongecko.app;

final class FtsQuery {

    private FtsQuery() {}

    static String match(String input) {
        String cleaned = input == null ? "" : input.trim();
        if (cleaned.isEmpty()) return "\"\"";

        String[] tokens = cleaned.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String token : tokens) {
            if (sb.length() > 0) sb.append(" OR ");
            sb.append('"').append(token.replace("\"", "\"\"")).append("\"*");
        }
        return sb.toString();
    }
}

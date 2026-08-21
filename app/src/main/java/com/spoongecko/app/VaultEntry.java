package com.spoongecko.app;

final class VaultEntry {
    final String host;
    final String username;
    final String password;

    VaultEntry(String host, String username, String password) {
        this.host = host;
        this.username = username;
        this.password = password;
    }
}

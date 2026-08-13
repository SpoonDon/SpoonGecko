package com.spoongecko.app;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link ExtensionController}.
 *
 * <p>These tests cover the pure-logic helper methods that do not require an Android
 * runtime or a live GeckoRuntime — they validate input validation, error formatting,
 * and the metadata accessor helpers.
 *
 * <p>Note: methods that call into GeckoRuntime (install, uninstall, enable, disable,
 * list) require an instrumented (device/emulator) test environment and are exercised
 * by manual QA or instrumented tests.
 */
public class ExtensionControllerTest {

    // ------------------------------------------------------------------ source validation

    @Test
    public void allowedSource_contentUri_returnsTrue() {
        assertTrue(ExtensionController.isAllowedSource("content://com.android.providers.downloads/my_downloads/42"));
    }

    @Test
    public void allowedSource_httpsUri_returnsTrue() {
        assertTrue(ExtensionController.isAllowedSource("https://addons.mozilla.org/firefox/downloads/file/3901066/ublock_origin.xpi"));
    }

    @Test
    public void allowedSource_httpUri_returnsFalse() {
        assertFalse(ExtensionController.isAllowedSource("http://example.com/ext.xpi"));
    }

    @Test
    public void allowedSource_fileUri_returnsFalse() {
        assertFalse(ExtensionController.isAllowedSource("file:///sdcard/ext.xpi"));
    }

    @Test
    public void allowedSource_null_returnsFalse() {
        assertFalse(ExtensionController.isAllowedSource(null));
    }

    @Test
    public void allowedSource_emptyString_returnsFalse() {
        assertFalse(ExtensionController.isAllowedSource(""));
    }

    // -------------------------------------------------------------- error formatting

    @Test
    public void formatInstallError_null_returnsGeneric() {
        String result = ExtensionController.formatInstallError(null);
        assertEquals("Install failed.", result);
    }

    @Test
    public void formatInstallError_corruptKeyword_returnsCorruptMessage() {
        String result = ExtensionController.formatInstallError("ERROR_CORRUPT_FILE at parse");
        assertTrue(result.contains("corrupted"));
    }

    @Test
    public void formatInstallError_incompatibleKeyword_returnsCompatMessage() {
        String result = ExtensionController.formatInstallError("ERROR_INCOMPATIBLE version");
        assertTrue(result.contains("compatible"));
    }

    @Test
    public void formatInstallError_signKeyword_returnsSignMessage() {
        String result = ExtensionController.formatInstallError("ERROR_SIGNEDSTATE unsigned");
        assertTrue(result.contains("signed"));
    }

    @Test
    public void formatInstallError_networkKeyword_returnsNetworkMessage() {
        String result = ExtensionController.formatInstallError("ERROR_NETWORK timeout");
        assertTrue(result.contains("network"));
    }

    @Test
    public void formatInstallError_unknownError_returnsRawAppended() {
        String result = ExtensionController.formatInstallError("something weird happened");
        assertTrue(result.startsWith("Install failed:"));
        assertTrue(result.contains("something weird happened"));
    }

    // --------------------------------------------------------------- metadata helpers (null safety)

    @Test
    public void getDisplayName_nullExtension_returnsUnknown() {
        assertEquals("Unknown", ExtensionController.getDisplayName(null));
    }

    @Test
    public void getVersion_nullExtension_returnsEmpty() {
        assertEquals("", ExtensionController.getVersion(null));
    }

    @Test
    public void isEnabled_nullExtension_returnsTrue() {
        assertTrue(ExtensionController.isEnabled(null));
    }
}

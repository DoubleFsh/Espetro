package org.espetro.client.aui;

import com.sighs.apricityui.ApricityUI;
import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;

/**
 * Large, readable denial / info card. Does not use the built-in 10px
 * "AUI // NOTICE" toast, and never intercepts the mouse.
 */
public final class AuiTips {
    public static final String DOCUMENT_PATH = "overlays/tips.html";
    private static final int DURATION_MS = 3_500;
    private static final long DEBOUNCE_MS = 1_500L;

    private static Document document;
    private static String lastKey = "";
    private static long lastShownAt;
    private static long hideAt;
    private static boolean visible;

    private AuiTips() {
    }

    public static void showDenial(String title, String body) {
        show(title, body, true, "denial:" + title + ":" + body);
    }

    public static void showInfo(String title, String body) {
        show(title, body, false, "info:" + title + ":" + body);
    }

    public static void showRaw(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        String stripped = message.replaceAll("(?i)\u00a7[0-9A-FK-OR]", "");
        show("无法操作", stripped, true, "raw:" + message.replace('\u00a7', '&'));
    }

    public static void tick() {
        if (visible && System.currentTimeMillis() >= hideAt) {
            hide();
        }
    }

    private static void show(String title, String body, boolean error, String debounceKey) {
        long now = System.currentTimeMillis();
        if (debounceKey.equals(lastKey) && now - lastShownAt < DEBOUNCE_MS) {
            return;
        }
        lastKey = debounceKey;
        lastShownAt = now;
        if (!ensureDocument()) {
            return;
        }
        Element root = document.getElementById("tip");
        Element titleNode = document.getElementById("tip-title");
        Element bodyNode = document.getElementById("tip-body");
        if (root == null || titleNode == null || bodyNode == null) {
            return;
        }
        titleNode.setTextContent(title == null ? "" : title);
        bodyNode.setTextContent(body == null ? "" : body);
        root.removeAttribute("hidden");
        root.setAttribute("class", error ? "tip error" : "tip");
        visible = true;
        hideAt = now + DURATION_MS;
    }

    private static void hide() {
        visible = false;
        if (document == null) {
            return;
        }
        Element root = document.getElementById("tip");
        if (root == null) {
            return;
        }
        root.setAttribute("hidden", "true");
        root.setAttribute("class", "tip hidden");
    }

    private static boolean ensureDocument() {
        if (document != null && document.isActive() && !document.isDisposed()) {
            return true;
        }
        document = ApricityUI.createDocument(DOCUMENT_PATH);
        if (document == null) {
            return false;
        }
        document.setReloadPersistent(true);
        hide();
        return true;
    }
}

package com.rutera.core.oauth;

import java.util.Map;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

/**
 * The small server-rendered page of {@code /oauth/authorize} (sign-in, consent, errors): no scripts, inline styles,
 * light and dark by the system setting. Every value put into it is HTML-escaped here.
 */
final class OAuthPage {

    private static final String STYLE = """
            :root{color-scheme:light dark;--bg:#f4f6f8;--card:#fff;--text:#1d2329;--muted:#5f6b76;--line:#d9dfe5;\
            --accent:#1c6dd0;--accent-text:#fff;--error-bg:#fdecec;--error:#a32020}
            @media (prefers-color-scheme:dark){:root{--bg:#14181c;--card:#1d2329;--text:#e8edf2;--muted:#9aa6b2;\
            --line:#343d46;--accent:#4c8fe6;--accent-text:#0b1220;--error-bg:#3a1b1b;--error:#ffb4b4}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,-apple-system,"Segoe UI",\
            Roboto,sans-serif;display:flex;justify-content:center;padding:48px 16px}
            main{width:100%;max-width:420px;background:var(--card);border:1px solid var(--line);border-radius:12px;\
            padding:28px}
            .brand{font-weight:700;letter-spacing:.08em;color:var(--accent);margin-bottom:16px}
            h1{font-size:20px;margin:0 0 8px}
            p{margin:0 0 16px}
            .muted{color:var(--muted)}
            .error{background:var(--error-bg);color:var(--error);border-radius:8px;padding:10px 12px;margin:0 0 16px}
            dl{margin:0 0 16px;display:grid;grid-template-columns:auto 1fr;gap:6px 12px}
            dt{color:var(--muted)}
            dd{margin:0;overflow-wrap:anywhere}
            label{display:block;margin:0 0 12px}
            input[type=text],input[type=password]{display:block;width:100%;margin-top:4px;padding:9px 10px;\
            border:1px solid var(--line);border-radius:8px;background:transparent;color:inherit;font:inherit}
            .buttons{display:flex;gap:8px;flex-wrap:wrap;margin-top:8px}
            button{font:inherit;padding:9px 16px;border-radius:8px;border:1px solid var(--line);background:transparent;\
            color:inherit;cursor:pointer}
            button.primary{background:var(--accent);border-color:var(--accent);color:var(--accent-text);font-weight:600}
            button.link{border:0;padding:0;color:var(--accent);background:none;margin-top:16px}
            """;

    private OAuthPage() {
    }

    /** One page; {@code body} is already-built HTML from the helpers below. */
    static String page(String lang, String title, String body) {
        return "<!doctype html><html lang=\"" + htmlEscape(lang) + "\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex\"><title>" + htmlEscape(title) + " | RUTERA</title>"
                + "<style>" + STYLE + "</style></head><body><main><div class=\"brand\">RUTERA</div>"
                + "<h1>" + htmlEscape(title) + "</h1>" + body + "</main></body></html>";
    }

    static String text(String text) {
        return "<p>" + htmlEscape(text) + "</p>";
    }

    static String muted(String text) {
        return "<p class=\"muted\">" + htmlEscape(text) + "</p>";
    }

    static String error(String text) {
        return text == null ? "" : "<p class=\"error\" role=\"alert\">" + htmlEscape(text) + "</p>";
    }

    /** Label / value rows. */
    static String details(Map<String, String> rows) {
        StringBuilder b = new StringBuilder("<dl>");
        rows.forEach((label, value) -> b.append("<dt>").append(htmlEscape(label)).append("</dt><dd>")
                .append(htmlEscape(value)).append("</dd>"));
        return b.append("</dl>").toString();
    }

    /** A POST form back to /oauth/authorize carrying the authorization request and the CSRF token. */
    static String form(Map<String, String> hidden, String content) {
        StringBuilder b = new StringBuilder("<form method=\"post\" action=\"/oauth/authorize\">");
        hidden.forEach((name, value) -> {
            if (value != null) {
                b.append("<input type=\"hidden\" name=\"").append(htmlEscape(name)).append("\" value=\"")
                        .append(htmlEscape(value)).append("\">");
            }
        });
        return b.append(content).append("</form>").toString();
    }

    static String input(String type, String name, String label, String value, String autocomplete) {
        return "<label>" + htmlEscape(label) + "<input type=\"" + type + "\" name=\"" + name + "\" value=\""
                + htmlEscape(value == null ? "" : value) + "\" autocomplete=\"" + autocomplete + "\" required></label>";
    }

    static String button(String action, String label, String css) {
        return "<button type=\"submit\" name=\"action\" value=\"" + action + "\""
                + (css == null ? "" : " class=\"" + css + "\"") + ">" + htmlEscape(label) + "</button>";
    }

    static String buttons(String... buttons) {
        return "<div class=\"buttons\">" + String.join("", buttons) + "</div>";
    }
}

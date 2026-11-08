package com.spoongecko.browser;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the page shown when a load fails. Pure Java (no Android classes) so
 * it can be unit-tested; ErrorPages decides WHAT to say, this decides HOW.
 *
 * Every piece of text that can come from outside (the failed URL, certificate
 * fields sent by the server) is HTML-escaped. The script below contains no
 * dynamic data at all.
 */
final class ErrorPageHtml {

    private ErrorPageHtml() {}

    /** Everything the page shows. */
    static final class Model {
        String title = "";                        // <title> and heading
        String failedUrl = "";                    // shown under the heading
        String lead = "";                         // main sentence
        final List<String> paragraphs = new ArrayList<>();
        final List<String> details = new ArrayList<>();   // monospace lines
        String altUrl;                            // optional "try the other scheme" link
        String altLabel;
        boolean offerProceed;                     // certificate override button
        String proceedHost = "";
    }

    static String render(Model m) {
        final StringBuilder h = new StringBuilder(4096);
        h.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
         .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
         .append("<title>").append(escape(m.title)).append("</title><style>")
         .append("body{margin:0;background:#000;color:#e8e8e8;font:16px/1.5 sans-serif;padding:24px 20px 48px}")
         .append(".w{max-width:560px;margin:0 auto}")
         .append("h1{font-size:22px;margin:8px 0 4px;font-weight:600}")
         .append(".u{color:#9aa0a6;word-break:break-all;font-size:14px;margin:0 0 14px}")
         .append("p{margin:10px 0}")
         .append(".d{background:#121212;border:1px solid #2a2a2a;border-radius:10px;padding:10px 12px;")
         .append("font:12px/1.55 monospace;color:#bdc1c6;word-break:break-all;white-space:pre-wrap;margin:14px 0}")
         .append(".b{display:inline-block;margin:6px 8px 6px 0;padding:11px 18px;border-radius:22px;")
         .append("border:1px solid #3c4043;background:#1e1e1e;color:#e8e8e8;font:15px sans-serif;text-decoration:none}")
         .append(".p{background:#8ab4f8;border-color:#8ab4f8;color:#000}")
         .append(".x{border-color:#f28b82;color:#f28b82;background:#000}")
         .append(".b[disabled]{opacity:.5}")
         .append("label{display:block;margin:14px 0 6px;color:#bdc1c6;font-size:14px}")
         .append("#e{color:#f28b82;font-size:14px;margin:8px 0}")
         .append("</style></head><body><div class=\"w\">");

        h.append("<h1>").append(escape(m.title)).append("</h1>");
        if (!m.failedUrl.isEmpty()) {
            h.append("<div class=\"u\">").append(escape(m.failedUrl)).append("</div>");
        }
        if (!m.lead.isEmpty()) h.append("<p>").append(escape(m.lead)).append("</p>");
        for (String p : m.paragraphs) h.append("<p>").append(escape(p)).append("</p>");

        if (!m.details.isEmpty()) {
            h.append("<div class=\"d\">");
            for (int i = 0; i < m.details.size(); i++) {
                if (i > 0) h.append('\n');
                h.append(escape(m.details.get(i)));
            }
            h.append("</div>");
        }

        if (m.offerProceed) {
            h.append("<label><input type=\"checkbox\" id=\"k\"> Remember this certificate for this device</label>")
             .append("<button class=\"b x\" id=\"g\">Continue to ").append(escape(m.proceedHost))
             .append(" (unsafe)</button>")
             .append("<div id=\"e\" hidden></div>");
        }
        if (m.altUrl != null) {
            h.append("<a class=\"b\" href=\"").append(escape(m.altUrl)).append("\">")
             .append(escape(m.altLabel == null ? m.altUrl : m.altLabel)).append("</a>");
        }
        h.append("<button class=\"b p\" id=\"r\">Try again</button></div>");

        // No dynamic data below this line.
        h.append("<script>")
         .append("document.getElementById('r').addEventListener('click',function(){location.reload();});");
        if (m.offerProceed) {
            h.append("var g=document.getElementById('g'),e=document.getElementById('e');")
             .append("g.addEventListener('click',function(){")
             .append("g.disabled=true;e.hidden=true;")
             .append("var temp=!document.getElementById('k').checked;")
             .append("function fail(x){g.disabled=false;e.textContent='Couldn\\u2019t continue: '+(x&&x.message?x.message:x);e.hidden=false;}")
             .append("try{")
             .append("if(typeof document.addCertException!=='function'){throw new Error('this browser build can\\u2019t override certificates');}")
             .append("document.addCertException(temp).then(function(){location.reload();},fail);")
             .append("}catch(x){fail(x);}")
             .append("});");
        }
        h.append("</script></body></html>");
        return h.toString();
    }

    static String escape(String s) {
        if (s == null) return "";
        final StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '&':  out.append("&amp;");  break;
                case '<':  out.append("&lt;");   break;
                case '>':  out.append("&gt;");   break;
                case '"':  out.append("&quot;"); break;
                case '\'': out.append("&#39;");  break;
                default:   out.append(c);
            }
        }
        return out.toString();
    }
}

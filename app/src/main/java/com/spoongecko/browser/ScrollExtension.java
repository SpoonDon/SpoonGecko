package com.spoongecko.browser;

import android.net.Uri;

/**
 * Inject-and-report scroll hook.
 *
 * Replaces the earlier WebExtension approach, which depended on
 * content_scripts running reliably in GeckoView 157 and on
 * browser.runtime.sendNativeMessage being reachable from a content script
 * context. That path failed silently — the delegate never fired, so
 * Tab.atTop stayed at its default (true) and pull-to-refresh was allowed
 * even on scrolled pages.
 *
 * New approach: TabManager calls injectScript() from onPageStop, and the
 * returned JavaScript installs a scroll listener. When the at-top state
 * flips, the script loads a hidden iframe with a custom URL scheme
 * (spoon-scroll://report?atTop=N). TabManager's NavigationDelegate
 * intercepts that scheme, updates the tab flag, and denies the navigation
 * so nothing actually loads and no history entry is created.
 */
public final class ScrollExtension {

    public static final String SCHEME = "spoon-scroll";
    private static final String PREFIX = SCHEME + ":";

    private ScrollExtension() {}

    public static boolean isScrollUrl(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    /** Returns the atTop value reported by a scroll URL, or null if malformed. */
    public static Boolean parseAtTop(String url) {
        if (!isScrollUrl(url)) return null;
        try {
            Uri u = Uri.parse(url);
            String v = u.getQueryParameter("atTop");
            if ("1".equals(v) || "true".equalsIgnoreCase(v)) return Boolean.TRUE;
            if ("0".equals(v) || "false".equalsIgnoreCase(v)) return Boolean.FALSE;
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * JavaScript to run after each page load. Idempotent: guarded by a
     * window flag so re-injection on the same document is a no-op.
     */
    public static String buildInjectScript() {
        return
            "(function(){" +
            "if(window.__sgScrollHooked)return;window.__sgScrollHooked=true;" +
            "var last=null,pending=false;" +
            "function findScroller(el){" +
              "var c=el;" +
              "while(c&&c.nodeType===1){" +
                "var s=null;try{s=getComputedStyle(c);}catch(e){s=null;}" +
                "if(s&&(s.overflowY==='auto'||s.overflowY==='scroll')&&" +
                  "c.scrollHeight-c.clientHeight>4){return c;}" +
                "c=c.parentElement;" +
              "}" +
              "return document.scrollingElement||document.documentElement;" +
            "}" +
            "function compute(ev){" +
              "var t=null;" +
              "if(ev){" +
                "if(ev.composedPath&&ev.composedPath().length){t=ev.composedPath()[0];}" +
                "else{t=ev.target;}" +
              "}" +
              "if(!t||t.nodeType!==1){t=document.body||document.documentElement;}" +
              "var inner=findScroller(t);" +
              "var root=document.scrollingElement||document.documentElement;" +
              "return (inner.scrollTop<=1)&&(root.scrollTop<=1);" +
            "}" +
            "function send(atTop){" +
              "if(atTop===last)return;last=atTop;" +
              "try{" +
                "var f=document.createElement('iframe');" +
                "f.style.display='none';f.style.width='0';f.style.height='0';" +
                "f.setAttribute('aria-hidden','true');" +
                "f.src='spoon-scroll://report?atTop='+(atTop?1:0);" +
                "(document.documentElement||document.body).appendChild(f);" +
                "setTimeout(function(){try{f.parentNode.removeChild(f);}catch(e){}},0);" +
              "}catch(e){}" +
            "}" +
            "function report(ev){" +
              "if(pending)return;pending=true;var t=ev;" +
              "requestAnimationFrame(function(){" +
                "pending=false;" +
                "try{send(compute(t));}catch(e){}" +
              "});" +
            "}" +
            "document.addEventListener('scroll',report,{capture:true,passive:true});" +
            "window.addEventListener('scroll',report,{capture:true,passive:true});" +
            "document.addEventListener('touchstart',report,{capture:true,passive:true});" +
            "document.addEventListener('touchmove',report,{capture:true,passive:true});" +
            "window.addEventListener('resize',report,{capture:true,passive:true});" +
            "document.addEventListener('DOMContentLoaded',function(){report(null);});" +
            "window.addEventListener('load',function(){report(null);});" +
            "report(null);" +
            "})();";
    }
}

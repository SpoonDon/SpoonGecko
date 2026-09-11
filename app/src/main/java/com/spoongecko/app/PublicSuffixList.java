package com.spoongecko.app;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class PublicSuffixList {

    private static final Set<String> MULTI_PART = new HashSet<>(Arrays.asList(
            "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "nhs.uk",
            "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au", "asn.au",
            "co.nz", "net.nz", "org.nz", "ac.nz", "govt.nz", "school.nz", "geek.nz", "gen.nz", "kiwi.nz", "maori.nz",
            "co.jp", "or.jp", "ne.jp", "ac.jp", "go.jp", "ed.jp", "gr.jp", "lg.jp",
            "co.kr", "or.kr", "ne.kr", "re.kr", "pe.kr", "go.kr", "ac.kr", "mil.kr",
            "com.br", "net.br", "org.br", "gov.br", "edu.br", "mil.br", "art.br", "blog.br", "eco.br", "emp.br", "ind.br", "inf.br", "med.br", "psi.br", "rec.br", "srv.br", "tmp.br", "tur.br", "tv.br", "vet.br", "wiki.br",
            "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn", "mil.cn",
            "com.hk", "net.hk", "org.hk", "edu.hk", "gov.hk", "idv.hk",
            "com.tw", "net.tw", "org.tw", "edu.tw", "gov.tw", "idv.tw",
            "com.sg", "net.sg", "org.sg", "edu.sg", "gov.sg", "per.sg",
            "com.my", "net.my", "org.my", "edu.my", "gov.my", "mil.my", "name.my",
            "co.in", "net.in", "org.in", "firm.in", "gen.in", "ind.in", "ac.in", "edu.in", "gov.in", "mil.in", "nic.in", "res.in",
            "co.za", "net.za", "org.za", "gov.za", "ac.za", "edu.za", "web.za",
            "com.mx", "net.mx", "org.mx", "edu.mx", "gob.mx",
            "com.ar", "net.ar", "org.ar", "edu.ar", "gob.ar", "gov.ar", "int.ar", "mil.ar", "musica.ar", "tur.ar",
            "com.tr", "net.tr", "org.tr", "edu.tr", "gov.tr", "gen.tr", "web.tr", "biz.tr", "info.tr", "av.tr", "dr.tr", "bel.tr", "pol.tr", "k12.tr", "name.tr",
            "com.ru", "net.ru", "org.ru", "pp.ru",
            "co.il", "org.il", "net.il", "ac.il", "gov.il", "muni.il", "idf.il",
            "com.sa", "net.sa", "org.sa", "edu.sa", "gov.sa", "med.sa", "pub.sa", "sch.sa",
            "com.eg", "net.eg", "org.eg", "edu.eg", "gov.eg", "sci.eg",
            "com.pk", "net.pk", "org.pk", "edu.pk", "gov.pk", "gob.pk", "web.pk",
            "com.bd", "net.bd", "org.bd", "edu.bd", "gov.bd", "ac.bd",
            "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn", "int.vn", "ac.vn", "biz.vn", "info.vn", "name.vn", "pro.vn", "health.vn",
            "com.ph", "net.ph", "org.ph", "edu.ph", "gov.ph", "mil.ph", "i.ph", "ngo.ph",
            "com.id", "net.id", "org.id", "web.id", "ac.id", "co.id", "go.id", "mil.id", "my.id", "biz.id", "sch.id", "or.id",
            "co.th", "in.th", "ac.th", "go.th", "mi.th", "net.th", "or.th",
            "com.ua", "net.ua", "org.ua", "edu.ua", "gov.ua", "in.ua",
            "com.pl", "net.pl", "org.pl", "edu.pl", "gov.pl", "info.pl", "biz.pl", "waw.pl",
            "com.gr", "net.gr", "org.gr", "edu.gr", "gov.gr",
            "com.pt", "net.pt", "org.pt", "edu.pt", "gov.pt", "int.pt", "publ.pt",
            "com.es", "nom.es", "org.es", "gob.es", "edu.es",
            "com.it", "net.it", "org.it", "edu.it", "gov.it",
            "com.fr", "net.fr", "org.fr", "asso.fr", "gouv.fr", "tm.fr", "prd.fr", "presse.fr",
            "co.at", "or.at", "ac.at", "gv.at",
            "co.be", "org.be", "ac.be",
            "co.nl", "com.nl", "net.nl", "org.nl",
            "co.dk", "com.dk", "net.dk", "org.dk",
            "co.no", "com.no", "net.no", "org.no",
            "co.se", "com.se", "net.se", "org.se",
            "co.fi", "com.fi", "net.fi", "org.fi",
            "co.ch", "com.ch", "net.ch", "org.ch",
            "co.hu", "com.hu", "net.hu", "org.hu", "edu.hu", "gov.hu",
            "co.cz", "com.cz", "net.cz", "org.cz",
            "co.ro", "com.ro", "net.ro", "org.ro",
            "com.bg", "net.bg", "org.bg",
            "com.hr", "net.hr", "org.hr",
            "com.rs", "net.rs", "org.rs", "edu.rs", "gov.rs", "in.rs",
            "com.sk", "net.sk", "org.sk",
            "com.si", "net.si", "org.si",
            "com.lt", "net.lt", "org.lt",
            "com.lv", "net.lv", "org.lv",
            "com.ee", "net.ee", "org.ee",
            "com.by", "net.by", "org.by",
            "com.uz", "net.uz", "org.uz",
            "com.kz", "net.kz", "org.kz", "edu.kz", "gov.kz",
            "com.ge", "net.ge", "org.ge", "edu.ge", "gov.ge",
            "com.az", "net.az", "org.az", "edu.az", "gov.az",
            "com.am", "net.am", "org.am", "edu.am",
            "com.md", "net.md", "org.md",
            "co.ke", "or.ke", "ne.ke", "go.ke", "ac.ke", "sc.ke", "me.ke", "mobi.ke", "info.ke",
            "com.ng", "net.ng", "org.ng", "edu.ng", "gov.ng", "i.ng", "mil.ng", "mobi.ng", "name.ng", "sch.ng",
            "com.gh", "edu.gh", "gov.gh", "org.gh", "mil.gh",
            "co.tz", "ac.tz", "go.tz", "or.tz", "ne.tz", "sc.tz",
            "co.ug", "ac.ug", "go.ug", "or.ug", "ne.ug", "sc.ug",
            "co.zw", "ac.zw", "go.zw", "or.zw", "ne.zw", "sc.zw",
            "github.io", "githubusercontent.com", "blogspot.com", "herokuapp.com", "vercel.app",
            "netlify.app", "web.app", "firebaseapp.com", "pages.dev", "workers.dev",
            "cloudfront.net", "amazonaws.com", "azurewebsites.net", "cloudapp.azure.com",
            "appspot.com", "s3.amazonaws.com", "gitlab.io", "surge.sh", "readthedocs.io"
    ));

    private PublicSuffixList() {}

    static String registrableDomain(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        if (h.isEmpty()) return "";
        if (h.startsWith("[") && h.endsWith("]")) return h;
        if (h.matches("^\\d{1,3}(\\.\\d{1,3}){3}$")) return h;

        String[] labels = h.split("\\.");
        if (labels.length <= 1) return h;

        for (int i = 0; i < labels.length - 1; i++) {
            StringBuilder sb = new StringBuilder();
            for (int j = i; j < labels.length; j++) {
                if (j > i) sb.append('.');
                sb.append(labels[j]);
            }
            String candidate = sb.toString();
            if (MULTI_PART.contains(candidate)) {
                if (i == 0) return h;
                return labels[i - 1] + "." + candidate;
            }
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }
}
